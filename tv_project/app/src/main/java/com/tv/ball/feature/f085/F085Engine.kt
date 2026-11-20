package com.tv.ball.feature.f085

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一条本地崩溃报告的元信息。
 *
 * [file] 是 `filesDir/crash/` 下的真实文件；[timeText] / [sizeText] 是给人看的两列；
 * [summary] 取报告头里的「异常: …」那一行，列表里不用点进去就知道崩在哪。
 */
data class CrashReport(
    val file: File,
    val timeText: String,
    val sizeText: String,
    val summary: String
)

/**
 * F085「崩溃捕获与本地报告」的能力层。
 *
 * 三件事，都在本对象里：
 *   1. [CrashHandler] 实现 `Thread.UncaughtExceptionHandler`，挂在主线程上做**全局**捕获；
 *   2. 崩溃时把设备信息 + 版本 + 完整堆栈写进 `filesDir/crash/crash-<时间>.txt`；
 *   3. 下次启动列出 / 读 / 删这些文件（[listReports] / [readReport] / [deleteReport]）。
 *
 * 关键约束：`install()` 必须链式调用旧 handler。吞掉默认 handler 会让系统不再弹
 * 「应用已停止」、也不再重启进程——应用就变成"卡死在那儿"的样子，比直接崩还难查。
 * 这里先写盘（同步写完，留足时间），再 `previous.uncaughtException(...)` 把异常交回去。
 *
 * 两个入口要分清：
 *   · [CrashHandler] 走的是**真崩溃**路径：写报告后交给系统，进程照样结束；
 *   · [saveReport] 是给页面「制造一次测试异常」用的：try/catch 手动调用，
 *     只写报告、不交给系统，进程继续活着。两者写出的文件格式完全一样。
 */
object F085Engine {

    /** 报告目录名：`filesDir/crash`。放 filesDir 而不是 cacheDir——缓存会被系统清掉。 */
    const val DIR_NAME: String = "crash"

    /** 文件名前缀，后面接时间戳。 */
    private const val FILE_PREFIX: String = "crash-"

    /** 文件名里的时间戳格式：能排序、不带冒号（部分 ROM 不允许文件名含 `:`）。 */
    private const val FILE_TIME_PATTERN: String = "yyyyMMdd-HHmmss-SSS"

    /** 报告正文里的时间格式：给人读的。 */
    private const val HEADER_TIME_PATTERN: String = "yyyy-MM-dd HH:mm:ss.SSS Z"

    /** 单个堆栈的最高条数上限：某些递归爆栈的报告能写出几万行，TextView 会卡死。 */
    private const val MAX_STACK_LINES: Int = 400

    /** 报告目录；`filesDir` 拿不到时给 null，所有操作按"空"处理，不抛。 */
    fun reportDir(context: Context): File? {
        val base = runCatching { context.applicationContext?.filesDir ?: context.filesDir }.getOrNull()
        return base?.let { File(it, DIR_NAME) }
    }

    // region 安装 / 卸载

    /**
     * 挂上全局捕获，返回**被接管前的那个 handler**（卸载时原样交回去）。
     *
     * [previous] 就是链上原来那一环，崩溃时由 [CrashHandler] 在写完报告后原样调回去，
     * 所以系统仍然会弹「应用已停止」并结束进程。
     *
     * 重复调用是安全的：要是当前 handler 已经是自己的（之前装过没卸），
     * 不会出现 A→A→A 的自环，直接把先前那次装的交回，链不会越接越长。
     */
    fun install(
        previous: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler(),
        context: Context? = null
    ): Thread.UncaughtExceptionHandler? {
        // 已经是自己的就别再套一层（否则链会变成 自己→自己→旧）
        val existing = previous as? CrashHandler
        if (existing != null) {
            if (context != null) existing.appContext = context
            return existing.previous
        }
        val handler = CrashHandler(previous)
        handler.appContext = context
        Thread.setDefaultUncaughtExceptionHandler(handler)
        return previous
    }

