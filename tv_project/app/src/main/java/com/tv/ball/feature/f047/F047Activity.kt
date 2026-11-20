package com.tv.ball.feature.f047

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF047Binding

/**
 * F047「搜索结果分类与空态」页面。
 *
 * 检索、分组、汇总全在 [F047Engine]；这里只做三件事：
 * 1. 准备示例数据（四类来源各若干条）；
 * 2. 把搜索框输入交给引擎；
 * 3. 按「有结果 / 无结果」两种状态画界面：有结果按来源分组、组标题带条数；
 *    无结果展示空态（图标 + 文案 + 建议），「全部清空」回到初始态。
 */
class F047Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF047Binding

    /** 来源顺序：决定分组在页面上的先后。 */
    private val sourceOrder: List<String> = listOf(SOURCE_HISTORY, SOURCE_FAVORITE, SOURCE_LOCAL, SOURCE_PLAYLIST)

    /** 内置示例数据，跨来源放了共同关键词（地球 / 宇宙），方便看分组效果。 */
    private val hits: List<F047Engine.Hit> = listOf(
        // 观看历史
        F047Engine.Hit("h1", SOURCE_HISTORY, "流浪地球 2 · 正片", "看到 01:12:38"),
        F047Engine.Hit("h2", SOURCE_HISTORY, "三体 第一季 01", "看到 00:38:10"),
        F047Engine.Hit("h3", SOURCE_HISTORY, "地球脉动 第二季 03", "看到 00:41:02"),

        // 收藏
        F047Engine.Hit("f1", SOURCE_FAVORITE, "地球脉动 第一季", "收藏于 2026-01-12"),
        F047Engine.Hit("f2", SOURCE_FAVORITE, "宇宙时空之旅 01", "收藏于 2026-02-03"),
        F047Engine.Hit("f3", SOURCE_FAVORITE, "流浪地球 2 · 4K", "收藏于 2026-02-18"),

        // 本地视频
        F047Engine.Hit("l1", SOURCE_LOCAL, "旅行到宇宙边缘.mp4", "Movies · 1.4 GB"),
        F047Engine.Hit("l2", SOURCE_LOCAL, "地球最后的夜晚.mp4", "Movies · 2.1 GB"),

        // 播放列表
        F047Engine.Hit("p1", SOURCE_PLAYLIST, "地球频道精选", "12 个条目"),
        F047Engine.Hit("p2", SOURCE_PLAYLIST, "星际穿越 + 流浪地球", "2 个条目")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF047Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = render(s?.toString().orEmpty())
        })

        binding.btnClearAll.setOnClickListener {
            binding.searchInput.setText("")
            render("")
            binding.searchInput.requestFocus()
        }

        binding.searchInput.nextFocusDownId = binding.btnClearAll.id
        binding.btnClearAll.nextFocusUpId = binding.searchInput.id

        render(binding.searchInput.text.toString())
    }

    /** 统一先藏再开，避免空态和结果同时残留。 */
    private fun render(rawQuery: String) {
        val query = rawQuery.trim()
        val sections = F047Engine.search(hits, query, sourceOrder, labels())

        binding.resultContainer.visibility = if (sections.isEmpty()) View.GONE else View.VISIBLE
        binding.emptyBlock.visibility = if (sections.isEmpty()) View.VISIBLE else View.GONE

        if (sections.isEmpty()) {
            renderEmpty(query)
        } else {
            binding.summaryValue.text = F047Engine.summary(
                sections,
                getString(R.string.f047_summary),
                getString(R.string.f047_summary_section)
            )
            renderSections(sections)
        }
    }

    /** 无结果空态：图标 + 标题（带关键词）+ 文案 + 三条建议。 */
    private fun renderEmpty(query: String) {
        binding.emptyIcon.setImageResource(R.drawable.ic_search)
        binding.emptyTitle.text = if (query.isEmpty()) {
            getString(R.string.f047_no_result_idle_title)
        } else {
            getString(R.string.f047_no_result_title, query)
        }
        binding.emptyBody.text = if (query.isEmpty()) {
            getString(R.string.f047_no_result_idle_body)
        } else {
            getString(R.string.f047_no_result_body)
        }
        binding.summaryValue.text = getString(R.string.f047_no_result_summary)
        binding.emptySuggestion.text = getString(R.string.f047_no_result_suggestion)
        binding.resultContainer.removeAllViews()
    }

    /** 有结果：每组一张卡，组标题带条数，组内按引擎给好的顺序（标题排序）。 */
    private fun renderSections(sections: List<F047Engine.Section>) {
        val container = binding.resultContainer
        container.removeAllViews()

        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        val rowMinHeight = resources.getDimensionPixelSize(R.dimen.list_row_min_height)
        val pad = dp(12)

        sections.forEachIndexed { index, section ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(this@F047Activity, R.drawable.bg_card_surface)
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (index == 0) 0 else gap }
            }

            card.addView(TextView(this).apply {
                text = getString(R.string.f047_group_header, section.label, section.count)
                setTextColor(color(R.color.theme_primary))
                textSize = 15f
                isFocusable = false
                setPadding(pad, dp(4), pad, dp(4))
            })

            section.hits.forEachIndexed { rowIndex, hit ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    minimumHeight = rowMinHeight
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(pad, dp(6), pad, dp(6))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = if (rowIndex == 0) 0 else gap / 2 }
                }

                row.addView(TextView(this).apply {
                    text = hit.title
                    setTextColor(color(R.color.theme_text_primary))
                    textSize = 15f
                    maxLines = 1
                    isFocusable = false
                })

                row.addView(TextView(this).apply {
                    text = hit.detail
                    setTextColor(color(R.color.theme_text_secondary))
                    textSize = 12f
                    maxLines = 1
                    isFocusable = false
                })

                card.addView(row)
            }

            container.addView(card)
        }
    }

    /** 来源 code -> 显示名。 */
    private fun labels(): Map<String, String> = mapOf(
        SOURCE_HISTORY to getString(R.string.f047_source_history),
        SOURCE_FAVORITE to getString(R.string.f047_source_favorite),
        SOURCE_LOCAL to getString(R.string.f047_source_local),
        SOURCE_PLAYLIST to getString(R.string.f047_source_playlist)
    )

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    companion object {
        private const val SOURCE_HISTORY = "history"
        private const val SOURCE_FAVORITE = "favorite"
        private const val SOURCE_LOCAL = "local"
        private const val SOURCE_PLAYLIST = "playlist"
    }
}
