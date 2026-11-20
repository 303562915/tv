package com.tv.ball.feature.f028

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF028Binding

/**
 * F028 历史搜索与筛选页面。
 *
 * 相关逻辑都在 [F028Engine]（纯 Kotlin），这里只做三件事：
 * 把筛选控件的变化翻译成 [F028Engine.Filter]，调 [F028Engine.apply] 拿结果，
 * 再把结果行贴到列表容器里。
 */
class F028Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF028Binding
    private val items = ArrayList<F028Engine.HistoryItem>()
    private val rows = ArrayList<HistoryRow>()
    private var currentRange = F028Engine.Range.ALL

    /**
     * 示例数据的时间基准。
     *
     * 筛选也用同一个基准：如果每次 render 都重新取 System.currentTimeMillis()，
     * 边界附近的条目（比如正好 7 天前那条）会因为几毫秒的差值在两次渲染间跳进跳出。
     */
    private var clockMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF028Binding.inflate(layoutInflater)
        setContentView(binding.root)

        clockMs = System.currentTimeMillis()
        items.addAll(buildSamples(clockMs))

        binding.rbRangeAll.isChecked = true
        binding.rangeGroup.setOnCheckedChangeListener { _, checkedId ->
            currentRange = when (checkedId) {
                R.id.rbRange7 -> F028Engine.Range.LAST_7_DAYS
                R.id.rbRange30 -> F028Engine.Range.LAST_30_DAYS
                else -> F028Engine.Range.ALL
            }
            render()
        }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = render()
        })

        binding.btnClearFilter.setOnClickListener {
            // 两个条件一起复位；setText / isChecked 会各自触发一次 render，最后再兜一次底，
            // 因为「本来就是空条件」时上面两次回调都不会发生。
            binding.searchInput.setText("")
            binding.rbRangeAll.isChecked = true
            currentRange = F028Engine.Range.ALL
            render()
            toast(getString(R.string.f028_toast_cleared))
        }

        render()
    }

    // region 示例数据

    /**
     * 12 条示例历史，时间跨度特意铺满 7 天 / 30 天 / 更早三档，
     * 好让「关键字 + 时间范围」的组合条件一眼能看出差别。
     */
    private fun buildSamples(now: Long): List<F028Engine.HistoryItem> = listOf(
        sample(now, 1, "三体 第一季 01", agoMinutes = 40, watchedMinutes = 22),
        sample(now, 2, "流浪地球 2", agoMinutes = 300, watchedMinutes = 120),
        sample(now, 3, "漫长的季节 01", agoMinutes = 1_560, watchedMinutes = 45),
        sample(now, 4, "三体 第二季 03", agoMinutes = 4_320, watchedMinutes = 38),
        sample(now, 5, "狂飙 第 12 集", agoMinutes = 8_640, watchedMinutes = 41),
        sample(now, 6, "流浪地球 1", agoMinutes = 12_960, watchedMinutes = 0),
        sample(now, 7, "三体 第一季 02", agoMinutes = 21_600, watchedMinutes = 44),
        sample(now, 8, "变形金刚 5", agoMinutes = 34_560, watchedMinutes = 60),
        sample(now, 9, "狂飙 第 3 集", agoMinutes = 64_800, watchedMinutes = 40),
        sample(now, 10, "三体 第二季 01", agoMinutes = 132_480, watchedMinutes = 39),
        sample(now, 11, "流浪地球 2 幕后花絮", agoMinutes = 216_000, watchedMinutes = 30),
        sample(now, 12, "漫长的季节 12", agoMinutes = 302_400, watchedMinutes = 52)
    )

    private fun sample(
        now: Long,
        id: Long,
        title: String,
        agoMinutes: Long,
        watchedMinutes: Long
    ): F028Engine.HistoryItem = F028Engine.HistoryItem(
        id = id,
        title = title,
        playedAtMs = now - agoMinutes * 60_000L,
        progressMs = watchedMinutes * 60_000L
    )

    // endregion

    // region 渲染

    private fun render() {
        val filter = F028Engine.Filter(
            keyword = binding.searchInput.text?.toString().orEmpty(),
            range = currentRange
        )
        val hits = F028Engine.apply(items, filter, clockMs)
        val listHadFocus = isInsideResults(currentFocus)

        binding.hitCount.text = getString(R.string.f028_hit_count, hits.size, items.size)
        rebuildRows(hits)

        val empty = hits.isEmpty()
        binding.emptyHint.visibility = if (empty) View.VISIBLE else View.GONE
        binding.resultContainer.visibility = if (empty) View.GONE else View.VISIBLE

        // 行是按当前条件重建的，原来那个焦点对象已经不在树上了；不接住的话 TV 上会表现为
        // 光标突然跳回搜索框。
        if (listHadFocus) {
            val first = rows.firstOrNull()?.root
            if (first != null) first.requestFocus() else binding.searchInput.requestFocus()
        }
    }

    private fun rebuildRows(hits: List<F028Engine.HistoryItem>) {
        val container = binding.resultContainer
        container.removeAllViews()
        rows.clear()

        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        val rowMinHeight = resources.getDimensionPixelSize(R.dimen.list_row_min_height)
        val pad = dp(12)

        hits.forEachIndexed { index, item ->
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
                background = ContextCompat.getDrawable(this@F028Activity, R.drawable.bg_mode_row)
                foreground = ContextCompat.getDrawable(this@F028Activity, R.drawable.bg_focus_ring)
            }

            val title = TextView(this).apply {
                text = item.title
                setTextColor(ContextCompat.getColor(this@F028Activity, R.color.theme_text_primary))
                textSize = 15f
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val meta = TextView(this).apply {
                text = metaText(item)
                setTextColor(ContextCompat.getColor(this@F028Activity, R.color.theme_text_secondary))
                textSize = 13f
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            }

            row.addView(title)
            row.addView(meta)
            container.addView(row)
            rows.add(HistoryRow(item, row))
        }

        // 动态行之间显式串上下焦点：默认的焦点搜索会跨到卡片外的按钮上，遥控器手感很差。
        rows.forEachIndexed { index, row ->
            row.root.id = View.generateViewId()
            if (index > 0) row.root.nextFocusUpId = rows[index - 1].root.id
            if (index < rows.lastIndex) row.root.nextFocusDownId = rows[index + 1].root.id
        }

        // 筛选区到结果区的接缝也在运行时补：结果行是动态生成的，XML 里写不出这些 id。
        val firstRowId = rows.firstOrNull()?.root?.id ?: View.NO_ID
        binding.rbRangeAll.nextFocusDownId = firstRowId
        binding.rbRange7.nextFocusDownId = firstRowId
        binding.rbRange30.nextFocusDownId = firstRowId
        binding.btnClearFilter.nextFocusDownId = firstRowId
        if (firstRowId != View.NO_ID) {
            rows.first().root.nextFocusUpId = binding.btnClearFilter.id
            rows.last().root.nextFocusDownId = View.NO_ID
        }
    }

    /** 一行的副标题：相对时间 ＋ 播放进度。 */
    private fun metaText(item: F028Engine.HistoryItem): String {
        val playedAt = DateUtils.getRelativeTimeSpanString(
            item.playedAtMs,
            clockMs,
            DateUtils.MINUTE_IN_MILLIS
        ).toString()
        val progress = if (item.progressMs <= 0L) {
            getString(R.string.f028_progress_zero)
        } else {
            getString(R.string.f028_progress_value, F028Engine.formatProgress(item.progressMs))
        }
        return getString(R.string.f028_meta, playedAt, progress)
    }

    /** 焦点当前是否落在结果容器内部，用来判断重建列表后要不要把焦点拉回来。 */
    private fun isInsideResults(view: View?): Boolean {
        var node: View? = view
        while (node != null) {
            if (node === binding.resultContainer) return true
            node = node.parent as? View
        }
        return false
    }

    // endregion

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private class HistoryRow(
        val item: F028Engine.HistoryItem,
        val root: LinearLayout
    )
}