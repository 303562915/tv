package com.tv.ball.feature.f027

import java.util.Calendar
import java.util.Locale

/**
 * F027 观看历史的能力层：纯 Kotlin，不碰 Android 框架。
 *
 * 做四件事：按自然日给历史分桶（今天 / 昨天 / 本周 / 更早）、把分桶拼成带标题的分组、
 * 维护多选集合、按选中集合批量删。
 *
 * 日与周的边界全部交给 [Calendar] 算：手写 86400000 的加减在夏令时那天会漂一小时，
 * 「今天」的标题就会把昨天的记录吞进来。分组只认本机时区，和系统日历一致。
 *
 * 页面侧 [F027Activity] 只负责示例数据、渲染和点按交互。
 */
object F027Engine {

    private const val MINUTE_MS = 60L * 1000L
    private const val HOUR_MS = 60L * MINUTE_MS

    /** 分组顺序：从新到旧，列表就按这个顺序往下排。 */
    private val BUCKET_ORDER = listOf(
        DayBucket.TODAY,
        DayBucket.YESTERDAY,
        DayBucket.THIS_WEEK,
        DayBucket.EARLIER
    )

    /** 一条观看记录。 */
    data class HistoryItem(
        val id: String,
        val title: String,
        val playedAtMs: Long,
        val progressMs: Long
    )

    /** 一个分组：标题（文案由调用方给）+ 组内条目（已按时间从新到旧排好）。 */
    data class DayGroup(
        val label: String,
        val items: List<HistoryItem>
    ) {
        val size: Int get() = items.size
    }

    /** 时间落在哪一桶。 */
    enum class DayBucket { TODAY, YESTERDAY, THIS_WEEK, EARLIER }

    // region 分组

    /**
     * 按自然日分组，标题用内置中文兜底。
     *
     * 这个两参重载只给没有 Context 的场景用（单测、日志）；UI 走三参重载把
     * 资源字符串塞进来，保证文案只有一个来源。
     */
    fun groupByDay(items: List<HistoryItem>, todayMs: Long): List<DayGroup> =
        groupByDay(items, todayMs) { bucket -> bucket.defaultLabel() }

    /**
     * 按自然日分组。
     *
     * @param items 全部历史，顺序无所谓，组内会重新按时间倒序。
     * @param todayMs 用来定位「今天」的时间戳，一般传 [System.currentTimeMillis]。
     * @param labelOf 桶 → 分组标题，页面传 `getString(...)`。
     */
    fun groupByDay(
        items: List<HistoryItem>,
        todayMs: Long,
        labelOf: (DayBucket) -> String
    ): List<DayGroup> {
        val bounds = DayBounds.of(todayMs)
        val byBucket = items.groupBy { bounds.bucketOf(it.playedAtMs) }

        val groups = ArrayList<DayGroup>(BUCKET_ORDER.size)
        BUCKET_ORDER.forEach { bucket ->
            val slice = byBucket[bucket].orEmpty()
            if (slice.isEmpty()) return@forEach
            // 同一桶里也按观看时间倒序，删除中间几条后顺序不会跳
            val sorted = slice.sortedWith(
                compareByDescending<HistoryItem> { it.playedAtMs }.thenBy { it.id }
            )
            groups += DayGroup(labelOf(bucket), sorted)
        }
        return groups
    }

    /** 单条记录属于哪一桶，供页面或测试直接查。 */
    fun bucketOf(playedAtMs: Long, todayMs: Long): DayBucket =
        DayBounds.of(todayMs).bucketOf(playedAtMs)

    // endregion

    // region 多选

    /**
     * 切换一条的选中状态，返回新集合。
     *
     * 用不可变集合进出：调用方拿到的一定是新对象，不会出现「明明取消了还留着」的脏集合。
     */
    fun toggleSelect(selectedIds: Set<String>, id: String): Set<String> =
        if (id in selectedIds) selectedIds - id else selectedIds + id

    /**
     * 批量删除：返回把所有选中 id 去掉之后的列表，原有顺序保持不变。
     *
     * @return 新列表；[selectedIds] 为空时返回与入参等价的副本，调用方可以无脑替换。
     */
    fun removeSelected(items: List<HistoryItem>, selectedIds: Set<String>): List<HistoryItem> =
        items.filterNot { it.id in selectedIds }

    // endregion

    // region 示例数据