    /**
     * 卸载并恢复成 [previous]。
     *
     * 只有在当前 handler **仍然是自己**时才恢复：别的模块（或系统）后来装了自己的，
     * 不该被本页一脚踹回老 handler。
     */
    fun uninstall(previous: Thread.UncaughtExceptionHandler?) {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current is CrashHandler) {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    /** 当前是否处在捕获状态（只看默认 handler 是不是本引擎这一份）。 */
    fun isInstalled(): Boolean =
        (Thread.getDefaultUncaughtExceptionHandler() as? CrashHandler)?.isForThisEngine() == true

    // endregion

    // region 写报告

    /**
     * 把一次异常写成报告文件，返回落盘的文件（失败时 null）。
     *
     * **同步写**：崩溃路径上进程随时会被系统杀掉，异步写大概率写一半就没机会了；
     * 报告就几 KB，主线程同步写完的代价远小于丢报告。
     *
     * [tag] 标记报告来源：`uncaught`（真崩溃）/ `manual`（页面里手动测试），
     * 在正文里留一行，方便区分"这次闪退"和"我刚才点的测试"。
     */
    fun saveReport(context: Context, throwable: Throwable, tag: String = TAG_UNCAUGHT): File? {
        val dir = reportDir(context) ?: return null
        if (!dir.exists() && !dir.mkdirs()) return null

        val now = Date()
        val name = FILE_PREFIX + SimpleDateFormat(FILE_TIME_PATTERN, Locale.US).format(now) + ".txt"
        val target = File(dir, name)
        val body = buildReport(context, throwable, tag, now)

        return try {
            target.writeText(body, Charsets.UTF_8)
            target
        } catch (e: Throwable) {
            // 磁盘满 / 权限被改：报告写不出来也不能把原异常盖掉，直接返回 null
            null
        }
    }

    /**
     * 报告正文：头（应用 / 设备 / 版本 / 来源 / 时间）+ 异常根因 + 完整堆栈 + 因果链。
     *
     * 因果链单独列一段：`Caused by:` 在堆栈里是嵌着的，拉直成一行行更好扫。
     */
    fun buildReport(
        context: Context,
        throwable: Throwable,
        tag: String,
        now: Date = Date()
    ): String {
        val sb = StringBuilder(2048)

        sb.appendLine("==== 崩溃报告 ====")
        sb.appendLine("应用: ${appLabel(context)}")
        sb.appendLine("包名: ${runCatching { context.packageName }.getOrDefault("?")}")
        sb.appendLine("版本: ${versionName(context)} (versionCode ${versionCode(context)})")
        sb.appendLine("时间: ${SimpleDateFormat(HEADER_TIME_PATTERN, Locale.US).format(now)}")
        sb.appendLine("来源: $tag${sourceNote(tag)}")
        sb.appendLine("线程: ${Thread.currentThread().name}")
        sb.appendLine("异常: ${describe(throwable)}")
        sb.appendLine()
        sb.appendLine("---- 设备 ----")
        sb.appendLine("机型: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("设备代号: ${Build.DEVICE} (${Build.PRODUCT})")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("构建: ${Build.DISPLAY} / ${Build.FINGERPRINT}")
        sb.appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        sb.appendLine()
        sb.appendLine("---- 堆栈 ----")
        sb.appendLine(stackText(throwable))
        sb.appendLine()
        sb.appendLine("---- 因果链 ----")
        sb.appendLine(causeChain(throwable))
        return sb.toString()
    }

    /** 完整堆栈；超过 [MAX_STACK_LINES] 行时截断并注明原本有多少行。 */
    fun stackText(throwable: Throwable): String {
        val raw = StringWriter()
        PrintWriter(raw).use { throwable.printStackTrace(it) }
        val lines = raw.toString().trimEnd().lines()
        if (lines.size <= MAX_STACK_LINES) return lines.joinToString("\n")
        val kept = lines.take(MAX_STACK_LINES).joinToString("\n")
        return "$kept\n… 省略 ${lines.size - MAX_STACK_LINES} 行（共 ${lines.size} 行）"
    }

    /** 因果链：`异常 <- 原因 <- 根因`，每条带异常类型与 message。 */
    fun causeChain(throwable: Throwable): String {
        val chain = ArrayList<String>()
        var current: Throwable? = throwable
        var guard = 0
        while (current != null && guard < 16) {
            chain.add(describe(current))
            val next = current.cause
            if (next === current) break
            current = next
            guard++
        }
        return chain.joinToString("\n   <- ")
    }

    /** `java.lang.IllegalStateException: 消息`；没有 message 时只给类名。 */
    fun describe(throwable: Throwable): String {
        val message = throwable.message
        val type = throwable.javaClass.name
        return if (message.isNullOrBlank()) type else "$type: $message"
    }

    // endregion

    // region 读取 / 列表 / 删除

    /**
     * 列出全部报告，**最新的在前**。
     *
     * 排序用文件名的倒序（文件名里是 `yyyyMMdd-HHmmss-SSS`，字典序即时间序），
     * 不依赖 lastModified：个别 ROM 上复制 / 恢复出来的文件 mtime 会错乱。
     */
    fun listReports(context: Context): List<CrashReport> {
        val dir = reportDir(context) ?: return emptyList()
        val files = runCatching {
            dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(".txt") }
        }.getOrNull() ?: return emptyList()

        return files.sortedByDescending { it.name }.map { file ->
            CrashReport(
                file = file,
                timeText = formatTime(file),
                sizeText = F085Engine.formatSize(file.length()),
                summary = headerValue(file, SUMMARY_KEY) ?: file.name
            )
        }
    }

    /** 读一份报告的全文；读不出来时返回一句可读的错误，页面照样能显示。 */
    fun readReport(file: File): String = runCatching {
        file.readText(Charsets.UTF_8)
    }.getOrElse { e ->
        "读取失败：${e.message ?: e.javaClass.simpleName}\n$file"
    }

    /**
     * 删一份报告。
     *
     * 只允许删报告目录下的文件：传进来一个别处的路径（误操作 / 旧数据）时不碰它，
     * 免得"清空历史"顺手把无关文件带走。
     */
    fun deleteReport(file: File): Boolean {
        if (!file.name.startsWith(FILE_PREFIX) || file.extension != "txt") return false
        val dir = file.parentFile ?: return false
        // 再挡一层：只认第一层目录下的文件，子目录里的同名前缀也不动
        if (dir.parentFile?.name != DIR_NAME && dir.name != DIR_NAME) return false
        return runCatching { file.isFile && file.delete() }.getOrDefault(false)
    }

    /** 清空全部报告，返回删掉的数量。 */
    fun deleteAll(context: Context): Int {
        var count = 0
        for (report in listReports(context)) {
            if (deleteReport(report.file)) count++
        }
        return count
    }

    /** 报告总数与总占用，页面顶部一次显示，省得逐条相加。 */
    fun totalSize(context: Context): Long = listReports(context).sumOf { it.file.length() }

    /**
     * 最后一次崩溃的一句话摘要：`时间 · 异常`。
     *
     * 没有报告时给 null，页面据此显示"还没有记录"。
     * 只读报告头前若干行（最多 64 行），不把整个文件读进内存。
     */
    fun lastCrashSummary(context: Context): String? {
        val latest = listReports(context).firstOrNull() ?: return null
        val summary = headerValue(latest.file, SUMMARY_KEY) ?: latest.file.name
        return "${latest.timeText} · $summary"
    }

    // endregion

    // region 小工具

    /** 人读大小：B / KB / MB，一位小数。报告都是 KB 级，不需要 GB 档。 */
    fun formatSize(bytes: Long): String {
        val b = if (bytes < 0L) 0L else bytes
        return when {
            b < 1024L -> "$b B"
            b < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", b / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024.0))
        }
    }

    /** 列表里的时间列：`MM-dd HH:mm:ss`，同一天崩的几份也分得开。 */
    private fun formatTime(file: File): String {
        val raw = file.name.removePrefix(FILE_PREFIX).removeSuffix(".txt")
        val parsed = runCatching {
            SimpleDateFormat(FILE_TIME_PATTERN, Locale.US).parse(raw)
        }.getOrNull()
        if (parsed != null) {
            return SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(parsed)
        }
        // 文件名不合模板（手工放进来的）就退回 mtime
        return SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(file.lastModified()))
    }

