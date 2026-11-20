package com.tv.ball.feature.f045

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
import com.tv.ball.databinding.ActivityF045Binding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * F045「搜索历史管理」页面。
 *
 * 规则全在 [HistoryStore]（去重、限量、删除、清空都只是它的方法），
 * 这里只做三件事：收关键词 → 交给 Store → 把 `all()` 的顺序原样铺成行。
 *
 * 页面上的三个入口对应三条验收：
 * - 搜索框提交 → `add()`：重复关键词只留最新一条，超过 20 条淘汰最旧；
 * - 每行右侧「删除」→ `remove()`：单条删除；
 * - 顶部「清空全部」→ `clear()`：整体清空。
 *
 * 没有假数据：历史为空就亮空态，不预置示例，避免「删完了又冒出来」。
 */
class F045Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF045Binding
    private lateinit var store: HistoryStore

    /** 动态生成的行（行本身 + 行内删除键），按视觉顺序串成 D-Pad 焦点链。 */
    private val focusChain = ArrayList<View>()

    private val rowViews = ArrayList<LinearLayout>()

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF045Binding.inflate(layoutInflater)
        setContentView(binding.root)

        store = HistoryStore.create(this)

        binding.btnSubmit.setOnClickListener { submit(binding.etKeyword.text?.toString().orEmpty()) }
        binding.etKeyword.setOnEditorActionListener { _, actionId, _ ->
            val confirmed = actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
            if (confirmed) {
                submit(binding.etKeyword.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }
        binding.btnClearAll.setOnClickListener { clearAll() }

        render()
        binding.etKeyword.requestFocus()
    }

    // region 三个动作

    /**
     * 记一条搜索。
     *
     * @param announce 是否提示「已记入历史」；从历史行点回来重搜时不重复提示
     */
    private fun submit(raw: String, announce: Boolean = true) {
        val keyword = HistoryStore.normalize(raw)
        if (keyword == null) {
            toast(getString(R.string.f045_toast_blank))
            binding.etKeyword.requestFocus()
            return
        }
        store.add(keyword)
        // 提交后清空输入框：一是提示「已经记下了」，二是再搜同词时能看出历史行被提到了第一位。
        binding.etKeyword.setText("")
        render()
        if (announce) toast(getString(R.string.f045_toast_added, keyword))
    }

    /** 单条删除。删完把焦点留在原位（删掉最后一条就回到搜索框）。 */
    private fun removeOne(entry: HistoryEntry, index: Int) {
        val removed = store.remove(entry.keyword)
        render()
        if (!removed) {
            toast(getString(R.string.f045_toast_missing, entry.keyword))
            return
        }
        toast(getString(R.string.f045_toast_removed, entry.keyword))
        val target = rowViews.getOrNull(index) ?: rowViews.lastOrNull()
        (target ?: binding.etKeyword).requestFocus()
    }

    /** 整体清空。清空后列表没有可聚焦的行，焦点必须主动还给搜索框。 */
    private fun clearAll() {
        val removed = store.clear()
        binding.etKeyword.setText("")
        render()
        if (removed == 0) {
            toast(getString(R.string.f045_toast_empty_history))
        } else {
            toast(getString(R.string.f045_toast_cleared, removed))
        }
        binding.etKeyword.requestFocus()
    }

    // endregion

    // region 渲染

    private fun render() {
        val entries = store.all()

        binding.statText.text = if (entries.isEmpty()) {
            getString(R.string.f045_stat_empty)
        } else {
            getString(R.string.f045_stat, entries.size, store.capacity)
        }

        // 空历史才亮空态；「清空全部」在没有历史时直接置灰，省一次无意义点击。
        binding.emptyState.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        binding.btnClearAll.isEnabled = entries.isNotEmpty()

        buildRows(entries)
        wireFocus()
    }

    /** 按 `all()` 的顺序铺行：第一行就是最近搜索的那条。 */
    private fun buildRows(entries: List<HistoryEntry>) {
        val container = binding.historyContainer
        container.removeAllViews()
        focusChain.clear()
        rowViews.clear()

        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        val rowMinHeight = resources.getDimensionPixelSize(R.dimen.list_row_min_height)
        val pad = dp(12)

        entries.forEachIndexed { index, entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = rowMinHeight
                setPadding(pad, dp(6), pad, dp(6))
                // TV 没有触摸屏：行必须可聚焦，否则 D-Pad 只能停在上面的按钮上。
                isFocusable = true
                isFocusableInTouchMode = true
                background = ContextCompat.getDrawable(this@F045Activity, R.drawable.bg_mode_row)
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

            val keywordLine = TextView(this).apply {
                text = getString(R.string.f045_row_keyword, index + 1, entry.keyword)
                setTextColor(color(R.color.theme_text_primary))
                textSize = 16f
                isFocusable = false
                maxLines = 1
            }

            val timeLine = TextView(this).apply {
                text = getString(R.string.f045_row_time, timeFormat.format(Date(entry.usedAtMs)))
                setTextColor(color(R.color.theme_text_secondary))
                textSize = 13f
                isFocusable = false
                maxLines = 1
            }

            lines.addView(keywordLine)
            lines.addView(timeLine)

            // 删除键做成一枚可聚焦的文字按钮：TV 上不能靠长按或滑动来删。
            val deleteChip = TextView(this).apply {
                text = getString(R.string.f045_row_delete)
                contentDescription = getString(R.string.f045_row_delete_of, entry.keyword)
                setTextColor(color(R.color.theme_error))
                textSize = 14f
                gravity = Gravity.CENTER
                minWidth = dp(64)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                isFocusable = true
                isFocusableInTouchMode = true
                background = ContextCompat.getDrawable(this@F045Activity, R.drawable.bg_mode_row)
                id = View.generateViewId()
            }

            // 焦点可见性靠背景切换：bg_mode_row 常态、bg_mode_card_selected 聚焦态。
            row.setOnFocusChangeListener { view, hasFocus ->
                view.background = ContextCompat.getDrawable(
                    this@F045Activity,
                    if (hasFocus) R.drawable.bg_mode_card_selected else R.drawable.bg_mode_row
                )
            }
            deleteChip.setOnFocusChangeListener { view, hasFocus ->
                view.background = ContextCompat.getDrawable(
                    this@F045Activity,
                    if (hasFocus) R.drawable.bg_mode_card_selected else R.drawable.bg_mode_row
                )
            }

            // 点行 = 用这条关键词重搜：既省一次遥控器输入，也顺手把它提到第一位。
            row.setOnClickListener {
                binding.etKeyword.setText("")
                submit(entry.keyword, announce = false)
                toast(getString(R.string.f045_toast_reused, entry.keyword))
            }
            deleteChip.setOnClickListener { removeOne(entry, index) }

            row.addView(lines)
            row.addView(deleteChip)

            container.addView(row)
            rowViews.add(row)
            focusChain.add(row)
            focusChain.add(deleteChip)
        }
    }

    /**
     * 把「搜索框 → 提交 → 清空全部 → 各行（行 / 删除）→ 回到搜索框」串成一条焦点链。
     *
     * 行是运行时建的，默认焦点搜索会直接跳出卡片，所以每次重建后重连一遍。
     */
    private fun wireFocus() {
        val chain = ArrayList<View>(focusChain.size + 3)
        chain.add(binding.etKeyword)
        chain.add(binding.btnSubmit)
        if (binding.btnClearAll.isEnabled) chain.add(binding.btnClearAll)
        chain.addAll(focusChain)

        chain.forEachIndexed { index, view ->
            view.nextFocusUpId = chain[(index - 1 + chain.size) % chain.size].id
            view.nextFocusDownId = chain[(index + 1) % chain.size].id
        }
    }

    // endregion

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
