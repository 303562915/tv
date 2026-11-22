package com.tv.ball.presentation.search

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.inputmethod.EditorInfo
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.tv.ball.databinding.ActivitySearchBinding
import com.tv.ball.feature.f044.F044Engine
import com.tv.ball.feature.f046.F046Engine
import com.tv.ball.feature.f050.F050Engine
import com.tv.ball.presentation.detail.VideoDetailActivity
import com.tv.ball.presentation.home.VideoListAdapter
import com.tv.ball.presentation.ranking.RankingAdapter
import com.tv.ball.data.local.SearchHistoryManager
import com.tv.ball.util.CustomToast
import com.tv.ball.util.HotSearchManager
import kotlinx.coroutines.launch

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private val viewModel: SearchViewModel by lazy {
        ViewModelProvider(this)[SearchViewModel::class.java]
    }
    private lateinit var videoAdapter: VideoListAdapter
    private lateinit var rankingAdapter: RankingAdapter
    private var isRankingMode = true

    /** 榜单全量（接口一次吐完，没有分页参数）；下面两个变量是本地分批展示的游标 */
    private var allRankings: List<com.tv.ball.presentation.ranking.RankingItem> = emptyList()
    private var rankingShownCount = 0

    /** 追加下一页的防抖：submitList 还没落地时不重复追加 */
    private var isLoadingMoreRanking = false

    /** 输入防抖：连续敲键只发最后一次请求。 */
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null

    /** 程序化改搜索框文本（点历史 chip / 榜单项）时置位，避免又触发一次防抖。 */
    private var programmaticTextChange = false

    /**
     * 上一次真正下发的关键词。
     *
     * 空态里「点击重试」要用它：那时输入框里的文本可能已经被用户改掉了，
     * 重试的必须是"刚才失败的那次"而不是半截新输入。
     */
    private var lastSubmittedQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化搜索历史管理器
        SearchHistoryManager.init(this)

        // 设置透明沉浸式状态栏
        com.tv.ball.utils.StatusBarUtils.setupTransparentStatusBar(this)

        setupRecyclerView()
        setupSearchInput()
        setupSearchButtonColor()
        setupTabs()
        setupBackButton()
        setupBackPressHandler()
        setupSearchHistory()
        observeViewModel()
        
        // 默认显示榜单模式
        switchToRankingMode()
        
        // 默认加载榜单
        viewModel.loadRanking()
    }

    private fun setupRecyclerView() {
        // 视频搜索结果适配器
        videoAdapter = VideoListAdapter { video ->
            val intent = Intent(this, VideoDetailActivity::class.java).apply {
                putExtra("DETAIL_URL", video.detailUrl)
                putExtra("COVER_URL", video.coverUrl)
                putExtra("SOURCE_KEY", video.sourceKey) // 传递来源信息
            }
            startActivity(intent)
        }
        
        // 榜单适配器
        rankingAdapter = RankingAdapter { rankingItem ->
            // 点击榜单项，搜索该视频
            setSearchText(rankingItem.title)
            performSearch()
        }

        setupRankingPaging()
    }

    /**
     * 榜单分页：接口 api.52vmy.cn/api/wl/top/tv 没有 page/size 参数，一次就把全量榜单吐回来，
     * 所以做本地分批展示——先给 30 条，滚到底再追加 30 条。
     * 一屏直接绑几十上百条 item 会同时触发几十次封面请求，滚动也更容易掉帧。
     */
    private fun setupRankingPaging() {
        binding.rvSearchResults.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                appendNextRankingPageIfNeeded()
            }
        })
    }

    /** 滚到「已展示的最后一条」时追加下一页；防抖靠 isLoadingMoreRanking，到底后自然停 */
    private fun appendNextRankingPageIfNeeded() {
        if (!isRankingMode || isLoadingMoreRanking) return
        if (allRankings.size <= rankingShownCount) return
        val layoutManager = binding.rvSearchResults.layoutManager as? LinearLayoutManager ?: return
        if (layoutManager.findLastVisibleItemPosition() < rankingShownCount - 1) return

        isLoadingMoreRanking = true
        rankingShownCount = minOf(rankingShownCount + RANKING_PAGE_SIZE, allRankings.size)
        rankingAdapter.submitList(allRankings.take(rankingShownCount)) {
            isLoadingMoreRanking = false
            // 新的一页如果不满一屏就不会再有 onScrolled，这里补一次检查，接着续下一页
            binding.rvSearchResults.post { appendNextRankingPageIfNeeded() }
        }
    }

    private fun setupSearchInput() {
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else {
                false
            }
        }

        // 边敲边搜：300ms 内的连续输入只发一次，旧请求由 ViewModel 取消
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (programmaticTextChange) return
                scheduleSearch(s?.toString().orEmpty())
            }
        })

        binding.btnSearch.setOnClickListener {
            performSearch()
        }
        
        binding.btnVoiceSearch.setOnClickListener {
            startVoiceSearch()
        }
        
        binding.btnClearHistory.setOnClickListener {
            clearAllHistory()
        }
    }

    private fun setupSearchButtonColor() {
        // 搜索按钮 = 这一屏唯一的主操作，由布局的 bg_primary_button 承载；
        // 这里只把下划线指示器对齐 primary token
        binding.tabIndicator.setBackgroundColor(
            androidx.core.content.ContextCompat.getColor(this, com.tv.ball.R.color.theme_primary)
        )
    }
    
    private fun setupSearchHistory() {
        updateSearchHistory()
    }
    
    private fun updateSearchHistory() {
        val history = SearchHistoryManager.getSearchHistory()
        binding.chipGroupHistory.removeAllViews()
        
        if (history.isEmpty()) {
            binding.historySection.visibility = View.GONE
        } else {
            binding.historySection.visibility = View.VISIBLE
            
            history.forEach { query ->
                val chip = Chip(this).apply {
                    text = query
                    isClickable = true
                    isCheckable = false
                    setOnClickListener {
                        setSearchText(query)
                        performSearch()
                    }
                    // 长按删这一条：历史里点一下就搜、长按就删，比给每个 chip 加关闭按钮干净
                    setOnLongClickListener {
                        removeHistoryEntry(query)
                        true
                    }
                }
                binding.chipGroupHistory.addView(chip)
            }
        }
    }

    /**
     * 删一条搜索历史。
     *
     * SearchHistoryManager 对外只有「整段读 / 整段清 / 单条加到最前」三个口子，
     * 这次不改它（不在本次可改的文件范围内），所以用现成 API 重建列表：
     * 倒序回填后顺序与删除前一致，也不去碰 SharedPreferences 的内部格式。
     * 历史最多 10 条，重建的开销可以忽略。
     */
    private fun removeHistoryEntry(keyword: String) {
        val remaining = SearchHistoryManager.getSearchHistory().filter { it != keyword }
        SearchHistoryManager.clearSearchHistory()
        remaining.asReversed().forEach { SearchHistoryManager.addSearchHistory(it) }
        updateSearchHistory()
        CustomToast.show(this, "已删除「$keyword」")
    }

    /** 清空全部搜索历史：整段清 + 收起历史区 + 提示。 */
    private fun clearAllHistory() {
        SearchHistoryManager.clearSearchHistory()
        updateSearchHistory()
        CustomToast.show(this, "已清空搜索历史")
    }
    
    private fun setupTabs() {
        // 只有榜单Tab，点击无需切换
        binding.tabRanking.setOnClickListener {
            // 已经在榜单模式，无需操作
        }

        // 默认选中榜单
        binding.tabRanking.setTextColor(
            androidx.core.content.ContextCompat.getColor(this, com.tv.ball.R.color.theme_text_primary)
        )
        binding.tabRanking.paint.isFakeBoldText = true
        binding.tabRanking.invalidate()
    }
    
    private fun switchToRankingMode() {
        binding.rvSearchResults.layoutManager = LinearLayoutManager(this)
        binding.rvSearchResults.adapter = rankingAdapter
    }
    
    private fun switchToSearchMode() {
        binding.rvSearchResults.layoutManager = GridLayoutManager(this, 3)
        binding.rvSearchResults.adapter = videoAdapter
    }
    
    private fun setupBackButton() {
        binding.btnBack.setOnClickListener {
            // 和系统返回键同一套逻辑：下拉展开时这一下先收下拉，收起之后再按才退出
            if (isSuggestionVisible()) {
                hideSuggestions()
            } else {
                finish()
            }
        }
    }

    /**
     * 系统返回键：下拉展开时先收起下拉，再按一次才退出页面。
     *
     * 走 [OnBackPressedDispatcher]（`onBackPressed()` 已废弃），和首页 / f005 的写法一致；
     * 回调挂在 Activity 生命周期上，销毁时自动移除，不会重复注册。
     */
    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSuggestionVisible()) {
                    hideSuggestions()
                    return
                }
                // 下拉已经收起：先把自己摘掉再转交，免得同一次返回又被这个回调拦一遍
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    /** IME / 搜索按钮 / tab chip 这类明确动作：取消排队中的防抖，立刻搜一次。 */
    private fun performSearch() {
        val query = binding.etSearch.text.toString().trim()
        if (query.isEmpty()) return
        cancelPendingSearch()
        // 明确动作代表关键词已经定了，下拉没有继续挂着的必要，先收起再搜
        hideSuggestions()
        // 明确动作（IME / 按钮 / chip）才记搜索历史；边敲边搜只出结果，不往历史里塞半截词
        submitSearch(query, recordHistory = true)
    }

    /**
     * 输入防抖：每次输入先取消上一次的排队任务；清空输入时连在飞的请求一起停掉，
     * 避免旧结果回填到空输入上。
     */
    private fun scheduleSearch(raw: String) {
        cancelPendingSearch()
        val query = raw.trim()
        if (query.isEmpty()) {
            viewModel.cancelActiveSearch()
            // 输入被清空：没有"上一次关键词"可重试，空态一起收起来，别留一个点了没反应的提示
            lastSubmittedQuery = ""
            hideNoResultsState()
            // 下拉也一起收起：清空输入后还挂着一个浮层会盖住榜单
            hideSuggestions()
            return
        }
        val task = Runnable {
            pendingSearch = null
            // 联想复用这一个 300ms 窗口：用户还在敲的时候一条都不算，
            // 停下来才一起出候选和搜索结果，按键延迟和以前完全一样
            refreshSuggestions(query)
            submitSearch(query, recordHistory = false)
        }
        pendingSearch = task
        debounceHandler.postDelayed(task, SEARCH_DEBOUNCE_MS)
    }

    private fun cancelPendingSearch() {
        pendingSearch?.let { debounceHandler.removeCallbacks(it) }
        pendingSearch = null
    }

    /** 真正下发一次搜索：切到结果模式后交给 ViewModel（新查询会取消上一次）。 */
    private fun submitSearch(query: String, recordHistory: Boolean) {
        if (recordHistory) {
            // 保存搜索历史
            SearchHistoryManager.addSearchHistory(query)
            updateSearchHistory()
        }

        // 隐藏Tab和历史，只显示搜索结果
        binding.tabLayout.visibility = View.GONE
        binding.historySection.visibility = View.GONE

        // 切换到搜索模式
        if (isRankingMode) {
            isRankingMode = false
            switchToSearchMode()
        }

        // 新的一次搜索：先收起上一轮的空态（可能是「搜索失败」，也可能只是「没找到」），
        // 否则新结果回来之前屏上还挂着上一次的提示
        lastSubmittedQuery = query
        hideNoResultsState()

        // 切换高亮关键词要在 submitList 之前落好：结果回来时 onBind 读的就是当前这次的词
        videoAdapter.highlightQuery = query

        // 使用多站点搜索
        viewModel.searchMultiSites(query)
    }

    /** 程序化填入搜索框（点历史 chip / 榜单项）：不触发防抖，由调用方立刻 performSearch。 */
    private fun setSearchText(query: String) {
        programmaticTextChange = true
        binding.etSearch.setText(query)
        binding.etSearch.setSelection(binding.etSearch.text?.length ?: 0)
        programmaticTextChange = false
    }

    // ===== 输入联想下拉 =====

    /**
     * 计算并渲染联想候选。
     *
     * 只在现有 300ms 防抖窗口里被调用，不另起一套计时器：按键顺序和以前完全一致，
     * 用户还在敲的时候一次都不算，停下来才跟搜索一起出候选。
     *
     * 候选池全部是本地的——历史、热词、当前榜单标题，不发任何网络请求。
     * 空输入这里直接收起下拉：页面本来就有平铺的历史区（historySection），
     * 再弹一个只含历史的浮层等于把同一份信息压两遍，而且会盖住榜单；
     * 用户想看历史时抬眼就是，不需要额外一层。
     */
    private fun refreshSuggestions(raw: String) {
        val query = raw.trim()
        if (query.isEmpty()) {
            hideSuggestions()
            return
        }

        val history = SearchHistoryManager.getSearchHistory()
        val titles = suggestionTitles()

        // 先拿字面候选：f044 已经内置「前缀优先 / 历史优先 / 去重 / 上限截断」
        val merged = ArrayList<F044Engine.Suggestion>(F044Engine.DEFAULT_LIMIT)
        val seen = HashSet<String>(F044Engine.DEFAULT_LIMIT * 4)
        F044Engine.suggest(query, history, titles, F044Engine.DEFAULT_LIMIT).forEach { item ->
            val key = item.text.trim().lowercase()
            if (key.isEmpty()) return@forEach
            if (seen.add(key)) merged.add(item)
        }

        // 拼音补位：f044 只认字面，用户打 lsh / lanse 时它一条都给不出来。
        // 只在查询是纯 ASCII 字母时补——带数字或空格的多半是别的东西，硬套首字母只会多出噪音。
        if (PINYIN_QUERY.matches(query)) {
            for (ranked in F046Engine.rank(titles, query)) {
                if (merged.size >= F044Engine.DEFAULT_LIMIT) break
                if (ranked.kind == F046Engine.MatchKind.NONE) continue
                val text = ranked.title.trim()
                if (text.isEmpty()) continue
                // 拼音命中一律记成「标题」：它们按定义都来自标题库
                if (seen.add(text.lowercase())) {
                    merged.add(F044Engine.Suggestion(text, F044Engine.Kind.TITLE))
                }
            }
        }

        renderSuggestions(merged.take(F044Engine.DEFAULT_LIMIT))
    }

    /**
     * 候选的标题侧池子：榜单标题在前，热词在后。
     *
     * 榜单是这一屏正在展示的真实内容，同排名时先给它更贴近用户眼前的上下文；
     * 热词只用来补面。收藏标题没进来——搜索页手上没有收藏的内存缓存，
     * 为了一句联想在每次按键后往数据库跑一趟不划算，所以直接跳过。
     */
    private fun suggestionTitles(): List<String> {
        val hotWords = HotSearchManager.getHotSearchWords()
        if (allRankings.isEmpty()) return hotWords
        val titles = ArrayList<String>(allRankings.size + hotWords.size)
        allRankings.forEach { item ->
            val title = item.title.trim()
            if (title.isNotEmpty()) titles.add(title)
        }
        titles.addAll(hotWords)
        return titles
    }

    /** 把候选画进下拉。行数很少（最多 DEFAULT_LIMIT 条），动态 LinearLayout 比配一个适配器简单。 */
    private fun renderSuggestions(suggestions: List<F044Engine.Suggestion>) {
        binding.suggestionPanel.removeAllViews()
        if (suggestions.isEmpty()) {
            hideSuggestions()
            return
        }

        val rows = ArrayList<View>(suggestions.size)
        suggestions.forEach { suggestion ->
            val row = buildSuggestionRow(suggestion)
            binding.suggestionPanel.addView(row)
            rows.add(row)
        }

        // 遥控器串焦：输入框按「下」进第一条，行间上下串联，最后一条按「下」落回结果列表。
        // 行的 id 是运行时生成的（行由代码创建，没有对应的布局文件），只能在这里接线。
        binding.etSearch.nextFocusDownId = rows.first().id
        rows.forEachIndexed { index, row ->
            row.nextFocusUpId = if (index == 0) binding.etSearch.id else rows[index - 1].id
            row.nextFocusDownId = if (index == rows.lastIndex) {
                binding.rvSearchResults.id
            } else {
                rows[index + 1].id
            }
        }

        binding.cardSuggestions.visibility = View.VISIBLE
    }

    /**
     * 造一行候选：左边是候选原文，右边是来源角标（历史 / 标题）。
     *
     * 行是运行时生成的，样式只能在代码里落：行高 52dp 给遥控器留够焦点区（下限是 48dp），
     * 背景取主题里那个 selectableItemBackground（和布局里其它可点元素同一套按压反馈），
     * 焦点描边直接复用仓库现有的 bg_focus_ring，不新增任何 drawable。
     */
    private fun buildSuggestionRow(suggestion: F044Engine.Suggestion): View {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (SUGGESTION_ROW_HEIGHT_DP * density).toInt()
            )
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
            isFocusable = true
            isClickable = true
        }

        // 布局里那句 selectableItemBackground 只能写在 XML 里，行是代码建的，
        // 所以按同一个 attr 从主题里取一次；取到的是空 id 时等于不设背景，点击照旧可用
        val background = TypedValue()
        if (theme.resolveAttribute(android.R.attr.selectableItemBackground, background, true)) {
            row.setBackgroundResource(background.resourceId)
        }
        row.foreground = ContextCompat.getDrawable(this, com.tv.ball.R.drawable.bg_focus_ring)

        val label = TextView(this).apply {
            text = suggestion.text
            setTextColor(
                ContextCompat.getColor(this@SearchActivity, com.tv.ball.R.color.theme_text_primary)
            )
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val badge = TextView(this).apply {
            // 角标文案在这里就地落下：引擎只回 history / title 这种键，翻译归界面
            text = when (suggestion.kind) {
                F044Engine.Kind.HISTORY -> "历史"
                F044Engine.Kind.TITLE -> "标题"
            }
            setTextColor(
                ContextCompat.getColor(this@SearchActivity, com.tv.ball.R.color.theme_text_secondary)
            )
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (12 * density).toInt() }
        }

        row.addView(label)
        row.addView(badge)
        row.setOnClickListener { applySuggestion(suggestion.text) }
        return row
    }

    /**
     * 选中一条候选：回填输入框并立刻搜一次。
     *
     * 走 [performSearch] 就是复用 [submitSearch] 的 recordHistory = true 那条路——
     * 用户明确点过的词和外点历史 chip 一样，值得进历史。
     */
    private fun applySuggestion(text: String) {
        hideSuggestions()
        setSearchText(text)
        performSearch()
    }

    /** 下拉是否正展开。 */
    private fun isSuggestionVisible(): Boolean =
        binding.cardSuggestions.visibility == View.VISIBLE

    /**
     * 收起下拉。
     *
     * 如果焦点正落在某一行上，把焦点交回输入框：电视上焦点不能掉进已隐藏的视图里，
     * 否则遥控器会失去落点，下一按没有任何反应。
     */
    private fun hideSuggestions() {
        binding.cardSuggestions.visibility = View.GONE
        // 行都不显示了，输入框按「下」不能再指向它们，否则遥控器会按到一个没有落点的地方
        binding.etSearch.nextFocusDownId = View.NO_ID
        val focused = currentFocus
        if (focused != null && focused.parent === binding.suggestionPanel) {
            binding.etSearch.requestFocus()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.searchResults.collect { results ->
                videoAdapter.submitList(results)
                if (!isRankingMode) {
                    renderSearchResultState(results)

                    // 预加载搜索结果的图片
                    if (results.isNotEmpty()) {
                        preloadSearchImages(results)
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.rankingList.collect { rankings ->
                // 换了一份榜单：游标归零，重新从第一页开始展示
                allRankings = rankings
                rankingShownCount = minOf(RANKING_PAGE_SIZE, rankings.size)
                isLoadingMoreRanking = false
                rankingAdapter.submitList(rankings.take(rankingShownCount))
                if (isRankingMode) {
                    if (rankings.isEmpty()) {
                        showNoResultsState("榜单暂时没有内容", retryable = false)
                    } else {
                        hideNoResultsState()
                    }
                    // 首批不满一屏时不会触发滚动，补一次检查把后面的页续上
                    binding.rvSearchResults.post { appendNextRankingPageIfNeeded() }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            }
        }
    }

    // ===== 空态：分「没有找到」和「请求失败」两种 =====

    /**
     * 搜索结果为空时，决定屏上那句提示到底是哪一种。
     *
     * 之所以要分两种：以前两种情况共用「暂无搜索结果」，用户看到"什么都没搜到"会以为片名打错了，
     * 实际可能是一次失败的请求，点一下重试就好了。
     *
     * 判断依据只能取**视图层能拿到的信号**：`SearchViewModel` 只对外暴露 `List<VideoItem>`
     * 和 `isLoading`，而且 `VideoRepository.searchMultiSites` 在内部把每个源抛出的异常都吞成了
     * 空列表（见它的 catch 分支），所以"全部源失败"和"全部源 0 命中"在这一层是同一种结果。
     * 视图层能可靠判定的失败信号只有"网络不可用"——没网时请求一定没成，
     * 这时候给可点击的重试才有意义；有网但空结果按"真的没搜到"展示。
     */
    private fun renderSearchResultState(results: List<com.tv.ball.presentation.home.VideoItem>) {
        if (results.isNotEmpty()) {
            hideNoResultsState()
            return
        }
        // 还没搜过（首次进页面 StateFlow 的初始空值）：不留空态，交给榜单/正常流程
        if (lastSubmittedQuery.isEmpty()) {
            hideNoResultsState()
            return
        }

        if (hasUsableNetwork()) {
            // 有网但 0 命中：多半是片名记错了，顺手从历史和热词里挑一个最像的补一句
            showNoResultsState(
                "没有找到相关内容",
                retryable = false,
                fuzzy = findFuzzyGuess(lastSubmittedQuery)
            )
        } else {
            showNoResultsState("搜索失败，点击重试", retryable = true)
        }
    }

    /**
     * 空态用的那个文本节点。
     *
     * 布局里 tvNoResults 是个「图标 + 一句说明」的 LinearLayout（TextView 没有自己的 id），
     * 所以这里按类型取第一个 TextView，不去依赖 childAt(1) 这种位置约定。
     */
    private fun noResultsLabel(): TextView? {
        val group = binding.tvNoResults
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child is TextView) return child
        }
        return null
    }

    /**
     * 从「历史 + 热词」里找一条和当前关键词最像的词，用作空态的第二句提示。
     *
     * 只吃这两个池子：都常驻内存、加起来十几条，而空态是要立刻画出来的，
     * 不能为了一句提示去翻整份榜单。找不到、或者找到的就是原词本身
     * （归一化后完全一致）都返回 null——那种情况下补一句「是不是要搜自己」纯属噪音。
     */
    private fun findFuzzyGuess(query: String): String? {
        if (query.isBlank()) return null
        val pool = LinkedHashSet<String>()
        pool.addAll(SearchHistoryManager.getSearchHistory())
        pool.addAll(HotSearchManager.getHotSearchWords())
        if (pool.isEmpty()) return null
        val best = F050Engine.rank(pool.toList(), query, FUZZY_TOLERANCE).firstOrNull() ?: return null
        if (best.reasonKey == F050Engine.REASON_EXACT) return null
        return best.title
    }

    /** 点空态里那句近似提示：换成那个词再搜一次（和候选行一样算明确动作，记历史）。 */
    private fun searchFuzzySuggestion(word: String) {
        setSearchText(word)
        performSearch()
    }

    /**
     * 显示空态。
     *
     * [retryable] = true 时整块可点、可聚焦（遥控器能走到），文字用主色以示"这里能操作"；
     * false 时只做提示，不抢焦点。
     *
     * [fuzzy] 非空时会在原文案后面补一句「，是不是要搜「XXX」？」，整块提示改成点一下直接搜那个词；
     * 这一档的优先级高于 [retryable]——用户真正想要的是那条更接近的结果，不是重发同一条查询。
     */
    private fun showNoResultsState(text: String, retryable: Boolean, fuzzy: String? = null) {
        val action: View.OnClickListener? = when {
            fuzzy != null -> View.OnClickListener { searchFuzzySuggestion(fuzzy) }
            retryable -> View.OnClickListener { retryLastSearch() }
            else -> null
        }
        val actionable = action != null

        noResultsLabel()?.let { label ->
            label.text = if (fuzzy != null) "$text，是不是要搜「$fuzzy」？" else text
            label.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (actionable) com.tv.ball.R.color.theme_primary else com.tv.ball.R.color.theme_text_secondary
                )
            )
        }

        binding.tvNoResults.setOnClickListener(action)
        binding.tvNoResults.isClickable = actionable
        binding.tvNoResults.isFocusable = actionable
        binding.tvNoResults.visibility = View.VISIBLE

        if (actionable) {
            // 结果区已经空了，焦点直接落在这一行上，遥控器按一下就能动。
            // 但用户还在输入框里敲字时（联想把一句空结果带回来的情况）不抢焦点：
            // 抢走之后接着输入就没有落点了，键盘也会被顶掉。
            val typing = currentFocus === binding.etSearch
            if (retryable || !typing) binding.tvNoResults.requestFocus()
        }
    }

    private fun hideNoResultsState() {
        binding.tvNoResults.visibility = View.GONE
    }

    /** 重试上一次真正下发的关键词（不是输入框里可能已被改动的半截文本）。 */
    private fun retryLastSearch() {
        val query = lastSubmittedQuery
        if (query.isEmpty()) return
        setSearchText(query)
        performSearch()
    }

    /**
     * 当前网络是否可用。
     *
     * 只用来区分「请求失败」和「确实没搜到」：拿不到 ConnectivityManager 时按"可用"处理，
     * 宁可显示"没有找到相关内容"，也不要在能联网的机器上误报失败。
     */
    private fun hasUsableNetwork(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ===== 语音搜索 =====

    /**
     * 语音搜索：交给系统的语音识别界面（[RecognizerIntent.ACTION_RECOGNIZE_SPEECH]），
     * 识别结果回到 [onActivityResult] 里当作关键词搜一次。
     *
     * 这样不需要本应用持有录音权限（录音发生在系统识别器里），也不用自己接麦克风。
     * 电视盒子经常没有可用的识别器，那种情况只提示一句，不崩。
     */
    private fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说出想搜的片名")
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_VOICE_SEARCH)
        } catch (e: ActivityNotFoundException) {
            CustomToast.show(this, "本机没有语音识别服务")
        }
    }

    /**
     * 语音识别结果回来：取第一候选当作关键词，直接搜一次。
     *
     * 用 `startActivityForResult` 而不是 ActivityResultLauncher，是因为这个页面既有的
     * 回调风格就是 requestCode 分支（见布局里其它入口），新增一个无需为一行结果引入契约类。
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VOICE_SEARCH || resultCode != RESULT_OK) return

        val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (spoken.isEmpty()) {
            CustomToast.show(this, "没有听清，请再说一次")
            return
        }
        setSearchText(spoken)
        performSearch()
    }

    /**
     * 预加载搜索结果图片 - 提升滚动流畅度
     */
    private fun preloadSearchImages(videos: List<com.tv.ball.presentation.home.VideoItem>) {
        val imageUrls = videos
            .take(30) // 预加载前30个
            .mapNotNull { it.coverUrl }
            .filter { it.isNotEmpty() }

        if (imageUrls.isEmpty()) return

        com.tv.ball.util.ImageLoader.preloadImages(
            context = this,
            urls = imageUrls,
            maxConcurrent = 6 // 6个并发
        ) { current, total ->
            android.util.Log.d("SearchActivity", "预加载进度: $current/$total")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 页面退出：排队中的防抖任务与在飞的搜索一起停掉，别让回调打到已销毁的视图上
        cancelPendingSearch()
        debounceHandler.removeCallbacksAndMessages(null)
        viewModel.cancelActiveSearch()
    }

    private companion object {
        /** 输入防抖窗口：连续敲键后 300ms 才真正发请求。 */
        const val SEARCH_DEBOUNCE_MS = 300L

        /** 榜单本地分批的大小：一次展示 / 追加 30 条。 */
        const val RANKING_PAGE_SIZE = 30

        /** 语音识别的请求码：结果在 onActivityResult 里取。 */
        const val REQUEST_VOICE_SEARCH = 9001

        /** 联想行高：遥控器焦点区要够大，48dp 是下限，这里给 52dp。 */
        const val SUGGESTION_ROW_HEIGHT_DP = 52

        /**
         * 拼音补位的触发条件：整串都是 ASCII 字母。
         *
         * 带数字、空格或中文的查询不补——那些交给 f044 的字面匹配就够了，
         * 硬把数字串当首字母去匹配只会多出噪音。
         */
        val PINYIN_QUERY = Regex("^[a-zA-Z]+$")

        /** 近似词的容错级别：2 = 错一个字、漏一个字都还算像。 */
        const val FUZZY_TOLERANCE = 2
    }
}
