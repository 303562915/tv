package com.tv.ball.presentation.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.tv.ball.R
import com.tv.ball.databinding.FragmentHomeBinding
import com.tv.ball.presentation.detail.VideoDetailActivity
import com.tv.ball.util.ImageLoader
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by lazy {
        ViewModelProvider(this)[HomeViewModel::class.java]
    }
    private lateinit var pagerAdapter: CategoryPagerAdapter
    private lateinit var bannerAdapter: BannerAdapter
    private val bannerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var currentBannerPosition = 0
    private var isBannerCollapsed = false // 轮播图是否收起
    private val BANNER_EXPANDED_HEIGHT = 180 // dp
    private val BANNER_COLLAPSED_HEIGHT = 60 // dp

    private val categories = listOf(
        Category("国产剧", 6),
        Category("日韩剧", 8),
        Category("电影", 1),
        Category("剧集", 2),
        Category("动漫", 4),
        Category("综艺", 3)
    )
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

//        setupBanner()
//        setupBannerToggle()
        setupSearchButton()
        setupViewPager()
        setupTabs()
        setupSwipeRefresh()
        observeViewModel()

        viewModel.loadVideos(categories[0].id, refresh = true)
    }
    
//    private fun setupBanner() {
//        // 使用本地占位图，避免网络图片加载失败
//        val defaultBannerData = listOf(
//            BannerItem(
//                imageUrl = "", // 使用空字符串，将显示占位图
//                linkUrl = "",
//                description = "精彩影视推荐",
//                visible = true
//            ),
//            BannerItem(
//                imageUrl = "", // 使用空字符串，将显示占位图
//                linkUrl = "",
//                description = "热门电影",
//                visible = true
//            ),
//            BannerItem(
//                imageUrl = "", // 使用空字符串，将显示占位图
//                linkUrl = "",
//                description = "最新剧集",
//                visible = true
//            )
//        )
//        
//        // 预加载轮播图
//        preloadBannerImages(defaultBannerData)
//        
//        bannerAdapter = BannerAdapter(defaultBannerData)
//        
//        // 配置ViewPager2 - 立即设置，不等待布局
//        binding.bannerViewPager.apply {
//            adapter = bannerAdapter
//            offscreenPageLimit = 1
//            
//            // 设置初始位置到中间，实现无限循环效果
//            val startPosition = Int.MAX_VALUE / 2
//            setCurrentItem(startPosition - startPosition % defaultBannerData.size, false)
//            
//            // 添加页面切换监听，更新指示器
//            registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
//                override fun onPageSelected(position: Int) {
//                    updateIndicator(position % defaultBannerData.size)
//                }
//            })
//        }
//        
//        // 初始化指示器
//        setupIndicator(defaultBannerData.size)
//        
//        // 自动轮播
//        startBannerAutoScroll()
//        
//        // 异步加载云端数据（不阻塞主页面）
//        loadBannerDataFromCloud(defaultBannerData)
//    }
//    
//    /**
//     * 预加载轮播图图片
//     */
//    private fun preloadBannerImages(bannerData: List<BannerItem>) {
//        val imageUrls = bannerData.mapNotNull { it.imageUrl }.filter { it.isNotEmpty() }
//        if (imageUrls.isEmpty()) return
//        
//        ImageLoader.preloadImages(
//            context = requireContext(),
//            urls = imageUrls,
//            maxConcurrent = 3
//        )
//    }
//    
//    private fun setupIndicator(count: Int) {
//        binding.indicatorContainer.removeAllViews()
//        
//        // 完全照搬 Lua 的指示器样式
//        val indicatorRadius = (4 * resources.displayMetrics.density).toInt()
//        val indicatorSpacing = (10 * resources.displayMetrics.density).toInt()
//        
//        for (i in 0 until count) {
//            val indicator = View(requireContext()).apply {
//                layoutParams = android.widget.LinearLayout.LayoutParams(
//                    indicatorRadius * 2, 
//                    indicatorRadius * 2
//                ).apply {
//                    if (i > 0) marginStart = indicatorSpacing
//                }
//                // 创建圆形背景
//                background = android.graphics.drawable.GradientDrawable().apply {
//                    shape = android.graphics.drawable.GradientDrawable.OVAL
//                    setColor(if (i == 0) 0xFFFFFFFF.toInt() else 0x80FFFFFF.toInt())
//                }
//            }
//            binding.indicatorContainer.addView(indicator)
//        }
//    }
//    
//    private fun updateIndicator(position: Int) {
//        for (i in 0 until binding.indicatorContainer.childCount) {
//            val indicator = binding.indicatorContainer.getChildAt(i)
//            (indicator.background as? android.graphics.drawable.GradientDrawable)?.setColor(
//                if (i == position) 0xFFFFFFFF.toInt() else 0x80FFFFFF.toInt()
//            )
//        }
//    }
//    
//    private fun loadBannerDataFromCloud(defaultData: List<BannerItem>) {
//        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
//            try {
//                android.util.Log.d("HomeFragment", "开始加载云端轮播图数据...")
//                
//                val client = okhttp3.OkHttpClient.Builder()
//                    .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
//                    .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
//                    .build()
//                    
//                val request = okhttp3.Request.Builder()
//                    .url("https://your-server-domain.com/app/api/banner_json.php")
//                    .build()
//                
//                val response = client.newCall(request).execute()
//                android.util.Log.d("HomeFragment", "云端响应状态: ${response.isSuccessful}, code: ${response.code}")
//                
//                if (response.isSuccessful) {
//                    val jsonString = response.body?.string()
//                    android.util.Log.d("HomeFragment", "云端数据长度: ${jsonString?.length}")
//                    
//                    if (!jsonString.isNullOrEmpty()) {
//                        val bannerItems = parseBannerJson(jsonString)
//                        android.util.Log.d("HomeFragment", "解析到 ${bannerItems.size} 个轮播图项")
//                        
//                        if (bannerItems.isNotEmpty()) {
//                            // 切换到主线程更新 UI
//                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
//                                // 预加载新的轮播图
//                                preloadBannerImages(bannerItems)
//                                
//                                bannerAdapter.updateData(bannerItems)
//                                setupIndicator(bannerItems.size)
//                                android.util.Log.d("HomeFragment", "云端轮播图数据已更新")
//                            }
//                        }
//                    }
//                } else {
//                    android.util.Log.e("HomeFragment", "云端数据加载失败: ${response.code}")
//                }
//            } catch (e: Exception) {
//                android.util.Log.e("HomeFragment", "加载云端数据异常: ${e.message}")
//                // 静默失败，使用默认数据
//            }
//        }
//    }
//    
//    private fun parseBannerJson(jsonString: String): List<BannerItem> {
//        return try {
//            val items = mutableListOf<BannerItem>()
//            val jsonArray = org.json.JSONArray(jsonString)
//            android.util.Log.d("HomeFragment", "JSON 数组长度: ${jsonArray.length()}")
//            
//            for (i in 0 until jsonArray.length()) {
//                val obj = jsonArray.getJSONObject(i)
//                val id = obj.optInt("id", 0)
//                val imageUrl = obj.getString("图片链接")
//                val linkUrl = obj.optString("跳转链接", "")
//                val description = obj.optString("描述", "")
//                val visible = obj.optString("visble", "true") == "true"
//
//                android.util.Log.d("HomeFragment", "轮播图 $i: id=$id, url=$imageUrl, desc=$description, visible=$visible")
//
//                items.add(
//                    BannerItem(
//                        id = id,
//                        imageUrl = imageUrl,
//                        linkUrl = linkUrl,
//                        description = description,
//                        visible = visible
//                    )
//                )
//            }
//            items
//        } catch (e: Exception) {
//            android.util.Log.e("HomeFragment", "解析 JSON 失败: ${e.message}", e)
//            emptyList()
//        }
//    }
//    
//    private fun startBannerAutoScroll() {
//        val autoScrollRunnable = object : Runnable {
//            override fun run() {
//                if (_binding != null) {
//                    val nextItem = binding.bannerViewPager.currentItem + 1
//                    binding.bannerViewPager.setCurrentItem(nextItem, true)
//                    bannerHandler.postDelayed(this, 4000)
//                }
//            }
//        }
//        bannerHandler.postDelayed(autoScrollRunnable, 4000)
//    }
    
    private fun setupSearchButton() {
        binding.searchBox.setOnClickListener {
            // 检查当前视频源是否是可可影视
            val currentSource = com.tv.ball.data.VideoSourceManager.getCurrentSource()
            if (currentSource == "keke") {
                // 显示可可影视筛选对话框
                showKekeFilterDialog()
            } else {
                // 其他视频源跳转到搜索页面
                val intent = Intent(requireContext(), com.tv.ball.presentation.search.SearchActivity::class.java)
                startActivity(intent)
            }
        }
        
        binding.ivHistory.setOnClickListener {
            // 切换到历史页面（悬浮胶囊底栏）
            (activity as? com.tv.ball.presentation.MainActivity)?.switchToHistory()
        }
    }
    
    private fun showKekeFilterDialog() {
        val filterSheet = KekeFilterBottomSheet.newInstance()
        filterSheet.setOnFilterAppliedListener { type, area, year, lang ->
            // 应用筛选条件，刷新视频列表
            val currentCategory = categories.getOrNull(binding.tabLayout.selectedTabPosition)?.id ?: 6
            viewModel.loadVideos(
                category = currentCategory,
                refresh = true,
                type = type,
                area = area,
                year = year,
                lang = lang
            )
        }
        filterSheet.show(childFragmentManager, "KekeFilterBottomSheet")
    }
    
    private fun setupTabs() {
        // TabLayout 会通过 TabLayoutMediator 自动创建和管理
    }

    private fun setupViewPager() {
        // 创建 ViewPager 适配器
        pagerAdapter = CategoryPagerAdapter(categories, viewModel) { recyclerView, dx, dy ->
            // 处理滚动事件
            val layoutManager = recyclerView.layoutManager as? GridLayoutManager
            layoutManager?.let {
//                // 向下滚动时自动收起轮播图，向上滚动时展开
//                if (dy > 20 && !isBannerCollapsed) {
//                    collapseBanner()
//                } else if (dy < -20 && isBannerCollapsed && it.findFirstVisibleItemPosition() == 0) {
//                    expandBanner()
//                }
            }
        }

        binding.viewPager.adapter = pagerAdapter

        // 使用 TabLayoutMediator 连接 TabLayout 和 ViewPager2
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            val category = categories[position]
            tab.customView = buildTabView(category.name, position == binding.viewPager.currentItem)
        }.attach()

        // 监听页面切换，更新样式和加载数据
        binding.viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)

                // 更新 Tab 样式
                for (i in 0 until binding.tabLayout.tabCount) {
                    val tab = binding.tabLayout.getTabAt(i)
                    (tab?.customView as? TextView)?.let { styleTab(it, i == position) }
                }

                // 加载当前分类的数据
                val category = categories[position]
                viewModel.loadVideos(category.id, refresh = true)
            }
        })
    }

    private fun buildTabView(name: String, selected: Boolean): TextView {
        val density = resources.displayMetrics.density
        val padH = (4 * density).toInt()
        val padV = (6 * density).toInt()
        return TextView(requireContext()).apply {
            text = name
            gravity = android.view.Gravity.CENTER
            setPadding(padH, padV, padH, padV)
            styleTab(this, selected)
        }
    }

    /**
     * 选中 primary + 浅底胶囊，未选中 onSurfaceVariant，无下划线（FlClash 状态色只标选中项）
     */
    private fun styleTab(tabView: TextView, selected: Boolean) {
        val context = tabView.context
        tabView.setTextColor(
            context.getColor(if (selected) R.color.theme_primary else R.color.theme_text_secondary)
        )
        tabView.setTypeface(
            tabView.typeface,
            if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
        )
        tabView.textSize = if (selected) 20f else 16f
        tabView.background = if (selected) {
            android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = resources.getDimension(R.dimen.radius_full)
                setColor(context.getColor(R.color.surface_block))
            }
        } else {
            null
        }
    }

    private fun setupRecyclerView() {
        // 已被 setupViewPager 替代
    }
    
