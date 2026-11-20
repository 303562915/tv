package com.tv.ball.feature.f029

import android.content.DialogInterface
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF029Binding
import com.tv.ball.util.CustomToast

/**
 * F029「收藏标签与分组」页。
 *
 * 页面分两块：上面管标签（改名 / 删除），下面看收藏（按标签筛选、逐条打标签）。
 * 标签逻辑一行都不在这里复刻 —— 全部走 [F029Engine] 的纯函数，
 * 这里只负责：拿当前 [Favorite] 列表 → 交给 Engine 算新列表 → 落盘 → 重绘。
 *
 * 六个示例收藏固定写在 [SAMPLE_FAVORITES]，覆盖「电影」「4K」这种一条挂多标签、
 * 多个条目共用一个标签的情况，标签计数与筛选一眼能核对上。
 */
class F029Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF029Binding

    /** 当前收藏集：打开页面时由示例集 ＋ 存档标签拼出，之后每次变更整体替换。 */
    private val favorites = ArrayList<Favorite>()

    /** 当前筛选的标签；null 表示「全部」。 */
    private var activeTag: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF029Binding.inflate(layoutInflater)
        setContentView(binding.root)

        favorites.addAll(F029Engine.load(this, SAMPLE_FAVORITES))
        // 重建时沿用上次的筛选；标签要是已经被删了，render() 里会把它清回「全部」
        activeTag = savedInstanceState?.getString(STATE_KEY_TAG)

        render()
        // 电视没有触摸屏：进页面焦点压在「全部」筛选块上，方向键立刻能操作
        binding.filterChips.getChildAt(0)?.requestFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_KEY_TAG, activeTag)
    }

    // region 渲染

    private fun render() {
        val tags = F029Engine.allTags(favorites)
        if (activeTag != null && tags.none { it.name == activeTag }) activeTag = null
        renderTags(tags)
        renderFilter(tags)
        renderFavorites()
    }

    /** 标签管理区：一条标签一行，右边「重命名」「删除」。 */
    private fun renderTags(tags: List<TagCount>) {
        binding.tagCount.text = getString(R.string.f029_tag_count, tags.size)
        binding.tagRows.removeAllViews()

        val empty = tags.isEmpty()
        binding.tagRows.visibility = if (empty) View.GONE else View.VISIBLE
        binding.tagEmptyHint.visibility = if (empty) View.VISIBLE else View.GONE

        tags.forEachIndexed { index, entry ->
            binding.tagRows.addView(buildTagRow(entry, index == 0))
        }
    }

    private fun buildTagRow(entry: TagCount, first: Boolean): View {
        val pad = dp(12)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_mode_row)
            setPadding(pad, dp(8), pad, dp(8))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = if (first) 0 else dp(6)
            }
        }

        row.addView(TextView(this).apply {
            text = getString(R.string.f029_tag_row, entry.name, entry.count)
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(this@F029Activity, R.color.theme_text_primary))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })

        val rename = actionButton(getString(R.string.f029_action_rename)) { askRenameTag(entry.name) }
        rename.tag = focusToken(TOKEN_TAG_RENAME, entry.name)
        row.addView(rename)

        val delete = actionButton(getString(R.string.f029_action_delete)) { askDeleteTag(entry.name) }
        delete.tag = focusToken(TOKEN_TAG_DELETE, entry.name)
        row.addView(delete)

        return row
    }

    /** 筛选条：「全部」＋ 每个标签（带条数），选中态用主题色。 */
    private fun renderFilter(tags: List<TagCount>) {
        binding.filterChips.removeAllViews()
        binding.filterChips.addView(buildFilterChip(getString(R.string.f029_filter_all), null, false))
        tags.forEach { entry ->
            binding.filterChips.addView(
                buildFilterChip(getString(R.string.f029_filter_chip, entry.name, entry.count), entry.name, true)
            )
        }
    }

    private fun buildFilterChip(label: String, target: String?, needsMargin: Boolean): Button {
        val chip = Button(this).apply {
            text = label
            isAllCaps = false
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                if (needsMargin) marginStart = resources.getDimensionPixelSize(R.dimen.card_gap)
            }
            setOnClickListener { selectFilter(target) }
        }
        chip.tag = focusToken(TOKEN_FILTER, target ?: "")
        applyChipStyle(chip, activeTag == target)
        return chip
    }

    /** 收藏区：先给一行统计，再按当前筛选渲染条目；没命中就显示空态。 */
    private fun renderFavorites() {
        val shown = F029Engine.filterByTag(favorites, activeTag)
        val tag = activeTag
        binding.favSummary.text = if (tag == null) {
            getString(R.string.f029_filter_status_all, shown.size)
        } else {
            getString(R.string.f029_filter_status_tag, tag, shown.size)
        }

        binding.favRows.removeAllViews()
        val empty = shown.isEmpty()
        binding.favRows.visibility = if (empty) View.GONE else View.VISIBLE
        binding.favEmptyHint.visibility = if (empty) View.VISIBLE else View.GONE

        shown.forEachIndexed { index, item ->
            binding.favRows.addView(buildFavoriteRow(item, index == 0))
        }
    }

    private fun buildFavoriteRow(item: Favorite, first: Boolean): View {
        val pad = dp(12)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_mode_card)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = if (first) 0 else dp(8)
            }
        }

        row.addView(TextView(this).apply {
            text = item.title
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(this@F029Activity, R.color.theme_text_primary))
        })

        // 标签块横向排，挂得多时这一行自己可以滚；每个块点一下 = 从这条收藏上摘掉
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        }
        if (item.tags.isEmpty()) {
            chips.addView(hintView(getString(R.string.f029_item_no_tag)))
        } else {
            item.tags.sorted().forEachIndexed { index, name ->
                chips.addView(buildItemChip(item, name, index > 0))
            }
        }
        row.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
            addView(chips)
        })

        val addTag = actionButton(getString(R.string.f029_action_add_tag)) { askAddTag(item) }
        addTag.tag = focusToken(TOKEN_ADD, item.id)
        addTag.layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = dp(8)
        }
        row.addView(addTag)

        return row
    }

    /** 条目标签块：胶囊底 ＋ 主题焦点描边，遥控器聚焦时看得见。 */
    private fun buildItemChip(item: Favorite, name: String, needsMargin: Boolean): TextView {
        val chip = TextView(this).apply {
            text = getString(R.string.f029_chip_tag, name)
            textSize = 12f
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setTextColor(ContextCompat.getColor(this@F029Activity, R.color.theme_text_primary))
            setBackgroundResource(R.drawable.bg_tag_inset)
            foreground = ContextCompat.getDrawable(this@F029Activity, R.drawable.bg_focus_ring)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                if (needsMargin) marginStart = resources.getDimensionPixelSize(R.dimen.card_gap)
            }
            setOnClickListener { removeTagFrom(item, name) }
        }
        chip.tag = focusToken(TOKEN_CHIP, item.id, name)
        return chip
    }

    private fun applyChipStyle(chip: Button, selected: Boolean) {
        // 用 tint 而不是换背景：保留按钮自带的焦点态，遥控器聚焦才有反馈
        ViewCompat.setBackgroundTintList(
            chip,
            ColorStateList.valueOf(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.theme_primary else R.color.surface_inset
                )
            )
        )
        chip.setTextColor(
            ContextCompat.getColor(
                this,
                if (selected) R.color.theme_on_primary else R.color.theme_text_primary
            )
        )
    }

    // endregion

    // region 交互

    private fun selectFilter(target: String?) {
        activeTag = target
        render()
        restoreFocus(focusToken(TOKEN_FILTER, target ?: ""))
    }

    /** 点条目标签块 = 摘掉这个标签，收藏本身留在原处。 */
    private fun removeTagFrom(item: Favorite, name: String) {
        commit(F029Engine.removeTag(favorites, item.id, name))
        toast(getString(R.string.f029_toast_tag_removed, name))
        render()
        restoreFocus(focusToken(TOKEN_ADD, item.id))
    }

    private fun askAddTag(item: Favorite) {
        val input = newTagInput()
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.f029_dialog_add_title, item.title))
            .setView(wrapForDialog(input))
            .setPositiveButton(R.string.f029_dialog_ok, null)
            .setNegativeButton(R.string.f029_dialog_cancel, null)
            .create()

        // 自己接管确定键：标签不合法时留在弹窗里，不要一闪即关
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val tag = F029Engine.normalizeTag(input.text.toString())
                if (tag == null) {
                    toast(getString(R.string.f029_toast_tag_empty))
                    return@setOnClickListener
                }
                if (tag in item.tags) {
                    toast(getString(R.string.f029_toast_tag_exists, tag))
                    return@setOnClickListener
                }
                dialog.dismiss()
                commit(F029Engine.addTag(favorites, item.id, tag))
                toast(getString(R.string.f029_toast_tag_added, tag))
                render()
                restoreFocus(focusToken(TOKEN_CHIP, item.id, tag), focusToken(TOKEN_ADD, item.id))
            }
        }
        dialog.show()
        input.requestFocus()
    }

    private fun askRenameTag(from: String) {
        val input = newTagInput()
        input.setText(from)
        input.setSelection(input.text.length)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.f029_dialog_rename_title, from))
            .setView(wrapForDialog(input))
            .setPositiveButton(R.string.f029_dialog_ok, null)
            .setNegativeButton(R.string.f029_dialog_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val to = F029Engine.normalizeTag(input.text.toString())
                if (to == null) {
                    toast(getString(R.string.f029_toast_tag_empty))
                    return@setOnClickListener
                }
                dialog.dismiss()
                if (to == from) return@setOnClickListener

                // 新名字已经存在时 Engine 会做合并，这里只决定提示哪一句
                val merged = favorites.any { to in it.tags }
                commit(F029Engine.renameTag(favorites, from, to))
                if (activeTag == from) activeTag = to
                toast(
                    if (merged) getString(R.string.f029_toast_tag_merged, from, to)
                    else getString(R.string.f029_toast_tag_renamed, from, to)
                )
                render()
                restoreFocus(focusToken(TOKEN_TAG_RENAME, to), focusToken(TOKEN_FILTER, to))
            }
        }
        dialog.show()
        input.requestFocus()
    }

    private fun askDeleteTag(name: String) {
        val affected = F029Engine.countOf(favorites, name)
        AlertDialog.Builder(this)
            .setTitle(R.string.f029_confirm_delete_title)
            .setMessage(getString(R.string.f029_confirm_delete_message, name, affected))
            .setPositiveButton(R.string.f029_confirm_delete_ok) { _, _ -> deleteTag(name, affected) }
            .setNegativeButton(R.string.f029_confirm_cancel, null)
            .show()
    }

    private fun deleteTag(name: String, affected: Int) {
        commit(F029Engine.deleteTag(favorites, name))
        if (activeTag == name) activeTag = null
        toast(getString(R.string.f029_toast_tag_deleted, name, affected))
        render()
        // 原来那行的「重命名」已经没了，退到「全部」筛选块上
        restoreFocus(focusToken(TOKEN_FILTER, ""))
    }

    /** 统一的落地方式：换列表 → 写盘 → 重绘（重绘由调用方决定何时做）。 */
    private fun commit(updated: List<Favorite>) {
        favorites.clear()
        favorites.addAll(updated)
        F029Engine.save(this, favorites)
    }

    // endregion

    // region 控件工厂

    private fun actionButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { onClick() }
        }

    private fun hintView(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        setTextColor(ContextCompat.getColor(this@F029Activity, R.color.theme_text_secondary))
        setPadding(dp(10), dp(5), dp(10), dp(5))
    }

    private fun newTagInput(): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT
        setSingleLine()
        hint = getString(R.string.f029_dialog_tag_hint, F029Engine.MAX_TAG_LENGTH)
        setTextColor(ContextCompat.getColor(this@F029Activity, R.color.theme_text_primary))
        setHintTextColor(ContextCompat.getColor(this@F029Activity, R.color.theme_text_secondary))
    }

    /** 弹窗里给 EditText 留点左右边距，直接 setView 会贴到边框上。 */
    private fun wrapForDialog(input: EditText): View {
        val wrapper = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        wrapper.addView(input, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return wrapper
    }

    // endregion

    // region 焦点与工具

    /**
     * 重绘会换掉所有动态控件，焦点随之丢失。
     * 用 tag 记身份，重绘后按 token 顺序找第一个还在的控件把焦点还回去。
     */
    private fun restoreFocus(vararg tokens: String) {
        tokens.forEach { token ->
            val target = binding.root.findViewWithTag<View>(token)
            if (target != null && target.isFocusable) {
                target.requestFocus()
                return
            }
        }
    }

    private fun focusToken(prefix: String, vararg parts: String): String =
        (listOf(prefix) + parts).joinToString("|")

    private fun toast(message: String) {
        CustomToast.show(this, message)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // endregion

    companion object {
        private const val STATE_KEY_TAG = "f029_active_tag"

        private const val TOKEN_FILTER = "f029|filter"
        private const val TOKEN_TAG_RENAME = "f029|tag|rename"
        private const val TOKEN_TAG_DELETE = "f029|tag|delete"
        private const val TOKEN_ADD = "f029|add"
        private const val TOKEN_CHIP = "f029|chip"

        /** 示例收藏：6 条，含「一条挂两个标签」与「两条共用一个标签」，便于核对计数。 */
        private val SAMPLE_FAVORITES = listOf(
            Favorite("fav_night_city", "城市夜景 4K 航拍", setOf("纪录片", "4K")),
            Favorite("fav_interstellar", "星际穿越", setOf("科幻", "电影")),
            Favorite("fav_breakfast", "十分钟快手早餐", setOf("美食")),
            Favorite("fav_octonauts", "海底小纵队 第三季", setOf("动画", "儿童")),
            Favorite("fav_old_films", "老电影修复合集", setOf("电影", "收藏夹")),
            Favorite("fav_summer_live", "夏日音乐节现场", setOf("音乐", "4K"))
        )
    }
}
