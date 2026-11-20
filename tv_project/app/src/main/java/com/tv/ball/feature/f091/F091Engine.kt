package com.tv.ball.feature.f091

import android.content.Context
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一次启动里的一个打点：阶段名 + **相对启动原点**的毫秒。
 *
 * [atMs] 存累计时间而不是本段耗时：调用方只会在「这一刻」，不知道前任是谁，
 * 让它自己去减上一段的耗时，等于逼每个打点位置都得记住上一段的数字。
 * 段耗时统一由 [StartupRun.costs] 事后相减，只算一次，口径也只有一处。
 */
data class StageMark(val name: String, val atMs: Long)

/**
 * 一段的耗时 = 相邻两个打点的差。
 *
 * 第一段（第一个打点）的耗时是从原点到第一个打点的那一段时间：
 * 用 [StartupTrace.begin] 的默认原点（进程创建）时，它包含「进程 fork → Application」的冷启动固定开销；
 * 用 `begin(fromProcessStart = false)` 时，它就是「打第一点之前跑的那点代码」。
 */
data class StageCost(val name: String, val ms: Long)

/**
 * 一次完整的启动记录。
 *
 * 不可变：页面拿到的永远是一个定格的快照，不会看到"只打了一半点的启动"。
 *
 * [startedAt] 只是墙钟时间，用来在列表和导出里说清"哪一次"，**不参与任何耗时计算**——
 * 用墙钟算耗时会踩到系统对时（NTP 往前跳一下，一段 40 ms 的初始化变成负数）。
 */
data class StartupRun(
    val startedAt: Long,
    val marks: List<StageMark>
) {

    /** 合计耗时 = 最后一个打点的相对时间。没有打点就是 0。 */
    val totalMs: Long get() = marks.lastOrNull()?.atMs ?: 0L

    /** 每段的耗时，顺序与 [marks] 一致；负差（乱序调用）按 0 处理，不让它污染"最慢一段"。 */
    val costs: List<StageCost>
        get() {
            if (marks.isEmpty()) return emptyList()
            val out = ArrayList<StageCost>(marks.size)
            var previous = 0L
            marks.forEach { mark ->
                val delta = mark.atMs - previous
                out.add(StageCost(mark.name, if (delta < 0L) 0L else delta))
                previous = mark.atMs
            }
            return out
        }

    /** 最慢的那一段；没有打点返回 null。并列时取更靠前的一段（先到的那段更值得先看）。 */
    val slowestStage: StageCost? get() = costs.maxByOrNull { it.ms }

    /** 某个阶段自己那一段的耗时；这次启动没打这个点返回 null。 */
    fun costOf(name: String): Long? = costs.firstOrNull { it.name == name }?.ms

    /** 某个阶段的累计时间（从原点到这一点的毫秒）。 */
    fun markAt(name: String): Long? = marks.firstOrNull { it.name == name }?.atMs
}

/**
 * F091「启动耗时打点」的探针。
 *
 * ## 这层是什么
 *
 * 一个**独立探针**：谁想量哪一段，就在那一段结束的位置写一行 [mark]，不要求宿主改
 * Application、也不要求页面先打开。页面只负责把结果画出来和导出，量测本身跟界面无关。
 *
 * ## 为什么用 uptimeMillis 而不是 System.currentTimeMillis
 *
 * 冷启动的耗时要能反映"用户从点图标到看见画面"的真实等待。
 * [SystemClock.uptimeMillis] 是单调时钟，不受系统对时、时区切换影响，
 * 也不会因为用户中途改时间出现负数；墙钟只拿来标记"哪一次"（[StartupRun.startedAt]）。
 *
 * ## 原点为什么可以往回推到进程创建
 *
 * 真正的冷启动在第一行业务代码之前就已经花掉了几百毫秒（进程 fork、Application 类加载）。
 * [Process.getStartUptimeMillis] 给出进程被 fork 的时刻，拿它当原点，
 * 第一个打点就能看到这段"看不见的开销"。演示页要的是"这一次模拟"的耗时，
 * 所以它显式用 `begin(fromProcessStart = false)` 把原点挪到按钮按下的那一刻。
 *
 * ## Context 从哪来
 *
 * 接口按需求保持无参（[mark] / [finish] / [snapshot] 都不带 Context），
 * 落盘所需的 Context 由宿主在任意一处调用一次 [attach] 交进来（页面在 onCreate 里交一次就够）。
 * 没 attach 过也能用，只是历史留在内存里、进程结束就没了——探针不应该因为没接线就崩掉。
 */
