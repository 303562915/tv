package com.tv.ball.feature.f044

import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF044Binding
import com.tv.ball.util.CustomToast

/**
 * 「搜索建议与联想」页。
 *
 * 页面上三块都是真状态，不是演示动画：
 * 1. 搜索框 —— 每敲一个字都真的走一次 [F044Engine.suggest]，候选列表跟着重画；
 * 2. 候选列表 —— 前缀命中的字染成 primary，行尾角标说明这条候选来自历史还是标题库；
 * 3. 点击填入 —— 点候选（或遥控器确认键）把整条文案填进搜索框并立刻执行搜索，
 *    搜索框内容、候选、结果行三者是同一份数据推出来的，不会出现「填了但没搜」。
 *
 * 历史与标题库这两份数据用页面内的示例固定住（历史 6 条、标题 8 条），
 * 因为 F044 只负责「输入 → 候选」这一段；真正的历史读写在 F043 全局搜索里，
 * 接线时把 [SAMPLE_HISTORY] / [SAMPLE_TITLES] 换成仓库返回的列表即可，[F044Engine.suggest] 的签名不用动。
 */
class F044Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF044Binding

    /** 上一次渲染出来的候选行；每次输入都会重建，所以只留引用给焦点串联用。 */
    private val rowViews = ArrayList<SuggestionRowView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF044Binding.inflate(layoutInflater)
        setContentView(binding.root)

        setupInput()
        setupClear()
        renderSampleSummary()
        refresh()

        // 电视没有触摸屏：进页把焦点落在搜索框，遥控器直接就能敲字
        binding.f044SearchInput.post { binding.f044SearchInput.requestFocus() }
    }

    // ===== 交互接线 =====

    private fun setupInput() {
        binding.f044SearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                refresh()
            }
        })
        // 带键盘的设备上 IME 的「搜索」键等价于确认一次：直接按当前输入搜
        binding.f044SearchInput.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                submit(binding.f044SearchInput.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }
    }

    private fun setupClear() {
        binding.f044Clear.setOnClickListener {
            binding.f044SearchInput.setText("")
            binding.f044SearchResult.setText(R.string.f044_result_idle)
            binding.f044SearchInput.requestFocus()
            CustomToast.show(this, getString(R.string.f044_toast_cleared))
        }
    }

    /** 点候选：整条文案填进搜索框，然后立刻搜索。 */
    private fun fill(item: F044Engine.Suggestion) {
        binding.f044SearchInput.setText(item.text)
        binding.f044SearchInput.setSelection(item.text.length)
        // setText 会同步触发 TextWatcher → refresh()，候选已按新输入重排，这里只补结果行
        submit(item.text)
    }

    /** 执行一次搜索：这里只把「搜什么」落到结果行，真正的结果页在 F043 全局搜索。 */
    private fun submit(raw: String) {
        val target = raw.trim()
        if (target.isEmpty()) {
            binding.f044SearchResult.setText(R.string.f044_result_idle)
            CustomToast.show(this, getString(R.string.f044_toast_empty))
            return
        }
        binding.f044SearchResult.text = getString(R.string.f044_result_filled, target)
        CustomToast.show(this, getString(R.string.f044_toast_search, target))
    }

    // ===== 渲染 =====

    /** 读一次输入框，走一次引擎，重画候选。 */
    private fun refresh() {
        val query = binding.f044SearchInput.text?.toString().orEmpty()
        val suggestions = F044Engine.suggest(query, SAMPLE_HISTORY, SAMPLE_TITLES, SUGGEST_LIMIT)
        renderSuggestions(query, suggestions)
    }

    private fun renderSuggestions(query: String, suggestions: List<F044Engine.Suggestion>) {
        val needle = query.trim()
        val fromHistory = suggestions.count { it.kind == F044Engine.Kind.HISTORY }
        binding.f044SuggestionCount.text = when {
            suggestions.isEmpty() -> getString(R.string.f044_status_none)
            // 空输入给的是最近历史，条数就是历史条数，不用再分来源
            needle.isEmpty() -> getString(R.string.f044_status_recent, suggestions.size)
            else -> getString(
                R.string.f044_status_hits,
                suggestions.size,
                fromHistory,
                suggestions.size - fromHistory
            )
        }
        binding.f044Empty.visibility = if (suggestions.isEmpty()) View.VISIBLE else View.GONE
        rebuildRows(suggestions, needle)
    }

    private fun rebuildRows(suggestions: List<F044Engine.Suggestion>, needle: String) {
        val container = binding.f044SuggestionList
        container.removeAllViews()
        rowViews.clear()
        // 旧行的 id 随着 removeAllViews 一起失效，先把向下焦点解掉，免得输入框往下按落在不存在的 view 上
        binding.f044SearchInput.nextFocusDownId = View.NO_ID
        if (suggestions.isEmpty()) return

        val rowHeight = resources.getDimensionPixelSize(R.dimen.list_row_min_height)
        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        val pad = dp(12)

        suggestions.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (index == 0) 0 else gap }
                minimumHeight = rowHeight
                setPadding(pad, 0, pad, 0)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                background = ContextCompat.getDrawable(this@F044Activity, R.drawable.bg_card_surface)
                foreground = ContextCompat.getDrawable(this@F044Activity, R.drawable.bg_focus_ring)
                setOnClickListener { fill(item) }
            }

            // 字号/字色直接按 TextAppearance.TVPlayer.Body 的 15sp / theme_text_primary 写死在这里：
            // 动态生成的行拿不到布局里的 textAppearance，而这套值是全站正文的基准
            val label = TextView(this).apply {
                text = highlight(item.text, needle)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, BODY_SP)
                setTextColor(ContextCompat.getColor(this@F044Activity, R.color.theme_text_primary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            // 角标对应 TextAppearance.TVPlayer.Caption 的 12sp，颜色按来源区分：历史 primary、标题 secondary
            val badge = TextView(this).apply {
                setText(
                    if (item.kind == F044Engine.Kind.HISTORY) R.string.f044_kind_history
                    else R.string.f044_kind_title
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, CAPTION_SP)
                setTextColor(
                    ContextCompat.getColor(
                        this@F044Activity,
                        if (item.kind == F044Engine.Kind.HISTORY) R.color.theme_primary
                        else R.color.theme_text_secondary
                    )
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            row.addView(label)
            row.addView(badge)
            container.addView(row)
            rowViews.add(SuggestionRowView(row, item))
        }

        // 动态行之间显式串 nextFocus：默认焦点搜索会跳过列表跑到页面外的控件上
        rowViews.forEachIndexed { index, item ->
            item.root.id = View.generateViewId()
            if (index > 0) item.root.nextFocusUpId = rowViews[index - 1].root.id
            if (index < rowViews.lastIndex) item.root.nextFocusDownId = rowViews[index + 1].root.id
        }
        binding.f044SearchInput.nextFocusDownId = rowViews.first().root.id
    }

    /** 把命中的那一段染成 primary，输入框里的字和候选行上的字一眼能对上。 */
    private fun highlight(text: String, needle: String): CharSequence {
        if (needle.isEmpty()) return text
        val start = text.lowercase().indexOf(needle.lowercase())
        if (start < 0) return text
        val end = (start + needle.length).coerceAtMost(text.length)
        val spanned = SpannableString(text)
        spanned.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.theme_primary)),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spanned
    }

    private fun renderSampleSummary() {
        binding.f044SampleNote.text =
            getString(R.string.f044_sample_summary, SAMPLE_HISTORY.size, SAMPLE_TITLES.size)
        binding.f044SampleHistory.text =
            getString(R.string.f044_sample_history, SAMPLE_HISTORY.joinToString(" · "))
        binding.f044SampleTitles.text =
            getString(R.string.f044_sample_titles, SAMPLE_TITLES.joinToString(" · "))
    }

    // ===== 小工具 =====

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    private companion object {
        /** 示例历史：6 条，按「最近的在前」排，和真实历史列表的约定一致。 */
        val SAMPLE_HISTORY = listOf(
            "流浪地球 3",
            "狂飙 第二季",
            "三体 动画",
            "漫长的季节",
            "庆余年 第二季",
            "繁花"
        )

        /** 示例标题库：8 条，故意和历史上部分撞名，用来验证「去重后角标仍是历史」。 */
        val SAMPLE_TITLES = listOf(
            "流浪地球 2",
            "流浪地球 3 预告",
            "三体 第一季",
            "三体 第二季",
            "狂飙 第一季",
            "庆余年 第一季",
            "漫长的季节 特别篇",
            "繁花 沪语版"
        )

        /** 一次最多显示几条候选：一屏看完，不把搜索框顶出视野。 */
        const val SUGGEST_LIMIT = 6

        /** 候选正文的字号，对齐 TextAppearance.TVPlayer.Body 的 15sp。 */
        const val BODY_SP = 15f

        /** 角标的字号，对齐 TextAppearance.TVPlayer.Caption 的 12sp。 */
        const val CAPTION_SP = 12f
    }
}

/** 候选行里需要保住的那一份引用：重建行时靠它串焦点。 */
private class SuggestionRowView(
    val root: LinearLayout,
    val suggestion: F044Engine.Suggestion
)
