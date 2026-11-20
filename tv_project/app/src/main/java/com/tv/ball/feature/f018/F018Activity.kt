package com.tv.ball.feature.f018

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF018Binding
import com.tv.ball.di.Dependencies
import com.tv.ball.utils.ThemeUtils

/**
 * 字幕文本检索与跳转。
 *
 * 检索逻辑全在 [F018Engine]（纯 Kotlin、可单测），这里只做三件事：
 * 拿关键字去搜、把命中区间用 SpannableString 高亮成片段、点「跳转」把目标时间与所在条目显示出来。
 * 真机上字幕轨由播放页传入，这一版用 6 条内置样例把链路跑通。
 */
class F018Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF018Binding
    private var hits: List<F018Engine.Hit> = emptyList()
    private var jumpedRow: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // 主题色 / 夜间模式和全站一致：建视图前先按配置套一遍，避免黑白闪一下
        ThemeUtils.applyNightMode(Dependencies.configRepository.getNightMode())
        super.onCreate(savedInstanceState)
        binding = ActivityF018Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSearch.setOnClickListener { runSearch() }
        // 遥控器/软键盘的「搜索」都走同一条路径，TV 上没有触摸屏只能靠 D-Pad + 确认键
        binding.searchInput.setOnEditorActionListener { _, actionId, event ->
            if (isSearchAction(actionId, event)) {
                runSearch()
                true
            } else {
                false
            }
        }

        binding.tvSummary.text = getString(R.string.f018_summary_idle)
        binding.tvEmpty.text = getString(R.string.f018_empty_hint)
        binding.tvEmpty.visibility = View.VISIBLE
        binding.searchInput.requestFocus()
    }

    private fun isSearchAction(actionId: Int, event: KeyEvent?): Boolean {
        if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) return true
        if (event == null || event.action != KeyEvent.ACTION_DOWN) return false
        return event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER
    }

    private fun runSearch() {
        val keyword = binding.searchInput.text?.toString()?.trim().orEmpty()
        binding.resultsBox.removeAllViews()
        binding.tvJumpTarget.visibility = View.GONE
        jumpedRow = null

        if (keyword.isEmpty()) {
            hits = emptyList()
            binding.tvSummary.text = getString(R.string.f018_summary_idle)
            binding.tvEmpty.text = getString(R.string.f018_empty_hint)
            binding.tvEmpty.visibility = View.VISIBLE
            return
        }

        hits = F018Engine.search(SAMPLE_CUES, keyword)
        if (hits.isEmpty()) {
            binding.tvSummary.text = getString(R.string.f018_summary_no_hit, keyword)
            binding.tvEmpty.text = getString(R.string.f018_empty_no_hit, keyword)
            binding.tvEmpty.visibility = View.VISIBLE
            return
        }

        binding.tvEmpty.visibility = View.GONE
        val cueCount = hits.map { it.cue.index }.distinct().size
        binding.tvSummary.text = getString(R.string.f018_summary_hits, keyword, hits.size, cueCount)

        var firstJump: Button? = null
        for (hit in hits) {
            val row = buildRow(hit)
            row.jump.setOnClickListener { showJumpTarget(row.root, hit) }
            binding.resultsBox.addView(row.root)
            if (firstJump == null) firstJump = row.jump
        }
        // 搜完把焦点交给第一条结果，遥控器用户按一下就能跳
        firstJump?.requestFocus()
    }

    /** 一行结果：时间列 + 高亮片段 + 跳转按钮。 */
    private fun buildRow(hit: F018Engine.Hit): ResultRow {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundResource(R.drawable.bg_mode_row)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        val time = TextView(this).apply {
            text = getString(R.string.f018_hit_time, F018Engine.format(hit.cue.startMs), hit.cue.index)
            setTextColor(ContextCompat.getColor(this@F018Activity, R.color.theme_text_secondary))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(dp(160), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val snippet = TextView(this).apply {
            text = highlight(hit)
            setTextColor(ContextCompat.getColor(this@F018Activity, R.color.theme_text_primary))
            textSize = 15f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        }

        val jump = Button(this).apply {
            text = getString(R.string.f018_action_jump)
            isFocusable = true
            setBackgroundResource(R.drawable.bg_button_secondary)
            setTextColor(ContextCompat.getColor(this@F018Activity, R.color.theme_primary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(12) }
        }

        row.addView(time)
        row.addView(snippet)
        row.addView(jump)
        return ResultRow(row, jump)
    }

    /**
     * 命中片段高亮：截取命中前后各 [CONTEXT_CHARS] 个字，把命中区间套上主色 + 加粗。
     *
     * 用 SpannableString + ForegroundColorSpan / StyleSpan，不自己拼富文本；
     * 折叠匹配时忽略过空白，所以区间按原文下标还原，'\n' 换成空格后长度不变、下标仍然对得上。
     */
    private fun highlight(hit: F018Engine.Hit): SpannableString {
        val flat = hit.cue.text.replace('\n', ' ')
        val from = (hit.matchStart - CONTEXT_CHARS).coerceAtLeast(0)
        val to = (hit.matchEnd + CONTEXT_CHARS).coerceAtMost(flat.length)
        val head = if (from > 0) "…" else ""
        val tail = if (to < flat.length) "…" else ""

        val builder = StringBuilder(head)
        builder.append(flat, from, to)
        builder.append(tail)
        val spannable = SpannableString(builder.toString())

        val start = (head.length + (hit.matchStart - from)).coerceIn(0, spannable.length)
        val end = (head.length + (hit.matchEnd - from)).coerceIn(start, spannable.length)
        if (end > start) {
            val accent = ContextCompat.getColor(this, R.color.theme_primary)
            spannable.setSpan(ForegroundColorSpan(accent), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return spannable
    }

    /** 点「跳转」：显示跳转目标时间、所在条目与那句台词，并把该行标成选中态。 */
    private fun showJumpTarget(row: View, hit: F018Engine.Hit) {
        jumpedRow?.setBackgroundResource(R.drawable.bg_mode_row)
        row.setBackgroundResource(R.drawable.bg_mode_card_selected)
        jumpedRow = row

        val text = getString(
            R.string.f018_jump_target,
            F018Engine.format(hit.cue.startMs),
            F018Engine.format(hit.cue.endMs),
            hit.cue.index,
            excerpt(hit)
        )
        binding.tvJumpTarget.text = text
        binding.tvJumpTarget.contentDescription = text
        binding.tvJumpTarget.visibility = View.VISIBLE
    }

    /** 跳转面板里的台词：整句放得下就整句，太长就围绕命中处截一段。 */
    private fun excerpt(hit: F018Engine.Hit): String {
        val flat = hit.cue.text.replace('\n', ' ')
        if (flat.length <= PLAIN_MAX) return flat
        val from = (hit.matchStart - CONTEXT_CHARS).coerceAtLeast(0)
        val to = (from + PLAIN_MAX).coerceAtMost(flat.length)
        return flat.substring(from, to) + "…"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class ResultRow(val root: View, val jump: Button)

    private companion object {
        /** 命中片段前后各留多少字。 */
        const val CONTEXT_CHARS = 12

        /** 跳转面板里单句台词的最大长度。 */
        const val PLAIN_MAX = 60

        /** 内置样例字幕：真机上换成播放页传入的当前字幕轨。 */
        val SAMPLE_CUES: List<F018Engine.Cue> = listOf(
            F018Engine.Cue(1, 3_200L, 7_600L, "我们抬头看星星，想把这一夜记住。"),
            F018Engine.Cue(2, 12_000L, 15_800L, "「這個世界值得我們守護」"),
            F018Engine.Cue(3, 24_500L, 28_200L, "We are not alone in the dark, 别一个人熬着。"),
            F018Engine.Cue(4, 41_000L, 45_900L, "雨停之前，别把灯关掉。"),
            F018Engine.Cue(5, 63_000L, 67_800L, "搜救队会在天亮前到达东岸，星星就是路标。"),
            F018Engine.Cue(6, 91_500L, 96_200L, "最后一班列车，十一点半发车。")
        )
    }
}