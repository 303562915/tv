package com.tv.ball.feature.f051

import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF051Binding

/**
 * F051「结果关键字高亮」页面。
 *
 * 区间计算全在 [F051Engine]；这里只做三件事：
 * 1. 输入框按空格拆多词，交给 [F051Engine.parseKeywords]；
 * 2. 结果列表每一行用 [SpannableString] + [ForegroundColorSpan]（`theme_primary`）
 *    + [StyleSpan]（BOLD）把命中片段画出来；
 * 3. 顶部统计「关键词 / 命中行 / 高亮片段」三个数。
 *
 * 示例词里故意放「流浪地球」和「地球」，用来演示重叠区间合并成一段。
 */
class F051Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF051Binding

    private var ignoreCase = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF051Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.inputKeywords.setText(getString(R.string.f051_sample_keywords))
        binding.inputKeywords.setSelection(binding.inputKeywords.text.length)

        binding.inputKeywords.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = render(s?.toString().orEmpty())
        })

        binding.btnSample.setOnClickListener {
            binding.inputKeywords.setText(getString(R.string.f051_sample_keywords))
            binding.inputKeywords.setSelection(binding.inputKeywords.text.length)
        }
        binding.btnClear.setOnClickListener { binding.inputKeywords.setText("") }
        binding.btnCase.setOnClickListener {
            ignoreCase = !ignoreCase
            refreshCaseLabel()
            render(binding.inputKeywords.text.toString())
        }

        refreshCaseLabel()
        render(binding.inputKeywords.text.toString())
    }

    /** 命中片段统一走主题色加粗，颜色只取 `theme_*` token。 */
    private fun render(rawKeywords: String) {
        val keywords = F051Engine.parseKeywords(rawKeywords)
        val lines = sampleLines()

        binding.resultContainer.removeAllViews()

        var hitLines = 0
        var hitParts = 0
        for (line in lines) {
            val ranges = F051Engine.findRanges(line, keywords, ignoreCase)
            if (ranges.isNotEmpty()) {
                hitLines += 1
                hitParts += ranges.size
            }
            binding.resultContainer.addView(buildRow(line, ranges))
        }

        if (lines.isEmpty()) {
            binding.resultContainer.addView(buildRow(getString(R.string.f051_result_empty), emptyList()))
        }

        binding.statsValue.text = getString(R.string.f051_stats, keywords.size, hitLines, hitParts)
    }

    /** 一行结果：命中区间各自加前景色 + 加粗，未命中的字符保持默认样式。 */
    private fun buildRow(line: String, ranges: List<IntRange>): TextView {
        val span = SpannableString(line)
        for (range in ranges) {
            val start = range.first.coerceAtLeast(0)
            val end = (range.last + 1).coerceAtMost(line.length)
            if (start >= end) continue
            span.setSpan(
                ForegroundColorSpan(color(R.color.theme_primary)),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            span.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        return TextView(this).apply {
            text = span
            textSize = 15f
            setTextColor(color(R.color.theme_text_primary))
            background = ContextCompat.getDrawable(this@F051Activity, R.drawable.bg_mode_row)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
    }

    /** 示例结果行，内容全部来自 `f051_` 前缀的字符串资源。 */
    private fun sampleLines(): List<String> = listOf(
        getString(R.string.f051_sample_line_1),
        getString(R.string.f051_sample_line_2),
        getString(R.string.f051_sample_line_3),
        getString(R.string.f051_sample_line_4),
        getString(R.string.f051_sample_line_5)
    )

    private fun refreshCaseLabel() {
        binding.btnCase.text = if (ignoreCase) {
            getString(R.string.f051_case_on)
        } else {
            getString(R.string.f051_case_off)
        }
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()
}