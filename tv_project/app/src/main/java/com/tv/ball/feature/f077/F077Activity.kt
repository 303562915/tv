package com.tv.ball.feature.f077

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF077Binding
import kotlin.math.roundToInt

/**
 * 「设置项搜索」页。
 *
 * 一段搜索框 + 一段结果列表。数据全部来自 [F077Engine]：
 * `catalog()` 是 20 个真实设置项的索引，`search()` 给出按「标题前缀 > 标题包含 > 别名」排好的结果。
 *
 * 输入即过滤：搜索框的 [TextWatcher] 每次变化都重画列表，不做防抖——索引只有 20 条，
 * 同步过滤的成本远低于一次转场延迟，加定时器反而会让「敲完还得等一下才出结果」。
 * 空查询（清空搜索框）把整份索引摊开，用户才知道这里能搜什么。
 *
 * 结果只做定位，不做跳转：点一行把「将进入 F0xx 功能页」写进状态行，**不启动任何页面**。
 * 原因与 F076 的「去清理」相同——本功能只负责回答「这项设置在哪一页」，
 * 真去改设置应当由用户在看得见完整上下文的对应功能页里按下去。
 *
 * 电视遥控器：搜索框里按上/下会把焦点交给返回键/第一条结果（见 [onSearchKey]），
 * 否则 EditText 会吃掉方向键，用户只能摸黑按返回。
 */
class F077Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF077Binding

    /** 索引只构造一次：它是常量表，没必要每敲一个字重建 20 个对象。 */
    private val catalog: List<SettingItem> = F077Engine.catalog()

    /** 搜索框文本变化 → 重新过滤。 */
    private val searchWatcher: TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(s: Editable?) {
            render(s?.toString().orEmpty())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF077Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.f077ClearButton.setOnClickListener {
            binding.f077SearchInput.setText("")
            binding.f077SearchInput.requestFocus()
        }
        binding.f077SearchInput.setOnKeyListener { _, keyCode, event -> onSearchKey(keyCode, event) }
        binding.f077SearchInput.addTextChangedListener(searchWatcher)

        render("")
        binding.f077SearchInput.requestFocus()
    }

    override fun onDestroy() {
        binding.f077SearchInput.removeTextChangedListener(searchWatcher)
        super.onDestroy()
    }

    // ===== 输入 =====

    /**
     * 搜索框上的方向键。
     *
     * EditText 默认把上/下当作光标移动，在遥控器上等于「按了没反应」；这里把上交给返回键、
     * 下交给第一条结果，左右仍留给光标移动（敲字时还要用）。
     */
    private fun onSearchKey(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> focusFirstResult()
            KeyEvent.KEYCODE_DPAD_UP -> {
                binding.btnBack.requestFocus()
                true
            }
            else -> false
        }
    }

    /** 焦点下移到第一条结果；没有结果时不动，把事件交回输入法。 */
    private fun focusFirstResult(): Boolean {
        val first = binding.f077ResultList.getChildAt(0) ?: return false
        first.requestFocus()
        return true
    }

    // ===== 渲染 =====

    /**
     * 按 [query] 重画整页：结果行、计数、状态行。
     *
     * 三个状态：空查询（摊开全部索引）、有命中（绿色，标出命中数）、无命中（黄色，给换个说法的提示）。
     */
    private fun render(query: String) {
        val results = F077Engine.search(catalog, query)

        binding.f077ResultList.removeAllViews()
        results.forEach { item -> binding.f077ResultList.addView(resultRow(item)) }

        binding.f077CountValue.text = getString(R.string.f077_count, results.size, catalog.size)

        when {
            results.isEmpty() -> setStatus(getString(R.string.f077_status_empty), R.color.theme_warning)
            F077Engine.normalize(query).isEmpty() -> setStatus(
                getString(R.string.f077_status_idle, catalog.size),
                R.color.theme_text_secondary
            )
            else -> setStatus(
                getString(R.string.f077_status_match, results.size),
                R.color.theme_success
            )
        }
    }

    /** 一行结果：标题 + 功能 id 徽标，下一行写分组与别名。 */
    private fun resultRow(item: SettingItem): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = ContextCompat.getDrawable(this@F077Activity, R.drawable.bg_card_surface)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            contentDescription = getString(
                R.string.f077_row_a11y,
                item.title,
                item.group,
                item.pageKey
            )
            setOnClickListener { onResultClick(item) }
        }
        row.addView(headRow(item))
        row.addView(metaRow(item))
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        return row
    }

    /** 第一行：左边标题，右边「F0xx」徽标——一眼看出点下去要去哪一页。 */
    private fun headRow(item: SettingItem): LinearLayout {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            TextView(this).apply {
                text = item.title
                setTextColor(color(R.color.theme_text_primary))
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
        )
        head.addView(
            TextView(this).apply {
                text = item.pageKey
                setTextColor(color(R.color.theme_primary))
                typeface = Typeface.MONOSPACE
                textSize = 13f
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = ContextCompat.getDrawable(
                    this@F077Activity,
                    R.drawable.bg_secondary_button
                )
            }
        )
        return head
    }

    /** 第二行：分组 + 别名，说明「这项设置属于哪一块」和「还能怎么搜到它」。 */
    private fun metaRow(item: SettingItem): TextView = TextView(this).apply {
        text = getString(R.string.f077_row_meta, item.group, item.aliasText())
        setTextColor(color(R.color.theme_text_secondary))
        textSize = 11f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) }
    }

    // ===== 状态 =====

    /**
     * 点一行：状态行写「将进入 F0xx 功能页：标题」。
     *
     * 刻意不 startActivity：本页的职责到「指出该去哪一页」为止，
     * 跳转、开关状态与副作用都留在对应功能页里。
     */
    private fun onResultClick(item: SettingItem) {
        setStatus(
            getString(R.string.f077_status_target, item.pageKey, item.title),
            R.color.theme_primary
        )
    }

    private fun setStatus(message: CharSequence, colorRes: Int) {
        binding.f077Status.text = message
        binding.f077Status.setTextColor(color(colorRes))
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).roundToInt()
}
