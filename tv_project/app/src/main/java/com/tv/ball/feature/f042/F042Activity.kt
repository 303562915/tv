package com.tv.ball.feature.f042

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF042Binding
import java.io.File

/**
 * F042「焦点记忆」页面。
 *
 * 相关规则都在 [F042Engine]（纯 Kotlin）：记忆怎么记、失效怎么兜底、JSON 怎么读写。
 * 这里只做三件事：
 * 1. 两个页面（Tab）各铺 10 条可聚焦行，焦点一动就把「当前页 + 条目 id + 下标」记下来；
 * 2. 切页面回来时先问引擎要「该落在哪」，再把焦点 `.requestFocus()` 过去；
 * 3. 把整张记忆表按 pageKey → itemId 原样列出来，并给一个「清空记忆」按钮。
 *
 * 记忆落在 `filesDir/f042_focus_memory.json`，杀进程重进也能回到上次那一条。
 */
class F042Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF042Binding

    /** 记忆表，进页面时从 JSON 读出来，之后任何焦点变化都写回文件。 */
    private lateinit var memory: F042Engine.FocusMemory

    /** 当前页面铺出来的 10 行，按页面内顺序排列，用来按下标要焦点。 */
    private val rows = ArrayList<Row>()

    /** 当前页面。 */
    private var currentPage: Page = PAGES.first()

    /** 当前页面的焦点下标；-1 表示还没落过焦点。 */
    private var focusedIndex = 0

    /** 焦点提示文案：恢复成功 / 失效回退 / 普通记住，三种说法换着贴。 */
    private var focusNotice: Int = R.string.f042_focus_saved

    /** 正在执行「恢复焦点」时置为该下标：这一次焦点变化不覆盖提示文案。 */
    private var pendingRestoreIndex: Int = -1

    /** 清空记忆后重建焦点会顺带记一笔，这一笔要挡掉，否则刚清空就又有了。 */
    private var suppressSave: Boolean = false

    /** onCreate 里的第一轮铺行不算「返回」，onResume 里不重复要焦点。 */
    private var ready: Boolean = false

    /** 首次 onResume 紧跟 onCreate，跳过一次，之后的 onResume 才是真的「回来」。 */
    private var hasResumed: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF042Binding.inflate(layoutInflater)
        setContentView(binding.root)

        memory = loadMemory()

        binding.tabPageA.setOnClickListener { switchPage(PAGE_A) }
        binding.tabPageB.setOnClickListener { switchPage(PAGE_B) }
        binding.btnClear.setOnClickListener { clearMemory() }

        switchPage(PAGE_A, announce = false)
        ready = true
    }

    /**
     * 从别的页面回到这里时把焦点再落回去。
     *
     * 这条路径和切 Tab 走的是同一套恢复逻辑：引擎里存的记忆不会因为「离开一趟」而丢，
     * 所以回来后仍然落在上次那一条；记忆失效则退回首项。
     * 首次 onResume 紧跟着 onCreate，行刚建好、焦点也已经落过一次，这里跳过不重复要。
     */
    override fun onResume() {
        super.onResume()
        if (!ready) return
        if (!hasResumed) {
            hasResumed = true
            return
        }
        restoreFocus(announce = true)
    }

    // region 页面切换与行构建

    /** 切到另一个页面。announce=true 时用 Toast 把「回到上次位置」说清楚。 */
    private fun switchPage(page: Page, announce: Boolean = true) {
        if (page.key == currentPage.key && rows.isNotEmpty()) {
            restoreFocus(announce = announce)
            return
        }
        currentPage = page
        focusedIndex = 0
        buildRows(page)
        renderTabs(page)
        restoreFocus(announce = announce)
    }

    /**
     * 铺当前页面的 10 条可聚焦行。
     *
     * 行只在切页面时重建：焦点变化只改背景色和文案，不重建视图，
     * 否则正在要焦点的那一行会被换掉，焦点直接掉到根布局上。
     */
    private fun buildRows(page: Page) {
        val container = binding.listContainer
        container.removeAllViews()
        rows.clear()

        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        val rowMinHeight = resources.getDimensionPixelSize(R.dimen.list_row_min_height)
        val pad = dp(16)
        val titleColor = color(R.color.theme_text_primary)

        page.items.forEachIndexed { index, item ->
            val row = TextView(this).apply {
                id = View.generateViewId()
                text = getString(R.string.f042_row_title, index + 1, item.title)
                setTextColor(titleColor)
                textSize = 15f
                maxLines = 1
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = rowMinHeight
                setPadding(pad, 0, pad, 0)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setBackgroundResource(R.drawable.bg_mode_row)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (index == 0) 0 else gap / 2 }

                setOnFocusChangeListener { view, hasFocus ->
                    view.background = ContextCompat.getDrawable(
                        this@F042Activity,
                        if (hasFocus) R.drawable.bg_mode_card_selected else R.drawable.bg_mode_row
                    )
                    (view as? android.widget.TextView)?.setTextColor(
                        color(if (hasFocus) R.color.theme_primary else R.color.theme_text_primary)
                    )
                    if (hasFocus) onRowFocused(index, item.id)
                }

                setOnClickListener {
                    // 点击也要记：触屏设备没有焦点移动，只有这一条路径能留下位置。
                    onRowFocused(index, item.id)
                }
            }
            container.addView(row)
            rows.add(Row(item.id, index, row))
        }

        // 动态生成的行之间没有现成的焦点链，显式串一串，方向键才不会跳出行外。
        rows.forEachIndexed { index, row ->
            if (index > 0) row.view.nextFocusUpId = rows[index - 1].view.id
            if (index < rows.lastIndex) row.view.nextFocusDownId = rows[index + 1].view.id
        }
        rows.firstOrNull()?.view?.nextFocusUpId = binding.tabPageA.id
        binding.tabPageA.nextFocusDownId = rows.firstOrNull()?.view?.id ?: View.NO_ID
        binding.tabPageB.nextFocusDownId = rows.firstOrNull()?.view?.id ?: View.NO_ID
        rows.lastOrNull()?.view?.nextFocusDownId = binding.btnClear.id
        binding.btnClear.nextFocusUpId = rows.lastOrNull()?.view?.id ?: View.NO_ID
    }

    /** 某一行拿到焦点：立刻记一笔，并把界面 / 记忆表刷新一遍。 */
    private fun onRowFocused(index: Int, itemId: String) {
        focusedIndex = index
        // 恢复焦点带来的那一次回调只更新下标：记忆已经在目标位置，不必再写一遍，
        // 也不能把「已回到上次位置 / 已回退首项」的提示覆盖成普通的「已记住」。
        if (index == pendingRestoreIndex) {
            pendingRestoreIndex = -1
            renderStats()
            return
        }
        if (suppressSave) {
            renderStats()
            return
        }
        memory.save(currentPage.key, itemId, index)
        persist()
        focusNotice = R.string.f042_focus_saved
        renderStats()
        renderMemory()
        // 用户自己挪过一次焦点，之前的「清空」态就结束了，后续焦点变化照常记。
        suppressSave = false
    }

    // endregion

    // region 焦点恢复

    /**
     * 问引擎「这一页该落在哪」，然后真的把焦点挪过去。
     *
     * 三种结果对应三种提示：
     * - 命中记忆 → 「已回到上次位置」；
     * - 记忆失效（条目已不在列表）→ 「已回退到首项」；
     * - 本来就没记忆 → 安静地落在首项，不打扰。
     */
    private fun restoreFocus(announce: Boolean) {
        if (rows.isEmpty()) return
        val page = currentPage
        val resolved = memory.resolve(page.key, page.items.map { it.id }) ?: return

        focusedIndex = resolved.index
        focusNotice = when {
            resolved.fallback -> R.string.f042_focus_fallback
            resolved.fromMemory -> R.string.f042_focus_restored
            else -> R.string.f042_focus_saved
        }
        // 焦点落过去时会触发一次 onFocusChange，那次回调不该把上面的提示改回去。
        pendingRestoreIndex = resolved.index
        focusRow(resolved.index)
        renderStats()
        renderMemory()

        if (!announce) return
        when {
            resolved.fallback -> toast(getString(R.string.f042_focus_fallback))
            resolved.fromMemory -> toast(
                getString(R.string.f042_focus_restored, getString(page.labelRes), resolved.index + 1)
            )
            else -> Unit
        }
    }

    /** 把焦点挪到第 [index] 行，顺手把这一行滚进可视区。 */
    private fun focusRow(index: Int) {
        val row = rows.getOrNull(index) ?: return
        row.view.post {
            row.view.requestFocus()
            val target = row.view.top + binding.listContainer.top
            binding.root.smoothScrollTo(0, (target - dp(112)).coerceAtLeast(0))
        }
    }

    // endregion

    // region 渲染

    /** 两个 Tab 的选中态：选中的用主色、全亮，未选中的压暗。 */
    private fun renderTabs(page: Page) {
        val active = color(R.color.theme_primary)
        val idle = color(R.color.theme_text_secondary)
        val activeA = page.key == PAGE_A.key
        binding.tabPageA.setTextColor(if (activeA) active else idle)
        binding.tabPageA.alpha = if (activeA) 1f else 0.6f
        binding.tabPageB.setTextColor(if (activeA) idle else active)
        binding.tabPageB.alpha = if (activeA) 0.6f else 1f
    }

    /** 页面统计 + 焦点文案。 */
    private fun renderStats() {
        val page = currentPage
        val label = getString(page.labelRes)
        val index = focusedIndex.coerceIn(0, (page.items.size - 1).coerceAtLeast(0))
        binding.pageStat.text = getString(R.string.f042_page_stat, label, page.items.size, index + 1)
        binding.focusStat.text = when (focusNotice) {
            R.string.f042_focus_restored -> getString(R.string.f042_focus_restored, label, index + 1)
            R.string.f042_focus_fallback -> getString(R.string.f042_focus_fallback)
            else -> getString(R.string.f042_focus_saved, label, index + 1)
        }
    }

    /**
     * 记忆表：一条一行，写成 `pageKey → itemId · 第 n 条`。
     *
     * 展示的是引擎里的原始键值，不是页面标题——这两个 key 就是持久化文件里真正存的东西，
     * 直接列出来才好核对「记的确实是这一页这一条」。
     * 条目已经不在当前列表里的记忆会带上「已失效」标记，下次恢复时引擎会把它们退回首项。
     */
    private fun renderMemory() {
        val container = binding.memoryContainer
        container.removeAllViews()
        val records = memory.records()
        binding.memoryStat.text = if (records.isEmpty()) {
            getString(R.string.f042_memory_empty)
        } else {
            getString(R.string.f042_memory_stat, records.size)
        }

        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        val pad = dp(12)
        records.forEachIndexed { position, record ->
            val page = PAGES.firstOrNull { it.key == record.pageKey }
            val valid = page == null || memory.isValid(record.pageKey, page.items.map { it.id })
            val line = TextView(this).apply {
                text = if (valid) {
                    getString(R.string.f042_memory_row, record.pageKey, record.itemId, record.index + 1)
                } else {
                    getString(R.string.f042_memory_row_stale, record.pageKey, record.itemId, record.index + 1)
                }
                setTextColor(color(if (valid) R.color.theme_text_secondary else R.color.theme_error))
                textSize = 13f
                isFocusable = false
                setPadding(pad, dp(6), pad, dp(6))
                background = ContextCompat.getDrawable(this@F042Activity, R.drawable.bg_mode_row)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (position == 0) 0 else gap / 2 }
            }
            container.addView(line)
        }
    }

    // endregion

    // region 记忆读写

    /** 记忆文件；固定名字，覆盖写，不需要多版本。 */
    private fun storeFile(): File = File(filesDir, STORE_FILE)

    /** 读记忆：文件不存在或内容坏掉都退化成空表，不让页面开不起来。 */
    private fun loadMemory(): F042Engine.FocusMemory {
        val file = storeFile()
        val text = runCatching { if (file.exists()) file.readText() else null }.getOrNull()
        return F042Engine.parse(text)
    }

    /** 写记忆：整份 JSON 覆盖写，条数是页面级（两三条），开销可以忽略。 */
    private fun persist() {
        runCatching { storeFile().writeText(memory.toJson()) }
    }

    /** 清空记忆：清完立刻刷新记忆表和当前页面，焦点随新的记忆状态回退到首项。 */
    private fun clearMemory() {
        val cleared = memory.forgetAll()
        if (cleared == 0) {
            toast(getString(R.string.f042_toast_cleared_empty))
            return
        }
        persist()
        renderMemory()
        focusNotice = R.string.f042_focus_saved
        // 清空后把焦点收回首项，这一路的焦点回调不再写记忆，否则「清空」等于白点。
        suppressSave = true
        focusedIndex = 0
        focusRow(0)
        // focusRow 是 post 出去的，等同一次事件循环之后再放开，普通焦点变化照常记。
        binding.root.post { suppressSave = false }
        renderStats()
        toast(getString(R.string.f042_toast_cleared, cleared))
    }

    // endregion

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** 一行列表：条目稳定 id、在页面里的下标、以及对应的视图。 */
    private class Row(val itemId: String, val index: Int, val view: TextView)

    /** 一个「页面」：记忆的 pageKey、Tab 文案、10 条内容。 */
    private class Page(val key: String, val labelRes: Int, val items: List<Item>)

    private class Item(val id: String, val title: String)

    private companion object {
        const val STORE_FILE = "f042_focus_memory.json"

        val PAGE_A: Page
        val PAGE_B: Page
        val PAGES: List<Page>

        init {
            PAGE_A = Page(
                F042Engine.KEY_PAGE_A,
                R.string.f042_tab_a,
                listOf(
                    Item("a01", "三体 第一季 01"),
                    Item("a02", "流浪地球 2"),
                    Item("a03", "漫长的季节 01"),
                    Item("a04", "狂飙 第 12 集"),
                    Item("a05", "宇宙时空之旅 01"),
                    Item("a06", "沙丘 2"),
                    Item("a07", "繁花 第 03 集"),
                    Item("a08", "变形金刚 5"),
                    Item("a09", "大江大河 第 08 集"),
                    Item("a10", "地球脉动 03")
                )
            )
            PAGE_B = Page(
                F042Engine.KEY_PAGE_B,
                R.string.f042_tab_b,
                listOf(
                    Item("b01", "星际穿越"),
                    Item("b02", "觉醒年代 第 01 集"),
                    Item("b03", "风骚律师 第 05 集"),
                    Item("b04", "黑客帝国 4"),
                    Item("b05", "雾山五行 01"),
                    Item("b06", "人世间 第 21 集"),
                    Item("b07", "奥本海默"),
                    Item("b08", "灵笼 第 06 集"),
                    Item("b09", "慢速列车 第 02 集"),
                    Item("b10", "深海")
                )
            )
            PAGES = listOf(PAGE_A, PAGE_B)
        }
    }
}