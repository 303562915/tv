package com.tv.ball.presentation.search

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
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
            binding.etSearch.setText(rankingItem.title)
            performSearch()
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
        // 使用 ThemeHelper 统一设置主题色
        com.tv.ball.util.ThemeHelper.setThemeBackground(binding.btnSearch)

        // 设置Tab指示器颜色
        binding.tabIndicator.setBackgroundColor(com.tv.ball.util.ThemeHelper.getThemeColor())
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
                        binding.etSearch.setText(query)
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

        // 默认选中榜单，使用 ThemeHelper 设置颜色
        com.tv.ball.util.ThemeHelper.setThemeTextColor(binding.tabRanking)
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

    private fun performSearch() {
        val query = binding.etSearch.text.toString().trim()
        if (query.isNotEmpty()) {
            // 保存搜索历史
            SearchHistoryManager.addSearchHistory(query)
            updateSearchHistory()
            
            // 隐藏Tab和历史，只显示搜索结果
            binding.tabLayout.visibility = View.GONE
            binding.historySection.visibility = View.GONE
            
            // 切换到搜索模式
            if (isRankingMode) {
                isRankingMode = false
                switchToSearchMode()
            }
            
            // 使用多站点搜索
            viewModel.searchMultiSites(query)
        }
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
                rankingAdapter.submitList(rankings)
                if (isRankingMode) {
                    binding.tvNoResults.visibility = if (rankings.isEmpty()) View.VISIBLE else View.GONE
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
}