    /**
     * 示例历史：10 条，时间点相对 [nowMs] 算，保证四个分组都能看到。
     *
     * 今天的 3 条按「今天已过去的时长」取比例，凌晨打开页面也不会掉到昨天；
     * 本周的 2 条只在周内还有空档时插入（周一周二本来就没有「本周但非今昨」的时段），
     * 缺位时补成更早的，总数恒为 10。
     *
     * 真实接入时把这里换成 Room 里的观看记录即可，分组 / 多选 / 删除逻辑一行都不用改。
     */
    fun sampleHistory(nowMs: Long): List<HistoryItem> {
        val bounds = DayBounds.of(nowMs)
        val elapsedToday = (nowMs - bounds.startOfToday).coerceAtLeast(1L)

        val times = ArrayList<Long>(10)
        times += bounds.startOfToday + (elapsedToday * 0.88).toLong()
        times += bounds.startOfToday + (elapsedToday * 0.61).toLong()
        times += bounds.startOfToday + (elapsedToday * 0.27).toLong()

        times += bounds.startOfYesterday + 9 * HOUR_MS + 20 * MINUTE_MS
        times += bounds.startOfYesterday + 15 * HOUR_MS + 5 * MINUTE_MS
        times += bounds.startOfYesterday + 21 * HOUR_MS + 40 * MINUTE_MS

        // 周内还剩整段空档才放「本周」样本，否则这两天属于今/昨，放进去就串桶了
        val hasWeekGap = bounds.startOfWeek < bounds.startOfYesterday
        if (hasWeekGap) {
            times += bounds.startOfWeek + 10 * HOUR_MS
            times += bounds.startOfWeek + 19 * HOUR_MS
        }

        val earlierCount = 10 - times.size
        for (index in 0 until earlierCount) {
            times += bounds.startOfWeek - (index + 1) * 2 * 24 * HOUR_MS
        }

        val titles = listOf(
            "英超第 12 轮 · 曼城 vs 阿森纳",
            "欧冠小组赛 · 皇马 vs 国际米兰",
            "纪录片《绿茵之上》第 3 集",
            "NBA 常规赛 · 湖人 vs 勇士",
            "西甲 · 巴萨 vs 塞维利亚",
            "体育新闻晚班车",
            "意甲 · 尤文 vs 米兰",
            "德甲 · 拜仁 vs 多特",
            "中超集锦 · 第 28 轮",
            "世界杯经典回放 · 2010 决赛"
        )
        val progress = longArrayOf(
            38 * MINUTE_MS,
            12 * MINUTE_MS,
            26 * MINUTE_MS,
            5 * MINUTE_MS,
            44 * MINUTE_MS,
            9 * MINUTE_MS,
            17 * MINUTE_MS,
            31 * MINUTE_MS,
            23 * MINUTE_MS,
            11 * MINUTE_MS
        )

        return times.mapIndexed { index, playedAtMs ->
            HistoryItem(
                id = "f027-%02d".format(Locale.US, index + 1),
                title = titles[index % titles.size],
                playedAtMs = playedAtMs,
                progressMs = progress[index % progress.size]
            )
        }.sortedByDescending { it.playedAtMs }
    }

    // endregion

    // region 格式化

    /** 观看时刻 → HH:mm。 */
    fun formatClock(ms: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = ms }
        return String.format(
            Locale.US,
            "%02d:%02d",
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE)
        )
    }

    /** 已看进度 → mm:ss，超过一小时给 h:mm:ss。 */
    fun formatProgress(ms: Long): String {
        val totalSeconds = (if (ms < 0L) 0L else ms) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    // endregion

    /** 无 Context 场景的兜底标题；页面侧永远走三参重载传资源文案。 */
    private fun DayBucket.defaultLabel(): String = when (this) {
        DayBucket.TODAY -> "今天"
        DayBucket.YESTERDAY -> "昨天"
        DayBucket.THIS_WEEK -> "本周"
        DayBucket.EARLIER -> "更早"
    }

    /**
     * 一天的三条边界：今天 0 点、昨天 0 点、本周一 0 点。
     *
     * 只保留这三个值，[bucketOf] 就能用三次比较把桶定下来，不必每条都重算日历。
     */
    data class DayBounds(
        val startOfToday: Long,
        val startOfYesterday: Long,
        val startOfWeek: Long
    ) {
        fun bucketOf(playedAtMs: Long): DayBucket = when {
            // 未来时间也算今天：设备时钟被拨快时，刚看的记录不该掉进「更早」
            playedAtMs >= startOfToday -> DayBucket.TODAY
            playedAtMs >= startOfYesterday -> DayBucket.YESTERDAY
            playedAtMs >= startOfWeek -> DayBucket.THIS_WEEK
            else -> DayBucket.EARLIER
        }

        companion object {
            fun of(nowMs: Long): DayBounds {
                val today = beginOfDay(nowMs)
                return DayBounds(
                    startOfToday = today,
                    startOfYesterday = shiftDays(today, -1),
                    startOfWeek = beginOfWeek(nowMs)
                )
            }

            /** 抹掉时分秒，只留当天 0 点。 */
            private fun beginOfDay(ms: Long): Long = Calendar.getInstance().apply {
                timeInMillis = ms
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            /** 整天加减：夏令时那天是 23 或 25 小时，只有 Calendar 的加法才对得上本地日期。 */
            private fun shiftDays(ms: Long, days: Int): Long = Calendar.getInstance().apply {
                timeInMillis = ms
                add(Calendar.DAY_OF_YEAR, days)
            }.timeInMillis

            /**
             * 本周一 0 点。
             *
             * firstDayOfWeek 固定成周一，不跟随地区设置（部分地区一周从周日开始），
             * 这样「本周」在不同设备上算出来的区间一致。
             */
            private fun beginOfWeek(ms: Long): Long = Calendar.getInstance().apply {
                firstDayOfWeek = Calendar.MONDAY
                minimalDaysInFirstWeek = 4
                timeInMillis = beginOfDay(ms)
                while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
                    add(Calendar.DAY_OF_YEAR, -1)
                }
            }.timeInMillis
        }
    }
}
