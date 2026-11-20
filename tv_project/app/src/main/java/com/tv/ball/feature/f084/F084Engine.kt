package com.tv.ball.feature.f084

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.tv.ball.BuildConfig
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志级别。
 *
 * 五级、按严重程度递增（[priority] 越大越严重），和排障时的直觉一致：
 * 设成 INFO 就只看 INFO/WARN/ERROR，设成 VERBOSE 就全都留。
 *
 * 这个枚举只属于 F084，不复用 `com.tv.ball.util.VideoFlowLogger` 里的那一套
 * （那套是 DEBUG/INFO/WARNING/ERROR/SUCCESS 的视频流程日志，语义不同、还要求 stage），
 * 免得改共享文件时把别的功能带坏。
 */
enum class LogLevel(val label: String, val priority: Int) {
    VERBOSE("V", 0),
    DEBUG("D", 1),
    INFO("I", 2),
    WARN("W", 3),
    ERROR("E", 4);

    /** 本级是否够得着 [threshold]：`WARN.atLeast(INFO)` 为 true，`DEBUG.atLeast(INFO)` 为 false。 */
    fun atLeast(threshold: LogLevel): Boolean = priority >= threshold.priority

    companion object {
        /** 界面单选组的顺序：从小到大。 */
        val ALL: List<LogLevel> = values().toList()

        /**
         * 「写一条测试日志」的轮转顺序：严重 → 轻。
         *
         * 这样第一下一定写得进（阈值最高也只到 ERROR），越按越往下探，
         * 碰到阈值以下就被挡掉 —— 过滤规则演示得出来，又不会让人一进页面就以为按钮坏了。
         */
        val WRITE_ORDER: List<LogLevel> = listOf(ERROR, WARN, INFO, DEBUG, VERBOSE)

        /** 存档里读回来的名字不认识（降级安装、手改 prefs）就退回 [INFO]，不让页面起不来。 */
        fun fromName(name: String?): LogLevel = ALL.firstOrNull { it.name == name } ?: INFO
    }
}

/**
 * 内存日志里的一条。
 *
 * 只有四个字段：时间戳、级别、TAG、正文 —— 环形缓冲上限 500 条，
 * 字段越多越容易在电视的小堆上抖，够排障用就行。
 */
data class LogEntry(val atMs: Long, val level: LogLevel, val tag: String, val msg: String) {

    /** 一行文本：`22:54:15.123 I/F084: 测试日志 #3（INFO）`。 */
    fun format(): String = timeText(atMs) + " " + level.label + "/" + tag + ": " + msg

    private companion object {
        fun timeText(atMs: Long): String =
            SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(atMs))
    }
}

/**
 * F084「日志级别与导出」的能力层。
 *
 * 干四件事：
 *   1. 级别阈值 + 判定：[shouldLog] 决定一条日志要不要留，[currentLevel] 是当前阈值；
 *   2. 环形内存日志：上限 [MAX_ENTRIES] 条，写满丢最旧的一条（[log]）；
 *   3. 导出：[dump] 拼出「设备 / 系统 / 应用版本 + 全部日志」的文本，
 *      [exportTo] 把它写到 `getExternalFilesDir("logs")/tvplayer-log-<时间>.txt`；
 *   4. 级别持久化：写进 SharedPreferences，进程重启后还是用户选的那一级（[attach] / [setLevel]）。
 *
 * 线程约定：buffer 只在这一个 object 里被读写，全部走 [lock]；
 * 级别用 @Volatile，读多写少，不做加锁。
 *
 * 接线上怎么用：播放器 / 网络层的日志调用方拿到 Context 后先 [attach]，再 `F084Engine.log(...)`；
 * 判定已经被 [log] 包住，调用方不需要自己判断级别。
 */
object F084Engine {

    /** 环形缓冲上限。500 条 × 每条约 80 字，稳在十几 KB，不会把电视内存顶出问题。 */
    const val MAX_ENTRIES = 500

    /** 默认 TAG：调用方没给 tag 时用它。 */
    const val DEFAULT_TAG = "F084"

    private const val PREFS_NAME = "f084_log_level"
    private const val KEY_LEVEL = "level"

