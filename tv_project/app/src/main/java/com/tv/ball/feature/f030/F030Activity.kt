package com.tv.ball.feature.f030

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF030Binding
import kotlin.math.roundToInt

/**
 * F030 继续观看队列页面。
 *
 * 规则全在 [F030Engine]（纯 Kotlin）里，这里只负责三件事：
 * 把两条阈值滑杆的变化翻成 [F030Engine.ProgressWindow]、调 [F030Engine.buildQueue] 拿队列，
 * 再把队列行贴进容器；点击行则模拟一次「续播到断点」，把该条的最近观看时间顶到最前。
 */
class F030Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF030Binding
    private val items = ArrayList<F030Engine.WatchItem>()
    private val rows = ArrayList<QueueRow>()
    private var window = F030Engine.DEFAULT_WINDOW

    /**
     * 示例数据的基准时刻。
     *
     * 排序和「刚刚看过」都以它为锚：如果每次渲染都重新取 System.currentTimeMillis()，
     * 点击续播排出来的顺序会跟示例数据的时间戳互相打架，看不出到底按谁排的。
     */
    private var clockMs = 0L

    /** 点击续播时给条目加的偏移，保证被点的那条稳定落在队首，不受同一毫秒多次点击影响。 */
    private var resumeTickMs = 0L

    private val store: F030ThresholdStore by lazy { F030PrefsStore(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF030Binding.inflate(layoutInflater)
        setContentView(binding.root)

        clockMs = System.currentTimeMillis()
        items.addAll(buildSamples(clockMs))
        // 阈值存在 SharedPreferences 里，上次调过这次进来还是上次的窗口。
        window = store.load() ?: F030Engine.DEFAULT_WINDOW

        binding.seekStart.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    window = F030Engine.withStart(window, progress)
                    // 归一化后可能把滑块顶回去（下界压到上界之上），这里要跟着同步，否则滑块位置和实际窗口不一致。
                    syncWindowControls()
                    persist()
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = persist()
            }
        )

        binding.seekEnd.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    window = F030Engine.withEnd(window, progress)
                    syncWindowControls()
                    persist()
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = persist()
            }
        )

        binding.btnReset.setOnClickListener {
            window = F030Engine.DEFAULT_WINDOW
            syncWindowControls()
            persist()
            render()
            toast(getString(R.string.f030_toast_reset))
        }

        syncWindowControls()
        render()
    }

    // region 示例数据

    /**
     * 固定 8 条示例，进度特意把两侧和边界都占满：
     * 0% / 2%（低于下界）、5%（正好下界）、33% / 68%（正常续播）、95%（正好上界）、97% / 100%（高于上界）。
     * 最近观看时间故意打乱成和列表顺序不一致，好让「按最近观看倒序」这条一眼可验。
     */
    private fun buildSamples(nowMs: Long): List<F030Engine.WatchItem> = listOf(
        sample(nowMs, 1, "三体 第一季 02", durationMinutes = 45, percent = 0, agoMinutes = 240),
        sample(nowMs, 2, "流浪地球 2", durationMinutes = 120, percent = 2, agoMinutes = 12),
        sample(nowMs, 3, "漫长的季节 03", durationMinutes = 45, percent = 5, agoMinutes = 240),
        sample(nowMs, 4, "狂飙 第 12 集", durationMinutes = 45, percent = 33, agoMinutes = 36),
        sample(nowMs, 5, "变形金刚 5", durationMinutes = 120, percent = 68, agoMinutes = 90),
        sample(nowMs, 6, "三体 第二季 04", durationMinutes = 45, percent = 95, agoMinutes = 6),
        sample(nowMs, 7, "狂飙 第 7 集", durationMinutes = 45, percent = 97, agoMinutes = 48),
        sample(nowMs, 8, "流浪地球 1", durationMinutes = 120, percent = 100, agoMinutes = 300)
    )

    private fun sample(
        nowMs: Long,
        id: Long,
        title: String,
        durationMinutes: Long,
        percent: Int,
        agoMinutes: Long
    ): F030Engine.WatchItem {
        val durationMs = durationMinutes * 60_000L
        // 断点按整数百分比反算，而不是手写毫秒数：这样「正好 5%」「正好 95%」两条真的落在阈值上，
        // 能被 inQueue 的容差比较命中，边界行为才验得出来。
        val positionMs = durationMs * percent / 100L
        return F030Engine.WatchItem(
            id = id,
            title = title,
            durationMs = durationMs,
            positionMs = positionMs,
            lastWatchedAtMs = nowMs - agoMinutes * 60_000L
        )
    }

    // endregion

    // region 阈值

    /** 滑块位置与文案都跟当前 [window] 对齐；程序性赋值触发的回调会被 fromUser 挡掉。 */
    private fun syncWindowControls() {
        val start = window.validStart
        val end = window.validEnd
        if (binding.seekStart.progress != start) binding.seekStart.progress = start
        if (binding.seekEnd.progress != end) binding.seekEnd.progress = end
        binding.thresholdValueLow.text = getString(R.string.f030_percent_value, start)
        binding.thresholdValueHigh.text = getString(R.string.f030_percent_value, end)
        binding.windowSummary.text = getString(R.string.f030_window_summary, start, end)
    }

    /**
     * 落盘。
     *
     * 直接写在变化回调里而不是只挂在 onStopTrackingTouch：D-Pad 调滑块不会触发 tracking 回调，
     * 只写 touch 那条路径的话遥控器调完的阈值下次进来就丢了。SharedPreferences 的 apply 是异步写盘。
     */
    private fun persist() {
        store.save(window)
    }

    // endregion

    // region 渲染

    /** @param focusItemId 重排后要把焦点落到哪一条（点击续播时用，让焦点跟着条目走）。 */
    private fun render(focusItemId: Long? = null) {
        val queue = F030Engine.buildQueue(items, F030Engine.DEFAULT_QUEUE_LIMIT, window)
        val stats = F030Engine.stats(items, window)
        val hadFocus = isInsideQueue(currentFocus)

        binding.statsText.text = getString(
            R.string.f030_stats,
            queue.size,
            stats.belowThreshold,
            stats.aboveThreshold,
            stats.total
        )
        rebuildRows(queue)

        val empty = queue.isEmpty()
        binding.emptyHint.visibility = if (empty) View.VISIBLE else View.GONE
        binding.queueContainer.visibility = if (empty) View.GONE else View.VISIBLE

        // 行是按当前窗口重建的，旧焦点对象已经不在树上；不接住的话 TV 上光标会掉回页面顶部。
        val target: LinearLayout? = when {
            // 点击续播后焦点跟着那条走，而不是留在原来的位置上（条目可能已经换位）。
            focusItemId != null -> rows.firstOrNull { it.item.id == focusItemId }?.root
            hadFocus -> rows.firstOrNull()?.root
            else -> null
        }
        when {
            target != null -> target.requestFocus()
            hadFocus -> binding.btnReset.requestFocus()
            else -> Unit
        }
    }

    private fun rebuildRows(queue: List<F030Engine.WatchItem>) {
        val container = binding.queueContainer
        container.removeAllViews()
        rows.clear()

        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        val rowMinHeight = resources.getDimensionPixelSize(R.dimen.list_row_min_height)
        val pad = dp(12)
        val primary = ContextCompat.getColor(this, R.color.theme_primary)
        val inset = ContextCompat.getColor(this, R.color.surface_inset)
        val textPrimary = ContextCompat.getColor(this, R.color.theme_text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.theme_text_secondary)

        queue.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (index == 0) 0 else gap }
                minimumHeight = rowMinHeight
                setPadding(pad, dp(10), pad, dp(10))
                isFocusable = true
                isFocusableInTouchMode = true
                background = ContextCompat.getDrawable(this@F030Activity, R.drawable.bg_mode_row)
                foreground = ContextCompat.getDrawable(this@F030Activity, R.drawable.bg_focus_ring)
                setOnClickListener { onRowClicked(item) }
            }

            val title = TextView(this).apply {
                text = item.title
                setTextColor(textPrimary)
                textSize = 15f
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val bar = ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal
            ).apply {
                max = PROGRESS_SCALE
                progress = (item.progressRatio * PROGRESS_SCALE).roundToInt()
                progressTintList = ColorStateList.valueOf(primary)
                progressBackgroundTintList = ColorStateList.valueOf(inset)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }

            val metaRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }

            val meta = TextView(this).apply {
                text = getString(
                    R.string.f030_row_meta,
                    item.progressPercent,
                    F030Engine.formatClock(F030Engine.resumePosition(item)),
                    F030Engine.formatClock(item.durationMs)
                )
                setTextColor(textSecondary)
                textSize = 13f
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val action = TextView(this).apply {
                text = getString(
                    R.string.f030_resume_from,
                    F030Engine.formatClock(F030Engine.resumePosition(item))
                )
                setTextColor(primary)
                textSize = 13f
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            metaRow.addView(meta)
            metaRow.addView(action)
            row.addView(title)
            row.addView(bar)
            row.addView(metaRow)
            container.addView(row)
            rows.add(QueueRow(item, row))
        }

        // 动态行之间显式串上下焦点：默认的焦点搜索会跳到卡片外的按钮上，遥控器手感很差。
        rows.forEachIndexed { index, row ->
            row.root.id = View.generateViewId()
            if (index > 0) row.root.nextFocusUpId = rows[index - 1].root.id
            if (index < rows.lastIndex) row.root.nextFocusDownId = rows[index + 1].root.id
        }

        // 阈值区到队列区的接缝也在运行时补：队列行是动态生成的，XML 里写不出这些 id。
        val firstRowId = rows.firstOrNull()?.root?.id ?: View.NO_ID
        binding.seekStart.nextFocusDownId = binding.seekEnd.id
        binding.seekEnd.nextFocusDownId = binding.btnReset.id
        binding.btnReset.nextFocusDownId = firstRowId
        if (firstRowId != View.NO_ID) {
            rows.first().root.nextFocusUpId = binding.btnReset.id
            rows.last().root.nextFocusDownId = View.NO_ID
        }
    }

    /**
     * 模拟续播：把断点时间点报给用户，并把该条的最近观看时间顶到最前，队列随即重排。
     *
     * 真实接线时这里换成 `player.seekTo(resumePosition)` + 起播放页即可，其余逻辑不用动。
     */
    private fun onRowClicked(item: F030Engine.WatchItem) {
        val resumeMs = F030Engine.resumePosition(item)
        toast(
            getString(
                R.string.f030_toast_resume,
                item.title,
                F030Engine.formatClock(resumeMs),
                F030Engine.formatClock(F030Engine.remainingMs(item))
            )
        )

        val index = items.indexOfFirst { it.id == item.id }
        if (index < 0) return
        resumeTickMs += 1L
        items[index] = items[index].copy(lastWatchedAtMs = clockMs + resumeTickMs)
        render(focusItemId = item.id)
    }

    /** 焦点当前是否落在队列容器内部，用来判断重建列表后要不要把焦点拉回来。 */
    private fun isInsideQueue(view: View?): Boolean {
        var node: View? = view
        while (node != null) {
            if (node === binding.queueContainer) return true
            node = node.parent as? View
        }
        return false
    }

    // endregion

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private class QueueRow(
        val item: F030Engine.WatchItem,
        val root: LinearLayout
    )

    private companion object {
        /**
         * 进度条满量程。取 1000 而不是 100，是为了让 0.333 这类进度在条上也看得出差别，
         * 同时仍是整数运算，不会因为浮点进度抖动。
         */
        const val PROGRESS_SCALE = 1000
    }
}

