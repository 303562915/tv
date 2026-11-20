package com.tv.ball.feature.f028

import java.util.Locale

/**
 * F028「历史搜索与筛选」的能力层。
 *
 * 刻意写成纯 Kotlin：不引用任何 Android API，也不读系统时钟。
 * 「最近 7/30 天」这类条件全靠调用方传进来的 [nowMs] 算，
 * 这样同一份数据在任意时间点跑出来的结果都可复现，也方便单测。
 */
object F028Engine {

    /** 一条播放历史。progressMs 只记「看到哪儿」，总时长由播放页另有来源。 */
    data class HistoryItem(
        val id: Long,
        val title: String,
        val playedAtMs: Long,
        val progressMs: Long
    )

    /** 时间范围档位。[days] 是回看窗口天数，0 表示不设下界。 */
    enum class Range(val days: Int) {
        ALL(0),
        LAST_7_DAYS(7),
        LAST_30_DAYS(30);

        /**
         * 区间下界：playedAtMs >= startMs 才算在范围内。
         * ALL 用 [Long.MIN_VALUE] 表示「不筛」，省掉一条额外的分支。
         */
        fun startMs(nowMs: Long): Long =
            if (days <= 0) Long.MIN_VALUE else nowMs - days.toLong() * DAY_MS

        companion object {
            /** 一天的毫秒数，避免各处各算一遍。 */
            const val DAY_MS: Long = 24L * 60L * 60L * 1000L

            /** 按下标取档位（0=全部 1=7 天 2=30 天），越界回落到全部。 */
            fun ofOrdinal(ordinal: Int): Range = values().getOrNull(ordinal) ?: ALL
        }
    }

    /**
     * 筛选条件：关键字 + 时间范围。
     * 两个条件之间是「并且」，任一不满足都不命中。
     */
    data class Filter(
        val keyword: String = "",
        val range: Range = Range.ALL
    ) {
        /** 关键字去掉首尾空白后的大小写无关形式，真正参与比较的值。 */
        val normalizedKeyword: String get() = keyword.trim().lowercase(Locale.ROOT)

        /** 两个条件都是「空/全部」，等价于没筛。 */
        val isCleared: Boolean get() = keyword.isBlank() && range == Range.ALL
    }

    /**
     * 按 [filter] 过滤 [items]，结果按播放时间倒序（最近看的在前）。
     *
     * @param nowMs 计算时间范围的基准时刻，由调用方提供，函数内不读系统时间。
     */
    fun apply(items: List<HistoryItem>, filter: Filter, nowMs: Long): List<HistoryItem> {
        val needle = filter.normalizedKeyword
        val start = filter.range.startMs(nowMs)
        return items
            .filter { matches(it, needle, start) }
            .sortedByDescending { it.playedAtMs }
    }

    /** 单条是否命中（给 UI 逐行判断或测试用）。 */
    fun matches(item: HistoryItem, filter: Filter, nowMs: Long): Boolean =
        matches(item, filter.normalizedKeyword, filter.range.startMs(nowMs))

    private fun matches(item: HistoryItem, needle: String, startMs: Long): Boolean {
        // 区间只卡下界：真实数据里偶尔出现略晚于 nowMs 的时间戳（跨设备/时钟回拨），
        // 那种条目不该因为「未来」被悄悄筛掉。
        if (item.playedAtMs < startMs) return false
        if (needle.isEmpty()) return true
        return item.title.lowercase(Locale.ROOT).contains(needle)
    }

    /** 命中条数。 */
    fun count(items: List<HistoryItem>, filter: Filter, nowMs: Long): Int =
        apply(items, filter, nowMs).size

    /** 把毫秒进度格式化成 `m:ss` 或 `h:mm:ss`，界面直接显示。 */
    fun formatProgress(progressMs: Long): String {
        val totalSeconds = (progressMs / 1000L).coerceAtLeast(0L)
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