package com.tv.ball.feature.f046

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import com.tv.ball.databinding.ActivityF046Binding

/**
 * F046「拼音首字母匹配」页面。
 *
 * 匹配规则全在 [F046Engine]（纯 Kotlin），这里只做三件事：
 * 收输入（文本框 + 三个快捷按钮）、把 [F046Engine.rank] 的结果按顺序铺成行、
 * 把每行的命中类型标出来。没有命中就显示空态，不做任何兜底假数据。
 */
class F046Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF046Binding

    /**
     * 8 条中文示例标题，都在遥控器上很难打全：
     * 输入 lsh 命中「蓝色海洋」，输入全拼 lanse 同时命中「蓝色海洋」和「蓝色星球」。
     */
    private val samples: List<String> = listOf(
        "蓝色海洋",
        "流浪地球",
        "星际穿越",
        "漫长的季节",
        "深海迷航",
        "蓝色星球",
        "宇宙时空之旅",
        "人间烟火"
    )

    /** 动态生成的结果行，只用来串 D-Pad 的上下焦点。 */
    private val resultRows = ArrayList<LinearLayout>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF046Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = render()
        })

        binding.etQuery.setOnEditorActionListener { _, actionId, _ ->
            val confirmed = actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
            if (confirmed) {
                render()
                true
            } else {
                false
            }
        }

        // 快捷按钮只负责把查询词塞进输入框，重算统一交给 TextWatcher，避免两条渲染路径。
        binding.btnQuickInitials.setOnClickListener { fillQuery(getString(R.string.f046_quick_initials)) }
        binding.btnQuickFull.setOnClickListener { fillQuery(getString(R.string.f046_quick_full)) }
        binding.btnQuickEmpty.setOnClickListener { fillQuery(getString(R.string.f046_quick_empty)) }

        renderCoverage()
        // 进页面就给一个能命中的查询，省得先手动打字才知道这页在干什么。
        fillQuery(getString(R.string.f046_quick_initials))
        binding.etQuery.requestFocus()
    }

    // region 查询

    private fun fillQuery(query: String) {
        binding.etQuery.setText(query)
        binding.etQuery.setSelection(query.length)
    }

    /**
     * 重算并重绘。
     *
     * 每次输入都整表重算：8 条标题的规模下这是最省心的做法，
     * 也保证结果顺序永远等于 [F046Engine.rank] 排出来的顺序，不会出现增量更新的偏差。
     */
    private fun render() {
        val raw = binding.etQuery.text?.toString().orEmpty().trim()
        val ranked = F046Engine.rank(samples, raw)

        binding.statText.text = if (raw.isEmpty()) {
            getString(R.string.f046_stat_idle)
        } else {
            getString(R.string.f046_stat, raw, ranked.size, samples.size)
        }

        // 查询为空时不算「无命中」，只有真输了东西还一条都没有才亮空态。
        binding.emptyState.visibility = if (raw.isNotEmpty() && ranked.isEmpty()) View.VISIBLE else View.GONE

        buildRows(ranked)
        wireFocus()
    }

    /** 按 rank 的顺序铺行：第一行就是最匹配的那条。 */
    private fun buildRows(ranked: List<F046Engine.Ranked>) {
        val container = binding.resultContainer
        container.removeAllViews()
        resultRows.clear()

        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        val rowMinHeight = resources.getDimensionPixelSize(R.dimen.list_row_min_height)
        val pad = dp(12)

        ranked.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = rowMinHeight
                setPadding(pad, dp(6), pad, dp(6))
                // TV 没有触摸屏，行本身必须可聚焦，否则 D-Pad 只能停在上面那几个按钮上。
                isFocusable = true
                isFocusableInTouchMode = true
                background = ContextCompat.getDrawable(this@F046Activity, R.drawable.bg_mode_row)
                id = View.generateViewId()
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (index == 0) 0 else gap / 2 }
            }

            val lines = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = false
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleLine = TextView(this).apply {
                text = getString(R.string.f046_row_title, index + 1, item.title)
                setTextColor(color(R.color.theme_text_primary))
                textSize = 16f
                isFocusable = false
                maxLines = 1
            }

            val keyLine = TextView(this).apply {
                text = getString(R.string.f046_row_keys, item.initials, item.fullPinyin)
                setTextColor(color(R.color.theme_text_secondary))
                textSize = 13f
                isFocusable = false
                maxLines = 1
            }

            val kindLabel = TextView(this).apply {
                text = kindText(item.kind, item.channel)
                setTextColor(color(R.color.theme_primary))
                textSize = 13f
                isFocusable = false
                setPadding(gap, dp(2), dp(4), dp(2))
            }

            lines.addView(titleLine)
            lines.addView(keyLine)
            row.addView(lines)
            row.addView(kindLabel)

            // 焦点可见性靠背景切换：bg_mode_row 常态、bg_mode_card_selected 聚焦态。
            row.setOnFocusChangeListener { view, hasFocus ->
                view.background = ContextCompat.getDrawable(
                    this@F046Activity,
                    if (hasFocus) R.drawable.bg_mode_card_selected else R.drawable.bg_mode_row
                )
            }
            row.setOnClickListener {
                toast(
                    getString(
                        R.string.f046_toast_picked,
                        item.title,
                        kindText(item.kind, item.channel)
                    )
                )
            }

            container.addView(row)
            resultRows.add(row)
        }
    }

    /**
     * 把「输入框 → 三个快捷按钮 → 结果行 → 回到输入框」串成一条焦点链。
     *
     * 结果行是动态建的，默认焦点搜索会直接跳出卡片，所以在每次重建后重连一遍。
     */
    private fun wireFocus() {
        binding.etQuery.nextFocusDownId = binding.btnQuickInitials.id
        binding.etQuery.nextFocusUpId = resultRows.lastOrNull()?.id ?: View.NO_ID

        binding.btnQuickInitials.nextFocusUpId = binding.etQuery.id
        binding.btnQuickInitials.nextFocusDownId = binding.btnQuickFull.id
        binding.btnQuickFull.nextFocusUpId = binding.btnQuickInitials.id
        binding.btnQuickFull.nextFocusDownId = binding.btnQuickEmpty.id
        binding.btnQuickEmpty.nextFocusUpId = binding.btnQuickFull.id
        binding.btnQuickEmpty.nextFocusDownId = resultRows.firstOrNull()?.id ?: View.NO_ID

        resultRows.forEachIndexed { index, row ->
            if (index > 0) row.nextFocusUpId = resultRows[index - 1].id
            row.nextFocusDownId = resultRows.getOrNull(index + 1)?.id ?: binding.etQuery.id
        }
    }

    /** 命中类型文案：强度 + 通道两个维度都要说清，不然「前缀」和「全拼前缀」看着一样。 */
    private fun kindText(kind: F046Engine.MatchKind, channel: F046Engine.HitChannel): String = when {
        kind == F046Engine.MatchKind.EXACT_INITIALS -> getString(R.string.f046_kind_exact_initials)
        kind == F046Engine.MatchKind.PREFIX && channel == F046Engine.HitChannel.FULL_PINYIN ->
            getString(R.string.f046_kind_prefix_pinyin)
        kind == F046Engine.MatchKind.PREFIX && channel == F046Engine.HitChannel.LITERAL ->
            getString(R.string.f046_kind_prefix_literal)
        kind == F046Engine.MatchKind.PREFIX -> getString(R.string.f046_kind_prefix_initials)
        kind == F046Engine.MatchKind.CONTAINS && channel == F046Engine.HitChannel.FULL_PINYIN ->
            getString(R.string.f046_kind_contains_pinyin)
        kind == F046Engine.MatchKind.CONTAINS && channel == F046Engine.HitChannel.LITERAL ->
            getString(R.string.f046_kind_contains_literal)
        kind == F046Engine.MatchKind.CONTAINS -> getString(R.string.f046_kind_contains_initials)
        else -> getString(R.string.f046_kind_none)
    }

    // endregion

    // region 拼音表覆盖

    /**
     * 拼音表覆盖提示。
     *
     * 表是手写的，生僻字会「查不到」，这跟「匹配逻辑坏了」看起来一样，
     * 所以把示例里的缺口直接写出来，免得被当成 bug 排查。
     */
    private fun renderCoverage() {
        val missing = F046Engine.missingChars(samples)
        binding.coverageText.text = if (missing.isEmpty()) {
            getString(R.string.f046_coverage_ok, F046Engine.dictionarySize)
        } else {
            getString(
                R.string.f046_coverage_gap,
                F046Engine.dictionarySize,
                missing.joinToString("、")
            )
        }
    }

    // endregion

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
