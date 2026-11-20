package com.tv.ball.feature.f089

import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF089Binding
import com.tv.ball.util.CustomToast

/**
 * 「长按上下文菜单」页。
 *
 * 一条列表 + 一块详情区。列表里每一项都能：
 * - **点一下**：直接播放（最常做的动作不给菜单挡路）；
 * - **长按**：弹出四选一菜单（播放 / 收藏 / 信息 / 删除）。
 *
 * 遥控器没有"长按"这个概念的盒子怎么用：按确认键按住不放，Android 的 View 会对
 * DPAD_CENTER / ENTER 的重复按键走 `performLongClick`，所以按住确认键 ≥ 500ms 就能出菜单；
 * 部分盒子的遥控器会把长按拆成"按键连发但不带 repeatCount"，所以这里再加两条兜底：
 * 聚焦某行时按「菜单键」，或收到 repeatCount ≥ 1 的确认键，都直接开菜单。
 *
 * 为什么菜单是 `BottomSheetDialog` 里**自建**的视图而不是布局文件：
 * 这份菜单的条目数随条目状态变（已删除的只剩两项），内容也要跟着 item 现算
 * （"收藏"还是"取消收藏"）。写成 XML 得在代码里再藏一半，反而更难读。
 * 卡片底用 [MaterialCardView] 给 16dp 圆角 + `surface_card` 填充，和全站卡片同一套语义色。
 *
 * 列表行同样是代码拼的：条目数据来自 [F089Engine.sampleItems]，行数不是固定的五条，
 * 焦点链也用动态 id 串（见 [buildList]），保证遥控器上下推不会跳出列表。
 */
