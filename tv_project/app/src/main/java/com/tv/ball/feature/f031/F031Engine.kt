package com.tv.ball.feature.f031

/**
 * F031「观看时长周报」的能力层。
 *
 * 刻意写成纯 Kotlin：不引用任何 Android API，也不读系统时钟——本周起点 `weekStartMs` 由调用方算好
 * （周一 00:00 那一刻）传进来，时区用 `tzOffsetMs` 显式传入（本地时间 = UTC + 该值）。
 * 「一条会话算到哪一天」这条规则只有这里一份，页面和后续真实数据源都走 [dailyTotals] / [summary]，
 * 免得一处按 UTC 分天、一处按本地分天，同一份播放记录在两个界面算出两个数。
 */
object F031Engine {

    /** 一周 7 天，柱状图正好 7 根，缺数据的那天补 0 也要占一根。 */
    const val DAYS_PER_WEEK: Int = 7

    /** 一天的毫秒数。只用来把「本地时间戳」折算成「第几天」，不参与任何日期计算。 */
    const val MS_PER_DAY: Long = 86_400_000L

    /**
     * 一条播放会话。
     *
     * @param id 稳定标识，示例数据与后续 Room 记录共用（排序、去重、定位都靠它）。
     * @param startedAtMs 开始播放的时刻（epoch 毫秒）。
     * @param playedMs 实际播放时长。<= 0 的脏数据在聚合时直接跳过。
     */
    data class Session(
        val id: Long,
        val startedAtMs: Long,
        val playedMs: Long
    )

    /** 某一天的合计。[dayIndex] 0 = 周一 … 6 = 周日。 */
    data class DayTotal(
        val dayIndex: Int,
        val ms: Long
    )

    /**
     * 周报汇总。
     *
     * @param dailyAvgMs 日均 = 总量 ÷ 7。整天算，没看的日子按 0 计入：只看两天就报「日均 6 小时」是误导。
     * @param longestDayIndex 最长单日是哪天，并列时取更靠前的那天；整周全 0 时为 0。
     */
    data class Summary(
        val totalMs: Long,
        val dailyAvgMs: Long,
        val longestDayMs: Long,
        val longestDayIndex: Int
    )

    /**
     * 按天聚合本周观看时长，恒定返回 7 项（0 ~ 6 天），没数据的那天是 0。
     *
     * 返回定长列表而不是稀疏 Map：柱状图要 7 根柱子，补 0 是渲染方的核心需求，
     * 让每个调用方各写一遍「缺失就补 0」迟早会漏一天。
     *
     * 归属规则：
     * - 一条会话整段算在它的**开始那天**。`playedMs` 没有逐日切分，跨零点硬切会造出不存在的数据。
     * - 开始时刻落在本周之外（早于周一 00:00 或晚于周日 24:00）的会话整条丢弃——那属于别的一周。
     * - 判断「第几天」用本地日历日，不用 `(startedAtMs - weekStartMs) / MS_PER_DAY` 直接除：
     *   后者只在两端时区一致时成立，换个时区就会把 23:30 的会话算到隔壁天。
     *
     * @param weekStartMs 本周起点（周一 00:00）的 epoch 毫秒，由调用方提供。
     * @param tzOffsetMs 时区偏移（毫秒），例如东八区为 8 * 3600_000。
     */
    fun dailyTotals(
        sessions: List<Session>,
        weekStartMs: Long,
        tzOffsetMs: Long
    ): List<DayTotal> {
        val anchorDay = localDayNumber(weekStartMs, tzOffsetMs)
        val buckets = LongArray(DAYS_PER_WEEK)
        sessions.forEach { session ->
            if (session.playedMs <= 0L) return@forEach
            val index = localDayNumber(session.startedAtMs, tzOffsetMs) - anchorDay
            if (index < 0L || index >= DAYS_PER_WEEK.toLong()) return@forEach
            buckets[index.toInt()] += session.playedMs
        }
        return List(DAYS_PER_WEEK) { dayIndex -> DayTotal(dayIndex, buckets[dayIndex]) }
    }

    /** 周报汇总：总量、日均、最长单日（含是哪天），一次算完，页面不用自己再遍历一遍。 */
    fun summary(
        sessions: List<Session>,
        weekStartMs: Long,
        tzOffsetMs: Long
    ): Summary {
        val totals = dailyTotals(sessions, weekStartMs, tzOffsetMs)
        var total = 0L
        var longest = 0L
        var longestIndex = 0
        totals.forEach { day ->
            total += day.ms
            // 严格大于：并列时保留更靠前的那天，同一份数据每次算出来的「最长单日」才不会跳。
            if (day.ms > longest) {
                longest = day.ms
                longestIndex = day.dayIndex
            }
        }
        return Summary(
            totalMs = total,
            dailyAvgMs = total / DAYS_PER_WEEK,
            longestDayMs = longest,
            longestDayIndex = longestIndex
        )
    }

    /**
     * 某根柱子该占的高度比例，0.0 ~ 1.0。
     *
     * 以本周最长的一天为满格等比缩放：绝对秒数画成柱子的话，一周总量小的时候整张图会缩成一条线。
     * 本周全 0（或传入脏值）时一律返回 0，由渲染方决定「0 也要留一截底座」。
     */
    fun barHeightRatio(dayMs: Long, weekMaxMs: Long): Float {
        if (dayMs <= 0L || weekMaxMs <= 0L) return 0f
        val ratio = dayMs.toDouble() / weekMaxMs.toDouble()
        return ratio.coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * 毫秒时长格式化成中文可读文本：`12 小时 30 分` / `12 小时` / `45 分钟` / `不到 1 分钟`。
     *
     * 周报里的量级都是小时级，保留秒没有意义；不足一分钟的单独给一句话，
     * 免得显示成「0 分钟」让人以为是没统计到。
     */
    fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "0 分钟"
        val totalMinutes = ms / 60_000L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0L && minutes > 0L -> "$hours 小时 $minutes 分"
            hours > 0L -> "$hours 小时"
            minutes > 0L -> "$minutes 分钟"
            else -> "不到 1 分钟"
        }
    }

    /**
     * 把 epoch 毫秒折算成本地日历日的序号（1970-01-01 本地 00:00 为 0）。
     *
     * 手写向下取整而不是直接相除：1970 年之前的时刻相除会向 0 取整，
     * 差一天的量级在周界上正好能把周日算成周一。
     */
    private fun localDayNumber(ms: Long, tzOffsetMs: Long): Long {
        val local = ms + tzOffsetMs
        return if (local >= 0L) {
            local / MS_PER_DAY
        } else {
            -((-local + MS_PER_DAY - 1L) / MS_PER_DAY)
        }
    }
}
