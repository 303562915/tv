package com.tv.ball.feature.f048

/**
 * F048 的防抖与竞态逻辑（纯 Kotlin，不碰 Android API）。
 *
 * 两个独立的零件，各管一件事：
 * 1. [Debouncer]：输入侧的防抖窗口。每次输入只登记「待发文本」并重置静默计时，
 *    窗口内还有后续输入就不发请求；
 * 2. [QueryRegistry]：请求侧的序号登记。每次发请求领一个递增序号，
 *    回来的结果只有序号还等于最新序号时才允许渲染，等于把旧请求全部作废。
 *
 * 时间来源用 [Clock] 注入，Activity 传 SystemClock，测试里传假时钟即可精确推进窗口。
 */
object F048Engine {

    /** 默认防抖窗口，验收要求的 300ms。 */
    const val DEFAULT_DEBOUNCE_MS: Long = 300L

    /** 参数面板每次调整的步长。 */
    const val DEBOUNCE_STEP_MS: Long = 50L

    const val MIN_DEBOUNCE_MS: Long = 0L
    const val MAX_DEBOUNCE_MS: Long = 1000L

    /** 「模拟连打 5 次」每次敲键的间隔，故意小于默认窗口，好把防抖打出来。 */
    const val BURST_INTERVAL_MS: Long = 120L

    /** 模拟网络的基准往返时延，用来制造「旧结果晚回来」的竞态。 */
    const val BASE_LATENCY_MS: Long = 380L

    /** 时间来源，返回毫秒即可，不要求绝对时间。 */
    fun interface Clock {
        fun now(): Long
    }

    /** 时间线上会出现的事件类型。 */
    enum class EventKind {
        /** 请求真的发出去了。 */
        SENT,

        /** 输入被防抖窗口拦住，没发出去。 */
        DROPPED,

        /** 结果回来时已经有更新的请求，本次结果被丢弃。 */
        OVERRIDDEN,

        /** 只跟参数调整有关，不影响请求。 */
        CONFIG
    }

    /**
     * 输入防抖。
     *
     * 用法：每次文本变化就调用一次 [onInput]。
     * - 返回 `true` 表示「现在就该发请求」（防抖关闭，窗口为 0）；
     * - 返回 `false` 表示「先别发」，调用方用 [quietRemainingMs] / [isQuiet] 决定何时补发。
     *
     * [pendingText] 始终是最后一次输入的文本，窗口结束时拿它去发请求。
     */
    class Debouncer(
        val delayMs: Long,
        private val clock: Clock = Clock { System.currentTimeMillis() }
    ) {

        /** 最后一次输入的文本，窗口到点后就是应该发出去的查询。 */
        var pendingText: String = ""
            private set

        /** 输入版本号，每来一次输入 +1，方便调用方判断「我拿到的是不是还是同一批输入」。 */
        var version: Int = 0
            private set

        private var lastInputAt: Long = NEVER

        /** 登记一次输入；返回此刻是否就该发请求（窗口 <= 0 才算立即发）。 */
        fun onInput(text: String): Boolean {
            pendingText = text
            version += 1
            lastInputAt = clock.now()
            return delayMs <= 0L
        }

        /** 静默窗口是否已经走完。 */
        fun isQuiet(): Boolean =
            lastInputAt != NEVER && clock.now() - lastInputAt >= delayMs

        /** 距离窗口结束还剩多少毫秒，已经结束则为 0。 */
        fun quietRemainingMs(): Long {
            if (lastInputAt == NEVER) return 0L
            val elapsed = clock.now() - lastInputAt
            return if (elapsed >= delayMs) 0L else delayMs - elapsed
        }

        /** 清空状态（输入被清掉、或参数被改动时调用）。 */
        fun reset() {
            pendingText = ""
            lastInputAt = NEVER
        }

        private companion object {
            const val NEVER: Long = Long.MIN_VALUE
        }
    }

    /**
     * 请求序号登记表，负责「只接受最新」。
     *
     * - [begin] 领序号，序号单调递增，序号最大者就是最新请求；
     * - [accept] 只在序号仍是最新时返回 `true`，旧请求回来一律 `false`；
     * - [cancelAll] 直接让所有在飞请求过期，用于输入被清空等场景。
     */
    class QueryRegistry {

        private var issued: Int = 0
        private var latest: Int = 0
        private val queries: MutableMap<Int, String> = LinkedHashMap()

        /** 最近一次被接受（真正渲染）的序号。 */
        var acceptedSeq: Int = 0
            private set

        /** 当前最新序号，也就是唯一还有资格渲染的那个。 */
        val latestSeq: Int
            get() = latest

        /** 一共发过多少次请求。 */
        val issuedCount: Int
            get() = issued

        /** 领一个新序号；[query] 只是留档，方便日志回查。 */
        fun begin(query: String): Int {
            issued += 1
            latest = issued
            queries[issued] = query
            return issued
        }

        /** 结果回来时调用：序号仍是最新才接受。 */
        fun accept(seq: Int): Boolean {
            if (seq != latest) return false
            acceptedSeq = seq
            return true
        }

        /** 这个序号是不是已经过期。 */
        fun isStale(seq: Int): Boolean = seq != latest

        /** 作废所有在飞请求，返回估计的被取消数量（已发出但仍未过期的请求数）。 */
        fun cancelAll(): Int {
            val alive = (latest - acceptedSeq).coerceAtLeast(0)
            issued += 1
            latest = issued
            return alive
        }

        /** 序号对应的查询文本，查不到给空串。 */
        fun queryOf(seq: Int): String = queries[seq].orEmpty()

        /** 清空全部登记，界面「清空日志与计数」时调用。 */
        fun reset() {
            issued = 0
            latest = 0
            acceptedSeq = 0
            queries.clear()
        }
    }

    /** 一次查询的模拟结果，[seq] 就是领到的序号。 */
    data class Result(
        val seq: Int,
        val query: String,
        val hits: Int
    )

    /** 超出范围的防抖参数拉回合法区间。 */
    fun clampDebounce(ms: Long): Long = ms.coerceIn(MIN_DEBOUNCE_MS, MAX_DEBOUNCE_MS)

    /**
     * 模拟搜索：同样的关键词始终给同样的命中数，和序号无关，
     * 这样界面上的数字变化只可能来自「换了关键词」，方便观察竞态。
     */
    fun search(seq: Int, query: String): Result {
        val trimmed = query.trim()
        val hits = if (trimmed.isEmpty()) 0 else 4 + Math.floorMod(trimmed.hashCode(), 18)
        return Result(seq = seq, query = trimmed, hits = hits)
    }

    /**
     * 模拟网络时延：按序号错开 380 / 450 / 520 / 590ms 循环。
     * 连打 5 次时先发的请求反而更慢，旧结果就会在新结果之后才回来，正好演示竞态。
     */
    fun latencyFor(seq: Int): Long = BASE_LATENCY_MS + (seq % 4) * 70L
}