object StartupTrace {

    /** 历史最多留多少次启动；超出丢最旧的一条（导出里看得见被留的是哪几次）。 */
    const val MAX_RUNS: Int = 10

    /** 单独一份 prefs，不跟别的功能挤同一个文件。 */
    const val PREFS_NAME: String = "f091_startup_trace"

    /** 约定好的三个阶段名；页面按这三个名字取数，宿主打点时用同一份常量，避免字符串写歪。 */
    const val STAGE_APPLICATION: String = "Application onCreate"
    const val STAGE_ACTIVITY: String = "Activity onCreate"
    const val STAGE_FIRST_FRAME: String = "首帧"

    /** 页面与宿主共用的三个阶段顺序：就是一次冷启动的时间顺序。 */
    val STAGES: List<String> = listOf(STAGE_APPLICATION, STAGE_ACTIVITY, STAGE_FIRST_FRAME)

    private const val KEY_RUNS = "runs"
    private const val KEY_STARTED_AT = "startedAt"
    private const val KEY_MARKS = "marks"
    private const val KEY_NAME = "name"
    private const val KEY_AT = "at"
    private const val UNNAMED_STAGE = "未命名阶段"
    private const val EXPORT_DIR = "f091"
    private const val EXPORT_PREFIX = "startup-trace-"
    private const val EXPORT_EXT = ".txt"

    /** 落盘用的 Context；[attach] 之前是 null。 */
    private var appContext: Context? = null

    /** 当前这一轮的原点（uptimeMillis）。0 表示没有进行中的一轮。 */
    private var origin: Long = 0L

    /** 这一轮开始的墙钟时间，只用于展示。 */
    private var startedAt: Long = 0L

    /** 这一轮已打下的点。 */
    private var pending: MutableList<StageMark> = ArrayList()

    /** 历史（新 → 旧），最多 [MAX_RUNS] 条。 */
    private var history: MutableList<StartupRun> = ArrayList()

    /** 历史是否已经从盘上读过；只读一次，之后以内存为准（内存里永远是最新的那份）。 */
    private var restored: Boolean = false

    /**
     * 日期格式只在这里建一份。
     *
     * [SimpleDateFormat] 不是线程安全的：探针的调用点都在主线程（Application/Activity/首帧回调），
     * 加上它只在导出和列表渲染时用，串行访问就够了。真要在后台线程打点，这一层得换成
     * `ThreadLocal` 或 `java.time`；现在没必要为一个探针背这个复杂度。
     */
    private val clockFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val fileStampFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    // ===== 生命周期 =====

    /**
     * 交给探针一个 Context，并顺手把上一次进程留下的历史读进内存。
     *
     * 幂等：重复调用只更新 Context，不会把已经读过的历史再读一遍（否则刚记下的一轮会被盘上的旧数据覆盖）。
     */
    fun attach(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (restored) return
        val raw = prefs(app).getString(KEY_RUNS, null)
        history = ArrayList(decode(raw))
        restored = true
    }

    /**
     * 开一轮新的启动记录。
     *
     * [fromProcessStart] = true（默认，真实探针用这个）：原点取进程创建时刻，
     * 第一个打点就能看见 Application 之前的那段固定开销；拿不到进程创建时刻时退回"此刻"。
     *
     * [fromProcessStart] = false：原点就是此刻。演示页用这个，否则进程已经活了几小时，
     * 第一段会变成"进程活了多久"，看不出这是在模拟一次冷启动。
     *
     * 不调用也能用：[mark] 发现没有进行中的一轮时会自动按默认原点开一轮，
     * 这样宿主只要在最关心的地方插一行 [mark] 就够了，不必先写 begin。
     */
    fun begin(fromProcessStart: Boolean = true) {
        val now = SystemClock.uptimeMillis()
        origin = (if (fromProcessStart) processStartUptime() else null) ?: now
        startedAt = System.currentTimeMillis()
        pending = ArrayList()
    }