    /** 导出目录名：`getExternalFilesDir("logs")`，不需要任何存储权限，接电脑能直接拷走。 */
    private const val LOG_DIR = "logs"
    private const val FILE_PREFIX = "tvplayer-log-"
    private const val FILE_EXT = ".txt"
    private const val FILE_TIME_PATTERN = "yyyyMMdd-HHmmss"

    private val lock = Any()

    /** 环形缓冲：addLast 入、超上限 removeFirst 丢最旧。 */
    private val buffer = ArrayDeque<LogEntry>()

    private var prefs: SharedPreferences? = null

    @Volatile
    private var threshold: LogLevel = LogLevel.INFO

    /** 当前级别阈值。 */
    val currentLevel: LogLevel
        get() = threshold

    /**
     * 绑定 Context 并恢复上次保存的级别。
     *
     * 幂等：第一次调用读一次 SharedPreferences 把级别取回来，之后再来只是空转，
     * 页面每次 onCreate 都调一下不会反复读盘。
     */
    fun attach(context: Context) {
        if (prefs != null) return
        synchronized(lock) {
            if (prefs != null) return
            val store = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = store
            threshold = LogLevel.fromName(store.getString(KEY_LEVEL, null))
        }
    }

    // ===== 级别 =====

    /**
     * 这条日志在当前阈值下要不要留。
     *
     * 参数顺序是「当前阈值, 消息级别」，和调用点 `shouldLog(currentLevel, WARN)` 的读法一致。
     */
    fun shouldLog(level: LogLevel, msgLevel: LogLevel): Boolean = msgLevel.atLeast(level)

    /** 同上，阈值取 [currentLevel]。 */
    fun shouldLog(msgLevel: LogLevel): Boolean = shouldLog(currentLevel, msgLevel)

    /**
     * 切级别（并落盘）。
     *
     * 只影响之后进来的日志：已经躺在缓冲里的条目不删 —— 用户刚把级别调到 DEBUG，
     * 结果刚才那条 ERROR 消失了，那才叫怪。
     */
    fun setLevel(context: Context, level: LogLevel) {
        attach(context)
        setLevel(level)
    }

    /** 同上，不碰 Context：已经 [attach] 过（或单测里没存档）时用这个重载。 */
    fun setLevel(level: LogLevel) {
        threshold = level
        prefs?.edit()?.putString(KEY_LEVEL, level.name)?.apply()
    }

    // ===== 写日志 =====

    /**
     * 写一条日志。
     *
     * @return true 表示这条进了缓冲（当前级别放行）；false 表示被级别过滤掉了 ——
     *         页面拿返回值告诉用户「刚才那条被过滤了」，而不是静默什么都不发生。
     */
    fun log(level: LogLevel, tag: String, msg: String): Boolean {
        if (!shouldLog(level)) return false

        val entry = LogEntry(
            atMs = System.currentTimeMillis(),
            level = level,
            tag = tag.ifBlank { DEFAULT_TAG },
            msg = msg
        )
        synchronized(lock) {
            buffer.addLast(entry)
            // 环形：写满就把最旧的一条挤出去，屏幕上永远是最近 500 条
            while (buffer.size > MAX_ENTRIES) {
                buffer.removeFirst()
            }
        }
        mirrorToLogcat(entry)
        return true
    }

    /** 顺带镜像一份到 logcat：`adb logcat -s TVPlayer` 也能看到，不然排障还得开页面。 */
    private fun mirrorToLogcat(entry: LogEntry) {
        val text = entry.tag + ": " + entry.msg
        when (entry.level) {
            LogLevel.VERBOSE -> Log.v(LOGCAT_TAG, text)
            LogLevel.DEBUG -> Log.d(LOGCAT_TAG, text)
            LogLevel.INFO -> Log.i(LOGCAT_TAG, text)
            LogLevel.WARN -> Log.w(LOGCAT_TAG, text)
            LogLevel.ERROR -> Log.e(LOGCAT_TAG, text)
        }
    }

