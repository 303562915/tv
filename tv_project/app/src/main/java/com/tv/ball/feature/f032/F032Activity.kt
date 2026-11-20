package com.tv.ball.feature.f032

import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF032Binding

/**
 * F032 播放偏好学习页面。
 *
 * 「模拟选择」区摆着线路与清晰度两排可聚焦行，每点一次就计数并立刻落盘；
 * 上半部分两张卡片分别展示「本次进入按上次结果默认选中的项」和「当前实时推荐」，
 * 后者就是下次进入时会被自动选中的那一项，因此行上带「默认」标记的会随点选实时移动。
 *
 * 学习规则全部在 [F032Engine]（纯 Kotlin），这里只负责建行、刷文案和存取 SharedPreferences。
 */
class F032Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF032Binding
    private lateinit var prefs: SharedPreferences
    private lateinit var engine: F032Engine

    /** 页面上所有可选行，按建行顺序排列，用来统一刷新文案和串焦点。 */
    private val rows = ArrayList<OptionRow>()

    /** 本次进入时从已保存结果里算出的默认项，之后点选不再改动它，专门用于「上次学习」展示。 */
    private var entryLine: F032Engine.Choice? = null
    private var entryQuality: F032Engine.Choice? = null

    /** 当前高亮的项（首次进入就是默认项），只影响选中配色。 */
    private var activeLine: String = ""
    private var activeQuality: String = ""

    /** 两条选项表，建行和「清空后回到第一项」都要用，存在这里免得两处各拼一遍。 */
    private var lineOptions: List<String> = emptyList()
    private var qualityOptions: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF032Binding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        engine = F032Engine()
        engine.importJson(prefs.getString(KEY_LEARNING, null))

        lineOptions = listOf(
            getString(R.string.f032_line_1),
            getString(R.string.f032_line_2),
            getString(R.string.f032_line_3)
        )
        qualityOptions = listOf(
            getString(R.string.f032_quality_1),
            getString(R.string.f032_quality_2),
            getString(R.string.f032_quality_3),
            getString(R.string.f032_quality_4)
        )

        // 默认项按已保存的学习结果选；记录里的取值如果已经不在这批选项里（改过文案/下线过线路），
        // 就退回第一项，不能让页面停在空选中状态。
        entryLine = engine.preferred(F032Engine.Kind.LINE)
        entryQuality = engine.preferred(F032Engine.Kind.QUALITY)
        activeLine = pickActive(F032Engine.Kind.LINE, lineOptions)
        activeQuality = pickActive(F032Engine.Kind.QUALITY, qualityOptions)

        buildRows(binding.lineContainer, F032Engine.Kind.LINE, lineOptions)
        buildRows(binding.qualityContainer, F032Engine.Kind.QUALITY, qualityOptions)
        linkFocus()

        binding.btnReset.setOnClickListener { resetLearning() }

        render()

        // 有学习结果时，明说这次是被自动选中的，否则「下次默认选它」这件事在页面上看不出来。
        if (entryLine != null || entryQuality != null) {
            toast(
                getString(
                    R.string.f032_toast_auto,
                    activeLine,
                    activeQuality
                )
            )
        }
    }

    // region 建行

    private fun buildRows(container: LinearLayout, kind: F032Engine.Kind, options: List<String>) {
        container.removeAllViews()
        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        val rowMinHeight = resources.getDimensionPixelSize(R.dimen.list_row_min_height)
        val padH = dp(12)
        val padV = dp(8)

        options.forEachIndexed { index, value ->
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (index == 0) 0 else gap }
                minimumHeight = rowMinHeight
                setPadding(padH, padV, padH, padV)
                isFocusable = true
                isFocusableInTouchMode = true
                background = ContextCompat.getDrawable(this@F032Activity, R.drawable.bg_card_surface)
                foreground = ContextCompat.getDrawable(this@F032Activity, R.drawable.bg_focus_ring)
            }

            val label = TextView(this).apply {
                text = value
                textSize = 15f
                maxLines = 1
                setTextColor(ContextCompat.getColor(this@F032Activity, R.color.theme_text_primary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val meta = TextView(this).apply {
                textSize = 12f
                maxLines = 2
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setTextColor(
                    ContextCompat.getColor(this@F032Activity, R.color.theme_text_secondary)
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            }

            val badge = TextView(this).apply {
                text = this@F032Activity.getString(R.string.f032_badge_default)
                textSize = 12f
                maxLines = 1
                visibility = View.GONE
                setTextColor(ContextCompat.getColor(this@F032Activity, R.color.theme_primary))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(4) }
            }

            root.addView(label)
            root.addView(meta)
            root.addView(badge)
            root.setOnClickListener { select(kind, value) }

            container.addView(root)
            rows.add(OptionRow(kind, value, root, label, meta, badge))
        }
    }

    /**
     * 动态行之间显式串上下焦点：上下串完再接到「清除」按钮，
     * 否则遥控器下键会按默认焦点搜索跨到卡片外，手感很差。
     */
    private fun linkFocus() {
        rows.forEachIndexed { index, row ->
            row.root.id = View.generateViewId()
            if (index > 0) row.root.nextFocusUpId = rows[index - 1].root.id
            if (index < rows.lastIndex) row.root.nextFocusDownId = rows[index + 1].root.id
        }
        rows.firstOrNull()?.root?.nextFocusUpId = View.NO_ID
        val lastId = rows.lastOrNull()?.root?.id ?: View.NO_ID
        rows.lastOrNull()?.root?.nextFocusDownId = binding.btnReset.id
        binding.btnReset.nextFocusUpId = lastId
    }

    // endregion

    // region 交互

    private fun select(kind: F032Engine.Kind, value: String) {
        engine.record(kind, value)
        persist()
        if (kind == F032Engine.Kind.LINE) activeLine = value else activeQuality = value
        render()
        toast(getString(R.string.f032_select_toast, value))
    }

    private fun resetLearning() {
        engine.clear()
        persist()

        // 清完就回到兜底状态：默认项、高亮都退回列表第一项，避免还挂着已经不存在的记录。
        entryLine = null
        entryQuality = null
        activeLine = pickActive(F032Engine.Kind.LINE, lineOptions)
        activeQuality = pickActive(F032Engine.Kind.QUALITY, qualityOptions)
        render()
        toast(getString(R.string.f032_reset_toast))
    }

    /** 写盘交给 apply()：主线程不做磁盘 IO。 */
    private fun persist() {
        prefs.edit().putString(KEY_LEARNING, engine.exportJson()).apply()
    }

    // endregion

    // region 渲染

    private fun render() {
        val primary = ContextCompat.getColor(this, R.color.theme_primary)
        val normal = ContextCompat.getColor(this, R.color.theme_text_primary)
        val secondary = ContextCompat.getColor(this, R.color.theme_text_secondary)

        rows.forEach { row ->
            val choice = engine.find(row.kind, row.value)
            val count = choice?.count ?: 0
            val recommended = engine.preferred(row.kind)
            val isDefault = count > 0 && recommended?.value == row.value
            val isActive = row.value == activeFor(row.kind)

            row.label.setTextColor(if (isActive) primary else normal)
            row.label.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)
            row.label.text = row.value

            row.meta.setTextColor(secondary)
            row.meta.text = if (choice == null || count <= 0) {
                getString(R.string.f032_row_never)
            } else {
                getString(
                    R.string.f032_row_meta,
                    getString(R.string.f032_row_count, count),
                    relativeTime(choice.lastUsedMs)
                )
            }
            row.badge.visibility = if (isDefault) View.VISIBLE else View.GONE
        }

        val fallback = getString(R.string.f032_entry_fallback_value)
        binding.entryLine.text =
            getString(R.string.f032_entry_line, entryLine?.value ?: fallback)
        binding.entryQuality.text =
            getString(R.string.f032_entry_quality, entryQuality?.value ?: fallback)

        val liveLine = engine.preferred(F032Engine.Kind.LINE)
        val liveQuality = engine.preferred(F032Engine.Kind.QUALITY)
        binding.liveLine.text = if (liveLine == null) {
            getString(R.string.f032_live_empty_line)
        } else {
            getString(R.string.f032_live_line, liveLine.value, liveLine.count)
        }
        binding.liveQuality.text = if (liveQuality == null) {
            getString(R.string.f032_live_empty_quality)
        } else {
            getString(R.string.f032_live_quality, liveQuality.value, liveQuality.count)
        }

        val snapshot = engine.snapshot()
        binding.summary.text = if (snapshot.isEmpty()) {
            getString(R.string.f032_summary_empty)
        } else {
            getString(
                R.string.f032_summary,
                engine.totalCount(),
                snapshot.count { it.kind == F032Engine.Kind.LINE },
                snapshot.count { it.kind == F032Engine.Kind.QUALITY }
            )
        }
    }

    /** 当前高亮项，类别内比对用。 */
    private fun activeFor(kind: F032Engine.Kind): String =
        if (kind == F032Engine.Kind.LINE) activeLine else activeQuality

    /** 默认项落在 [options] 里就用它，否则用第一项兜底。 */
    private fun pickActive(kind: F032Engine.Kind, options: List<String>): String {
        val preferred = engine.preferred(kind)?.value
        if (preferred != null && options.contains(preferred)) return preferred
        return options.firstOrNull().orEmpty()
    }

    /**
     * 「最近使用」用相对时间而不是绝对时刻：跨天、跨时区都不需要额外处理，
     * 也免得测试机上时间不对时整页看着像坏数据。
     */
    private fun relativeTime(timeMs: Long): String = DateUtils.getRelativeTimeSpanString(
        timeMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()

    // endregion

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private class OptionRow(
        val kind: F032Engine.Kind,
        val value: String,
        val root: LinearLayout,
        val label: TextView,
        val meta: TextView,
        val badge: TextView
    )

    private companion object {
        /** 学习结果的落盘位置，键名与功能 id 对齐，方便排查。 */
        const val PREFS_NAME = "f032_preference_learning"
        const val KEY_LEARNING = "f032_learning_json"
    }
}