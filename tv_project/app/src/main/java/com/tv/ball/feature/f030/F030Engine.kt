package com.tv.ball.feature.f030

import java.util.Locale
import kotlin.math.roundToInt

/**
 * F030「继续观看队列」的能力层。
 *
 * 刻意写成纯 Kotlin：不引用任何 Android API，也不读系统时钟（`lastWatchedAtMs` 由调用方填）。
 * 「哪些条目算没看完」这条规则只有这里一份，页面和后续播放页接线都走 [inQueue] / [buildQueue]，
 * 免得一处 5% 一处 3% 各写一套。
 */
object F030Engine {

    /** 默认收录下界：进度低于 5% 视为还没真正开始看。 */
    const val DEFAULT_START_PERCENT: Int = 5

    /** 默认收录上界：进度高于 95% 视为基本看完，不该再占队列。 */
    const val DEFAULT_END_PERCENT: Int = 95

    /** 队列默认最多显示几条。 */
    const val DEFAULT_QUEUE_LIMIT: Int = 20

    /** 默认收录窗口，页面第一次进来用它。 */
    val DEFAULT_WINDOW: ProgressWindow = ProgressWindow()

    /**
     * 边界比较的容差。
     *
     * 进度是「毫秒 ÷ 总时长」除出来的浮点值，正好 5% / 95% 的条目在除法后可能差 1 ulp，
     * 直接比较会时进时出。1e-9 远小于 1% 的步长，既兜住误差又不会把 4.99% 误收进队列。
     */
    private const val RATIO_EPSILON: Double = 1e-9

    /**
     * 一条观看记录。
     *
     * @param durationMs 总时长，<= 0 表示时长未知（这类条目不参与队列，算不出进度）。
     * @param positionMs 断点位置，脏数据可能超过 [durationMs]，比较前会夹紧。
     * @param lastWatchedAtMs 最近一次观看时刻，队列按它倒序。
     */
    data class WatchItem(
        val id: Long,
        val title: String,
        val durationMs: Long,
        val positionMs: Long,
        val lastWatchedAtMs: Long
    ) {
        /** 已看进度，0.0~1.0。时长非法的条目恒为 0。 */
        val progressRatio: Double
            get() {
                if (durationMs <= 0L) return 0.0
                return positionMs.coerceIn(0L, durationMs).toDouble() / durationMs.toDouble()
            }

        /** 已看进度百分数（四舍五入到整数），只用于界面文案。 */
        val progressPercent: Int
            get() = (progressRatio * 100.0).roundToInt()
    }

    /**
     * 收录窗口：进度在 [startPercent, endPercent] 闭区间内才算「没看完」。
     *
     * 两端都是闭区间是有意的——正好看到 5% 的人显然刚开始，正好 95% 的人还没看完。
     * 构造参数可能来自持久化，脏值在这里统一夹紧，不抛异常。
     */
    data class ProgressWindow(
        val startPercent: Int = DEFAULT_START_PERCENT,
        val endPercent: Int = DEFAULT_END_PERCENT
    ) {
        /** 归一化后的下界，0~99。 */
        val validStart: Int get() = startPercent.coerceIn(0, 99)

        /** 归一化后的上界，[validStart] + 1 ~ 100。 */
        val validEnd: Int get() = endPercent.coerceIn(validStart + 1, 100)

        /** 下界占位比例，拿 [WatchItem.progressRatio] 直接比。 */
        val startRatio: Double get() = validStart / 100.0

        /** 上界占位比例。 */
        val endRatio: Double get() = validEnd / 100.0

        /** [ratio] 是否落在窗口内（含两端）。 */
        fun contains(ratio: Double): Boolean =
            ratio >= startRatio - RATIO_EPSILON && ratio <= endRatio + RATIO_EPSILON
    }

    /** 队列统计：队列条数 + 两侧被挡掉的条数，页面底部直接显示。 */
    data class Stats(
        val queued: Int,
        val belowThreshold: Int,
        val aboveThreshold: Int,
        val total: Int
    )

    /**
     * 单条是否进队列。
     *
     * 时长非法（<= 0）的直接排除：算不出进度，也就谈不上「续播到第几秒」。
     */
    fun inQueue(item: WatchItem, window: ProgressWindow = DEFAULT_WINDOW): Boolean {
        if (item.durationMs <= 0L) return false
        return window.contains(item.progressRatio)
    }

    /**
     * 组队列：先按窗口过滤，再按最近观看倒序，最后截断到 [limit] 条。
     *
     * 排序加了 id 作为次级键：真实数据里同一次同步写进来的一批条目时间戳可能完全相同，
     * 没有次级键的话每次渲染顺序都可能变，列表会莫名跳动。
     *
     * @param limit <= 0 表示不截断。
     */
    fun buildQueue(
        items: List<WatchItem>,
        limit: Int = DEFAULT_QUEUE_LIMIT,
        window: ProgressWindow = DEFAULT_WINDOW
    ): List<WatchItem> {
        val queued = items
            .filter { inQueue(it, window) }
            .sortedWith(
                compareByDescending<WatchItem> { it.lastWatchedAtMs }
                    .thenByDescending { it.id }
            )
        return if (limit <= 0) queued else queued.take(limit)
    }

    /** 断点位置（毫秒），点击续播时传给播放页的 seekTo。 */
    fun resumePosition(item: WatchItem): Long {
        if (item.durationMs <= 0L) return item.positionMs.coerceAtLeast(0L)
        return item.positionMs.coerceIn(0L, item.durationMs)
    }

    /** 从断点续播还剩多久，负值归零。 */
    fun remainingMs(item: WatchItem): Long {
        if (item.durationMs <= 0L) return 0L
        return (item.durationMs - resumePosition(item)).coerceAtLeast(0L)
    }

    /** 统计当前窗口下的三档条数。 */
    fun stats(items: List<WatchItem>, window: ProgressWindow = DEFAULT_WINDOW): Stats {
        var queued = 0
        var below = 0
        var above = 0
        items.forEach { item ->
            when {
                item.durationMs <= 0L -> Unit
                inQueue(item, window) -> queued++
                item.progressRatio < window.startRatio -> below++
                else -> above++
            }
        }
        return Stats(queued, below, above, items.size)
    }

    /** 拖下界：不能压到上界之上，至少留 1% 的窗口。 */
    fun withStart(window: ProgressWindow, startPercent: Int): ProgressWindow {
        val end = window.validEnd
        return ProgressWindow(startPercent.coerceIn(0, end - 1), end)
    }

    /** 拖上界：不能压到下界之下，至少留 1% 的窗口。 */
    fun withEnd(window: ProgressWindow, endPercent: Int): ProgressWindow {
        val start = window.validStart
        return ProgressWindow(start, endPercent.coerceIn(start + 1, 100))
    }

    /** 毫秒时长格式化成 `m:ss` 或 `h:mm:ss`，界面直接显示。 */
    fun formatClock(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }
}