    /** 快照：按写入顺序（最旧 → 最新）返回，调用方自己决定要不要倒序显示。 */
    fun snapshot(): List<LogEntry> = synchronized(lock) { buffer.toList() }

    /** 当前缓冲里有多少条。 */
    fun count(): Int = synchronized(lock) { buffer.size }

    /** 清空缓冲（级别设置不动）。 */
    fun clear() {
        synchronized(lock) { buffer.clear() }
    }

    // ===== 导出 =====

    /**
     * 拼出完整导出文本：设备与版本信息在前，日志在后。
     *
     * 设备段是排障必需的 —— 同一份日志在小米和海思方案上表现完全不同，
     * 没有型号和系统版本，光看时间戳没法定位。应用版本取 [BuildConfig]，
     * 构建类型也写进去，免得 debug 包的行为被当成线上问题。
     */
    fun dump(): String {
        val entries = snapshot()
        val text = StringBuilder(4096)

        text.append("TVPlayer 诊断日志").append('\n')
        text.append("导出时间: ").append(stamp(System.currentTimeMillis(), "yyyy-MM-dd HH:mm:ss")).append('\n')
        text.append("日志级别: ").append(currentLevel.name).append('\n')
        text.append("日志条数: ").append(entries.size).append(" / ").append(MAX_ENTRIES).append('\n')
        text.append("========== 设备 ==========").append('\n')
        text.append("厂商: ").append(Build.MANUFACTURER).append('\n')
        text.append("型号: ").append(Build.MODEL).append('\n')
        text.append("设备代号: ").append(Build.DEVICE).append('\n')
        text.append("Android: ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(')').append('\n')
        text.append("系统指纹: ").append(Build.FINGERPRINT).append('\n')
        text.append("应用版本: ").append(BuildConfig.VERSION_NAME)
            .append(" (").append(BuildConfig.VERSION_CODE).append(')').append('\n')
        text.append("构建类型: ").append(if (BuildConfig.DEBUG) "debug" else "release").append('\n')
        text.append("========== 日志 ==========").append('\n')

        if (entries.isEmpty()) {
            text.append("（缓冲为空）").append('\n')
        } else {
            entries.forEach { entry ->
                text.append(entry.format()).append('\n')
            }
        }
        return text.toString()
    }

    /**
     * 导出到文件，返回落盘后的 [File]。
     *
     * 目录优先 `getExternalFilesDir("logs")`（接电脑能直接拷），外部存储没挂上
     * （个别电视机的 USB 存储掉线）就退回内部 `filesDir/logs`，宁可路径藏在内部也别让功能不可用。
     * 目录建不出来 / 磁盘满抛 [IOException]，由页面层提示。
     */
    fun exportTo(context: Context): File {
        val dir = context.getExternalFilesDir(LOG_DIR) ?: File(context.filesDir, LOG_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("导出目录建不出来：" + dir.absolutePath)
        }
        val target = File(dir, fileName(System.currentTimeMillis()))
        target.writeText(dump(), Charsets.UTF_8)
        return target
    }

    /** 文件名：`tvplayer-log-20261120-225415.txt`。 */
    fun fileName(now: Long): String = FILE_PREFIX + stamp(now, FILE_TIME_PATTERN) + FILE_EXT

    /** 一行设备摘要，页面标题下面那行用它：`Xiaomi M2101K9C · Android 13 (API 33) · v1.0.0 (1)`。 */
    fun deviceSummary(): String = Build.MANUFACTURER + " " + Build.MODEL +
        " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")" +
        " · v" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"

    /** 字节数转人话：`812 B` / `1.2 KB`。导出结果行直接用它，不显示裸字节。 */
    fun formatBytes(bytes: Long): String {
        val safe = if (bytes < 0L) 0L else bytes
        return when {
            safe < 1024L -> safe.toString() + " B"
            safe < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", safe / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", safe / (1024.0 * 1024.0))
        }
    }

    private fun stamp(now: Long, pattern: String): String =
        SimpleDateFormat(pattern, Locale.US).format(Date(now))

    /** logcat 里的总 TAG，`adb logcat -s TVPlayer` 一条命令就能筛。 */
    private const val LOGCAT_TAG = "TVPlayer"
}
