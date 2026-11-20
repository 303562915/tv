package com.tv.ball.feature.f092

import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 内存与线程探针引擎。
 *
 * ## 为什么要有这东西
 *
 * 「长播会不会越来越卡」这件事，肉眼看不出来：一分钟里堆涨 2 MB 完全正常（图片缓存、临时 Bitmap），
 * 但涨到 200 MB 还只增不减就是漏了。要判断"是不是漏"，需要的不是某一刻的数字，
 * 而是**一条按秒排下来的序列**：看它有没有持续向上、有没有回落。所以这里做三件事——
 * 按秒采样、把样本串成一条定长环形序列、从这条序列里算出趋势判定。页面只负责显示。
 *
 * ## 采样口径
 *
 * - **堆内存**：`Runtime.getRuntime()` 的 `totalMemory() - freeMemory()` 是"当前已用"，
 *   `maxMemory()` 是这台设备的单进程堆上限。两个值都换算成 MB。
 *   `maxMemory()` 在极少数没有上限的运行时里会返回一个天文数字，那种情况直接按 0 处理
 *   （页面显示成"未设上限"），免得占比算出来是 0.00001%。
 * - **线程数**：优先 `activeThreads()`，它给的是 JVM 侧统计的真实存活线程；
 *   某些厂商 ROM 上 MXBean 会抛，那时退回 `Thread.activeCount()`——口径略粗（只数当前线程组），
 *   但趋势仍然可用，页面不需要知道降级发生过。
 * - **时间**：记 `SystemClock.elapsedRealtime()`，开机以来的单调毫秒。用它而不是墙上时间，
 *   是因为用户改时区、系统校时都会让 `currentTimeMillis` 跳一下，一跳就会在曲线上画出假尖峰。
 *   导出文本时再把单调时间折算成钟表时间给人看。
 *
 * ## 环形缓冲
 *
 * 容量 [RING_CAPACITY] = 300，按 [SAMPLE_INTERVAL_MS] = 1 秒采一次，正好覆盖 5 分钟。
 * 满了就丢最旧的一条：探针是长时间挂着的工具，绝不能让它自己变成内存泄漏源。
 *
 * ## 趋势判定
 *
 * [trend] 只看窗口（[WINDOW_MS] = 5 分钟）里的首尾两条：`deltaMb = 最新 - 最旧`。
 * 阈值可调（页面上的 ± 按钮），超过阈值即判为"异常增长"。两个防误报的细节：
 *
 * 1. 窗口内样本少于 2 条时不给判定——一个点画不出趋势；
 * 2. 首尾间隔小于 [MIN_TREND_SPAN_MS]（10 秒）时一律判"平稳"：刚进页面时堆从 30 MB 跳到 60 MB
 *    是首屏加载的正常抖动，不是泄漏。delta 仍然如实返回，页面想显示就显示。
 *
 * 全程只有 [sample] 读系统、[append] 写缓冲，两者都可以在任意线程调，内部一把锁。
 */
object F092Engine {

    /** 环形缓冲容量：1 秒一条 × 300 条 = 5 分钟。 */
    const val RING_CAPACITY = 300

    /** 采样间隔（毫秒）。页面上的定时器用的就是这个值。 */
    const val SAMPLE_INTERVAL_MS = 1_000L

    /** 趋势窗口：最近 5 分钟。 */
    const val WINDOW_MS = 5 * 60 * 1000L

    /** 首尾间隔短于这个值不做"增长"判定，避免首屏加载抖动被当泄漏。 */
    const val MIN_TREND_SPAN_MS = 10_000L

    /** 默认增长阈值（MB）。页面上的 ± 按钮在这个值的上下调整。 */
    const val DEFAULT_GROWTH_MB = 8f

    /** 阈值调整步长（MB）。 */
    const val THRESHOLD_STEP_MB = 1f

    /** 阈值下限（MB）。 */
    const val MIN_THRESHOLD_MB = 1f

    /** 阈值上限（MB）。 */
    const val MAX_THRESHOLD_MB = 64f