    /**
     * 打一个点：记下「从原点到此刻」的毫秒，返回刚记下的这一条。
     *
     * 名字为空会记成 [UNNAMED_STAGE]：探针在线上跑，宁可留一条名字不好看的记录，
     * 也不要因为一句手滑的 `mark("")` 把整次启动丢掉。
     *
     * 允许同名重复（比如列表页每重建一次首帧都打一次），[StartupRun.costOf] 取第一条。
     */
    fun mark(name: String): StageMark {
        if (origin <= 0L) begin()
        val stage = name.trim().ifEmpty { UNNAMED_STAGE }
        val mark = StageMark(stage, SystemClock.uptimeMillis() - origin)
        pending.add(mark)
        return mark
    }

    /**
     * 收尾：把这一轮的定点封成一条 [StartupRun]、压进历史、落盘，然后开始下一轮的空位。
     *
     * 一个点都没打就返回 null —— 空记录只会把"最近 10 次"里真有用的那几次挤掉。
     * 落盘失败不抛给调用方：量测是辅助信息，不能因为写盘出错让首页打不开。
     */
    fun finish(): StartupRun? {
        if (pending.isEmpty()) return null
        val run = StartupRun(startedAt, pending.toList())
        pending = ArrayList()
        origin = 0L
        history.add(0, run)
        while (history.size > MAX_RUNS) {
            history.removeAt(history.size - 1)
        }
        persist()
        return run
    }

    // ===== 读取 =====

    /** 历史快照，新 → 旧，最多 [MAX_RUNS] 条。 */
    fun snapshot(): List<StartupRun> = history.toList()

    /** 最近一次（也就是页面上"上次启动"要显示的那次）；还没有记录返回 null。 */
    fun latest(): StartupRun? = history.firstOrNull()

    /** 这一轮已经打了哪些点（用于边跑边看，不用等 [finish]）。 */
    fun inProgress(): List<StageMark> = pending.toList()

    // ===== 导出 =====

    /**
     * 把历史导成一段人能读、也能贴进工单的纯文本。
     *
     * 每段同时给两个数：`+段耗时`（这一段花多久）和 `@累计`（到这一段为止花了多久）。
     * 只看段耗时容易漏掉"每段都不慢但加起来就是慢"的情况，累计列就是给这种情况留的。
     */
    fun exportText(now: Long = System.currentTimeMillis()): String {
        val builder = StringBuilder()
        builder.append("F091 启动耗时打点\n")
        builder.append("导出时间: ").append(formatClock(now)).append('\n')
        builder.append("记录: ").append(history.size).append(" / ").append(MAX_RUNS).append(" 次（新 → 旧）\n")
        if (history.isEmpty()) {
            builder.append("\n（暂无记录）\n")
            return builder.toString()
        }
        history.forEachIndexed { index, run ->
            builder.append('\n')
            builder.append('#').append(index + 1).append(' ')
                .append(formatClock(run.startedAt))
                .append("  合计 ").append(run.totalMs).append(" ms")
                .append("  阶段 ").append(run.marks.size).append(" 个")
            run.slowestStage?.let { slowest ->
                builder.append("  最慢 ").append(slowest.name).append(' ').append(slowest.ms).append(" ms")
            }
            builder.append('\n')
            var previous = 0L
            run.marks.forEach { stage ->
                val delta = stage.atMs - previous
                previous = stage.atMs
                builder.append("  ").append(stage.name.padEnd(24))
                    .append(" +").append(delta).append(" ms")
                    .append("   @").append(stage.atMs).append(" ms\n")
            }
        }
        return builder.toString()
    }

