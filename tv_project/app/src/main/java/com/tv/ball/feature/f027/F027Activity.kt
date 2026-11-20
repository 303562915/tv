package com.tv.ball.feature.f027

import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF027Binding
import com.tv.ball.di.Dependencies
import com.tv.ball.util.CustomToast
import com.tv.ball.utils.ThemeUtils

/**
 * 观看历史页：按自然日分组 + 多选批量删除。
 *
 * 分组、选中集合、批量删全在 [F027Engine] 里，这里只管三件事：
 * 造 10 条示例历史、把分组渲染成「标题 + 行」、维护多选这一层交互。
 *
 * TV 没有触摸屏，长按靠遥控器按住中心键，所以顶部另给一个「多选」按钮兜底；
 * 删除后整页重算分组——某天删空了，「昨天」「本周」这些标题也要跟着消失。
 */
class F027Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF027Binding

    /** 当前历史；删除时整体替换，保证分组标题跟着重算。 */
    private val history = ArrayList<F027Engine.HistoryItem>()

    /** 选中集合；用 LinkedHashSet 记住点选顺序，删除提示里的条数才稳。 */
    private val selectedIds = LinkedHashSet<String>()

    private var selectionMode: Boolean = false

    /** 刷新后要还回去的焦点行 id；遥控器操作时焦点不能掉出页面。 */
    private var focusedId: String? = null

    companion object {
        /** 置灰时的透明度，与 F002/F008 保持一致。 */
        private const val DISABLED_ALPHA = 0.4f

        /** 未选中指示点的透明度：压暗一点，视觉上不跟选中态抢。 */
        private const val IDLE_MARK_ALPHA = 0.4f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 与全站一致：进页面前先把夜间模式定下来，避免闪一次浅色
        ThemeUtils.applyNightMode(Dependencies.configRepository.getNightMode())
        super.onCreate(savedInstanceState)
        binding = ActivityF027Binding.inflate(layoutInflater)
        setContentView(binding.root)

        history.addAll(F027Engine.sampleHistory(System.currentTimeMillis()))

        binding.btnSelectMode.setOnClickListener { toggleSelectionMode() }
        binding.btnSelectAll.setOnClickListener { selectAll() }
        binding.btnDelete.setOnClickListener { askDeleteSelected() }

        // 多选状态下按返回先退选择模式，再按一次才退出页面
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selectionMode) {
                    exitSelectionMode()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        render()
        binding.btnSelectMode.requestFocus()
    }

    // region 交互

    private fun toggleSelectionMode() {
        if (selectionMode) {
            exitSelectionMode()
            return
        }
        selectionMode = true
        selectedIds.clear()
        render()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedIds.clear()
        render()
        CustomToast.show(this, getString(R.string.f027_toast_cleared))
    }

    /** 点条目 = 选中/取消；第一次点顺手进多选，省一次「先点按钮」。 */
    private fun toggleItem(item: F027Engine.HistoryItem) {
        selectionMode = true
        val next = F027Engine.toggleSelect(selectedIds.toSet(), item.id)
        selectedIds.clear()
        selectedIds.addAll(next)
        render()
    }

    private fun selectAll() {
        if (!selectionMode || history.isEmpty()) return
        selectedIds.clear()
        history.forEach { selectedIds.add(it.id) }
        render()
        CustomToast.show(this, getString(R.string.f027_toast_all, selectedIds.size))
    }

    private fun askDeleteSelected() {
        if (selectedIds.isEmpty()) {
            CustomToast.show(this, getString(R.string.f027_toast_nothing_selected))
            return
        }
        val count = selectedIds.size
        AlertDialog.Builder(this)
            .setTitle(R.string.f027_confirm_title)
            .setMessage(getString(R.string.f027_confirm_message, count))
            .setPositiveButton(R.string.f027_confirm_delete) { _, _ -> deleteSelected(count) }
            .setNegativeButton(R.string.f027_confirm_cancel, null)
            .show()
    }

    private fun deleteSelected(count: Int) {
        val remaining = F027Engine.removeSelected(history, selectedIds)
        history.clear()
        history.addAll(remaining)
        selectedIds.clear()
        selectionMode = false
        focusedId = null
        render()
        CustomToast.show(this, getString(R.string.f027_toast_deleted, count))
    }

    // endregion

    // region 渲染

    private fun render() {
        // 先记住旧焦点：重建后按钮还在，行已经被换掉了
        val previousFocus = currentFocus

        binding.historyContainer.removeAllViews()
        val groups = F027Engine.groupByDay(history, System.currentTimeMillis(), ::groupLabel)

        var firstGroup = true
        groups.forEach { group ->
            binding.historyContainer.addView(buildGroupHeader(group, firstGroup))
            firstGroup = false
            group.items.forEach { item ->
                binding.historyContainer.addView(buildRow(item))
            }
        }
        chainFocus()

        val hasItems = history.isNotEmpty()
        binding.emptyHint.visibility = if (hasItems) View.GONE else View.VISIBLE
        binding.historyContainer.visibility = if (hasItems) View.VISIBLE else View.GONE

        binding.tvSummary.text = when {
            !hasItems -> getString(R.string.f027_summary_empty)
            selectionMode -> getString(R.string.f027_summary_selected, selectedIds.size)
            else -> getString(R.string.f027_summary_count, history.size)
        }

        binding.btnSelectMode.text = getString(
            if (selectionMode) R.string.f027_action_exit else R.string.f027_action_select
        )
        applyEnabled(binding.btnSelectAll, selectionMode && hasItems)
        applyEnabled(binding.btnDelete, selectionMode && hasItems)

        restoreFocus(previousFocus)
    }

    private fun buildGroupHeader(group: F027Engine.DayGroup, first: Boolean): View =
        TextView(this).apply {
            text = getString(R.string.f027_group_header, group.label, group.size)
            applySectionStyle(this)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = if (first) 0 else dp(16)
                bottomMargin = dp(6)
            }
            setPadding(dp(4), 0, dp(4), 0)
        }

    private fun buildRow(item: F027Engine.HistoryItem): View {
        val selected = item.id in selectedIds
        val pad = dp(12)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = resources.getDimensionPixelSize(R.dimen.list_row_min_height)
            setPadding(pad, dp(10), pad, dp(10))
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = ContextCompat.getDrawable(
                this@F027Activity,
                if (selected) R.drawable.bg_mode_card_selected else R.drawable.bg_mode_card
            )
            foreground = ContextCompat.getDrawable(this@F027Activity, R.drawable.bg_focus_ring)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            // 用 tag 记条目 id：刷新后靠它把焦点还回原来那一行
            tag = item.id
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) focusedId = item.id }
            setOnClickListener { binding.root.post { toggleItem(item) } }
            // 遥控器按住中心键就是长按；返回 true 后系统不会再补发一次 click
            setOnLongClickListener {
                binding.root.post { toggleItem(item) }
                true
            }
        }

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        info.addView(TextView(this).apply {
            text = item.title
            applyBodyStyle(this)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })

        info.addView(TextView(this).apply {
            text = getString(
                R.string.f027_item_meta,
                F027Engine.formatProgress(item.progressMs),
                F027Engine.formatClock(item.playedAtMs)
            )
            applyCaptionStyle(this)
            maxLines = 1
        })

        val mark = ImageView(this).apply {
            setImageResource(if (selected) R.drawable.ic_check_circle else R.drawable.ic_circle_gray)
            alpha = if (selected) 1f else IDLE_MARK_ALPHA
            contentDescription = getString(
                if (selected) R.string.f027_item_selected else R.string.f027_item_unselected
            )
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                marginStart = pad
            }
        }

        row.addView(info)
        row.addView(mark)
        return row
    }

    /**
     * 动态行之间显式串 nextFocus。
     *
     * 默认的焦点搜索会顺着坐标去找，跨过分组标题时容易跳到页面外的按钮上，
     * 这里按渲染顺序把上下邻居钉死，遥控器一路按下去就是列表原来的顺序。
     */
    private fun chainFocus() {
        val rows = (0 until binding.historyContainer.childCount)
            .map { binding.historyContainer.getChildAt(it) }
            .filter { it.tag is String }
        rows.forEachIndexed { index, row ->
            if (row.id == View.NO_ID) row.id = View.generateViewId()
            if (index > 0) row.nextFocusUpId = rows[index - 1].id
            if (index < rows.lastIndex) row.nextFocusDownId = rows[index + 1].id
        }
        // 首行往上回到操作条、操作条往下回到首行，遥控器不会从列表头直接跳飞
        rows.firstOrNull()?.let { first ->
            binding.btnSelectMode.nextFocusDownId = first.id
            first.nextFocusUpId = R.id.btnSelectMode
        }
    }

    /**
     * 刷新后还原焦点：旧焦点还在（比如按的是按钮）就还给它，
     * 否则回到上次聚焦的行，行也被删了才落到顶部按钮。
     */
    private fun restoreFocus(previous: View?) {
        if (previous != null && previous.parent != null && previous.isFocusable && previous.isEnabled) {
            previous.requestFocus()
            return
        }
        val row = focusedId?.let { findRow(it) }
        if (row != null) {
            row.requestFocus()
        } else {
            binding.btnSelectMode.requestFocus()
        }
    }

    private fun findRow(id: String): View? {
        for (index in 0 until binding.historyContainer.childCount) {
            val child = binding.historyContainer.getChildAt(index)
            if (child.tag == id) return child
        }
        return null
    }

    private fun applyEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else DISABLED_ALPHA
        // 置灰后不能成为焦点落点，否则遥控器会停在一个按不动的按钮上
        view.isFocusable = enabled
    }

    private fun groupLabel(bucket: F027Engine.DayBucket): String = getString(
        when (bucket) {
            F027Engine.DayBucket.TODAY -> R.string.f027_group_today
            F027Engine.DayBucket.YESTERDAY -> R.string.f027_group_yesterday
            F027Engine.DayBucket.THIS_WEEK -> R.string.f027_group_this_week
            F027Engine.DayBucket.EARLIER -> R.string.f027_group_earlier
        }
    )

    /**
     * 动态行没法在 XML 里挂 `textAppearance`，这里按 themes.xml 里
     * `TextAppearance.TVPlayer.SectionTitle / Body / Caption` 同一套值补上，
     * 颜色仍走语义 token，不出现十六进制。
     */
    private fun applySectionStyle(view: TextView) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        view.setTextColor(color(R.color.theme_text_secondary))
        view.letterSpacing = 0.08f
        view.isAllCaps = true
    }

    private fun applyBodyStyle(view: TextView) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        view.setTextColor(color(R.color.theme_text_primary))
    }

    private fun applyCaptionStyle(view: TextView) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        view.setTextColor(color(R.color.theme_text_secondary))
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    // endregion
}
