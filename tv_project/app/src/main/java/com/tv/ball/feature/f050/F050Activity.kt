package com.tv.ball.feature.f050

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF050Binding

/**
 * F050「模糊匹配容错」页面。
 *
 * 所有判定都在 [F050Engine] 里，这里只负责三件事：
 * 1. 收一句查询、收一个容错级别（0~2）；
 * 2. 把 8 条演示标题按命中分排出来，每条都带上「为什么算命中」；
 * 3. 一条都没命中时给出空态，而不是留一块白板。
 *
 * 演示入口固定演示一件最容易说明问题的事：
 * `luse hai yang` 拼错一个音节，在容错 2 下仍能落到「蓝色海洋」。
 */
class F050Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF050Binding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF050Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rgTolerance.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId != View.NO_ID) rerun()
        }
        binding.btnSearch.setOnClickListener { searchFromInput() }
        binding.btnDemo.setOnClickListener { runDemo() }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                searchFromInput()
                true
            } else {
                false
            }
        }

        render("")
    }

    // region 交互

    /** 容错级别：单选按钮的位置就是允许的编辑距离，界面上不做二次换算。 */
    private fun currentTolerance(): Int = when (binding.rgTolerance.checkedRadioButtonId) {
        R.id.rb_tol0 -> 0
        R.id.rb_tol1 -> 1
        else -> F050Engine.TOLERANCE_MAX
    }

    /** 输入框里的内容直接匹配；全空白（归一化后为空）不触发。 */
    private fun searchFromInput() {
        val raw = binding.searchInput.text.toString()
        if (F050Engine.normalize(raw).isEmpty()) {
            toast(getString(R.string.f050_blank_query))
            return
        }
        render(raw)
        toast(getString(R.string.f050_toast_searched, raw.trim()))
    }

    /** 演示：填 `luse hai yang`，级别拉到 2，然后直接跑一遍。 */
    private fun runDemo() {
        binding.searchInput.setText(DEMO_QUERY)
        binding.searchInput.setSelection(DEMO_QUERY.length)
        binding.rbTol2.isChecked = true
        render(DEMO_QUERY)
        toast(getString(R.string.f050_toast_searched, DEMO_QUERY))
    }

    /** 换了容错级别：输入框里还有内容就按新级别重算，没有就回到引导态。 */
    private fun rerun() {
        val raw = binding.searchInput.text.toString()
        if (F050Engine.normalize(raw).isEmpty()) {
            render("")
            return
        }
        render(raw)
    }

    // endregion

    // region 渲染

    /**
     * 整页刷新。
     *
     * 空查询走引导态：结果区清空、空态隐藏、状态行只说「还没查询」。
     * 有查询就交给 [F050Engine.rank]，命中为空时显示带查询词和当前级别的空态。
     */
    private fun render(rawQuery: String) {
        val tolerance = currentTolerance()
        val normalized = F050Engine.normalize(rawQuery)

        binding.normalizeStat.text = if (normalized.isEmpty()) {
            getString(R.string.f050_normalize_idle)
        } else {
            getString(R.string.f050_normalize_stat, normalized)
        }

        val matches = if (normalized.isEmpty()) {
            emptyList()
        } else {
            F050Engine.rank(DEMO_TITLES, rawQuery, tolerance)
        }

        binding.resultStat.text = when {
            normalized.isEmpty() -> getString(R.string.f050_status_idle)
            matches.isEmpty() -> getString(R.string.f050_result_empty)
            else -> getString(R.string.f050_status_hit, matches.size, tolerance)
        }

        val showEmpty = normalized.isNotEmpty() && matches.isEmpty()
        binding.emptyState.visibility = if (showEmpty) View.VISIBLE else View.GONE
        if (showEmpty) {
            binding.emptyState.text = getString(R.string.f050_empty_hit, rawQuery.trim(), tolerance)
        }

        val container = binding.resultContainer
        container.removeAllViews()
        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        matches.forEachIndexed { index, match ->
            container.addView(resultRow(match, index, matches.size, gap))
        }
    }

    /** 一条结果行：标题 | 分数与距离 | 命中原因角标；点一下把判定细节再报一次。 */
    private fun resultRow(match: F050Engine.Match, index: Int, total: Int, gap: Int): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@F050Activity, R.drawable.bg_mode_row)
            isClickable = true
            isFocusable = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = if (index == 0 || total == 0) 0 else gap / 2 }

            val title = TextView(this@F050Activity).apply {
                text = match.title
                textSize = 15f
                setTextColor(color(R.color.theme_text_primary))
                isFocusable = false
            }
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val meta = TextView(this@F050Activity).apply {
                text = getString(R.string.f050_result_meta, match.score, match.distance)
                textSize = 12f
                setTextColor(color(R.color.theme_text_secondary))
                setPadding(0, 0, dp(8), 0)
                isFocusable = false
            }
            addView(
                meta,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            val badge = TextView(this@F050Activity).apply {
                text = reasonLabel(match.reasonKey)
                textSize = 12f
                setTextColor(color(if (match.score >= 90) R.color.theme_primary else R.color.theme_text_secondary))
                background = ContextCompat.getDrawable(this@F050Activity, R.drawable.ranking_badge_bg)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                isFocusable = false
            }
            addView(
                badge,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            setOnClickListener {
                toast(getString(R.string.f050_toast_row, match.title, reasonLabel(match.reasonKey), match.distance))
            }
        }

    /** 命中原因键 → 界面文案。引擎只回键，翻译只在这一处。 */
    private fun reasonLabel(reasonKey: String): String = getString(
        when (reasonKey) {
            F050Engine.REASON_EXACT -> R.string.f050_reason_exact
            F050Engine.REASON_PREFIX -> R.string.f050_reason_prefix
            F050Engine.REASON_CONTAINS -> R.string.f050_reason_contains
            F050Engine.REASON_PINYIN -> R.string.f050_reason_pinyin
            F050Engine.REASON_PINYIN_PART -> R.string.f050_reason_pinyin_part
            F050Engine.REASON_PINYIN_TYPO -> R.string.f050_reason_pinyin_typo
            else -> R.string.f050_reason_typo
        }
    )

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    // endregion

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** 演示查询：lanse 少写一个音节、空格也不规范，正好一次覆盖「拼错 + 空格」。 */
        const val DEMO_QUERY = "luse hai yang"

        /**
         * 8 条中文演示标题。
         *
         * 刻意混了完整句（漫长的季节）、带空格的季号（三体 第二季）和两字短名（繁花、狂飙），
         * 这样一条查询能同时演示前缀、包含、错字、拼音四条链路。
         */
        val DEMO_TITLES: List<String> = listOf(
            "蓝色海洋",
            "深海迷航",
            "漫长的季节",
            "三体 第二季",
            "流浪地球",
            "繁花",
            "隐秘的角落",
            "狂飙"
        )
    }
}