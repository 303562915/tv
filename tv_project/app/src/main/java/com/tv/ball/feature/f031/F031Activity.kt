package com.tv.ball.feature.f031

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF031Binding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * F031 观看时长周报页面。
 *
 * 分天聚合与汇总全在 [F031Engine]（纯 Kotlin）里，这里只做三件事：
 * 把「本周起点 + 时区」喂给 Engine、把 7 个日合计画成 7 根竖条、把总量/日均/最长单日贴进三个统计格。
 * 上一周 / 下一周切换只改 [weekOffset]，其余照旧重算——Engine 不读时钟，所以翻到哪一周都算得出来。
 */
class F031Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF031Binding

    /** 示例会话，进页面时按 [baseWeekStartMs] 生成一次。 */
    private val sessions = ArrayList<F031Engine.Session>()

    /** 7 根柱子，建好一次后只更新高度与配色，切周时不重建。 */
    private val bars = ArrayList<BarColumn>()

    /** 页面打开那一刻，只用来算本周起点与生成示例；渲染阶段不再读它。 */
    private var nowMs = 0L

    /** 本周（周一 00:00）的 epoch 毫秒，翻周时以它为基准加减。 */
    private var baseWeekStartMs = 0L

    /** 相对本周的周偏移：0 = 本周，-1 = 上一周，-2 = 上上周…… */
    private var weekOffset = 0

    private val dayLabels: List<String> by lazy {
        listOf(
            getString(R.string.f031_day_1),
            getString(R.string.f031_day_2),
            getString(R.string.f031_day_3),
            getString(R.string.f031_day_4),
            getString(R.string.f031_day_5),
            getString(R.string.f031_day_6),
            getString(R.string.f031_day_7)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF031Binding.inflate(layoutInflater)
        setContentView(binding.root)

        nowMs = System.currentTimeMillis()
        baseWeekStartMs = weekStartOf(nowMs)
        sessions.addAll(buildSamples(baseWeekStartMs))

        buildBars()

        binding.btnPrev.setOnClickListener { shiftWeek(-1) }
        binding.btnNext.setOnClickListener { shiftWeek(1) }

        render()
    }

    // region 示例数据

    /**
     * 25 条示例会话铺满本周：每天 3~4 条，小时段和时长都固定写死。
     *
     * 每天合计（分钟）：周一 255、周二 160、周三 260、周四 150、周五 275、周六 540、周日 320，
     * 合计 1960 分钟 = 32 小时 40 分，除以 7 天正好 4 小时 40 分，最长单日是周六。
     * 挑这组数是有意的：总量、日均、最长单日三个数彼此不等且都能一眼验算。
     */
    private fun buildSamples(weekStartMs: Long): List<F031Engine.Session> {
        val built = ArrayList<F031Engine.Session>(SAMPLE_PLAN.sumOf { it.size })
        var id = 1L
        SAMPLE_PLAN.forEachIndexed { dayIndex, dayPlan ->
            dayPlan.forEach { (hourOfDay, minutes) ->
                built.add(
                    F031Engine.Session(
                        id = id++,
                        // 周一 00:00 + 第几天 + 当天第几小时。会话都落在当天之内，不跨零点。
                        startedAtMs = weekStartMs +
                            dayIndex * F031Engine.MS_PER_DAY +
                            hourOfDay * 3_600_000L,
                        playedMs = minutes * 60_000L
                    )
                )
            }
        }
        return built
    }

    // endregion

    // region 周定位

    /**
     * 本周起点：把「今天」回退到最近的周一 00:00。
     *
     * 显式把 firstDayOfWeek 设成周一：部分地区的默认值是周日，不设的话这里会算出「上周日」。
     */
    private fun weekStartOf(nowMs: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = nowMs
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val daysSinceMonday = (calendar.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        calendar.add(Calendar.DAY_OF_MONTH, -daysSinceMonday)
        return calendar.timeInMillis
    }

    /**
     * 第 [offset] 周的周一 00:00。
     *
     * 用 Calendar 加减「周」而不是直接减 7 * 86400000：有夏令时的时区里一周不一定是 7 * 24 小时，
     * 减常数会让起点漂到周日 23:00 或周一 01:00，整周的日界就全错了。
     */
    private fun weekStartFor(offset: Int): Long {
        if (offset == 0) return baseWeekStartMs
        return Calendar.getInstance().apply {
            timeInMillis = baseWeekStartMs
            add(Calendar.WEEK_OF_YEAR, offset)
        }.timeInMillis
    }

    private fun shiftWeek(delta: Int) {
        val target = weekOffset + delta
        if (target > 0) {
            toast(getString(R.string.f031_toast_latest))
            return
        }
        if (target < MIN_WEEK_OFFSET) {
            toast(getString(R.string.f031_toast_earliest, -MIN_WEEK_OFFSET))
            return
        }
        weekOffset = target
        render()
    }

    // endregion

    // region 柱状图

    /**
     * 建 7 根竖条（只建一次）。
     *
     * 每根柱子的结构是「数值文字 / 柱子 / 星期标签」自上而下、整列贴底对齐：
     * 标签高度一致，柱子底边就自然落在同一条基线上，不用手算偏移。
     */
    private fun buildBars() {
        val container = binding.chartContainer
        container.removeAllViews()
        bars.clear()

        val valueColor = ContextCompat.getColor(this, R.color.theme_text_secondary)
        for (dayIndex in 0 until F031Engine.DAYS_PER_WEEK) {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1f
                )
            }

            val value = TextView(this).apply {
                text = F031Engine.formatDuration(0L)
                setTextColor(valueColor)
                textSize = 11f
                maxLines = 1
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(BAR_WIDTH_DP), dp(BAR_MIN_DP))
            }

            val label = TextView(this).apply {
                text = dayLabels[dayIndex]
                setTextColor(valueColor)
                textSize = 12f
                maxLines = 1
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }

            column.addView(value)
            column.addView(bar)
            column.addView(label)
            container.addView(column)
            bars.add(BarColumn(bar, value))
        }
    }

    /**
     * 按当天的时长把 7 根柱子重设高度。
     *
     * 高度按「本周最长的一天」等比缩放（[F031Engine.barHeightRatio]），最长那根满格；
     * 0 的那天保留一截底座（[BAR_MIN_DP]），否则空周看起来像柱子丢了，而不是那天没看。
     */
    private fun updateBars(totals: List<F031Engine.DayTotal>, summary: F031Engine.Summary) {
        val maxHeightPx = dp(BAR_MAX_DP)
        val minHeightPx = dp(BAR_MIN_DP)
        val colorMax = ContextCompat.getColor(this, R.color.theme_primary)
        val colorNormal = ContextCompat.getColor(this, R.color.theme_secondary)
        val colorEmpty = ContextCompat.getColor(this, R.color.theme_outline_variant)
        val textPrimary = ContextCompat.getColor(this, R.color.theme_text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.theme_text_secondary)

        totals.forEach { day ->
            val column = bars.getOrNull(day.dayIndex) ?: return@forEach
            val isLongest = summary.longestDayMs > 0L && day.dayIndex == summary.longestDayIndex
            val ratio = F031Engine.barHeightRatio(day.ms, summary.longestDayMs)
            val height = (maxHeightPx * ratio).roundToInt().coerceIn(minHeightPx, maxHeightPx)

            val params = column.bar.layoutParams as? LinearLayout.LayoutParams
            if (params != null) {
                if (params.height != height) {
                    params.height = height
                    column.bar.layoutParams = params
                }
            } else {
                column.bar.layoutParams = LinearLayout.LayoutParams(dp(BAR_WIDTH_DP), height)
            }

            column.bar.setBackgroundColor(
                when {
                    day.ms <= 0L -> colorEmpty
                    isLongest -> colorMax
                    else -> colorNormal
                }
            )
            column.value.setTextColor(if (isLongest) textPrimary else textSecondary)
            column.value.text = F031Engine.formatDuration(day.ms)
        }
    }

    // endregion

    // region 渲染

    /**
     * 重画当前这一周。
     *
     * 时区偏移按被展示那一周取（[TimeZone.getOffset]），不按「今天」取：
     * 在夏令时地区翻到上个月，光是偏移就差一小时，日界会跟着错位。
     */
    private fun render() {
        val weekStartMs = weekStartFor(weekOffset)
        val tzOffsetMs = TimeZone.getDefault().getOffset(weekStartMs).toLong()
        val totals = F031Engine.dailyTotals(sessions, weekStartMs, tzOffsetMs)
        val summary = F031Engine.summary(sessions, weekStartMs, tzOffsetMs)

        binding.weekLabel.text = getString(
            R.string.f031_week_range,
            dateText(weekStartMs),
            dateText(daysLater(weekStartMs, F031Engine.DAYS_PER_WEEK - 1))
        )
        binding.weekCaption.text = if (weekOffset == 0) {
            getString(R.string.f031_week_current)
        } else {
            getString(R.string.f031_week_past, -weekOffset)
        }

        binding.statTotalValue.text = F031Engine.formatDuration(summary.totalMs)
        binding.statAvgValue.text = F031Engine.formatDuration(summary.dailyAvgMs)
        binding.statLongestValue.text = F031Engine.formatDuration(summary.longestDayMs)
        binding.statLongestDay.text = if (summary.totalMs <= 0L) {
            getString(R.string.f031_stat_longest_none)
        } else {
            getString(
                R.string.f031_stat_longest_day,
                dayLabels[summary.longestDayIndex.coerceIn(0, F031Engine.DAYS_PER_WEEK - 1)]
            )
        }

        binding.chartScale.text = if (summary.longestDayMs > 0L) {
            getString(R.string.f031_chart_scale, F031Engine.formatDuration(summary.longestDayMs))
        } else {
            getString(R.string.f031_chart_scale_empty)
        }
        binding.emptyHint.visibility = if (summary.totalMs <= 0L) View.VISIBLE else View.GONE

        updateBars(totals, summary)
    }

    // endregion

    private fun dateText(ms: Long): String =
        SimpleDateFormat("M月d日", Locale.CHINA).format(Date(ms))

    /** 在给定时刻上加天数，日期文案用；同样走 Calendar，避免 DST 日差一小时。 */
    private fun daysLater(ms: Long, days: Int): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        add(Calendar.DAY_OF_MONTH, days)
    }.timeInMillis

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).roundToInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** 一根柱子：柱子本体 + 柱顶数值文字。星期标签只在建的时候用，不参与更新。 */
    private class BarColumn(
        val bar: View,
        val value: TextView
    )

    private companion object {
        /** 柱子满格高度：正好装进布局里 200dp 的绘图区（还要留给数值文字和星期标签）。 */
        const val BAR_MAX_DP = 132

        /** 柱子宽度。7 根并排，留够间隙才看得出是 7 天。 */
        const val BAR_WIDTH_DP = 22

        /** 0 值那天的底座高度，让「没看」也画得出来。 */
        const val BAR_MIN_DP = 3

        /** 最多往前翻 8 周，再早的播放记录留不下了，翻下去只会是一排 0。 */
        const val MIN_WEEK_OFFSET = -8

        /**
         * 25 条示例会话：每个元素是一天，天内是「第几小时开始 → 播放多少分钟」。
         *
         * 每天条数 4 / 3 / 4 / 3 / 4 / 4 / 3 = 25 条，条数和时长都不平均分布，
         * 这样「最长单日」和「日均」才会跟「总量」拉出明显差距，一眼能看出三个数不是同一份东西。
         */
        val SAMPLE_PLAN: List<List<Pair<Int, Long>>> = listOf(
            listOf(7 to 45L, 12 to 30L, 19 to 120L, 21 to 60L),
            listOf(8 to 25L, 13 to 40L, 20 to 95L),
            listOf(7 to 60L, 12 to 35L, 18 to 110L, 22 to 55L),
            listOf(9 to 30L, 14 to 45L, 20 to 75L),
            listOf(7 to 50L, 12 to 40L, 19 to 130L, 22 to 55L),
            listOf(10 to 180L, 14 to 120L, 19 to 150L, 22 to 90L),
            listOf(9 to 100L, 15 to 80L, 20 to 140L)
        )
    }
}
