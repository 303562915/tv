package com.tv.ball.feature.f049

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF049Binding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * F049「热词与最近查询排序」页面。
 *
 * 版面和规则分得很清：排序、去重、落盘全在 [F049Engine]；
 * 这里只做三件事：
 * 1. 把热词榜和最近查询画出来（热词带点击数角标）；
 * 2. 点热词 → 记一次点击 + 直接搜；点最近查询 → 直接搜；
 * 3. 每次变化整份 JSON 覆盖写到 `files/f049_hot_search.json`，下次进来沿用。
 *
 * 首次进入（文件还没有）灌一批演示数据：演示数据里特意留了两条点击数相同的热词，
 * 用来直观看到「点击数一样时按最近点击时间排」。
 */
class F049Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF049Binding
    private lateinit var store: F049Engine.Store
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF049Binding.inflate(layoutInflater)
        setContentView(binding.root)

        store = loadStore()
        if (store.isEmpty()) {
            seedDemo()
            persist()
        }

        binding.btnSearch.setOnClickListener { searchFromInput() }
        binding.btnClearRecent.setOnClickListener { clearRecent() }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                searchFromInput()
                true
            } else {
                false
            }
        }

        render(null)
    }

    // region 交互

    /** 搜索框里的内容直接搜；空输入不记一条空的最近查询。 */
    private fun searchFromInput() {
        val text = F049Engine.normalize(binding.searchInput.text.toString())
        if (runSearch(text)) {
            toast(getString(R.string.f049_toast_searched, text))
        }
    }

    /**
     * 点热词：先累加点击数（热词榜立刻重排），再当成一次搜索。
     *
     * 两件事写在同一份内存表里，只在最后落一次盘，避免中间态被写进文件。
     */
    private fun onHotWordClicked(word: F049Engine.HotWord) {
        val updated = store.recordClick(word.text, System.currentTimeMillis())
        runSearch(word.text)
        toast(getString(R.string.f049_toast_clicked, word.text, updated?.clicks ?: word.clicks))
    }

    /** 点最近查询：不累加热词点击，只是把这句话再搜一次（时间戳刷新、位置回到最前）。 */
    private fun onRecentClicked(query: F049Engine.Query) {
        if (runSearch(query.text)) {
            toast(getString(R.string.f049_toast_searched, query.text))
        }
    }

    /**
     * 执行一次搜索：记查询 → 落盘 → 回填输入框 → 刷新。
     *
     * 返回是否真的搜了（空输入会被挡掉，界面只提示一句）。
     */
    private fun runSearch(raw: String): Boolean {
        val text = F049Engine.normalize(raw)
        if (text.isEmpty()) {
            toast(getString(R.string.f049_search_blank))
            return false
        }
        store.recordQuery(text, System.currentTimeMillis())
        persist()
        binding.searchInput.setText(text)
        binding.searchInput.setSelection(text.length)
        render(text)
        return true
    }

    /** 清空最近查询；热词榜不动——「热」和「最近」是两份独立的表。 */
    private fun clearRecent() {
        val cleared = store.clearRecent()
        if (cleared == 0) {
            toast(getString(R.string.f049_cleared_empty))
            return
        }
        persist()
        render(null)
        toast(getString(R.string.f049_cleared, cleared))
    }

    // endregion

    // region 渲染

    /**
     * 整页刷新。
     *
     * [lastSearch] 是刚搜过的那句话（没搜过传 null）。没有就退化成「最近一条查询」，
     * 再没有就显示引导语——状态栏永远说得出「上一次搜的是什么」。
     */
    private fun render(lastSearch: String?) {
        renderHotWords()
        renderRecentQueries()

        val newest = store.recentQueries(1).firstOrNull()
        binding.searchStatus.text = when {
            lastSearch != null -> getString(R.string.f049_search_status_last, lastSearch)
            newest != null -> getString(R.string.f049_search_status_last, newest.text)
            else -> getString(R.string.f049_search_status_empty)
        }
    }

    private fun renderHotWords() {
        val words = store.hotWords(F049Engine.DEFAULT_HOT_LIMIT)
        binding.hotStat.text = if (words.isEmpty()) {
            getString(R.string.f049_hot_empty)
        } else {
            getString(R.string.f049_hot_stat, words.size)
        }

        val container = binding.hotContainer
        container.removeAllViews()
        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        words.forEachIndexed { index, word ->
            val row = rowShell(index, words.size, gap)

            val rank = TextView(this).apply {
                text = getString(R.string.f049_hot_rank, index + 1)
                textSize = 13f
                setTextColor(color(if (index < 3) R.color.theme_primary else R.color.theme_text_secondary))
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = false
            }
            row.addView(rank, LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT))

            val label = TextView(this).apply {
                text = word.text
                textSize = 15f
                setTextColor(color(R.color.theme_text_primary))
                isFocusable = false
            }
            row.addView(
                label,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )

            val badge = TextView(this).apply {
                text = getString(R.string.f049_hot_count_badge, word.clicks)
                textSize = 12f
                setTextColor(color(R.color.theme_text_secondary))
                background = ContextCompat.getDrawable(this@F049Activity, R.drawable.ranking_badge_bg)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                isFocusable = false
            }
            row.addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            row.setOnClickListener { onHotWordClicked(word) }
            row.setOnFocusChangeListener { _, focused -> label.setTextColor(color(if (focused) R.color.theme_primary else R.color.theme_text_primary)) }
            container.addView(row)
        }
    }

    private fun renderRecentQueries() {
        val queries = store.recentQueries(F049Engine.DEFAULT_RECENT_LIMIT)
        binding.recentStat.text = if (queries.isEmpty()) {
            getString(R.string.f049_recent_empty)
        } else {
            getString(R.string.f049_recent_stat, queries.size)
        }
        binding.btnClearRecent.isEnabled = queries.isNotEmpty()

        val container = binding.recentContainer
        container.removeAllViews()
        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        queries.forEachIndexed { index, query ->
            val row = rowShell(index, queries.size, gap)

            val label = TextView(this).apply {
                text = query.text
                textSize = 15f
                setTextColor(color(R.color.theme_text_primary))
                isFocusable = false
            }
            row.addView(
                label,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )

            val whenText = TextView(this).apply {
                text = formatTime(query.atMs)
                textSize = 12f
                setTextColor(color(R.color.theme_text_secondary))
                isFocusable = false
            }
            row.addView(whenText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            row.setOnClickListener { onRecentClicked(query) }
            row.setOnFocusChangeListener { _, focused -> label.setTextColor(color(if (focused) R.color.theme_primary else R.color.theme_text_primary)) }
            container.addView(row)
        }
    }

    /** 列表行的公共外壳：圆角行底、可点击可聚焦、行间距只加在非首行。 */
    private fun rowShell(index: Int, total: Int, gap: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ContextCompat.getDrawable(this@F049Activity, R.drawable.bg_mode_row)
        isClickable = true
        isFocusable = true
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = if (index == 0 || total == 0) 0 else gap / 2 }
    }

    private fun formatTime(ms: Long): String = timeFormat.format(Date(ms))

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    // endregion

    // region 演示数据

    /**
     * 首次进入的演示数据。
     *
     * 热词里「漫长的季节」和「狂飙」点击数都是 27，但「狂飙」更近被点过，
     * 所以它排在前面——这就是「同数按最近」的可视证据；
     * 最近查询按时间从新到旧给出，其中「三体」在热词里也能看到，方便验证两条链路互不干扰。
     */
    private fun seedDemo() {
        val now = System.currentTimeMillis()
        DEMO_HOT.forEachIndexed { index, (text, clicks) ->
            store.restoreHot(F049Engine.HotWord(text, clicks, now - index * HOUR_MS))
        }
        DEMO_QUERIES.forEachIndexed { index, text ->
            store.restoreQuery(F049Engine.Query(text, now - index * HOUR_MS))
        }
        store.trim()
    }

    // endregion

    // region 持久化

    /** 数据文件；固定名字、覆盖写，不需要多版本。 */
    private fun storeFile(): File = File(filesDir, STORE_FILE)

    /** 读文件：不存在或内容坏掉都退化成空表，不让首页开不起来。 */
    private fun loadStore(): F049Engine.Store {
        val file = storeFile()
        val text = runCatching { if (file.exists()) file.readText() else null }.getOrNull()
        return F049Engine.parse(text)
    }

    /** 写文件：整份 JSON 覆盖写，条数是页面级（十几条），开销可以忽略。 */
    private fun persist() {
        runCatching { storeFile().writeText(store.toJson()) }
    }

    // endregion

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val STORE_FILE = "f049_hot_search.json"
        const val HOUR_MS = 60L * 60L * 1000L

        /** 演示热词：文本 → 点击次数。 */
        val DEMO_HOT: List<Pair<String, Int>> = listOf(
            "三体" to 42,
            "狂飙" to 27,
            "漫长的季节" to 27,
            "流浪地球" to 22,
            "甄嬛传" to 18,
            "庆余年" to 15,
            "奥本海默" to 11
        )

        /** 演示查询：按时间从新到旧。 */
        val DEMO_QUERIES: List<String> = listOf(
            "三体 第二季",
            "4K 片源",
            "纪录片",
            "粤语 老片"
        )
    }
}
