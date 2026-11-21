package com.tv.ball.presentation.shortdrama

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.tv.ball.R
import com.tv.ball.databinding.ActivityShortDramaBinding
import com.tv.ball.di.Dependencies
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 免费短剧列表页（Activity 侧）。
 *
 * 这一版只做「把已有布局接上数据」：布局（activity_short_drama / item_short_drama）已经由
 * 上一位同学铺好，这里把搜索防抖、搜索历史、热门词、Tab 记忆、三态 footer、首屏骨架、
 * 继续观看、列数自适应逐个接线，不改布局结构和 ShortDramaRepository / ViewModel。
 */
class ShortDramaActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ShortDramaActivity"

        /**
         * 每页条数。仓库里采集源固定一页 20 条（ShortDramaRepository 注释与 pg 翻页一致），
         * ViewModel 没有暴露「到底」状态，所以只能靠「这次追加回来几条 < 20」推断没有更多。
         * 采集源少给一条不算到底，用 < 而不是 != 是为了容忍源端截断。
         */
        private const val PAGE_SIZE = 20

        /** 输入防抖：300ms 内继续打字就把上一次的搜索任务掐掉 */
        private const val SEARCH_DEBOUNCE_MS = 300L

        /** 骨架卡片数：首屏一屏能看到的量，6 个足够铺满手机三列两行 */
        private const val SKELETON_COUNT = 6

        /** 详情页消费的「从第几集开始播」extra，与 ShortDramaDetailActivity 的读取名一致 */
        private const val EXTRA_START_EPISODE = "START_EPISODE"

        /** 记上次选中的分类；只存 Tab 标签，不存搜索词（搜索词属于一次性的临时状态） */
        private const val UI_PREFS = "short_drama_ui"
        private const val KEY_LAST_TAB = "last_tab"

        /**
         * 翻页请求的兜底检查延迟：ViewModel 在「已有请求在途 / 已到底」时会直接跳过 loadMore()
         * 而不产生任何状态变化，此时没人会把 footer 的「加载中…」收掉，所以延迟一拍自己核对。
         */
        private const val APPEND_GUARD_MS = 800L
    }

    private lateinit var binding: ActivityShortDramaBinding
    private val viewModel: ShortDramaViewModel by lazy {
        ViewModelProvider(this)[ShortDramaViewModel::class.java]
    }
    private lateinit var adapter: ShortDramaAdapter
    private lateinit var skeletonAdapter: SkeletonAdapter

    // 分类由数据源提供（都与采集源实测有货），改词表只需改 ShortDramaRepository.tabs
    private val categories = ShortDramaRepository.tabs.map { it.label }

    /** 热门词 = Tab 标签前 8 个（Tab 词本身就是「实测有货」的高频词，不用另建词表） */
    private val hotWords = categories.take(8)

    private var currentTabIndex = 0

    /** 在途的防抖任务；回车/点词/清空时取消它 */
    private var searchDebounceJob: Job? = null

    /** 程序化改写输入框时抑制 watcher，避免自己触发「退出搜索 + 再防抖一次」 */
    private var suppressWatcher = false

    /**
     * 发翻页请求那一刻的列表长度。
     * 为什么不能拿「上一次 dramas 的长度」当基准：ViewModel 是先更新 dramas、再清 isLoading，
     * 等 loading 变成 false 时上一次长度已经被新数据覆盖了，差值恒为 0，永远判断不出到底。
     */
    private var appendBaseSize = 0

    /** 当前在途请求是不是「翻页」 */
    private var appendRequested = false

    /** 推断出的「没有更多」 */
    private var noMore = false

    /** 翻页失败：footer 变成「加载失败，点我重试」 */
    private var footerFailed = false

    /**
     * 本次加载是不是用户下拉触发的。
     * 为什么单独记：下拉刷新会先清空列表（ViewModel refresh 里 _dramas = emptyList），
     * 这时「空列表 + loading」看起来像首屏，renderState 会把系统转圈关掉换成骨架，
     * 用户刚拉出来的 spinner 就凭空消失了；记住来源才能把 spinner 留住。
     */
    private var pullRefreshing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // 夜间模式与其它页一致：必须在 super.onCreate 之前应用
        val nightMode = com.tv.ball.di.Dependencies.configRepository.getNightMode()
        com.tv.ball.utils.ThemeUtils.applyNightMode(nightMode)
        super.onCreate(savedInstanceState)
        binding = ActivityShortDramaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.tv.ball.utils.StatusBarUtils.setupTransparentStatusBar(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.tvFooter.setOnClickListener { retryFromFooter() }

        setupTabs()
        buildHotWordChips()
        setupSearch()
        setupRecyclerView()
        setupSwipeRefresh()
        observeViewModel()

        // 进页面就按上次选中的分类拉第一页（tab 恢复已经在 setupTabs 里做完，避免重复请求）
        viewModel.loadDramas(categories[currentTabIndex], page = 1, refresh = true)
    }

    // ---------------- 列数自适应 ----------------

    /**
     * 按屏幕宽度定列数：手机竖屏 3 列、折叠屏展开 / 小平板 4 列、平板横屏 5 列。
     * 真实网格与骨架网格必须用同一个结果，否则骨架铺 3 列、数据到了跳成 5 列，页面会闪一下。
     */
    private fun resolveGridColumns(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return when {
            widthDp < 600 -> 3
            widthDp <= 840 -> 4
            else -> 5
        }
    }

    // ---------------- 分类 Tab + 记忆 ----------------

    private fun uiPrefs() = Dependencies.context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)

    private fun setupTabs() {
        val saved = uiPrefs().getString(KEY_LAST_TAB, null)
        // 找不到（首次进页 / 词表被改过）就回落到第一个 Tab
        currentTabIndex = categories.indexOfFirst { it == saved }.takeIf { it >= 0 } ?: 0

        categories.forEach { category ->
            binding.tabLayout.addTab(binding.tabLayout.newTab().setText(category))
        }

        // 先 select 再挂监听：select() 也会回调 onTabSelected，
        // 顺序反了会在进页面时对同一个分类连打两次请求
        binding.tabLayout.getTabAt(currentTabIndex)?.select()

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val index = tab?.position ?: return
                currentTabIndex = index
                // 只在真正切换分类时写盘：搜索态下 Tab 是隐藏的，不会误存
                uiPrefs().edit().putString(KEY_LAST_TAB, categories[index]).apply()

                // 点 Tab = 回到分类浏览：清掉搜索词（搜索态本来隐藏了 Tab，这里是防御性处理）
                clearSearchInputSilently()
                resetPaging()
                viewModel.loadDramas(categories[index], page = 1, refresh = true)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}

            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    // ---------------- 热门词 chip ----------------

    /**
     * 用最简 TextView + 搜索框同一套胶囊底拼 chip，不引 Chip 组件的勾选/关闭图标状态，
     * 少一层主题依赖，也避免和 TabLayout 的选中态视觉打架。
     */
    private fun buildHotWordChips() {
        binding.chipContainer.removeAllViews()
        val textColor = ContextCompat.getColor(this, R.color.theme_text_primary)
        val chipBg = ContextCompat.getDrawable(this, R.drawable.search_input_bg)?.mutate()
        hotWords.forEach { word ->
            val chip = TextView(this).apply {
                text = word
                textSize = 13f
                setTextColor(textColor)
                background = chipBg?.constantState?.newDrawable()?.mutate()
                setPadding(dp(14), dp(6), dp(14), dp(6))
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener {
                    // 点热门词算明确提交：填进输入框（不触发防抖）+ 立即搜 + 记历史
                    fillSearchInputSilently(word)
                    submitSearch(word, record = true)
                }
            }
            binding.chipContainer.addView(chip)
        }
    }

    /** chip 行只在搜索态、或「当前分类/搜索结果为空」时出现；正常分类浏览时让位给 Tab */
    private fun updateChipsVisibility(keyword: String, empty: Boolean, loading: Boolean) {
        val show = keyword.isNotEmpty() || (empty && !loading)
        binding.chipScroll.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---------------- 搜索：防抖 / 历史 / 提交 ----------------

    private fun setupSearch() {
        // 回车（IME_ACTION_SEARCH 或物理 Enter）= 立即搜，并把在途的 300ms 防抖掐掉
        binding.etSearch.setOnEditorActionListener { _, actionId, event ->
            val enterDown = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            val submitted = actionId == EditorInfo.IME_ACTION_SEARCH || enterDown
            if (submitted) {
                submitSearch(binding.etSearch.text?.toString().orEmpty(), record = true)
            }
            submitted
        }

        binding.etSearch.doAfterTextChanged { text ->
            if (suppressWatcher) return@doAfterTextChanged
            val kw = text?.toString().orEmpty()
            searchDebounceJob?.cancel()

            if (kw.isBlank()) {
                // 清空输入框 = 退出搜索，回到原来的分类（沿用 ViewModel 的 exitSearch 行为）
                binding.etSearch.dismissDropDown()
                if (viewModel.searchKeyword.value.isNotEmpty()) {
                    resetPaging()
                    viewModel.exitSearch()
                }
                return@doAfterTextChanged
            }

            // 防抖：不在每次按键都打采集源，等 300ms 内的输入停下来再搜
            searchDebounceJob = lifecycleScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                submitSearch(kw, record = false)
            }
        }

        // 历史建议：聚焦 / 点输入框时挂上去
        binding.etSearch.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) showHistoryDropdown()
        }
        binding.etSearch.setOnClickListener { showHistoryDropdown() }

        // 点历史词 = 明确提交（AutoCompleteTextView 会先把文案替换掉，之后的防抖被 submitSearch 取消）
        binding.etSearch.setOnItemClickListener { parent, _, position, _ ->
            val kw = parent.getItemAtPosition(position) as? String ?: return@setOnItemClickListener
            submitSearch(kw, record = true)
        }
    }

    /**
     * 搜索历史下拉。历史为空时直接摘掉 adapter 并收下拉，避免出现一个空白浮层。
     * 只在「输入框为空」或「已经在搜索态」时展开，正在输入时不打扰。
     */
    private fun showHistoryDropdown() {
        val shouldShow = binding.etSearch.text.isNullOrEmpty() ||
            viewModel.searchKeyword.value.isNotEmpty()
        attachHistoryAdapter(show = shouldShow)
    }

    /**
     * 把搜索历史挂到 AutoCompleteTextView 上（completionThreshold 在 XML 里是 1）。
     * show=false 只更新数据、不弹浮层：进入搜索态时先备好建议，等用户点输入框再展开，
     * 避免刚搜完又被一个浮层盖住结果。
     */
    private fun attachHistoryAdapter(show: Boolean) {
        val history = ShortDramaSearchHistory.all()
        if (history.isEmpty()) {
            binding.etSearch.setAdapter(null)
            binding.etSearch.dismissDropDown()
            return
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, history.toMutableList())
        binding.etSearch.setAdapter(adapter)
        // XML 里 completionThreshold=1 只管「有输入时的过滤」；输入框为空时要列出整份历史，
        // 得把阈值降到 0，否则空文本不会触发过滤，下拉是空的。
        binding.etSearch.threshold = if (binding.etSearch.text.isNullOrEmpty()) 0 else 1
        if (show) {
            binding.etSearch.showDropDown()
        } else if (binding.etSearch.text.isNullOrEmpty()) {
            // 有文字时不主动收：那可能是用户正在打字过滤出来的建议，不该被顺手关掉
            binding.etSearch.dismissDropDown()
        }
    }

    /**
     * 真正的搜索入口（防抖、回车、热门词、历史点选都走这里）。
     *
     * record=true 只给「明确提交」用：防抖自动搜不写历史，否则输入「霸总」的过程中
     * 「霸」「霸总」都会被记进去，历史很快变成前缀垃圾。
     */
    private fun submitSearch(keyword: String, record: Boolean) {
        searchDebounceJob?.cancel()
        searchDebounceJob = null
        val kw = keyword.trim()
        if (kw.isEmpty()) {
            if (viewModel.searchKeyword.value.isNotEmpty()) {
                resetPaging()
                viewModel.exitSearch()
            }
            return
        }

        if (record) {
            ShortDramaSearchHistory.add(kw)
            hideKeyboard()
        }
        binding.etSearch.dismissDropDown()
        if (binding.etSearch.text?.toString() != kw) {
            fillSearchInputSilently(kw)
        }
        resetPaging()
        viewModel.search(kw)
    }

    /** 程序化填词：不触发「退出搜索 / 防抖」那一套 watcher 逻辑 */
    private fun fillSearchInputSilently(keyword: String) {
        suppressWatcher = true
        binding.etSearch.setText(keyword)
        binding.etSearch.setSelection(keyword.length)
        suppressWatcher = false
    }

    /** 程序化清空：点 Tab 回分类时用，避免 watcher 又跑一遍 exitSearch */
    private fun clearSearchInputSilently() {
        if (binding.etSearch.text.isNullOrEmpty()) return
        suppressWatcher = true
        binding.etSearch.setText("")
        binding.etSearch.setSelection(0)
        suppressWatcher = false
        binding.etSearch.dismissDropDown()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
        binding.etSearch.clearFocus()
    }

    // ---------------- 列表 / 骨架 / 翻页 ----------------

    private fun setupRecyclerView() {
        val columns = resolveGridColumns()

        adapter = ShortDramaAdapter { drama -> openDetail(drama) }
        binding.recyclerView.layoutManager = GridLayoutManager(this, columns)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                // itemCount 为 0 时 findLastVisibleItemPosition() 返回 -1，会误判成「已接近底部」
                if (lm.itemCount == 0) return
                // 提前 4 格开始预取，滚到底之前数据已经在路上
                if (lm.findLastVisibleItemPosition() >= lm.itemCount - 4) requestLoadMore()
            }
        })

        // 骨架：同一套列数 + 同一种 ViewHolder 结构，数据到位时只有内容出现、位置不跳
        skeletonAdapter = SkeletonAdapter(SKELETON_COUNT)
        binding.skeletonRecycler.layoutManager = GridLayoutManager(this, columns)
        binding.skeletonRecycler.adapter = skeletonAdapter
        binding.skeletonRecycler.itemAnimator = null
        // 骨架不可交互：禁止点击/触摸（占位块本身没监听，这里再加一道）
        binding.skeletonRecycler.isEnabled = false
        binding.skeletonRecycler.isClickable = false
    }

    private fun openDetail(drama: ShortDramaItem) {
        val intent = Intent(this, ShortDramaDetailActivity::class.java)
        intent.putExtra("BOOK_ID", drama.bookId)
        intent.putExtra("TITLE", drama.title)
        intent.putExtra("COVER", drama.cover)
        // 继续观看：把「看到第几集」带给详情页（详情页按 Int 读取 START_EPISODE）；
        // 没进度就不传，让详情页自己从头开始
        ShortDramaProgressStore.progressOf(drama.bookId)
            ?.takeIf { it.episodeNum > 0 }
            ?.let { intent.putExtra(EXTRA_START_EPISODE, it.episodeNum) }
        startActivity(intent)
    }

    /**
     * 一次请求结束后推断「到底没有」。
     *
     * 为什么只能这么推断：ShortDramaViewModel 没有公开「没有更多」状态（这次不允许改它），
     * 只能在 Activity 侧看条数——首屏/搜索看「本页拿回几条」，翻页看「这次比请求前多了几条」，
     * 少于 PAGE_SIZE 就认为后面没了。
     */
    private fun evaluateEndReached(isAppend: Boolean, baseSize: Int) {
        val size = viewModel.dramas.value.size
        val delta = if (isAppend) size - baseSize else size
        if (delta < PAGE_SIZE) noMore = true
        if (isAppend) {
            Log.d(TAG, "翻页返回 ${delta} 条（总计 $size），noMore=$noMore")
        }
    }

    private fun requestLoadMore() {
        if (noMore || viewModel.isLoading.value) return
        appendRequested = true
        // 基准取 StateFlow 里的真实条数，不用 adapter.itemCount：
        // ListAdapter.submitList 是异步 diff，itemCount 会晚一帧才变，拿它算差值会永远得到 0
        appendBaseSize = viewModel.dramas.value.size
        viewModel.loadMore()

        // 兜底：ViewModel 可能因为「有请求在途 / 已到底」直接跳过，不会有 loading 状态变化，
        // 这里延迟自查一次，把 footer 的「加载中…」收掉，并按实际增量判断是否到底
        lifecycleScope.launch {
            delay(APPEND_GUARD_MS)
            if (appendRequested && !viewModel.isLoading.value) {
                evaluateEndReached(isAppend = true, baseSize = appendBaseSize)
                appendRequested = false
                renderState()
            }
        }
    }

    /** 一次新的取数（首屏 / 刷新 / 搜索 / 切分类）都要把翻页状态清干净 */
    private fun resetPaging() {
        appendRequested = false
        noMore = false
        footerFailed = false
        appendBaseSize = 0
        pullRefreshing = false
    }

    private fun setupSwipeRefresh() {
        // 布局里 SwipeRefresh 的子 View 是 LinearLayout（列表 + footer），
        // 不指定回调的话「列表已经滚下去」也会被判成可下拉，往下滚就误触发刷新
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            binding.recyclerView.canScrollVertically(-1)
        }
        binding.swipeRefresh.setOnRefreshListener {
            resetPaging()
            pullRefreshing = true
            if (viewModel.searchKeyword.value.isNotEmpty()) {
                // 搜索态下拉 = 重搜当前关键词
                viewModel.search(viewModel.searchKeyword.value)
            } else {
                viewModel.loadDramas(categories[currentTabIndex], page = 1, refresh = true)
            }
        }
    }

    // ---------------- 三态 footer ----------------

    private fun renderFooter() {
        val hasItems = viewModel.dramas.value.isNotEmpty()
        val footer = binding.tvFooter
        when {
            footerFailed -> {
                footer.text = "加载失败，点我重试"
                footer.isClickable = true
                footer.visibility = View.VISIBLE
            }
            noMore && hasItems -> {
                footer.text = "没有更多了"
                footer.isClickable = false
                footer.visibility = View.VISIBLE
            }
            appendRequested && hasItems -> {
                footer.text = "加载中…"
                footer.isClickable = false
                footer.visibility = View.VISIBLE
            }
            else -> {
                footer.isClickable = false
                footer.visibility = View.GONE
            }
        }
    }

    private fun retryFromFooter() {
        if (!footerFailed) return
        footerFailed = false
        val keyword = viewModel.searchKeyword.value
        when {
            keyword.isNotEmpty() -> {
                // 搜索态重试 = 重搜当前词
                resetPaging()
                viewModel.search(keyword)
            }
            viewModel.dramas.value.isEmpty() -> {
                // 首屏就失败：重新拉当前分类第一页
                resetPaging()
                viewModel.loadDramas(categories[currentTabIndex], page = 1, refresh = true)
            }
            else -> {
                // 翻页失败：接着翻下一行
                noMore = false
                requestLoadMore()
            }
        }
        renderFooter()
    }

    // ---------------- 状态汇总 ----------------

    /** 把「骨架 / 空态 / 列表 / Tab / chip / footer」按当前三个 StateFlow 一次算完 */
    private fun renderState() {
        val dramas = viewModel.dramas.value
        val loading = viewModel.isLoading.value
        val keyword = viewModel.searchKeyword.value
        val empty = dramas.isEmpty()

        val firstLoad = loading && empty
        // 首屏骨架只在「第一次加载且一条都没有」时出现；下拉刷新已有内容时走系统转圈
        binding.skeletonRecycler.visibility = if (firstLoad) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
        binding.emptyView.visibility = if (empty && !loading) View.VISIBLE else View.GONE
        binding.emptyView.text = if (keyword.isEmpty()) "暂无短剧" else "没搜到相关短剧"
        // 下拉刷新保留自己的转圈（即使列表被清空、骨架同时出现）；翻页时不用整页转圈
        binding.swipeRefresh.isRefreshing =
            loading && (pullRefreshing || (!firstLoad && !appendRequested))

        // 搜索态隐藏分类 Tab（否则继续点 Tab 会和搜索结果打架），清空关键词就恢复
        binding.tabLayout.visibility = if (keyword.isEmpty()) View.VISIBLE else View.GONE

        updateChipsVisibility(keyword, empty, loading)
        renderFooter()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.dramas.collect { dramas ->
                adapter.submitList(dramas)
                renderState()
            }
        }

        lifecycleScope.launch {
            viewModel.isLoading.collect { loading ->
                if (!loading) {
                    pullRefreshing = false
                    // 首屏/搜索/切分类的一次请求也走同一套判断：
                    // 一页就拿全（< 20）时，进页面直接就能显示「没有更多了」，不用等用户先翻一次
                    evaluateEndReached(isAppend = appendRequested, baseSize = appendBaseSize)
                    appendRequested = false
                }
                renderState()
            }
        }

        lifecycleScope.launch {
            viewModel.searchKeyword.collect { keyword ->
                // 进入搜索态时把历史挂好（不主动弹浮层，等用户点输入框再展开）；
                // 刚提交过的词已经被记进历史，这里重新挂一次能立刻带上新词
                if (keyword.isNotEmpty()) {
                    attachHistoryAdapter(show = false)
                }
                renderState()
            }
        }

        lifecycleScope.launch {
            viewModel.error.collect { error ->
                if (error == null) {
                    footerFailed = false
                } else {
                    Log.e(TAG, "短剧列表请求失败: $error")
                    // 列表里还有内容 = 翻页失败，交给 footer 的「点我重试」；
                    // 首屏失败（列表为空）由空态 + Toast 提示，footer 不重复占位
                    if (viewModel.dramas.value.isNotEmpty()) {
                        appendRequested = false
                        footerFailed = true
                    }
                    Toast.makeText(this@ShortDramaActivity, error, Toast.LENGTH_SHORT).show()
                }
                renderState()
            }
        }
    }

    /**
     * 骨架用的极简适配器：固定 N 个占位 item，不进 ListAdapter。
     * 放在本文件里是因为骨架只有这一个页面用，不值得单开一个文件。
     *
     * 构造参数叫 count 而不是 itemCount：`private val itemCount` 会生成 getItemCount() 访问器，
     * 和覆写的 getItemCount() 同 JVM 签名，Kotlin 会报 platform declaration clash。
     */
    private class SkeletonAdapter(private val count: Int) :
        RecyclerView.Adapter<SkeletonAdapter.Holder>() {

        class Holder(view: View) : RecyclerView.ViewHolder(view)

        override fun getItemCount(): Int = count

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_short_drama_skeleton, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            // 占位块，无内容可绑
        }
    }
}