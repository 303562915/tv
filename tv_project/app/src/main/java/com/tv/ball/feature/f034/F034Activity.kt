package com.tv.ball.feature.f034

import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF034Binding
import com.tv.ball.util.CustomToast

/**
 * F034「队列另存为播放列表」页面。
 *
 * 页面上三块：当前队列（只读）、另存为（输入名称 + 保存）、已保存的列表。
 * 「存不存得进去」全在 [F034Engine] 里判定，这里只做三件事：
 * 把模拟队列铺出来、把保存结论翻译成弹窗或提示、把列表渲染出来。
 *
 * 重名是这条流程里唯一的分叉：弹窗给「覆盖」和「另存为 xxx-2」两条路，
 * 用户选定后才 [F034Engine.commit]，点取消就什么都不会写进去。
 */
class F034Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF034Binding

    /** 临时队列。真实场景是用户在各处凑出来的一串，这里固定 5 项模拟。 */
    private val queue = ArrayList<String>()

    /**
     * 渲染用的时间基准。
     *
     * 「x 分钟前保存」全按这一刻算：如果每次渲染都重新取 System.currentTimeMillis()，
     * 同一屏里两条列表的时间基准会差几毫秒，来回重建时看着会跳。
     */
    private var clockMs = 0L

    /** 列表行，重建后用来串 D-Pad 的上下焦点。 */
    private val listRows = ArrayList<LinearLayout>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF034Binding.inflate(layoutInflater)
        setContentView(binding.root)

        clockMs = System.currentTimeMillis()
        queue.addAll(demoQueue())
        renderQueue()
        renderLists(focusOn = null)

        binding.btnSave.setOnClickListener { saveAs() }
        // 遥控器输入完直接按「完成」也能存，省一次下移。
        binding.nameInput.setOnEditorActionListener { _, _, _ ->
            saveAs()
            true
        }
    }

    // region 示例数据

    /** 5 条模拟队列，正好凑出一屏，能看出「顺序就是播放顺序」。 */
    private fun demoQueue(): List<String> = listOf(
        "三体 第一季 01",
        "流浪地球 2",
        "漫长的季节 03",
        "狂飙 第 12 集",
        "变形金刚 5"
    )

    // endregion

    // region 保存

    private fun saveAs() {
        val typed = binding.nameInput.text?.toString().orEmpty()
        when (val result = F034Engine.saveAs(typed, queue, System.currentTimeMillis())) {
            is F034Engine.Result.Saved -> commit(result.playlist, overwritten = false)
            is F034Engine.Result.NameConflict -> askConflict(result)
            F034Engine.Result.EmptyName -> CustomToast.show(this, getString(R.string.f034_toast_blank_name))
        }
    }

    /** 重名弹窗：覆盖 / 自动加后缀 / 取消。两条出路都由引擎算好，这里只挑一条 commit。 */
    private fun askConflict(conflict: F034Engine.Result.NameConflict) {
        // 后缀先算好：列表里已经有「xxx-2」时，弹窗上的建议名要跟着变成「xxx-3」。
        val renamed = conflict.renameWith(F034Engine.freeSuffix(conflict.requestedName))
        AlertDialog.Builder(this)
            .setTitle(R.string.f034_conflict_title)
            .setMessage(
                getString(
                    R.string.f034_conflict_message,
                    conflict.existing.name,
                    conflict.existing.size,
                    savedAgo(conflict.existing.createdAtMs),
                    renamed.name
                )
            )
            .setPositiveButton(R.string.f034_conflict_overwrite) { _, _ ->
                commit(conflict.overwrite(), overwritten = true)
            }
            .setNegativeButton(getString(R.string.f034_conflict_rename, renamed.name)) { _, _ ->
                commit(renamed, overwritten = false)
            }
            .setNeutralButton(R.string.f034_conflict_cancel, null)
            .show()
    }

    /**
     * 落库 + 重绘 + 提示。
     *
     * 名称输入框刻意不清空：同一个名字再点一次保存就会走重名弹窗，
     * 这样覆盖 / 改名两种解法都能当场试出来。
     */
    private fun commit(playlist: F034Engine.Playlist, overwritten: Boolean) {
        F034Engine.commit(playlist)
        renderLists(focusOn = playlist.name)
        val message = if (overwritten) {
            getString(R.string.f034_toast_overwritten, playlist.name, playlist.size)
        } else {
            getString(R.string.f034_toast_saved, playlist.name, playlist.size)
        }
        CustomToast.show(this, message)
    }

    // endregion

    // region 渲染

    /** 队列区：只读展示，行不做焦点，遥控器上下移动时不用在中间停一脚。 */
    private fun renderQueue() {
        val container = binding.queueContainer
        container.removeAllViews()

        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        val pad = dp(12)

        queue.forEachIndexed { index, title ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (index == 0) 0 else gap }
                setPadding(pad, dp(8), pad, dp(8))
                background = ContextCompat.getDrawable(this@F034Activity, R.drawable.bg_mode_row)
            }

            val order = TextView(this).apply {
                text = getString(R.string.f034_queue_order, index + 1)
                setTextColor(ContextCompat.getColor(this@F034Activity, R.color.theme_text_secondary))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val name = TextView(this).apply {
                text = title
                setTextColor(ContextCompat.getColor(this@F034Activity, R.color.theme_text_primary))
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { marginStart = dp(10) }
            }

            row.addView(order)
            row.addView(name)
            container.addView(row)
        }

        binding.queueCount.text = getString(R.string.f034_queue_count, queue.size)
    }

    /** 列表区：按名称顺序铺行；[focusOn] 非空时把焦点落到那条上（刚存完能看见自己存的东西）。 */
    private fun renderLists(focusOn: String?) {
        val lists = F034Engine.all()
        val container = binding.listContainer
        val hadFocus = isInsideList(currentFocus)

        container.removeAllViews()
        listRows.clear()

        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        val rowMinHeight = resources.getDimensionPixelSize(R.dimen.list_row_min_height)
        val pad = dp(12)

        lists.forEachIndexed { index, playlist ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (index == 0) 0 else gap }
                minimumHeight = rowMinHeight
                setPadding(pad, dp(6), pad, dp(6))
                isFocusable = true
                isFocusableInTouchMode = true
                background = ContextCompat.getDrawable(this@F034Activity, R.drawable.bg_mode_row)
                foreground = ContextCompat.getDrawable(this@F034Activity, R.drawable.bg_focus_ring)
            }

            val title = TextView(this).apply {
                text = playlist.name
                setTextColor(ContextCompat.getColor(this@F034Activity, R.color.theme_text_primary))
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val meta = TextView(this).apply {
                text = metaText(playlist)
                setTextColor(ContextCompat.getColor(this@F034Activity, R.color.theme_text_secondary))
                textSize = 13f
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            }

            // 点一条 = 把它读回队列区，用来确认存进去的确实是这一串。
            row.setOnClickListener { loadIntoQueue(playlist) }
            row.addView(title)
            row.addView(meta)
            container.addView(row)
            listRows.add(row)
        }

        linkFocus()

        binding.savedCount.text = getString(R.string.f034_saved_count, lists.size)
        binding.emptyHint.visibility = if (lists.isEmpty()) View.VISIBLE else View.GONE

        // 刚存完 / 刚覆盖的那条优先；否则保持「原来焦点在列表里」的位置，别把焦点甩回顶部。
        val wanted = if (focusOn == null) -1 else lists.indexOfFirst { it.name == focusOn }
        when {
            wanted >= 0 -> listRows[wanted].requestFocus()
            hadFocus && listRows.isNotEmpty() -> listRows.first().requestFocus()
            hadFocus -> binding.btnSave.requestFocus()
        }
    }

    /** 一行的副标题：条目数 ＋ 相对保存时间。 */
    private fun metaText(playlist: F034Engine.Playlist): String =
        getString(R.string.f034_playlist_meta, playlist.size, savedAgo(playlist.createdAtMs))

    private fun savedAgo(createdAtMs: Long): String =
        DateUtils.getRelativeTimeSpanString(createdAtMs, clockMs, DateUtils.MINUTE_IN_MILLIS).toString()

    private fun loadIntoQueue(playlist: F034Engine.Playlist) {
        queue.clear()
        queue.addAll(playlist.items)
        renderQueue()
        CustomToast.show(this, getString(R.string.f034_toast_loaded, playlist.name, playlist.size))
    }

    /**
     * 动态行之间显式串焦点：默认的焦点搜索会从最后一行跳到页外，
     * 遥控器手感很差；另外 XML 里写不出运行时生成的 id，接缝只能在这里补。
     */
    private fun linkFocus() {
        listRows.forEachIndexed { index, row ->
            row.id = View.generateViewId()
            if (index > 0) row.nextFocusUpId = listRows[index - 1].id
            if (index < listRows.lastIndex) row.nextFocusDownId = listRows[index + 1].id
        }

        val firstId = listRows.firstOrNull()?.id ?: View.NO_ID
        binding.btnSave.nextFocusDownId = firstId
        if (firstId != View.NO_ID) listRows.first().nextFocusUpId = binding.btnSave.id
    }

    /** 焦点当前是否落在列表容器内，用来判断重建后要不要把焦点接回去。 */
    private fun isInsideList(view: View?): Boolean {
        var node: View? = view
        while (node != null) {
            if (node === binding.listContainer) return true
            node = node.parent as? View
        }
        return false
    }

    // endregion

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()
}