    /**
     * 一条采样。
     *
     * @param heapUsedMb 当前已用堆（MB，已由字节换算）。
     * @param heapMaxMb 单进程堆上限（MB）；运行时未设上限时为 0。
     * @param threadCount 存活线程数。
     * @param atMs `SystemClock.elapsedRealtime()` 的单调毫秒，用于排序、求跨度和换算钟表时间。
     */
    data class Sample(
        val heapUsedMb: Float,
        val heapMaxMb: Float,
        val threadCount: Int,
        val atMs: Long
    ) {
        /** 已用 / 上限，0..1；上限未知时为 0（页面据此显示"—"而不是 0%）。 */
        val heapRatio: Float
            get() = if (heapMaxMb <= 0f) 0f else (heapUsedMb / heapMaxMb).coerceIn(0f, 1f)

        /** 上限是否可信（正常运行时的 `maxMemory()` 总有值）。 */
        val hasHeapCeiling: Boolean
            get() = heapMaxMb > 0f
    
    /** Android 没有 ThreadMXBean：递归数线程组里的活跃线程。 */
    private fun activeThreads(): Int {
        val root = Thread.currentThread().threadGroup?.let { g ->
            var cur = g
            while (cur.parent != null) cur = cur.parent!!
            cur
        } ?: return Thread.activeCount()
        return countGroup(root)
    }

    private fun countGroup(group: ThreadGroup): Int {
        var n = group.activeCount()
        val children = arrayOfNulls<ThreadGroup>(group.activeGroupCount() + 4)
        val got = group.enumerate(children, true)
        for (i in 0 until got) children[i]?.let { n += countGroup(it) }
        return n
    }
}

    /**
     * 窗口趋势。
     *
     * @param growing 是否判定为异常增长（超过阈值且跨度够长）。
     * @param deltaMb 窗口首尾的堆差（MB），可为负——回落说明 GC 起了作用，是健康信号。
     */
    data class Trend(val growing: Boolean, val deltaMb: Float)

    /** 缓冲的保护锁。采样、追加、取快照都在锁里完成，页面 1 秒一次、导出按钮随时按都不冲突。 */
    private val lock = Any()

    private val ring = ArrayDeque<Sample>(RING_CAPACITY)

    // ===== 采样 =====

    /**
     * 采一条当前值。
     *
     * 只读系统状态、不碰缓冲：[sample] 和 [append] 分开，是为了让调用方能先看到值再决定要不要留档
     * （例如"清空记录"之后的第一条仍然要显示在页面上）。
     *
     * @param nowMs 采样时刻，默认取单调时钟；单测可以塞固定值。
     */
    fun sample(nowMs: Long = SystemClock.elapsedRealtime()): Sample {
        val runtime = Runtime.getRuntime()
        val usedBytes = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        val maxBytes = runtime.maxMemory()

        // maxMemory() 偶发返回天文数字（无上限运行时），那种情况按"未设上限"报 0。
        val maxMb = when {
            maxBytes <= 0L -> 0f
            maxBytes > UNREASONABLE_HEAP_BYTES -> 0f
            else -> bytesToMb(maxBytes)
        }

        return Sample(
            heapUsedMb = bytesToMb(usedBytes),
            heapMaxMb = maxMb,
            threadCount = liveThreadCount(),
            atMs = nowMs
        )
    }

    /**
     * 追加一条样本，超出容量就丢最旧的一条。
     *
     * 重复追加同一时刻的样本不会去重：探针记录的是"被观测到了什么"，不做业务层面的清洗。
     */
    fun append(sample: Sample) {
        synchronized(lock) {
            if (ring.size >= RING_CAPACITY) ring.removeFirst()
            ring.addLast(sample)
        }
    }

    /** 当前缓冲里的全部样本，按时间升序。返回副本，调用方随便改不会影响缓冲。 */
    fun snapshot(): List<Sample> = synchronized(lock) { ring.toList() }

    /** 最新一条；还没采过就是 null。 */
    fun latest(): Sample? = synchronized(lock) { ring.lastOrNull() }

    /** 丢请求清空缓冲。页面上的「清空记录」用它重新开始观察。 */
    fun clear() {
        synchronized(lock) { ring.clear() }
    }

    // ===== 趋势 =====

