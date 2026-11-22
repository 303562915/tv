package com.tv.ball.presentation.search

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.tv.ball.databinding.ActivitySearchBinding
import com.tv.ball.presentation.detail.VideoDetailActivity
import com.tv.ball.presentation.home.VideoListAdapter
import com.tv.ball.presentation.ranking.RankingAdapter
import com.tv.ball.data.local.SearchHistoryManager
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
            // TODO: 实现语音搜索
            android.widget.Toast.makeText(this, "语音搜索功能开发中", android.widget.Toast.LENGTH_SHORT).show()
        }
        
        binding.btnClearHistory.setOnClickListener {
            SearchHistoryManager.clearSearchHistory()
            updateSearchHistory()
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
                }
                binding.chipGroupHistory.addView(chip)
            }
        }
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
            finish()
        }
    }

    /** IME / 搜索按钮 / tab chip 这类明确动作：取消排队中的防抖，立刻搜一次。 */
    private fun performSearch() {
        val query = binding.etSearch.text.toString().trim()
        if (query.isEmpty()) return
        cancelPendingSearch()
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
            return
        }
        val task = Runnable {
            pendingSearch = null
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

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.searchResults.collect { results ->
                videoAdapter.submitList(results)
                if (!isRankingMode) {
                    binding.tvNoResults.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE

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
                    binding.tvNoResults.visibility = if (rankings.isEmpty()) View.VISIBLE else View.GONE
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
    }
}
