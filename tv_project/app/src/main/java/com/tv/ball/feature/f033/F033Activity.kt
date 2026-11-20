package com.tv.ball.feature.f033

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF033Binding
import java.io.StringReader
import java.io.StringWriter

/**
 * F033 队列编辑页面。
 *
 * 队列状态在 [F033Queue]（纯 Kotlin，可脱离 Android 单测），这里只做四件事：
 * 把遥控器操作翻译成 move / removeAt / insertAt、重建队列行、
 * 把顺序和在播项写回 SharedPreferences、把在播项在界面上标出来。
 *
 * 关于「拖动排序」：电视没有触摸屏，ItemTouchHelper 那套拖拽既够不着也不好用，
 * 所以顺序调整改成遥控器语义——长按 OK 锁定一行，再用上移/下移把它搬到想要的位置，
 * 再长按一次解锁。页面顶部把这条约定写清楚了。
 */
class F033Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF033Binding
    private lateinit var prefs: SharedPreferences
    private lateinit var queue: F033Queue

    /** 被长按锁定、接下来由上移/下移搬动的那一条；null 表示「跟着焦点所在行」。 */
    private var lockedId: String? = null

    /** 焦点应该落在第几行；重建列表时用它把光标接回同一行而不是弹回第一行。 */
    private var focusedRowIndex = 0

    private val rows = ArrayList<QueueRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF033Binding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        queue = loadQueue()

        binding.btnMoveUp.setOnClickListener { moveTarget(-1) }
        binding.btnMoveDown.setOnClickListener { moveTarget(1) }
        binding.btnDelete.setOnClickListener { deleteTarget() }
        binding.btnReset.setOnClickListener { resetToSamples() }

        focusedRowIndex = if (queue.currentIndex == F033Queue.NO_ITEM) 0 else queue.currentIndex
        render()
    }

    // region 持久化

    /**
     * 读回上次的队列；没存过、或者存的内容坏掉时回落到示例队列。
     *
     * 顺序和在播项存在同一个 JSON 文档里：顺序一变，在播项的下标也跟着变，
     * 拆成两个键存容易读到「新顺序 + 旧下标」的半截状态。
     */
    private fun loadQueue(): F033Queue {
        val raw = prefs.getString(KEY_QUEUE, null)
        if (!raw.isNullOrEmpty()) {
            parseQueue(raw)?.let { return it }
        }
        return sampleQueue()
    }

    private fun parseQueue(raw: String): F033Queue? = try {
        val items = ArrayList<QueueItem>()
        var currentId: String? = null
        val reader = JsonReader(StringReader(raw))
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                FIELD_ITEMS -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        reader.beginObject()
                        var id = ""
                        var title = ""
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                FIELD_ID -> id = reader.nextString()
                                FIELD_TITLE -> title = reader.nextString()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        // 没有 id 的条目无法在搬运后重新定位在播项，直接当坏数据丢掉。
                        if (id.isNotEmpty()) items.add(QueueItem(id, title))
                    }
                    reader.endArray()
                }
                FIELD_CURRENT -> {
                    currentId = if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                        null
                    } else {
                        reader.nextString()
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        reader.close()
        F033Queue(items, currentId)
    } catch (e: Exception) {
        // 上次写盘被打断、或者文件被人手改坏：当作没存过，别让页面因为一段坏 JSON 直接打不开。
        null
    }

    /** 顺序变化即时落盘：上移、下移、删除、重置改完队列后都立刻调一次。 */
    private fun saveQueue() {
        val writer = StringWriter()
        val json = JsonWriter(writer)
        json.beginObject()
        json.name(FIELD_ITEMS).beginArray()
        queue.items.forEach { item ->
            json.beginObject()
            json.name(FIELD_ID).value(item.id)
            json.name(FIELD_TITLE).value(item.title)
            json.endObject()
        }
        json.endArray()
        val current = queue.currentId
        if (current == null) {
            json.name(FIELD_CURRENT).nullValue()
        } else {
            json.name(FIELD_CURRENT).value(current)
        }
        json.endObject()
        json.close()
        prefs.edit().putString(KEY_QUEUE, writer.toString()).apply()
    }

    // endregion

    // region 编辑动作

    /** 上移 / 下移：目标是被锁定的那条，没锁定就跟随焦点所在行。 */
    private fun moveTarget(step: Int) {
        val index = resolveTargetIndex()
        if (index == F033Queue.NO_ITEM) {
            toast(getString(R.string.f033_toast_no_target))
            return
        }
        val to = index + step
        if (to < 0) {
            toast(getString(R.string.f033_toast_top))
            return
        }
        if (to > queue.size - 1) {
            toast(getString(R.string.f033_toast_bottom))
            return
        }
        if (!queue.move(index, to)) {
            toast(getString(R.string.f033_toast_no_target))
            return
        }
        // 锁定的是条目本身：搬完仍然锁着它，方便连续按上移/下移一路调到想去的位置。
        focusedRowIndex = to
        saveQueue()
        render()
    }

    private fun deleteTarget() {
        val index = resolveTargetIndex()
        if (index == F033Queue.NO_ITEM) {
            toast(getString(R.string.f033_toast_no_target))
            return
        }
        val wasCurrent = index == queue.currentIndex
        val removed = queue.removeAt(index) ?: return
        if (removed.id == lockedId) lockedId = null
        saveQueue()

        if (queue.isEmpty) {
            focusedRowIndex = 0
            render()
            toast(getString(R.string.f033_toast_queue_empty))
            return
        }

        focusedRowIndex = index.coerceAtMost(queue.size - 1)
        render()
        if (wasCurrent) {
            toast(
                getString(
                    R.string.f033_toast_deleted_current,
                    removed.title,
                    queue.currentIndex + 1
                )
            )
        } else {
            toast(getString(R.string.f033_toast_deleted, removed.title))
        }
    }

    private fun resetToSamples() {
        queue = sampleQueue()
        lockedId = null
        focusedRowIndex = 0
        saveQueue()
        render()
        toast(getString(R.string.f033_toast_reset))
    }

    /**
     * 长按行：锁定 / 解锁。
     *
     * 遥控器只有 OK 一个常用键，长按和短按天然分开，正好当「拿起 / 放下」用，
     * 于是 TV 上也能获得「先抓起再挪动」这种和拖拽等价的手感。
     */
    private fun toggleLock(index: Int) {
        val item = queue.itemAt(index) ?: return
        if (lockedId == item.id) {
            lockedId = null
            focusedRowIndex = index
            render()
            toast(getString(R.string.f033_toast_unlocked))
        } else {
            lockedId = item.id
            focusedRowIndex = index
            render()
            toast(getString(R.string.f033_toast_locked, item.title))
        }
    }

    /** 移动/删除的目标行：优先锁定行，其次焦点行，最后回落到在播项。 */
    private fun resolveTargetIndex(): Int {
        lockedId?.let { id ->
            val index = queue.indexOf(id)
            if (index != F033Queue.NO_ITEM) return index
        }
        if (focusedRowIndex in 0..queue.size - 1) return focusedRowIndex
        if (queue.currentIndex != F033Queue.NO_ITEM) return queue.currentIndex
        return if (queue.isEmpty) F033Queue.NO_ITEM else 0
    }

    // endregion

    // region 渲染

    /** 重建队列行并同步顶部信息；焦点尽量留在原地（按钮或同一行）。 */
    private fun render() {
        val restoreButtonId = currentFocus?.id ?: View.NO_ID
        rebuildRows(restoreButtonId)
        updateHeader()
    }

    private fun updateHeader() {
        val current = queue.currentItem
        binding.currentLabel.text = if (current == null) {
            getString(R.string.f033_current_none)
        } else {
            getString(R.string.f033_current_value, current.title)
        }

        binding.queueSummary.text = if (queue.currentIndex == F033Queue.NO_ITEM) {
            getString(R.string.f033_summary_no_current, queue.size)
        } else {
            getString(R.string.f033_summary, queue.size, queue.currentIndex + 1)
        }

        val locked = lockedId?.let { id -> queue.itemAt(queue.indexOf(id)) }
        binding.lockLabel.text = if (locked == null) {
            getString(R.string.f033_lock_none)
        } else {
            getString(R.string.f033_lock_value, locked.title)
        }

        val empty = queue.isEmpty
        binding.emptyHint.visibility = if (empty) View.VISIBLE else View.GONE
        binding.rowsContainer.visibility = if (empty) View.GONE else View.VISIBLE
        // 队列空了就没有可编辑的对象，按钮一起停掉，遥控器不会停在一个按了没反应的控件上。
        binding.btnMoveUp.isEnabled = !empty
        binding.btnMoveDown.isEnabled = !empty
        binding.btnDelete.isEnabled = !empty
    }

    private fun rebuildRows(restoreButtonId: Int) {
        val container = binding.rowsContainer
        container.removeAllViews()
        rows.clear()

        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        val rowMinHeight = resources.getDimensionPixelSize(R.dimen.list_row_min_height)

        queue.items.forEachIndexed { index, item ->
            val isCurrent = index == queue.currentIndex
            val isLocked = item.id == lockedId

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (index == 0) 0 else gap }
                minimumHeight = rowMinHeight
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isFocusable = true
                isFocusableInTouchMode = true
                id = View.generateViewId()
                background = rowBackground(isCurrent)
                foreground = ContextCompat.getDrawable(this@F033Activity, R.drawable.bg_focus_ring)
            }

            val position = TextView(this).apply {
                text = getString(R.string.f033_position, index + 1)
                setTextColor(
                    ContextCompat.getColor(
                        this@F033Activity,
                        if (isCurrent) R.color.theme_primary else R.color.theme_text_secondary
                    )
                )
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            val title = TextView(this).apply {
                text = item.title
                setTextColor(
                    ContextCompat.getColor(
                        this@F033Activity,
                        if (isCurrent) R.color.theme_text_primary else R.color.theme_text_secondary
                    )
                )
                textSize = 15f
                maxLines = 1
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            row.addView(position)
            row.addView(title)

            if (isCurrent) {
                row.addView(buildTag(getString(R.string.f033_tag_current), R.color.theme_primary))
            }
            if (isLocked) {
                row.addView(buildTag(getString(R.string.f033_tag_locked), R.color.theme_tertiary))
            }

            row.setOnLongClickListener {
                toggleLock(index)
                true
            }
            row.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && index in 0..queue.size - 1) focusedRowIndex = index
            }

            container.addView(row)
            rows.add(QueueRow(item, row))
        }

        // 动态行的上下焦点必须显式串起来，否则默认的焦点搜索会跨到编辑按钮上，遥控器手感很差。
        rows.forEachIndexed { index, row ->
            if (index > 0) row.root.nextFocusUpId = rows[index - 1].root.id
            if (index < rows.lastIndex) row.root.nextFocusDownId = rows[index + 1].root.id
        }
        val firstRowId = rows.firstOrNull()?.root?.id ?: View.NO_ID
        if (rows.isNotEmpty()) {
            rows.first().root.nextFocusUpId = binding.btnMoveUp.id
            binding.btnMoveUp.nextFocusDownId = firstRowId
            binding.btnMoveDown.nextFocusDownId = firstRowId
            binding.btnDelete.nextFocusDownId = firstRowId
            binding.btnReset.nextFocusDownId = firstRowId
        }

        restoreFocus(restoreButtonId)
    }

    /**
     * 重建后焦点对象已经不在视图树上，这里主动把它接回去：
     * 按钮上的焦点留在按钮上（方便连按上移/下移），行上的焦点落到同一行。
     */
    private fun restoreFocus(restoreButtonId: Int) {
        val button = if (restoreButtonId != View.NO_ID) {
            findViewById<View>(restoreButtonId)
        } else {
            null
        }
        if (button != null && button.isEnabled && button.requestFocus()) return

        val row = rows.getOrNull(focusedRowIndex) ?: rows.firstOrNull()
        if (row != null) {
            row.root.requestFocus()
        } else {
            // 队列已空：没有行可停，把焦点交给「重置为示例队列」，遥控器不会悬空。
            binding.btnReset.requestFocus()
        }
    }

    /** 行底：在播行用 theme_selected_bg（圆角跟卡片一致），其余行透明，不描边。 */
    private fun rowBackground(isCurrent: Boolean): android.graphics.drawable.Drawable {
        if (!isCurrent) {
            return ContextCompat.getDrawable(this, R.drawable.bg_mode_row)!!
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resources.getDimensionPixelSize(R.dimen.radius_md).toFloat()
            setColor(ContextCompat.getColor(this@F033Activity, R.color.theme_selected_bg))
        }
    }

    /** 行尾的小标签（在播 / 移动中）：inset 底 + 主题色文字，不抢焦点。 */
    private fun buildTag(text: String, colorRes: Int): TextView = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(this@F033Activity, colorRes))
        textSize = 12f
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(2), dp(8), dp(2))
        background = ContextCompat.getDrawable(this@F033Activity, R.drawable.bg_tag_orange)
        isFocusable = false
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) }
    }

    /**
     * 行内左右方向键吃掉：队列行是单行横向排列，放行会让焦点在行内子 TextView 之间乱跳，
     * 遥控器上表现为「按右没反应」。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            val focus = currentFocus
            if (focus != null && rows.any { it.root === focus }) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // endregion

    private fun sampleQueue(): F033Queue {
        val fresh = F033Queue()
        SAMPLE_TITLES.forEachIndexed { index, title ->
            // 用 insertAt 逐条铺：顺带保证「插入不改变在播项指向」这条路径真的走通。
            fresh.insertAt(index, QueueItem(id = SAMPLE_ID_PREFIX + (index + 1), title = title))
        }
        // 示例的在播项取第 2 条，搬动/删除之后高亮跟着谁一眼就能看出来。
        fresh.selectAt(1)
        return fresh
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private class QueueRow(
        val item: QueueItem,
        val root: LinearLayout
    )

    companion object {
        /** 需求约定的 SharedPreferences 文件名。 */
        private const val PREFS_NAME = "f033_queue"

        /** 队列 JSON（顺序 + 在播项 id）存在这个键下。 */
        private const val KEY_QUEUE = "queue_json"

        private const val FIELD_ITEMS = "items"
        private const val FIELD_ID = "id"
        private const val FIELD_TITLE = "title"
        private const val FIELD_CURRENT = "current"

        /** 示例条目 id 前缀：固定下来，重启后仍能靠 id 认回原来那条。 */
        private const val SAMPLE_ID_PREFIX = "f033-sample-"

        /** 示例队列：剧集、电影、纪录片、综艺各有，方便试着搬动和删除。 */
        private val SAMPLE_TITLES = listOf(
            "三体 第一季 01",
            "流浪地球 2",
            "漫长的季节 01",
            "狂飙 第 12 集",
            "地球脉动 S01E01",
            "声生不息 第 3 期",
            "人生一串 第一集"
        )
    }
}