    /**
     * 导出目录：优先应用专属外部目录（`Android/data/<包名>/files/f091`）。
     *
     * 这个目录不需要任何存储权限，用户也能用 adb 或文件管理器直接取走——
     * 电视盒子没有分享面板，导出的实际用法就是"用 adb 拉出来"。
     */
    fun exportDir(context: Context): File =
        context.getExternalFilesDir(EXPORT_DIR) ?: File(context.filesDir, EXPORT_DIR)

    /** 把 [text] 写成一次导出文件，返回落盘后的文件；目录建不出来或写失败抛 [IOException]，由页面提示。 */
    fun writeExport(
        context: Context,
        text: String,
        now: Long = System.currentTimeMillis()
    ): File {
        val dir = exportDir(context)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("导出目录建不出来: " + dir.absolutePath)
        }
        val file = File(dir, EXPORT_PREFIX + fileStampFormat.format(Date(now)) + EXPORT_EXT)
        file.writeText(text, Charsets.UTF_8)
        return file
    }

    // ===== 序列化 =====

    /**
     * 历史 → JSON。手写而不是靠反射库：探针是个独立小模块，不该为一个数组引入序列化依赖。
     *
     * 形如 `[{"startedAt":1732100000000,"marks":[{"name":"Application onCreate","at":312}]}]`。
     */
    fun encode(runs: List<StartupRun>): String {
        val root = JSONArray()
        runs.forEach { run ->
            val marks = JSONArray()
            run.marks.forEach { stage ->
                marks.put(
                    JSONObject()
                        .put(KEY_NAME, stage.name)
                        .put(KEY_AT, stage.atMs)
                )
            }
            root.put(
                JSONObject()
                    .put(KEY_STARTED_AT, run.startedAt)
                    .put(KEY_MARKS, marks)
            )
        }
        return root.toString()
    }

    /**
     * JSON → 历史。
     *
     * 逐条容错：整体不是合法 JSON 就当没有记录；某一条坏了只丢那一条，某个点名字空了只丢那个点。
     * 量测数据是辅助信息，不能因为一次进程被杀导致的半截写入，让页面直接打不开。
     */
    fun decode(raw: String?): List<StartupRun> {
        if (raw.isNullOrBlank()) return emptyList()
        val root = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val runs = ArrayList<StartupRun>(root.length())
        for (index in 0 until root.length()) {
            val json = runCatching { root.getJSONObject(index) }.getOrNull() ?: continue
            val run = runCatching { parseRun(json) }.getOrNull() ?: continue
            if (run.marks.isNotEmpty()) runs.add(run)
        }
        return runs
    }

    private fun parseRun(json: JSONObject): StartupRun {
        val startedAt = json.optLong(KEY_STARTED_AT, 0L)
        val marksJson = json.optJSONArray(KEY_MARKS) ?: JSONArray()
        val marks = ArrayList<StageMark>(marksJson.length())
        for (index in 0 until marksJson.length()) {
            val mark = marksJson.optJSONObject(index) ?: continue
            val name = mark.optString(KEY_NAME, "").trim()
            if (name.isEmpty()) continue
            marks.add(StageMark(name, mark.optLong(KEY_AT, 0L)))
        }
        return StartupRun(startedAt, marks)
    }

    // ===== 小工具 =====

    /** 墙钟毫秒格式化成 `2026-11-20 23:30:00`，页面和导出共用一份口径。 */
    fun formatClock(at: Long): String = clockFormat.format(Date(at))

    /**
     * 进程被 fork 的时刻（uptimeMillis），拿不到返回 null。
     *
     * [Process.getStartUptimeMillis] 是 API 24 起才有的，minSdk 正好是 24；
     * 仍然用 runCatching 兜一层——个别定制 ROM 会把它做成直接抛异常。
     * 返回值必须落在 (0, now) 区间内，否则（0 或未来时间）认为不可信，退回"此刻"当原点。
     */
    private fun processStartUptime(): Long? {
        val start = runCatching { Process.getStartUptimeMillis() }.getOrNull() ?: return null
        val now = SystemClock.uptimeMillis()
        return if (start > 0L && start < now) start else null
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun persist() {
        val context = appContext ?: return
        runCatching {
            prefs(context).edit().putString(KEY_RUNS, encode(history)).apply()
        }
    }
}
