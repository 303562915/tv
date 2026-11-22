package com.tv.ball.presentation.home

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
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

    /** 轮播数据与签名：签名（详情地址串）没变就不重建，避免每次状态更新都重新拉一遍图 */
    private var bannerItems: List<BannerItem> = emptyList()
    private var bannerSignature = ""

    /** 自动轮播只挂这一个 Runnable，start / stop 都是摘挂它，不重复创建计时 */
    private val bannerAutoScrollTask = object : Runnable {
        override fun run() {
            if (_binding == null) return
            if (bannerItems.size < 2) return
            binding.bannerViewPager.setCurrentItem(binding.bannerViewPager.currentItem + 1, true)
        }
    }

    /** 骨架屏灰块只生成一次，之后只切可见性 */
    private var skeletonBuilt = false

    /** 待恢复的滚动位置：页下标 -> (首个可见项, 顶部偏移)。对应页拿到数据后再滚，否则位置会被 LayoutManager 丢掉 */
    private val pendingScroll = mutableMapOf<Int, Pair<Int, Int>>()

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

        setupBanner()
        setupSearchButton()
        setupViewPager()
        setupTabs()
        setupSwipeRefresh()
        setupErrorState()
        observeViewModel()
        // 恢复上次停留的分类页与滚动位置（切 Tab 会重建整个 Fragment，位置得自己记）
        restoreScrollPositions(savedInstanceState)

        // 首屏：恢复完页签后只在当前分类确实没数据时才请求，避免和 onPageSelected 重复发
        val firstCategoryId = currentCategoryId()
        val firstState = viewModel.pageState(firstCategoryId)
        if (firstState.items.isEmpty() && !firstState.loading) {
            viewModel.loadFirstPage(firstCategoryId)
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        // Fragment 被重建时 savedInstanceState 才带得到旧的页签 / 位置；换 Tab 的 replace() 不留状态，
        // 那种情况由 companion 里的进程级记忆兜底
        if (savedInstanceState != null) restoreScrollPositions(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        // 回到前台才继续轮播；离开时停掉，避免在后台拿着 Handler 一直翻页
        startBannerAutoScroll()
    }

    override fun onPause() {
        super.onPause()
        stopBannerAutoScroll()
        // MainActivity 用 replace() 换页，离开后这里拿不到 savedInstanceState，必须主动记进进程级缓存
        captureScrollPositions()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        captureScrollPositions()

        val positions = IntArray(categories.size) { -1 }
        val offsets = IntArray(categories.size)
        categories.forEachIndexed { index, category ->
            scrollMemory[category.id]?.let { saved ->
                positions[index] = saved.first
                offsets[index] = saved.second
            }
        }
        outState.putIntArray(KEY_SCROLL_POSITIONS, positions)
        outState.putIntArray(KEY_SCROLL_OFFSETS, offsets)
        outState.putInt(KEY_CURRENT_PAGE, rememberedPage)
    }
    
    // ===================== 首页轮播 =====================

    /**
     * 轮播图（旧实现引用的 bannerBinding / 云端 banner 接口都已不存在，这里按当前字段重写）。
     *
     * 数据源：当前分类首屏里有封面的前几条。仓库里没有可用的 banner 接口（旧代码那个地址是
     * your-server-domain.com 占位），所以纯本地派生，不额外发请求，也不再引入新的网络依赖。
     * 一条都没有时 bannerContainer 直接 GONE，绝不留「占着 180dp 却一片空白」的坑。
     */
    private fun setupBanner() {
        binding.bannerContainer.visibility = View.GONE

        bannerAdapter = BannerAdapter(onItemClick = { item ->
            // 和列表项同一条详情路径；没有 detailUrl 时 openVideoDetail 自己会忽略
            openVideoDetail(item.detailUrl, item.imageUrl)
        })
        binding.bannerViewPager.apply {
            adapter = bannerAdapter
            offscreenPageLimit = 1
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    if (bannerItems.isEmpty()) return
                    currentBannerPosition = position % bannerItems.size
                    updateIndicator(currentBannerPosition)
                    // 自动翻页与手滑后都从这里续下一次，保证任何时候只有一个计时
                    startBannerAutoScroll()
                }

                override fun onPageScrollStateChanged(state: Int) {
                    // 滑动被打断（连滑、切页）时计时可能已经空掉，回稳后再挂一次
                    if (state == ViewPager2.SCROLL_STATE_IDLE) startBannerAutoScroll()
                }
            })
        }

        // 收起状态跨进程记住：上次收着的话，这次进来还是收着的
        isBannerCollapsed = homePrefs().getBoolean(KEY_BANNER_COLLAPSED, false)
        applyBannerCollapsedState(animate = false)
        binding.bannerToggleIcon.setOnClickListener { toggleBannerCollapsed() }
    }

    /** 列表数据变化时重建轮播；签名没变就跳过，免得每次状态更新都重新拉一遍图 */
    private fun applyBannerItems(videos: List<VideoItem>) {
        if (_binding == null) return
        val candidates = videos
            .filter { it.coverUrl.isNotBlank() && it.detailUrl.isNotBlank() }
            .take(BANNER_MAX_ITEMS)
        val signature = candidates.joinToString("|") { it.detailUrl }
        if (signature == bannerSignature) return
        bannerSignature = signature

        bannerItems = candidates.map { video ->
            BannerItem(
                imageUrl = video.coverUrl,
                linkUrl = "", // 不走外链，点击交给详情页
                description = video.title,
                detailUrl = video.detailUrl
            )
        }

        if (bannerItems.isEmpty()) {
            stopBannerAutoScroll()
            // 不清空 adapter：ViewPager2 的 currentItem 停在很靠后的位置，一次砍到 0 条没好处，
            // 容器已经 GONE，旧图不会被看到；等有新数据再整体 updateData
            binding.bannerContainer.visibility = View.GONE
            return
        }

        bannerAdapter.updateData(bannerItems)
        // 先把圆点建好，下面 setCurrentItem 触发的 onPageSelected 才有东西可刷
        currentBannerPosition = 0
        setupIndicator(bannerItems.size)
        // 从中间那一格起步，左右都能一直翻，观感上就是无限循环
        val middle = Int.MAX_VALUE / 2
        binding.bannerViewPager.setCurrentItem(middle - middle % bannerItems.size, false)
        preloadBannerImages()
        binding.bannerContainer.visibility = View.VISIBLE
        applyBannerCollapsedState(animate = false)
    }

    /** 轮播图先预加载，翻到下一页时不至于白一下 */
    private fun preloadBannerImages() {
        val urls = bannerItems.map { it.imageUrl }.filter { it.isNotBlank() }
        if (urls.isEmpty()) return
        ImageLoader.preloadImages(requireContext(), urls, maxConcurrent = 3)
    }

    /** 几条数据几个点；只有一张图时不摆指示器 */
    private fun setupIndicator(count: Int) {
        binding.indicatorContainer.removeAllViews()
        if (count <= 1) return
        val size = (6 * resources.displayMetrics.density).toInt()
        val gap = (6 * resources.displayMetrics.density).toInt()
        val context = requireContext()
        for (index in 0 until count) {
            val dot = View(context)
            dot.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                if (index > 0) marginStart = gap
            }
            dot.background = dotDrawable(index == 0)
            binding.indicatorContainer.addView(dot)
        }
    }

    private fun updateIndicator(position: Int) {
        for (index in 0 until binding.indicatorContainer.childCount) {
            (binding.indicatorContainer.getChildAt(index).background as? GradientDrawable)
                ?.setColor(dotColor(index == position))
        }
    }

    /** 圆点只用现成颜色与 OVAL 形状画，不新增 drawable：选中 theme_primary，未选中 surface_inset */
    private fun dotDrawable(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(dotColor(selected))
    }

    private fun dotColor(selected: Boolean): Int = requireContext().getColor(
        if (selected) R.color.theme_primary else R.color.surface_inset
    )

    /** 自动轮播：只挂一个 Runnable，进来先摘再挂，任何时候最多一个计时 */
    private fun startBannerAutoScroll() {
        bannerHandler.removeCallbacks(bannerAutoScrollTask)
        if (_binding == null) return
        if (bannerItems.size < 2) return // 一张图没什么可翻的
        if (isBannerCollapsed) return // 收起时别在看不见的地方白翻
        if (binding.bannerContainer.visibility != View.VISIBLE) return
        bannerHandler.postDelayed(bannerAutoScrollTask, BANNER_AUTO_SCROLL_MS)
    }

    private fun stopBannerAutoScroll() {
        bannerHandler.removeCallbacks(bannerAutoScrollTask)
    }
    
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

        // 短剧入口：免费短剧列表（分类 Tab + 下拉刷新 + 自动连播下一集）
        binding.chipShortDrama.setOnClickListener {
            startActivity(
                Intent(requireContext(), com.tv.ball.presentation.shortdrama.ShortDramaActivity::class.java)
            )
        }
    }
    
    private fun showKekeFilterDialog() {
        val filterSheet = KekeFilterBottomSheet.newInstance()
        filterSheet.setOnFilterAppliedListener { type, area, year, lang ->
            // 应用筛选条件，刷新视频列表
            val currentCategory = categories.getOrNull(binding.tabLayout.selectedTabPosition)?.id ?: 6
            viewModel.loadFirstPage(
                category = currentCategory,
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
        // 每页的数据、翻页、重试都由自己那页的适配器负责；Fragment 只做分发
        pagerAdapter = CategoryPagerAdapter(
            categories = categories,
            onVideoClick = { video -> openVideoDetail(video.detailUrl, video.coverUrl) },
            onLoadMore = { categoryId -> viewModel.loadNextPage(categoryId) },
            onRetry = { categoryId -> viewModel.retryPage(categoryId) },
            onPageBound = { position -> renderPage(position) }
        )

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
                refreshTabStyles(position)

                // 记住这次选中的分类：进程被杀 / 冷启动再进来还停在这一页
                rememberCategory(position)

                // 切到该分类：已有缓存就先把数据推给它那页，只有完全没数据时才发请求
                renderPage(position)
            }
        })
    }

    /** 列表项与轮播图共用这条详情跳转：参数键只在这里写一次，避免两处各拼一套 */
    private fun openVideoDetail(detailUrl: String, coverUrl: String) {
        if (detailUrl.isBlank()) return // 没有详情地址的轮播项点了什么都不做
        val intent = Intent(requireContext(), VideoDetailActivity::class.java)
        intent.putExtra("DETAIL_URL", detailUrl)
        intent.putExtra("COVER_URL", coverUrl)
        startActivity(intent)
    }

    /** 把一个分类页的当前状态推进它自己的适配器；没有状态时顺手发首屏请求 */
    private fun renderPage(position: Int) {
        val category = categories.getOrNull(position) ?: return
        val state = viewModel.pages.value[category.id]
        // 从没请求过 → 发首屏请求（先判空，后面的 state 才能是稳定非空）
        if (state == null) {
            viewModel.loadFirstPage(category.id)
            return
        }
        // 首屏没成功且还有下一试才重试；源本来就返回 0 条（hasMore=false）时不再重复打
        val needsFirstLoad = state.items.isEmpty() && !state.loading && state.error == null && state.hasMore
        if (needsFirstLoad) {
            viewModel.loadFirstPage(category.id)
            return
        }
        pagerAdapter.submit(position, state.items, footerOf(state)) {
            fillViewportIfNeeded(position)
        }
        if (position == binding.viewPager.currentItem) {
            updateListState()
            // 切到有缓存的分类时不会有新的 pages 发射，轮播得在这里跟着换
            applyBannerItems(state.items)
        }
    }

    /** Tab 样式按「当前是哪一页」重刷一遍（页签是 TabLayoutMediator 按分类下标建的） */
    private fun refreshTabStyles(selected: Int) {
        for (i in 0 until binding.tabLayout.tabCount) {
            val tab = binding.tabLayout.getTabAt(i)
            (tab?.customView as? TextView)?.let { styleTab(it, i == selected) }
        }
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
    
    // ===================== 轮播折叠 / 展开 =====================

    /** 点图标切换收起 / 展开，状态写进 home_prefs，下次进首页还认这个状态 */
    private fun toggleBannerCollapsed() {
        isBannerCollapsed = !isBannerCollapsed
        homePrefs().edit().putBoolean(KEY_BANNER_COLLAPSED, isBannerCollapsed).apply()
        applyBannerCollapsedState(animate = true)
    }

    /**
     * 把「收起」落到高度上：展开 180dp / 收起 60dp，动画 200ms，收起与展开共用这一条路径。
     * 现成的箭头只有一套（ic_expand_more），状态靠旋转图标表达，不新增 drawable。
     */
    private fun applyBannerCollapsedState(animate: Boolean) {
        val targetDp = if (isBannerCollapsed) BANNER_COLLAPSED_HEIGHT else BANNER_EXPANDED_HEIGHT
        val targetPx = (targetDp * resources.displayMetrics.density).toInt()
        val params = binding.bannerContainer.layoutParams
        val startPx = if (params.height > 0) params.height else targetPx

        if (animate && startPx != targetPx) {
            ValueAnimator.ofInt(startPx, targetPx).apply {
                duration = BANNER_ANIM_MS
                addUpdateListener { animator ->
                    val lp = binding.bannerContainer.layoutParams
                    lp.height = animator.animatedValue as Int
                    binding.bannerContainer.layoutParams = lp
                }
                start()
            }
        } else {
            params.height = targetPx
            binding.bannerContainer.layoutParams = params
        }

        // 收起后图片被压到 60dp，指示器淡出、图标翻过来告诉用户还能展开
        binding.indicatorContainer.animate()
            .alpha(if (isBannerCollapsed) 0f else 1f)
            .setDuration(BANNER_ANIM_MS)
            .start()
        binding.bannerToggleIcon.animate()
            .rotation(if (isBannerCollapsed) 180f else 0f)
            .setDuration(BANNER_ANIM_MS)
            .start()

        if (isBannerCollapsed) stopBannerAutoScroll() else startBannerAutoScroll()
    }
    
    private fun setupSwipeRefresh() {
        // ViewPager2 自己不响应 canScrollVertically，认它会让「列表滚到中间再下拉」也触发刷新，
        // 所以直接问当前页的 RecyclerView 还能不能往上滚
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            pagerAdapter.getRecyclerView(binding.viewPager.currentItem)
                ?.canScrollVertically(-1) ?: false
        }
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refreshPage(currentCategoryId())
        }
    }

    /** 当前 ViewPager 页对应的分类 id（Tab 上没挂 tag，只能按位置取） */
    private fun currentCategoryId(): Int =
        categories.getOrNull(binding.viewPager.currentItem)?.id ?: categories.first().id

    /** 错误态：整页换成「加载失败 + 重试」，重试重新拉当前分类 */
    private fun setupErrorState() {
        binding.errorView.visibility = View.GONE
        binding.btnErrorRetry.setOnClickListener {
            binding.errorView.visibility = View.GONE
            viewModel.refreshPage(currentCategoryId())
        }
    }

    /** 记下当前页滚动位置，供 onPause / onSaveInstanceState 使用 */
    private fun captureScrollPositions() {
        if (_binding == null || !this::pagerAdapter.isInitialized) return
        val recyclerView = pagerAdapter.getRecyclerView(binding.viewPager.currentItem) ?: return
        val layoutManager = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
        val position = layoutManager.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return
        val offset = layoutManager.findViewByPosition(position)?.top ?: 0
        scrollMemory[currentCategoryId()] = position to offset
        rememberedPage = binding.viewPager.currentItem
    }

    /** 恢复页签；滚动位置先记下，等数据回来再滚 */
    private fun restoreScrollPositions(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            val positions = savedInstanceState.getIntArray(KEY_SCROLL_POSITIONS)
            val offsets = savedInstanceState.getIntArray(KEY_SCROLL_OFFSETS)
            if (positions != null && offsets != null) {
                categories.forEachIndexed { index, category ->
                    if (index < positions.size && positions[index] >= 0) {
                        scrollMemory[category.id] = positions[index] to offsets.getOrElse(index) { 0 }
                    }
                }
            }
            val savedPage = savedInstanceState.getInt(KEY_CURRENT_PAGE, -1)
            if (savedPage in categories.indices) rememberedPage = savedPage
        } else {
            // 没有 savedInstanceState = 冷启动（进程被杀 / 首次进来），此时只有 SharedPreferences 里
            // 记着的「上次选中的分类 id」可用；按 id 查下标，id 已不在列表里就回退 0
            rememberedPage = savedCategoryIndex()
        }
        if (rememberedPage in categories.indices) {
            if (rememberedPage != binding.viewPager.currentItem) {
                binding.viewPager.setCurrentItem(rememberedPage, false)
            }
            // 页签是先按 currentItem(=0) 建出来的，恢复到别的页后要立刻按新页重刷样式
            refreshTabStyles(rememberedPage)
        }
    }

    /** 上次停留的分类在本列表里的下标；没存过 / 存的 id 已失效都退到 0 */
    private fun savedCategoryIndex(): Int {
        val savedId = homePrefs().getInt(KEY_LAST_CATEGORY_ID, NO_CATEGORY)
        val index = categories.indexOfFirst { it.id == savedId }
        return if (index >= 0) index else 0
    }

    /** 切换分类时写入，供下次启动恢复 */
    private fun rememberCategory(position: Int) {
        val category = categories.getOrNull(position) ?: return
        homePrefs().edit().putInt(KEY_LAST_CATEGORY_ID, category.id).apply()
    }

    /** 首页自己的偏好文件：只存「上次选中的分类 id」这一个键，不和播放设置混在一起 */
    private fun homePrefs() = requireContext()
        .getSharedPreferences(PREFS_HOME, android.content.Context.MODE_PRIVATE)

    /** 数据回来后再滚：这时 LayoutManager 才有子项可定位 */
    private fun applyPendingScroll() {
        val saved = scrollMemory[currentCategoryId()] ?: return
        val recyclerView = pagerAdapter.getRecyclerView(binding.viewPager.currentItem) ?: return
        recyclerView.post {
            (recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                ?.scrollToPositionWithOffset(saved.first, saved.second)
        }
    }
    
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.pages.collect { pages ->
                        var currentItems: List<VideoItem> = emptyList()
                        pages.forEach { (categoryId, state) ->
                            val position = categories.indexOfFirst { it.id == categoryId }
                            if (position < 0) return@forEach

                            val isCurrentPage = position == binding.viewPager.currentItem
                            if (isCurrentPage) currentItems = state.items

                            pagerAdapter.submit(position, state.items, footerOf(state)) {
                                // 数据到位后再恢复滚动位置，此时才有子项可定位
                                if (isCurrentPage) applyPendingScroll()
                                fillViewportIfNeeded(position)
                            }
                        }

                        // 下拉刷新只在「首屏 / 刷新」时转圈，翻页交给 footer 的进度条
                        val currentState = pages[currentCategoryId()]
                        binding.swipeRefresh.isRefreshing = currentState?.refreshing == true

                        updateListState()

                        // 轮播跟着当前分类的列表走；currentItems 为空时这里会把容器藏起来（不留空白）
                        applyBannerItems(currentItems)

                        if (currentItems.isNotEmpty()) preloadImages(currentItems)
                    }
                }

                launch {
                    // 只有一次性提示（换源、错误原因）走这里；整页错误态由分页状态决定
                    viewModel.error.collect { error ->
                        if (error == null) return@collect
                        if (error.contains("已自动切换")) {
                            com.tv.ball.util.CustomToast.showLong(requireContext(), error)
                        } else {
                            com.tv.ball.util.CustomToast.show(requireContext(), error)
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

    private fun footerOf(state: HomePageState): PageFooterState = when {
        state.error != null -> PageFooterState.ERROR
        state.loading && !state.refreshing && state.items.isNotEmpty() -> PageFooterState.LOADING
        else -> PageFooterState.HIDDEN
    }

    /**
     * 加载中 / 空 / 错 / 成功四态互斥地落在当前分类上：
     * 首屏还没有结果（状态都没建出来、或正在拉）→ 骨架屏；
     * 首屏失败 → 错误态；空了且不在加载 → 空态；其余交给列表。
     */
    private fun updateListState() {
        if (_binding == null || !this::pagerAdapter.isInitialized) return
        val state = viewModel.pages.value[currentCategoryId()]
        val items = state?.items.orEmpty()
        val firstPageFailed = state?.error != null && items.isEmpty()
        val showSkeleton = !firstPageFailed && items.isEmpty() && (state == null || state.loading)
        val showEmpty = !firstPageFailed && items.isEmpty() && !showSkeleton

        if (showSkeleton) buildSkeletonOnce()
        binding.skeletonContainer.visibility = if (showSkeleton) View.VISIBLE else View.GONE

        binding.errorView.visibility = if (firstPageFailed) View.VISIBLE else View.GONE
        if (firstPageFailed) state?.error?.let { binding.tvErrorMessage.text = it }

        binding.emptyView.visibility = if (showEmpty) View.VISIBLE else View.GONE
    }

    /**
     * 骨架屏灰块只在第一次需要时生成，之后只切可见性。
     * 行列数照真实网格（3 列）来，数据到达时卡片位置不会跳；底色 surface_block + radius_md，
     * 不新增 layout / drawable —— 直接拼 View 塞进 fragment_home 里的 skeletonGrid。
     */
    private fun buildSkeletonOnce() {
        if (skeletonBuilt || _binding == null) return
        skeletonBuilt = true

        val density = resources.displayMetrics.density
        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        val blockHeight = (200 * density).toInt()

        for (row in 0 until SKELETON_ROWS) {
            val rowView = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (row > 0) topMargin = gap }
            }
            for (column in 0 until SKELETON_COLUMNS) {
                val block = View(requireContext()).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = resources.getDimension(R.dimen.radius_md)
                        setColor(requireContext().getColor(R.color.surface_block))
                    }
                    layoutParams = LinearLayout.LayoutParams(0, blockHeight, 1f).apply {
                        if (column > 0) marginStart = gap
                    }
                }
                rowView.addView(block)
            }
            binding.skeletonGrid.addView(rowView)
        }
    }

    /**
     * 首屏不满一屏时 RecyclerView 不会回调 onScrolled，翻页就永远触发不了。
     * 每次数据提交后补一次检查，没填满就接着要下一页（ViewModel 里有在途/到底的拦截，不会连发）。
     */
    private fun fillViewportIfNeeded(position: Int) {
        val recyclerView = pagerAdapter.getRecyclerView(position) ?: return
        val videoCount = pagerAdapter.getVideoItemCount(position)
        if (videoCount == 0) return
        recyclerView.post {
            val layoutManager = recyclerView.layoutManager as? GridLayoutManager ?: return@post
            val lastVisible = layoutManager.findLastVisibleItemPosition()
            // NO_POSITION：刚绑定还没完成布局，位置未知，等真实滚动或下一次 commit 再判
            if (lastVisible == RecyclerView.NO_POSITION) return@post
            if (lastVisible >= videoCount - 1) {
                categories.getOrNull(position)?.let { viewModel.loadNextPage(it.id) }
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

    /**
     * 继续观看：标题 + 集数 + 断点提示。三段拼在同一行 TextView 里（tvContinuePlayTitle 已经在布局里），
     * 不新增控件，保持 Widget.TVPlayer.Card 那套「卡上一行」的结构。
     */
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

        val parts = mutableListOf("继续观看：${history.videoTitle}")
        // 集数：电影没有集数，空着就不占位
        if (!history.currentEpisode.isNullOrEmpty()) parts += history.currentEpisode
        // 断点：能算出剩余就写「还剩 xx 分钟」，算不出来（没有总时长）就不显示这一段
        val progressText = continueProgressText(history)
        if (progressText.isNotEmpty()) parts += progressText

        binding.tvContinuePlayTitle.text = parts.joinToString(" · ")
        binding.tvContinuePlayTitle.setTextColor(
            requireContext().getColor(R.color.theme_text_primary)
        )

        // 点击 → 直接续播到断点（沿用原来的参数，不另拼一套播放器入参）
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

    /**
     * 断点文案：duration - position 换成「还剩 x 分钟」（超过 1 小时写 x 小时 y 分钟）。
     * 剩下的不足一分钟时改显示进度百分比，让人一眼看到「马上就完了」；
     * 总时长压根没有（直播源 / 老记录）时不硬编，这段留空。
     */
    private fun continueProgressText(history: com.tv.ball.data.model.HistoryEntity): String {
        val duration = history.duration
        if (duration <= 0) return ""
        val remainingMs = (duration - history.watchedPosition).coerceAtLeast(0)
        if (remainingMs < REMAINING_MINUTE_MS) {
            val percent = (history.watchedPosition * 100 / duration).coerceIn(0, 100)
            return "已看 $percent%"
        }
        // 向上取整：还剩 59 秒也算「1 分钟」，不会出现「还剩 0 分钟」
        val minutes = ((remainingMs + REMAINING_MINUTE_MS - 1) / REMAINING_MINUTE_MS).toInt()
        return if (minutes >= 60) {
            "还剩 ${minutes / 60} 小时 ${minutes % 60} 分钟"
        } else {
            "还剩 $minutes 分钟"
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        bannerHandler.removeCallbacksAndMessages(null)
        _binding = null
    }

    companion object {
        private const val KEY_SCROLL_POSITIONS = "home_scroll_positions"
        private const val KEY_SCROLL_OFFSETS = "home_scroll_offsets"
        private const val KEY_CURRENT_PAGE = "home_current_page"

        /** 首页偏好文件 / 键：进程级记忆之外，跨进程也要记住上次选中的分类 */
        private const val PREFS_HOME = "home_prefs"
        private const val KEY_LAST_CATEGORY_ID = "home_last_category_id"

        /** 没有存过分类 id 时的哨兵值（真实分类 id 都是正整数） */
        private const val NO_CATEGORY = -1

        /** 轮播：折叠状态同样存在 home_prefs，冷启动进来还认 */
        private const val KEY_BANNER_COLLAPSED = "home_banner_collapsed"

        /** 自动轮播间隔：4 秒一屏，10 秒内能看完一轮，不至于在首页干等 */
        private const val BANNER_AUTO_SCROLL_MS = 4000L

        /** 折叠 / 展开动画时长 */
        private const val BANNER_ANIM_MS = 200L

        /** 轮播最多取几条：首页首屏那几行里挑，太多反而撞车列表 */
        private const val BANNER_MAX_ITEMS = 5

        /** 骨架屏：3 列 × 2 行 = 6 块，和真实网格 SPAN_COUNT 对齐 */
        private const val SKELETON_COLUMNS = 3
        private const val SKELETON_ROWS = 2

        /** 一分钟的毫秒数：断点文案算「还剩」用 */
        private const val REMAINING_MINUTE_MS = 60_000L

        /** 进程内记忆：MainActivity 用 replace() 换页时拿不到 savedInstanceState，这里兜底 */
        private val scrollMemory = mutableMapOf<Int, Pair<Int, Int>>()
        private var rememberedPage = 0
    }
}

data class Category(val name: String, val id: Int)

