package com.tv.ball.presentation.shortdrama

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.tv.ball.R
import com.tv.ball.databinding.FragmentShortDramaBinding
import com.tv.ball.di.Dependencies
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 首页「免费短剧」Tab 的列表页。
 *
 * 行为与列表页 ShortDramaActivity 对齐：列数自适应、首屏骨架、三态 footer、
 * 上次分类记忆、网格滚动位置恢复。区别只在于这里是 Fragment，没有搜索框和热门词。
 */
class ShortDramaFragment : Fragment() {

    companion object {
        /** 骨架卡片数：手机三列两行，够铺满首屏 */
        private const val SKELETON_COUNT = 6

        /**
         * 记上次选中的分类。键名前缀与 Activity 侧（short_drama_ui）分开：
         * 两边是不同页面，各自的「上次分类」互不干扰。
         */
        private const val UI_PREFS = "short_drama_fragment_ui"
        private const val KEY_LAST_TAB = "last_tab"

        /** 重建时还原网格滚动位置 */
        private const val KEY_SCROLL_POSITION = "scroll_position"

        /**
         * 翻页请求的兜底检查延迟：ViewModel 在「已有请求在途 / 已到底」时会直接跳过 loadMore()
         * 而不产生任何状态变化，此时没人会把 footer 的「加载中…」收掉，所以延迟一拍自己核对。
         */
        private const val APPEND_GUARD_MS = 800L
    }

    private var _binding: FragmentShortDramaBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ShortDramaViewModel by lazy {
        ViewModelProvider(this)[ShortDramaViewModel::class.java]
    }

    private lateinit var adapter: ShortDramaAdapter
    private lateinit var skeletonAdapter: SkeletonAdapter

    /**
     * 网格的 LayoutManager。存成字段是因为保存/恢复滚动位置时要取 findFirstVisibleItemPosition()，
     * 而 setupRecyclerView 里原来是个局部变量，外面拿不到。
     */
    private lateinit var gridLayoutManager: GridLayoutManager

    // 分类由数据源提供，和列表页共用同一套词表
    private val categories = ShortDramaRepository.tabs.map { it.label }

    private var currentTabIndex = 0

    /** 上一次真正发过请求的分类：同一分类不重复请求（重建时 Tab 恢复会再回调一次选中） */
    private var loadedCategory: String? = null

    /** 首屏是否已经发过请求：onViewCreated 末尾的兜底，保证「任何情况下首屏只发一次」 */
    private var initialLoadDone = false

    /**
     * 待恢复的滚动位置（NO_POSITION 表示不用还原）。
     * 为什么不在 onViewCreated 里直接 scrollToPosition：那时列表还是空的，
     * 必须等第一次数据到、RecyclerView 真有 item 了再定位，所以先记着，提交列表后再用。
     */
    private var pendingScrollPosition = RecyclerView.NO_POSITION

    // ---------------- 翻页 / footer 三态 ----------------

    /**
     * 是否已经到底。
     * 主信号是 ViewModel 的 hasMore（它知道「这一页真的没数据」），
     * 条数推断只作为兜底：请求被跳过、没有状态变化时没人推 hasMore。
     */
    private var noMore = false

    /** 当前在途的请求是不是「翻页」 */
    private var appendRequested = false

    /** 翻页失败：footer 变成「加载失败，点我重试」 */
    private var footerFailed = false

    /** 上一次请求失败过：空态也跟着变成可点的「加载失败，点我重试」（正常空数据不可点） */
    private var loadFailed = false

    /** 发翻页请求那一刻的列表长度，用来判断这次追加到底有没有拿到新数据 */
    private var appendBaseSize = 0

    /**
     * 本次加载是不是用户下拉触发的。
     * 为什么单独记：下拉刷新会先清空列表，这时「空列表 + 加载中」看起来像首屏，
     * renderState 会把系统转圈换成骨架，用户刚拉出来的 spinner 就凭空消失了；
     * 记住来源才能把 spinner 留住。
     */
    private var pullRefreshing = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentShortDramaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 重建（旋转 / 进程被杀 / 视图被回收）时把网格滚动位置捡回来；Tab 由 prefs + setupTabs 负责
        val restoredScroll = savedInstanceState?.getInt(KEY_SCROLL_POSITION, RecyclerView.NO_POSITION)
        if (restoredScroll != null && restoredScroll >= 0) {
            pendingScrollPosition = restoredScroll
        }