class F089Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF089Binding

    /** 当前列表快照；每次操作后整条替换，行视图再按新值刷新。 */
    private val items = mutableListOf<MediaItem>()

    /** 条目 id → 行视图，刷新时按 id 找，不依赖下标（列表顺序以后可能变）。 */
    private val holders = LinkedHashMap<String, RowHolder>()

    /** 当前打开的菜单；非 null 时不再重复开第二张。 */
    private var sheet: BottomSheetDialog? = null

    private class RowHolder(
        val row: LinearLayout,
        val title: TextView,
        val subtitle: TextView,
        val status: TextView
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF089Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        items.addAll(F089Engine.sampleItems())
        buildList()
        renderSummary()

        binding.f089Info.text = getString(R.string.f089_info_empty)
        holders.values.firstOrNull()?.row?.requestFocus()
    }

    // ===== 列表 =====

    /**
     * 按 [items] 重画列表，并把焦点链接好。
     *
     * 只在进入页面时调用一次：之后的状态变化走 [refresh] 原地改文字和颜色，
     * 不重建视图——重建会把焦点丢掉，遥控器用户每次收藏完都得重新找位置。
     */
    private fun buildList() {
        binding.f089List.removeAllViews()
        holders.clear()

        items.forEachIndexed { index, item ->
            val holder = createRow(index, item)
            holders[item.id] = holder

            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (index > 0) params.topMargin = dp(6)
            binding.f089List.addView(holder.row, params)
        }

        val rows = items.mapNotNull { holders[it.id]?.row }
        rows.forEach { it.id = View.generateViewId() }
        rows.forEachIndexed { index, row ->
            row.nextFocusUpId = if (index == 0) binding.btnBack.id else rows[index - 1].id
            row.nextFocusDownId =
                if (index == rows.lastIndex) binding.btnBack.id else rows[index + 1].id
        }
        binding.btnBack.nextFocusDownId = rows.firstOrNull()?.id ?: View.NO_ID
    }

    /**
     * 拼一行。
     *
     * 一行四段：序号（等宽，方便一眼数到第几条）+ 标题/副标题（占满剩余宽度）+ 时长 + 状态标记。
     * 状态标记是这一行的"当前值"：收藏显示星标、删除显示已删除并把整行压暗，
     * 用户不用打开菜单就知道这一条现在是什么状态。
     */
    private fun createRow(index: Int, item: MediaItem): RowHolder {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = resources.getDimensionPixelSize(R.dimen.list_row_min_height)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = ContextCompat.getDrawable(this@F089Activity, R.drawable.bg_card_surface)
        }

        val ordinal = textView(12f, R.color.theme_text_secondary, monospace = true).apply {
            text = (index + 1).toString().padStart(2, '0')
        }
        row.addView(ordinal, LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT))

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = textView(16f, R.color.theme_text_primary)
        val subtitle = textView(12f, R.color.theme_text_secondary).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        column.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(
            subtitle,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            }
        )
        row.addView(
            column,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
                marginEnd = dp(10)
            }
        )

        val duration = textView(12f, R.color.theme_text_secondary, monospace = true).apply {
            text = item.durationText
        }
        row.addView(duration, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val status = textView(12f, R.color.theme_outline).apply {
            gravity = Gravity.END
        }
        row.addView(
            status,
            LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(10)
            }
        )

        val holder = RowHolder(row, title, subtitle, status)
        row.setOnClickListener { quickPlay(item.id) }
        row.setOnLongClickListener {
            openActionSheet(item.id)
            true
        }
        // 兜底：菜单键 / 确认键连发（部分盒子的长按走的是这条路），见类注释
        row.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                false
            } else {
                val confirmRepeat = (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER) && event.repeatCount >= 1
                if (keyCode == KeyEvent.KEYCODE_MENU || confirmRepeat) {
                    openActionSheet(item.id)
                    true
                } else {
                    false
                }
            }
        }

        applyState(holder, item)
        return holder
    }

    /**
     * 把条目的状态画到行上。
     *
     * 删除态整行压暗（标题、副标题、状态文案都换 `theme_disabled`，并加半透明），
     * 而不是把它从列表里抽掉：抽掉之后用户没法再对它长按"撤销删除"，
     * 这在电视上等于"删了就找不回来"。
     */
    private fun applyState(holder: RowHolder, item: MediaItem) {
        holder.title.text = item.title
        holder.subtitle.text = item.subtitle

        holder.title.setTextColor(
            color(if (item.deleted) R.color.theme_disabled else R.color.theme_text_primary)
        )
        holder.subtitle.setTextColor(
            color(if (item.deleted) R.color.theme_disabled else R.color.theme_text_secondary)
        )

        holder.status.text = when {
            item.deleted -> getString(R.string.f089_state_deleted)
            item.favorite -> getString(R.string.f089_state_favorite)
            else -> getString(R.string.f089_state_none)
        }
        holder.status.setTextColor(
            color(
                when {
                    item.deleted -> R.color.theme_disabled
                    item.favorite -> R.color.theme_primary
                    else -> R.color.theme_outline
                }
            )
        )
        holder.row.alpha = if (item.deleted) 0.5f else 1f
    }

    /** 单个条目变了就地刷新，不动其它行、不打断焦点。 */
    private fun refresh(item: MediaItem) {
        holders[item.id]?.let { applyState(it, item) }
    }

    private fun renderSummary() {
        binding.f089Summary.text = getString(
            R.string.f089_summary_counts,
            items.size,
            items.count { it.favorite },
            items.count { it.deleted }
        )
    }

    // ===== 操作 =====

    /** 点一下 = 直接播放。已标记删除的条目在这里拦下，跟菜单里不给播放项是同一条规则。 */
    private fun quickPlay(itemId: String) {
        val current = items.firstOrNull { it.id == itemId } ?: return
        if (current.deleted) {
            CustomToast.show(this, getString(R.string.f089_toast_play_blocked, current.title))
            return
        }
        execute(ContextAction.PLAY, itemId)
    }

    /**
     * 执行一个菜单项。
     *
     * 按 id 重新取一次条目再算：菜单是从开菜单那一刻的快照拼出来的，
     * 期间列表可能已经被别的入口改过（比如删除后立刻点按），以当前值为准才不会覆盖掉新状态。
     */
    private fun execute(action: ContextAction, itemId: String) {
        val current = items.firstOrNull { it.id == itemId } ?: return
        val change = F089Engine.apply(action, current)

        if (change.changed) {
            val index = items.indexOfFirst { it.id == change.item.id }
            if (index >= 0) items[index] = change.item
            refresh(change.item)
            renderSummary()
        }
        if (action == ContextAction.INFO) {
            binding.f089Info.text = change.detail
        }

        CustomToast.show(this, toastFor(change))
    }

    /** 引擎只给效果，文案在这里拼——资源归界面层。 */
    private fun toastFor(change: ItemChange): String = when (change.effect) {
        ChangeEffect.PLAY_REQUESTED -> getString(R.string.f089_toast_play, change.item.title)
        ChangeEffect.FAVORITE_ADDED -> getString(R.string.f089_toast_favorite_added, change.item.title)
        ChangeEffect.FAVORITE_REMOVED -> getString(R.string.f089_toast_favorite_removed, change.item.title)
        ChangeEffect.INFO_READ -> getString(R.string.f089_toast_info, change.item.title)
        ChangeEffect.DELETE_MARKED -> getString(R.string.f089_toast_delete, change.item.title)
        ChangeEffect.DELETE_UNDONE -> getString(R.string.f089_toast_restore, change.item.title)
    }

    // ===== 上下文菜单 =====

    /**
     * 长按某条 → 弹菜单。
     *
     * 已经开着菜单就直接返回：长按本身会同时触发"长按回调"和"确认键连发兜底"两条路，
     * 不挡一下会叠出两层弹窗。
     */
    private fun openActionSheet(itemId: String) {
        if (sheet != null) return
        val item = items.firstOrNull { it.id == itemId } ?: return

        val dialog = BottomSheetDialog(this)

        val card = MaterialCardView(this).apply {
            radius = resources.getDimension(R.dimen.radius_md)
            setCardBackgroundColor(color(R.color.surface_card))
            cardElevation = 0f
            strokeWidth = 0
            isFocusable = true
            isFocusableInTouchMode = true
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
        }

        val head = textView(18f, R.color.theme_text_primary).apply { text = item.title }
        column.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val headCaption = textView(12f, R.color.theme_text_secondary).apply {
            text = getString(R.string.f089_sheet_subtitle, item.subtitle, item.durationText)
        }
        column.addView(
            headCaption,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            }
        )

        val headHint = textView(12f, R.color.theme_outline).apply {
            text = getString(R.string.f089_sheet_hint)
        }
        column.addView(
            headHint,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
                bottomMargin = dp(8)
            }
        )

        val menuRows = ArrayList<LinearLayout>()
        F089Engine.actionsFor(item).forEach { action ->
            val row = createMenuRow(action, item) {
                dialog.dismiss()
                execute(action, itemId)
            }
            menuRows.add(row)
            column.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                }
            )
        }

        // 菜单行之间用动态 id 串焦点链，首尾相接：按住方向键会在菜单里循环，
        // 不会跑到弹窗背后的列表上去（背后那些行此刻不可见，焦点跑过去等于"按键失灵"）。
        menuRows.forEach { it.id = View.generateViewId() }
        menuRows.forEachIndexed { index, row ->
            row.nextFocusUpId =
                if (index == 0) menuRows.last().id else menuRows[index - 1].id
            row.nextFocusDownId =
                if (index == menuRows.lastIndex) menuRows.first().id else menuRows[index + 1].id
        }

        card.addView(
            column,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val container = FrameLayout(this).apply {
            setPadding(dp(16), 0, dp(16), dp(16))
        }
        container.addView(
            card,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        dialog.setContentView(container)

        sheet = dialog
        dialog.setOnShowListener {
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            menuRows.firstOrNull()?.requestFocus()
        }
        dialog.setOnDismissListener {
            sheet = null
            // 焦点还给刚操作过的那一行，遥控器能接着往下推
            holders[itemId]?.row?.requestFocus()
        }
        dialog.show()
    }

    /**
     * 菜单里的一行：主标签 + 一句结果提示（"加入收藏，列表里会出现星标"这种）。
     *
     * 提示不是装饰：电视上光看"收藏"两个字，用户不知道点完会发生什么；
     * 已收藏的条目这里写"再选一次取消"，也就顺带解释了为什么菜单项只有一个而不是两个。
     */
    private fun createMenuRow(
        action: ContextAction,
        item: MediaItem,
        onPick: () -> Unit
    ): LinearLayout {
        val labelRes = when (action) {
            ContextAction.PLAY -> R.string.f089_action_play
            ContextAction.FAVORITE ->
                if (item.favorite) R.string.f089_action_favorite_on else R.string.f089_action_favorite
            ContextAction.INFO -> R.string.f089_action_info
            ContextAction.DELETE ->
                if (item.deleted) R.string.f089_action_restore else R.string.f089_action_delete
        }
        val hintRes = when (action) {
            ContextAction.PLAY -> R.string.f089_sheet_hint_play
            ContextAction.FAVORITE ->
                if (item.favorite) R.string.f089_sheet_hint_favorite_on else R.string.f089_sheet_hint_favorite_off
            ContextAction.INFO -> R.string.f089_sheet_hint_info
            ContextAction.DELETE ->
                if (item.deleted) R.string.f089_sheet_hint_restore else R.string.f089_sheet_hint_delete
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = resources.getDimensionPixelSize(R.dimen.list_row_min_height)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = ContextCompat.getDrawable(this@F089Activity, R.drawable.bg_card_surface)
        }

        val label = textView(16f, R.color.theme_text_primary).apply {
            text = getString(labelRes)
        }
        row.addView(
            label,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val hint = textView(12f, R.color.theme_text_secondary).apply {
            text = getString(hintRes)
            gravity = Gravity.END
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        row.addView(
            hint,
            LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(10)
            }
        )

        // 焦点落在哪一行，哪一行标签变主题色：遥控器上看不出 hover，靠这个指示当前项
        row.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            label.setTextColor(
                color(if (hasFocus) R.color.theme_primary else R.color.theme_text_primary)
            )
            hint.setTextColor(
                color(if (hasFocus) R.color.theme_primary else R.color.theme_text_secondary)
            )
        }
        row.setOnClickListener { onPick() }
        return row
    }

    // ===== 小工具 =====

    private fun textView(sizeSp: Float, colorRes: Int, monospace: Boolean = false): TextView =
        TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color(colorRes))
            includeFontPadding = false
            if (monospace) typeface = Typeface.MONOSPACE
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)
}