//    private fun setupBannerToggle() {
//        // 点击轮播图容器切换展开/收起状态
//        binding.bannerContainer.setOnClickListener {
//            if (isBannerCollapsed) {
//                expandBanner()
//            } else {
//                collapseBanner()
//            }
//        }
//    }
//    
//    private fun collapseBanner() {
//        if (isBannerCollapsed) return
//        
//        isBannerCollapsed = true
//        val heightInPx = (BANNER_COLLAPSED_HEIGHT * resources.displayMetrics.density).toInt()
//        
//        // 使用动画平滑过渡
//        val animator = android.animation.ValueAnimator.ofInt(
//            binding.bannerContainer.height,
//            heightInPx
//        )
//        animator.addUpdateListener { animation ->
//            val params = binding.bannerContainer.layoutParams
//            params.height = animation.animatedValue as Int
//            binding.bannerContainer.layoutParams = params
//        }
//        animator.duration = 300
//        animator.start()
//        
//        // 隐藏指示器
//        binding.indicatorContainer.animate()
//            .alpha(0f)
//            .setDuration(200)
//            .start()
//        
//        // 切换图标为向下箭头
//        binding.bannerToggleIcon.setImageResource(R.drawable.ic_expand_more)
//    }
//    
//    private fun expandBanner() {
//        if (!isBannerCollapsed) return
//        
//        isBannerCollapsed = false
//        val heightInPx = (BANNER_EXPANDED_HEIGHT * resources.displayMetrics.density).toInt()
//        
//        // 使用动画平滑过渡
//        val animator = android.animation.ValueAnimator.ofInt(
//            binding.bannerContainer.height,
//            heightInPx
//        )
//        animator.addUpdateListener { animation ->
//            val params = binding.bannerContainer.layoutParams
//            params.height = animation.animatedValue as Int
//            binding.bannerContainer.layoutParams = params
//        }
//        animator.duration = 300
//        animator.start()
//        
//        // 显示指示器
//        binding.indicatorContainer.animate()
//            .alpha(1f)
//            .setDuration(200)
//            .start()
//        
//        // 切换图标为向上箭头
//        binding.bannerToggleIcon.setImageResource(R.drawable.ic_expand_less)
//    }
    
    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            val selectedTab = binding.tabLayout.getTabAt(binding.tabLayout.selectedTabPosition)
            val categoryId = selectedTab?.tag as? Int ?: 6
            viewModel.loadVideos(categoryId, refresh = true)
        }
    }
    
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.videos.collect { videos ->
                        // 更新当前页面的适配器
                        val currentPosition = binding.viewPager.currentItem
                        pagerAdapter.getAdapter(currentPosition)?.submitList(videos)
                        binding.emptyView.visibility = if (videos.isEmpty()) View.VISIBLE else View.GONE

                        // 预加载前30张图片
                        if (videos.isNotEmpty()) {
                            preloadImages(videos)
                        }
                    }
                }

                launch {
                    viewModel.isLoading.collect { isLoading ->
                        binding.swipeRefresh.isRefreshing = isLoading
                    }
                }

                launch {
                    viewModel.error.collect { error ->
                        error?.let {
                            // 如果错误消息包含"已自动切换"，使用更长的显示时间
                            if (it.contains("已自动切换")) {
                                com.tv.ball.util.CustomToast.showLong(requireContext(), it)
                            } else {
                                com.tv.ball.util.CustomToast.show(requireContext(), it)
                            }
                        }
                    }
                }

                launch {
                    viewModel.continuePlayingVideo.collect { history ->
                        updateContinuePlayingCard(history)
                    }
                }
            }
        }
    }
    
    /**
     * 预加载图片 - 提升滚动流畅度
     */
    private fun preloadImages(videos: List<VideoItem>) {
        val imageUrls = videos
            .take(30) // 预加载前30个
            .mapNotNull { it.coverUrl }
            .filter { it.isNotEmpty() }

        if (imageUrls.isEmpty()) return

        ImageLoader.preloadImages(
            context = requireContext(),
            urls = imageUrls,
            maxConcurrent = 6 // 提高并发数
        ) { current, total ->
            android.util.Log.d("HomeFragment", "预加载进度: $current/$total")
        }
    }

    private fun updateContinuePlayingCard(history: com.tv.ball.data.model.HistoryEntity?) {
        if (history == null) {
            binding.cardContinuePlay.visibility = View.GONE
            return
        }

        binding.cardContinuePlay.visibility = View.VISIBLE

        // 卡片保持 card 表面，颜色只留给状态
        binding.cardContinuePlay.setCardBackgroundColor(
            requireContext().getColor(R.color.surface_card)
        )

        // 格式化标题
        val titleText = if (history.currentEpisode.isNullOrEmpty()) {
            "继续播放：${history.videoTitle}"
        } else {
            "继续播放：${history.videoTitle} - ${history.currentEpisode}"
        }
        binding.tvContinuePlayTitle.text = titleText
        binding.tvContinuePlayTitle.setTextColor(
            requireContext().getColor(R.color.theme_text_primary)
        )

        // 点击事件
        binding.cardContinuePlay.setOnClickListener {
            val intent = Intent(requireContext(), com.tv.ball.presentation.player.VideoPlayerActivity::class.java).apply {
                putExtra("VIDEO_URL", history.videoUrl)
                putExtra("VIDEO_TITLE", history.videoTitle)
                putExtra("THUMBNAIL_URL", history.thumbnailUrl)
                putExtra("SEEK_POSITION", history.watchedPosition)
                putExtra("DETAIL_URL", history.detailUrl)
                putExtra("EPISODES_JSON", history.episodesJson)
                putExtra("CURRENT_EPISODE", history.currentEpisode)
            }
            startActivity(intent)
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        bannerHandler.removeCallbacksAndMessages(null)
        _binding = null
    }
}

data class Category(val name: String, val id: Int)