        setupTabs()
        setupRecyclerView()
        setupSwipeRefresh()
        observeViewModel()

        binding.emptyView.setOnClickListener { retryFromEmpty() }
        binding.tvFooter.setOnClickListener { retryFromFooter() }

        // 兜底：Tab 的「恢复上次分类」是 setupTabs 里 getTabAt(...).select() 做的，
        // 那时监听还没挂上，不会回调；下面保证首屏（含重建）一定有且只有一次请求
        if (!initialLoadDone) {
            initialLoadDone = true
            loadCategory(categories[currentTabIndex])
        }

        // 先渲染一次：请求已经发出（loading=true），骨架能立刻出现，不会闪一下空白
        renderState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 只有网格有内容时才有位置可存；解析不出来就存 NO_POSITION，恢复端会当成「不需要还原」
        val first = if (::gridLayoutManager.isInitialized) {
            gridLayoutManager.findFirstVisibleItemPosition()
        } else {
            RecyclerView.NO_POSITION
        }
        outState.putInt(KEY_SCROLL_POSITION, first)
    }

    // ---------------- 列数自适应 ----------------

    /** 与列表页同一套阈值：手机 3 列、折叠屏/小平板 4 列、平板横屏 5 列 */
    private fun resolveGridColumns(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return when {
            widthDp < 600 -> 3
            widthDp <= 840 -> 4
            else -> 5
        }
    }

    // ---------------- 分类 Tab + 记忆 ----------------

    private fun uiPrefs() =
        Dependencies.context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)

    private fun setupTabs() {
        val saved = uiPrefs().getString(KEY_LAST_TAB, null)
        // 找不到（首次进页 / 词表被改过）就回落到第一个 Tab
        currentTabIndex = categories.indexOfFirst { it == saved }.takeIf { it >= 0 } ?: 0

        categories.forEach { category ->
            binding.tabLayout.addTab(
                binding.tabLayout.newTab().setText(category)
            )
        }

        // 先 select 再挂监听：addTab 加第一个 Tab 时会自动选中，select() 本身也会回调 onTabSelected，
        // 顺序反了会在进页面时对同一个分类连打两次请求
        binding.tabLayout.getTabAt(currentTabIndex)?.select()

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val index = tab?.position ?: return
                // 位置越界（Tab 列表还在重建时可能拿到 -1）直接跳过，避免 categories[index] 崩
                if (index !in categories.indices) return

                currentTabIndex = index
                // 用户切了分类就写盘，下次进页面直接回到这个 Tab
                uiPrefs().edit().putString(KEY_LAST_TAB, categories[index]).apply()

                // 同一分类不重复请求：重建时 Tab 会把「上次选中的」再回调一次，不能又打一遍
                if (categories[index] == loadedCategory) return
                initialLoadDone = true
                loadCategory(categories[index])
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}

            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    /**
     * 一次「整屏重来」的请求（首屏 / 切分类 / 下拉刷新 / 空态重试）都走这里：
     * 清掉翻页与失败状态，再让 ViewModel 拉第一页（refresh = true 会顺手清掉它的页缓存）。
     */
    private fun loadCategory(category: String, fromPull: Boolean = false) {
        loadedCategory = category
        resetPaging()
        // 新的请求开始了，「上次失败」这个标记作废，否则空态会一直挂着「点我重试」
        loadFailed = false
        // 下拉刷新会先清空列表：记下来，renderState 才知道该留住系统转圈而不是换成骨架
        pullRefreshing = fromPull
        viewModel.loadDramas(category, page = 1, refresh = true)
    }

    // ---------------- 列表 / 骨架 / 翻页 ----------------

    private fun setupRecyclerView() {
        // 真实网格与骨架网格必须用同一个列数，否则骨架铺 3 列、数据到了跳成 5 列，页面会闪一下
        val columns = resolveGridColumns()

        adapter = ShortDramaAdapter { drama ->
            val intent = Intent(requireContext(), ShortDramaDetailActivity::class.java)
            intent.putExtra("BOOK_ID", drama.bookId)
            intent.putExtra("TITLE", drama.title)
            intent.putExtra("COVER", drama.cover)
            startActivity(intent)
        }

        gridLayoutManager = GridLayoutManager(requireContext(), columns)
        binding.recyclerView.layoutManager = gridLayoutManager
        binding.recyclerView.adapter = adapter

        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                if (dy <= 0) return
                // itemCount 为 0 时 findLastVisibleItemPosition() 返回 -1，会误判成「已接近底部」
                if (gridLayoutManager.itemCount == 0) return
                // 提前 4 格开始预取，滚到底之前数据已经在路上
                if (gridLayoutManager.findLastVisibleItemPosition() >= gridLayoutManager.itemCount - 4) {
                    requestLoadMore()
                }
            }
        })

        // 骨架：同一套列数 + 同一种 ViewHolder 结构，数据到位时只有内容出现、位置不跳
        skeletonAdapter = SkeletonAdapter(SKELETON_COUNT)
        binding.skeletonRecycler.layoutManager = GridLayoutManager(requireContext(), columns)
        binding.skeletonRecycler.adapter = skeletonAdapter
        // 骨架是静态占位，关掉 item 动画，显隐时不会有淡入造成的闪屏
        binding.skeletonRecycler.itemAnimator = null
        binding.skeletonRecycler.visibility = View.GONE
    }

    /**
     * 翻页入口。
     * 到底与否优先看 ViewModel 的 hasMore；这里同时记下请求前的列表长度，
     * 等 loading 变回 false 时对比——条数没变也当到底，覆盖「请求被跳过、状态没变」的情况。
     */
    private fun requestLoadMore() {
        if (noMore || appendRequested || viewModel.isLoading.value) return

        appendRequested = true
        // 基准取 StateFlow 里的真实条数，不用 adapter.itemCount：
        // ListAdapter.submitList 是异步 diff，itemCount 会晚一帧才变，拿它算差值永远得到 0
        appendBaseSize = viewModel.dramas.value.size
        // 立刻出「加载中…」，不等下一次状态回调（ViewModel 有可能直接跳过这次 loadMore）
        renderFooter()
        viewModel.loadMore()

        // 兜底：ViewModel 在「有请求在途 / 已到底」时会跳过 loadMore() 且不产生任何状态变化，
        // footer 会永远停在「加载中…」。延迟一拍自己核对一次，并按实际增量判断是否到底。
        viewLifecycleOwner.lifecycleScope.launch {
            delay(APPEND_GUARD_MS)
            if (!appendRequested || viewModel.isLoading.value) return@launch
            if (viewModel.dramas.value.size <= appendBaseSize) noMore = true
            appendRequested = false
            renderFooter()
        }
    }

    /** 一次新的取数（首屏 / 切分类 / 下拉 / 空态重试）都要把翻页状态清干净 */
    private fun resetPaging() {
        appendRequested = false
        noMore = false
        footerFailed = false
        appendBaseSize = 0
    }

    private fun setupSwipeRefresh() {
        // 布局里 SwipeRefresh 的子 View 是 LinearLayout（内容区 + footer），
        // 不指定回调的话「列表已经滚下去」也会被判成可下拉，往下滚就误触发刷新
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            binding.recyclerView.canScrollVertically(-1)
        }

        binding.swipeRefresh.setOnRefreshListener {
            loadCategory(categories[currentTabIndex], fromPull = true)
        }
    }

    // ---------------- 三态 footer ----------------

    /**
     * footer 三态：加载失败（可点重试）> 没有更多了 > 加载中…，其它情况直接隐藏。
     * 「有数据」是后两态的前置条件：首屏失败时列表是空的，footer 再顶一条会跟空态文案打架。
     */
    private fun renderFooter() {
        if (_binding == null) return
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

    /** footer 点击：只在「翻页失败」时出现，清掉失败/到底标记后再翻一次 */
    private fun retryFromFooter() {
        if (!footerFailed) return
        footerFailed = false
        noMore = false
        renderFooter()
        // 走 requestLoadMore 而不是直接 loadMore()：这样「加载中…」和到底判断也走同一条路径
        requestLoadMore()
    }

    /** 空态点击：只有「上次请求失败」时空态才可点，这里再判一次，避免误触 */
    private fun retryFromEmpty() {
        if (!loadFailed) return
        loadCategory(categories[currentTabIndex])
    }

    // ---------------- 状态渲染 ----------------

    /**
     * 骨架 / 列表 / 空态三选一。
     * 骨架只在「正在加载 + 一条数据都没有」时出现：首屏和切分类（ViewModel 会先清空列表）
     * 都靠它给反馈，否则用户看到的就是一片空白。
     */
    private fun renderState() {
        if (_binding == null) return
        val dramas = viewModel.dramas.value
        val loading = viewModel.isLoading.value
        val empty = dramas.isEmpty()

        val firstLoad = loading && empty
        binding.skeletonRecycler.visibility = if (firstLoad) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
        binding.emptyView.visibility = if (empty && !loading) View.VISIBLE else View.GONE

        if (empty && !loading) {
            // 失败过就换成可点的重试入口；正常空数据（源里就是没货）保持不可点
            binding.emptyView.text = if (loadFailed) "加载失败，点我重试" else "暂无短剧"
            binding.emptyView.isClickable = loadFailed
        }

        // 下拉刷新保留系统转圈；首屏交给骨架，翻页不用整页转圈
        binding.swipeRefresh.isRefreshing =
            loading && (pullRefreshing || (!firstLoad && !appendRequested))

        renderFooter()
    }

    /**
     * 数据到位后再还原滚动位置。
     * 为什么每次提交列表都调：pendingScrollPosition 一旦用掉就清空，后续翻页/刷新不受影响；
     * 写成「有值才滚」比在 onViewCreated 里赌一个延迟更稳。
     */
    private fun restoreScrollIfNeeded() {
        if (_binding == null) return
        val target = pendingScrollPosition
        if (target == RecyclerView.NO_POSITION) return
        if (adapter.itemCount <= target) return
        pendingScrollPosition = RecyclerView.NO_POSITION
        binding.recyclerView.scrollToPosition(target)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.dramas.collect { dramas ->
                        // 用 submitList 的回调还原滚动位置：刚提交时 itemCount 还是 0，
                        // scrollToPosition 会被直接丢掉
                        adapter.submitList(dramas) { restoreScrollIfNeeded() }
                        renderState()
                    }
                }

                launch {
                    viewModel.isLoading.collect { isLoading ->
                        if (!isLoading) {
                            pullRefreshing = false
                            // 一次翻页结束：条数没变 = 采集源这页没给新数据，保守当成到底
                            if (appendRequested) {
                                if (viewModel.dramas.value.size <= appendBaseSize) noMore = true
                                appendRequested = false
                            }
                            // 真实信号优先：ViewModel 只有「这一页真的没数据」才把 hasMore 置 false，
                            // 最后一页恰好满 20 条也能判对，不用再等用户多滚一次空请求
                            if (!viewModel.hasMore.value) noMore = true
                        }
                        renderState()
                    }
                }

                launch {
                    viewModel.error.collect { error ->
                        if (error != null) {
                            // 失败标记只在「发下一次请求」时清（见 loadCategory），
                            // 这里不写成 loadFailed = (error != null)：弹完 Toast 会立刻消费掉
                            // 这条错误，随后收到的 null 会把重试入口自己抹掉，空屏上就只剩一句话。
                            loadFailed = true
                            // 列表里还有内容 = 翻页失败，交给 footer 的「点我重试」；
                            // 首屏失败（列表为空）由空态 + Toast 提示，footer 不重复占位
                            if (viewModel.dramas.value.isNotEmpty()) {
                                appendRequested = false
                                footerFailed = true
                            }
                            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
                            // StateFlow 不会因为被读过就自己清空：不清掉的话，
                            // 旋转后重新 collect、或从详情页返回再订阅，都会把同一句话再弹一次
                            viewModel.consumeError()
                        }
                        renderState()
                    }
                }
            }
        }
    }

    /**
     * 骨架用的极简适配器：固定 N 个占位 item，不进 ListAdapter。
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