    /** 从报告头里取 `键: 值` 那一行的值；只扫前 64 行。 */
    private fun headerValue(file: File, key: String): String? = runCatching {
        file.useLines(Charsets.UTF_8) { lines ->
            lines.take(64)
                .firstOrNull { it.startsWith("$key: ") }
                ?.removePrefix("$key: ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    private fun sourceNote(tag: String): String = when (tag) {
        TAG_MANUAL -> "（本页测试按钮手动调用 saveReport 写盘，异常已被 catch，进程没有崩）"
        else -> "（全局 UncaughtExceptionHandler 捕获，写盘后已交还系统默认处理）"
    }

    private fun appLabel(context: Context): String = runCatching {
        val info = context.applicationInfo
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault("(未知)")

    private fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun versionCode(context: Context): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }.getOrDefault(0L)

    /** 报告头里「异常」那个键：列表摘要就靠它，改这里同时改 [buildReport]。 */
    private const val SUMMARY_KEY: String = "异常"

    /** 真崩溃。 */
    const val TAG_UNCAUGHT: String = "uncaught"

    /** 页面手动测试。 */
    const val TAG_MANUAL: String = "manual"

    // endregion

    /**
     * 全局未捕获异常处理器。
     *
     * 处理顺序（顺序不能换）：
     *   1. 先把异常写盘——此时进程还活着，系统的"应用已停止"对话框还没出来；
     *   2. 再调用 [previous]：系统默认 handler 会弹崩溃框并杀掉进程。
     *      少了这一步，应用就变成静默卡死的状态，用户只会觉得"没反应"。
     *
     * 写盘本身包在 try/catch 里：报告写不出来也必须把异常交回系统。
     */
    class CrashHandler(val previous: Thread.UncaughtExceptionHandler?) :
        Thread.UncaughtExceptionHandler {

        /** 应用上下文，[install] 之后由页面补上（handler 挂在 Thread 上，拿不到 Context）。 */
        @Volatile
        var appContext: Context? = null

        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            val context = appContext
            if (context != null) {
                // 报告写不出来（磁盘满 / 权限）也必须往下走：异常优先交回系统
                runCatching { saveReport(context, throwable, TAG_UNCAUGHT) }
            }
            // 无论写没写成，异常都交回系统：进程该结束就结束
            previous?.uncaughtException(thread, throwable)
        }

        internal fun isForThisEngine(): Boolean = true
    }
}
