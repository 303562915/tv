package com.tv.ball.feature.f043

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
import com.tv.ball.databinding.ActivityF043Binding

/**
 * F043「全局搜索」页面。
 *
 * 检索规则全在 [F043Engine]（纯 Kotlin）：这里只负责
 * 1. 准备四类示例数据（历史 / 收藏 / 本地 / 播放列表 各 4 条）；
 * 2. 把搜索框的输入转给引擎；
 * 3. 按「空查询 / 无命中 / 有结果」三种状态画界面，结果按来源分组、组标题带条数。
 */
class F043Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF043Binding

    /** 内置示例数据：四类各 4 条，跨来源故意放了共同关键词，方便看分组效果。 */
    private val docs: List<F043Engine.Doc> = listOf(
        // 观看历史
        F043Engine.Doc("h1", F043Engine.Source.HISTORY, "流浪地球 2 · 正片", "观看于今天 20:14 · 看到 01:12:38"),
        F043Engine.Doc("h2", F043Engine.Source.HISTORY, "三体 第一季 01", "观看于昨天 21:05 · 看到 00:38:10"),
        F043Engine.Doc("h3", F043Engine.Source.HISTORY, "地球脉动 第二季 03", "观看于 3 天前 · 看到 00:41:02"),
        F043Engine.Doc("h4", F043Engine.Source.HISTORY, "蓝色星球 01", "观看于上周 · 看到 00:49:55"),

        // 收藏
        F043Engine.Doc("f1", F043Engine.Source.FAVORITE, "地球脉动 第一季", "收藏于 2026-01-12"),
        F043Engine.Doc("f2", F043Engine.Source.FAVORITE, "宇宙时空之旅 01", "收藏于 2026-02-03"),
        F043Engine.Doc("f3", F043Engine.Source.FAVORITE, "流浪地球 2 · 4K", "收藏于 2026-02-18"),
        F043Engine.Doc("f4", F043Engine.Source.FAVORITE, "行星 第 3 集", "收藏于 2026-03-01"),

        // 本地视频
        F043Engine.Doc("l1", F043Engine.Source.LOCAL, "地球最后的夜晚.mp4", "Movies · 2.1 GB"),
        F043Engine.Doc("l2", F043Engine.Source.LOCAL, "旅行到宇宙边缘.mp4", "Movies · 1.4 GB"),
        F043Engine.Doc("l3", F043Engine.Source.LOCAL, "纪录片合集 01.mkv", "Download · 860 MB"),
        F043Engine.Doc("l4", F043Engine.Source.LOCAL, "地球脉动 S02E01.mkv", "TV · 3.6 GB"),

        // 播放列表
        F043Engine.Doc("p1", F043Engine.Source.PLAYLIST, "地球频道精选", "播放列表 · 12 个条目"),
        F043Engine.Doc("p2", F043Engine.Source.PLAYLIST, "纪录片 · 周末连播", "播放列表 · 8 个条目"),
        F043Engine.Doc("p3", F043Engine.Source.PLAYLIST, "星际穿越 + 流浪地球", "播放列表 · 2 个条目"),
        F043Engine.Doc("p4", F043Engine.Source.PLAYLIST, "自然之声", "播放列表 · 5 个条目")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF043Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = render(s?.toString().orEmpty())
        })

        binding.btnClear.setOnClickListener {
            binding.searchInput.setText("")
            render("")
            binding.searchInput.requestFocus()
        }

        // 焦点从搜索框往下就是清空按钮，清空按钮再往下没有可聚焦项，交给默认行为。
        binding.searchInput.nextFocusDownId = binding.btnClear.id
        binding.btnClear.nextFocusUpId = binding.searchInput.id

        render(binding.searchInput.text?.toString().orEmpty())
    }

    /**
     * 一次渲染把三种状态都摆好：先统一隐藏，再按当前查询打开其中一个，
     * 这样不会出现「空态和结果同时留着」的残影。
     */
    private fun render(rawQuery: String) {
        val query = rawQuery.trim()
        renderStat()

        val groups = F043Engine.search(docs, query)
        val empty = query.isEmpty()

        binding.emptyBlock.visibility = if (empty) View.VISIBLE else View.GONE
        binding.noResultBlock.visibility = if (!empty && groups.isEmpty()) View.VISIBLE else View.GONE
        binding.resultContainer.visibility = if (!empty && groups.isNotEmpty()) View.VISIBLE else View.GONE

        if (empty) {
            binding.summaryValue.text = getString(R.string.f043_empty_body)
            renderRecent()
        } else if (groups.isEmpty()) {
            binding.summaryValue.text = getString(R.string.f043_no_result_body)
            binding.noResultTitle.text = getString(R.string.f043_no_result, query)
        } else {
            binding.summaryValue.text = getString(
                R.string.f043_summary,
                query,
                F043Engine.totalHits(groups),
                groups.size
            )
            renderGroups(groups)
        }
    }

    /** 搜索框下方那行「共 N 条可搜 · 各来源多少条」。 */
    private fun renderStat() {
        val stats = F043Engine.stats(docs)
        binding.statValue.text = getString(
            R.string.f043_stat,
            docs.size,
            stats[F043Engine.Source.HISTORY] ?: 0,
            stats[F043Engine.Source.FAVORITE] ?: 0,
            stats[F043Engine.Source.LOCAL] ?: 0,
            stats[F043Engine.Source.PLAYLIST] ?: 0
        )
    }

    /** 空查询时每个来源只列一条最近记录，作为「从哪开始搜」的提示。 */
    private fun renderRecent() {
        val container = binding.emptyRecentContainer
        container.removeAllViews()

        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        val rowMinHeight = resources.getDimensionPixelSize(R.dimen.list_row_min_height)
        val pad = dp(12)

        F043Engine.recent(docs, perSource = 1).forEachIndexed { index, doc ->
            val line = TextView(this).apply {
                text = getString(R.string.f043_empty_recent_row, sourceLabel(doc.source), doc.title)
                setTextColor(color(R.color.theme_text_primary))
                textSize = 15f
                maxLines = 1
                isFocusable = false
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = rowMinHeight
                setPadding(pad, dp(4), pad, dp(4))
                background = ContextCompat.getDrawable(this@F043Activity, R.drawable.bg_mode_row)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (index == 0) 0 else gap / 2 }
            }
            container.addView(line)
        }
    }

    /** 有命中时按来源分组渲染，每组一张卡：组标题带条数，组内按引擎给的顺序（标题排序）。 */
    private fun renderGroups(groups: List<F043Engine.Group>) {
        val container = binding.resultContainer
        container.removeAllViews()

        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        val rowMinHeight = resources.getDimensionPixelSize(R.dimen.list_row_min_height)
        val pad = dp(12)

        groups.forEachIndexed { groupIndex, group ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(this@F043Activity, R.drawable.bg_card_surface)
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (groupIndex == 0) 0 else gap }
            }

            val header = TextView(this).apply {
                text = getString(R.string.f043_group_header, sourceLabel(group.source), group.count)
                setTextColor(color(R.color.theme_primary))
                textSize = 15f
                isFocusable = false
                setPadding(pad, dp(4), pad, dp(4))
            }
            card.addView(header)

            group.hits.forEachIndexed { rowIndex, doc ->
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
                    text = doc.title
                    setTextColor(color(R.color.theme_text_primary))
                    textSize = 15f
                    maxLines = 1
                    isFocusable = false
                })

                row.addView(TextView(this).apply {
                    text = getString(R.string.f043_row_meta, sourceLabel(doc.source), doc.subtitle)
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

    /** 来源枚举 -> 界面文案。 */
    private fun sourceLabel(source: F043Engine.Source): String = getString(
        when (source) {
            F043Engine.Source.HISTORY -> R.string.f043_source_history
            F043Engine.Source.FAVORITE -> R.string.f043_source_favorite
            F043Engine.Source.LOCAL -> R.string.f043_source_local
            F043Engine.Source.PLAYLIST -> R.string.f043_source_playlist
        }
    )

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()
}