    /**
     * 窗口趋势，默认阈值 [DEFAULT_GROWTH_MB]；页面按用户调的阈值传参。
     *
     * 判定规则见类文档：首尾差 ≥ 阈值、且首尾跨度 ≥ [MIN_TREND_SPAN_MS] 才算增长。
     */
    fun trend(thresholdMb: Float = DEFAULT_GROWTH_MB): Trend {
        val window = window()
        if (window.size < 2) return Trend(growing = false, deltaMb = 0f)

        val first = window.first()
        val last = window.last()
        val deltaMb = last.heapUsedMb - first.heapUsedMb
        val spanMs = last.atMs - first.atMs
        val growing = spanMs >= MIN_TREND_SPAN_MS && deltaMb >= thresholdMb
        return Trend(growing = growing, deltaMb = deltaMb)
    }

    /** 窗口内线程数的首尾差；没有足够样本时为 0。正数说明线程在变多（常见于反复 RecyclerView 重建）。 */
    fun threadDelta(): Int {
        val window = window()
        if (window.size < 2) return 0
        return window.last().threadCount - window.first().threadCount
    }

    /** 窗口内首尾的跨度（毫秒）；不足两条时为 0。 */
    fun windowSpanMs(): Long {
        val window = window()
        if (window.size < 2) return 0L
        return window.last().atMs - window.first().atMs
    }

    /**
     * 截出最近 [WINDOW_MS] 的那一段。
     *
     * 缓冲本身只有 5 分钟，正常情况下窗口 = 整个缓冲；但用户把页面切到后台再回来、
     * 或者设备被挂起过，样本间隔会被拉长，这时缓冲覆盖的时间会超过 5 分钟，
     * 就必须真的按时间截断，否则曲线横轴会悄悄变成 8 分钟。
     */
    fun window(): List<Sample> {
        val list = snapshot()
        if (list.isEmpty()) return emptyList()
        val newest = list.last().atMs
        val start = list.indexOfFirst { newest - it.atMs <= WINDOW_MS }
        // start <= 0 意味着整段缓冲都在 5 分钟内，直接用；否则丢掉窗口外的开头部分。
        return if (start <= 0) list else list.drop(start)
    }

    // ===== 导出 =====

    /**
     * 把缓冲导成纯文本：人看的那一段（当前值 + 趋势判定）+ 机器能对表的一行一样本。
     *
     * 不接 Context：文案在这里拼好，页面塞进 TextView 即可，也可以整段拷走贴给同事。
     * 时间同时给「时刻」和「距今」两列——前者对应日志，后者对应"涨了多久"。
     *
     * @param thresholdMb 判定阈值，默认 [DEFAULT_GROWTH_MB]；页面传当前调过的值，导出文本里的结论才和页面一致。
     */
    fun exportText(thresholdMb: Float = DEFAULT_GROWTH_MB): String {
        val list = snapshot()
        val sb = StringBuilder(64 + list.size * 48)
        val wallNow = System.currentTimeMillis()
        val wallOffset = wallNow - SystemClock.elapsedRealtime()

        sb.append("内存与线程探针 · 采样导出").append('\n')
        sb.append("生成时间：").append(stamp(wallNow)).append('\n')
        sb.append("采样间隔：").append(mb(SAMPLE_INTERVAL_MS / 1000f)).append(" 秒")
        sb.append("　容量：").append(list.size).append(" / ").append(RING_CAPACITY).append(" 条")
        if (list.size >= 2) {
            sb.append("　覆盖：").append(spanLabel(list.last().atMs - list.first().atMs))
        }
        sb.append("\n\n")

        val latest = list.lastOrNull()
        if (latest == null) {
            sb.append("还没有样本：进入页面后每 1 秒记录一条，最多保留最近 ")
                .append(RING_CAPACITY)
                .append(" 条（约 5 分钟）。")
            return sb.toString()
        }

        sb.append("当前堆内存：").append(mb(latest.heapUsedMb)).append(" MB / ")
        sb.append(if (latest.hasHeapCeiling) mb(latest.heapMaxMb) + " MB" else "未设上限")
        sb.append("（").append(percentLabel(latest.heapRatio)).append("）").append('\n')
        sb.append("当前线程数：").append(latest.threadCount).append('\n')

        val trend = trend(thresholdMb)
        sb.append("窗口趋势：").append(signedMb(trend.deltaMb))
        sb.append(" MB　阈值 ").append(signedMb(thresholdMb)).append(" MB　判定：")
        sb.append(if (trend.growing) "异常增长" else "未见异常增长").append('\n')
        sb.append("线程变化：").append(signedInt(threadDelta()))
        sb.append(" 根　跨度 ").append(spanLabel(windowSpanMs())).append("\n\n")

        sb.append("时刻        距今     堆MB    上限MB   占用     线程").append('\n')
        val newest = list.last().atMs
        for (sample in list) {
            sb.append(clockOf(sample.atMs, wallOffset)).append("  ")
            sb.append(padLeft(spanLabel(newest - sample.atMs), 7)).append("  ")
            sb.append(padLeft(mb(sample.heapUsedMb), 7)).append("  ")
            sb.append(padLeft(ceilingLabel(sample), 7)).append("  ")
            sb.append(padLeft(percentLabel(sample.heapRatio), 5)).append("  ")
            sb.append(padLeft(sample.threadCount.toString(), 4)).append('\n')
        }

        sb.append('\n')
        sb.append("读法：堆MB 列持续上升不回落 = 可疑泄漏；线程 列只增不减 = 有对象没被回收（线程持有 Context 就一起漏）。")
        sb.append("判定阈值可调，短时抖动（首屏加载、切页）通常会自己回落到基线，不算增长。")
        return sb.toString()
    }