/** 阈值的存取入口。抽成接口是为了让页面不直接绑 SharedPreferences，换存储只要换实现。 */
private interface F030ThresholdStore {
    /** 没存过返回 null，页面据此用 [F030Engine.DEFAULT_WINDOW]。 */
    fun load(): F030Engine.ProgressWindow?

    fun save(window: F030Engine.ProgressWindow)
}

/**
 * 阈值的 SharedPreferences 实现。
 *
 * 用 applicationContext，不持有 Activity；prefs 文件名和 [com.tv.ball.data.local.LocalDataSource]
 * 一致（video_player_prefs），播放页接线时直接读同一份 f030_ 键即可。
 */
private class F030PrefsStore(context: Context) : F030ThresholdStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): F030Engine.ProgressWindow? {
        if (!prefs.contains(KEY_START) && !prefs.contains(KEY_END)) return null
        return F030Engine.ProgressWindow(
            prefs.getInt(KEY_START, F030Engine.DEFAULT_START_PERCENT),
            prefs.getInt(KEY_END, F030Engine.DEFAULT_END_PERCENT)
        )
    }

    override fun save(window: F030Engine.ProgressWindow) {
        prefs.edit()
            .putInt(KEY_START, window.validStart)
            .putInt(KEY_END, window.validEnd)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "video_player_prefs"
        const val KEY_START = "f030_queue_start_percent"
        const val KEY_END = "f030_queue_end_percent"
    }
}
