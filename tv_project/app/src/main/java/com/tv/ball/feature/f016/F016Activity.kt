package com.tv.ball.feature.f016

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF016Binding
import com.tv.ball.util.CustomToast
import kotlin.math.roundToInt

/**
 * 「双语双行字幕合成」页。
 *
 * 两个输入框预填内置示例 SRT，点「合成」把两段文本交给 [F016Engine]：
 * 解析 → 按时间轴切分合成 → 结果列表（时间 + 主行 + 副行）+ 统计（合成条数 / 重叠条数 / 单行条数）。
 *
 * 结果行是代码里动态拼的：行数取决于字幕条数，用 XML 写死没有意义；
 * 行本身不可聚焦，电视上焦点只在两个输入框和「合成」之间走，不会掉进长列表。
 * 时间换算、合并规则全部在引擎里，界面只负责画。
 */
class F016Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF016Binding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF016Binding.inflate(layoutInflater)
        setContentView(binding.root)

        // 预填示例：手边没有 SRT 文件也能立刻看到重叠/单行的差别
        binding.f016PrimaryInput.setText(F016Engine.SAMPLE_PRIMARY)
        binding.f016SecondaryInput.setText(F016Engine.SAMPLE_SECONDARY)

        binding.f016MergeButton.setOnClickListener { renderMerged(showToast = true) }

        renderMerged(showToast = false)

        // 进页面焦点落在「合成」上，遥控器确认键一键出结果；要改文本再按上键进输入框
        binding.f016MergeButton.requestFocus()
    }

    /**
     * 输入框里输入法要吃确认键（换行），所以焦点在 EditText 上时放行；
     * 其余焦点位置确认键等同点「合成」，和页面上写的「确认键执行」一致。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && isConfirmKey(event.keyCode)) {
            if (currentFocus is EditText) return super.dispatchKeyEvent(event)
            renderMerged(showToast = true)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isConfirmKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    /** 解析两段输入 → 合成 → 刷新统计与列表。 */
    private fun renderMerged(showToast: Boolean) {
        val primary = F016Engine.parseSrt(binding.f016PrimaryInput.text.toString())
        val secondary = F016Engine.parseSrt(binding.f016SecondaryInput.text.toString())
        val merged = F016Engine.merge(primary, secondary)
        val summary = F016Engine.summarize(merged)

        renderStats(primary, secondary, summary)
        renderRows(merged)

        if (showToast) {
            CustomToast.show(
                this,
                if (merged.isEmpty()) {
                    getString(R.string.f016_toast_empty)
                } else {
                    getString(R.string.f016_toast_done, summary.total, summary.overlap)
                }
            )
        }
    }

    private fun renderStats(
        primary: List<F016Engine.Cue>,
        secondary: List<F016Engine.Cue>,
        summary: F016Engine.Summary
    ) {
        binding.f016StatTotal.text = summary.total.toString()
        binding.f016StatOverlap.text = summary.overlap.toString()
        binding.f016StatSingle.text = summary.single.toString()

        val span = F016Engine.timelineSpan(primary, secondary)
        binding.f016StatDetail.text = if (span == null) {
            getString(R.string.f016_empty_result)
        } else {
            // 覆盖率按两路平均算：1.0 表示整条时间轴上下两行都在
            val percent = (F016Engine.overlapRatio(primary, secondary) * 100f).roundToInt()
            getString(
                R.string.f016_stat_detail,
                F016Engine.formatTime(span.first),
                F016Engine.formatTime(span.second),
                percent,
                summary.primaryOnly,
                summary.secondaryOnly
            )
        }
    }

    private fun renderRows(merged: List<F016Engine.MergedCue>) {
        val container: LinearLayout = binding.f016ResultList
        container.removeAllViews()

        if (merged.isEmpty()) {
            container.addView(buildHint(getString(R.string.f016_empty_result)))
            return
        }

        merged.forEachIndexed { index, cue ->
            container.addView(buildRow(index, cue))
        }
    }

    /** 一条结果：时间行 + 主行 +（有则）副行。 */
    private fun buildRow(index: Int, cue: F016Engine.MergedCue): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card_surface)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (index > 0) topMargin = dp(6) }
        }

        row.addView(
            TextView(this).apply {
                text = getString(
                    if (cue.isOverlap) R.string.f016_row_time_overlap else R.string.f016_row_time_single,
                    F016Engine.formatTime(cue.startMs),
                    F016Engine.formatTime(cue.endMs)
                )
                setTextColor(ContextCompat.getColor(this@F016Activity, R.color.theme_text_secondary))
                textSize = 12f
            }
        )

        cue.primaryText?.let { text ->
            row.addView(
                lineView(
                    tag = getString(R.string.f016_row_tag_primary),
                    text = text,
                    colorRes = R.color.theme_text_primary,
                    sizeSp = 15f
                )
            )
        }
        cue.secondaryText?.let { text ->
            row.addView(
                lineView(
                    tag = getString(R.string.f016_row_tag_secondary),
                    text = text,
                    colorRes = R.color.theme_text_secondary,
                    sizeSp = 13f
                )
            )
        }
        return row
    }

    /** 一行字幕：前缀「主 / 副」用弱色，正文用对应强调色。 */
    private fun lineView(tag: String, text: String, colorRes: Int, sizeSp: Float): View =
        TextView(this).apply {
            this.text = if (tag.isEmpty()) text else "$tag · $text"
            setTextColor(ContextCompat.getColor(this@F016Activity, colorRes))
            textSize = sizeSp
            setPadding(0, dp(4), 0, 0)
        }

    private fun buildHint(message: String): View =
        TextView(this).apply {
            text = message
            setTextColor(ContextCompat.getColor(this@F016Activity, R.color.theme_text_secondary))
            textSize = 13f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).roundToInt()
}