    // ===== 内部工具 =====

    /** `maxMemory()` 大于这个数就认为"未设上限"（1 TB 的字节数），避免占比算成 0.00001%。 */
    private const val UNREASONABLE_HEAP_BYTES = 1024L * 1024L * 1024L * 1024L

    private const val BYTES_PER_MB = 1024f * 1024f

    private fun bytesToMb(bytes: Long): Float = bytes / BYTES_PER_MB

    /** 存活线程数：优先 MXBean，抛了退回 `Thread.activeCount()`（口径粗一点但趋势可用）。 */
    private fun liveThreadCount(): Int {
        val precise = runCatching { activeThreads() }.getOrNull()
        if (precise != null && precise > 0) return precise
        return Thread.activeCount()
    }

    private fun mb(value: Float): String = String.format(Locale.US, "%.1f", value)

    /** 带符号的 MB：`+1.8` / `-0.4`，负数不加号（JS 里那种 +-0 的写法这里不要）。 */
    private fun signedMb(value: Float): String =
        String.format(Locale.US, "%+.1f", value)

    private fun signedInt(value: Int): String = if (value > 0) "+$value" else value.toString()

    private fun percentLabel(ratio: Float): String = String.format(Locale.US, "%.1f%%", ratio * 100f)

    /** 上限未知时显示 `—`，别把 0 MB 当成"堆满"。 */
    private fun ceilingLabel(sample: Sample): String =
        if (sample.hasHeapCeiling) mb(sample.heapMaxMb) else "—"

    private fun padLeft(text: String, width: Int): String =
        if (text.length >= width) text else " ".repeat(width - text.length) + text

    private fun stamp(wallMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(wallMs))

    private fun clockOf(atMs: Long, wallOffsetMs: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(atMs + wallOffsetMs))

    /** 跨度说人话：`45 秒` / `3 分 12 秒`。 */
    fun spanLabel(spanMs: Long): String {
        val totalSec = (spanMs / 1000L).coerceAtLeast(0L)
        val min = totalSec / 60L
        val sec = totalSec % 60L
        return if (min <= 0L) "${sec} 秒" else "${min} 分 ${sec} 秒"
    }

    /** Android 上没有 ThreadMXBean：递归数线程组里的活跃线程。 */
    private fun activeThreads(): Int {
        var group = Thread.currentThread().threadGroup
        while (group?.parent != null) group = group.parent
        return group?.let { countGroup(it) } ?: Thread.activeCount()
    }

    private fun countGroup(group: ThreadGroup): Int {
        var n = group.activeCount()
        val children = arrayOfNulls<ThreadGroup>(group.activeGroupCount() + 4)
        val got = group.enumerate(children, true)
        for (i in 0 until got) {
            children[i]?.let { n += countGroup(it) }
        }
        return n
    }
}
