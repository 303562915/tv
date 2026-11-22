package com.tv.ball.presentation.detail

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.tabs.TabLayout
import com.tv.ball.R
import com.tv.ball.data.model.FavoriteEntity
import com.tv.ball.databinding.ActivityVideoDetailBinding
import com.tv.ball.domain.model.Episode
import com.tv.ball.domain.model.VideoDetail
import com.tv.ball.presentation.player.VideoPlayerActivity
import com.tv.ball.util.ImageLoader
import kotlinx.coroutines.launch
import org.json.JSONObject

class VideoDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoDetailBinding
    private val viewModel: VideoDetailViewModel by lazy {
        ViewModelProvider(this)[VideoDetailViewModel::class.java]
    }
    private lateinit var episodeAdapter: EpisodeAdapter
    private var episodeGroups: List<EpisodeGroup> = emptyList()
    private var originalEpisodes: List<Episode> = emptyList() // 保存原始剧集列表（带线路前缀）
    private var currentGroupIndex = 0

    /** 详情地址：失败重试要复用，所以提成成员（原先是 onCreate 里的局部变量） */
    private var detailUrl: String = ""
    private var coverUrl: String = ""
    private var sourceKey: String = ""

    /** 详情地址本身无效时（源没给链接 / 视频 id 换不出地址）的失败文案；非空表示连请求都没发 */
    private var fatalUrlError: String? = null

    /** 是否已经拿到过详情：刷新失败时保留已有内容，别一次失败就清屏 */
    private var hasDetail = false

    /** 100+ 集的展开状态；切线路会重置回收起 */
    private var episodesExpanded = false

    /**
     * 当前已经铺开的集数上限。
     * 收起时固定 [COLLAPSED_EPISODE_COUNT]；展开后按 [EPISODE_PAGE_SIZE] 一批往上加，
     * 不再「一点展开就把 100+ 个格子全 inflate 出来」。
     */
    private var episodeVisibleLimit = COLLAPSED_EPISODE_COUNT

    /** 收藏 / 分享用的稳定 id：detailUrl 的 hash，不再用时间戳（时间戳每次进页面都变，收藏键对不上） */
    private var videoId: Long = 0L
    private var isFavorite = false

    // 仓储统一从 DI 取；原先每次刷详情都 new 一个 LocalDataSource + HistoryRepository
    private val favoriteDao by lazy { com.tv.ball.di.Dependencies.database.favoriteDao() }
    private val historyRepository by lazy { com.tv.ball.di.Dependencies.historyRepository }

    data class EpisodeGroup(
        val sourceName: String,
        val episodes: List<Episode>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        // 夜间模式必须在 super.onCreate 之前应用：主题一 attach 就锁死，之后再改只能重建 Activity
        val nightMode = com.tv.ball.di.Dependencies.configRepository.getNightMode()
        com.tv.ball.utils.ThemeUtils.applyNightMode(nightMode)
        super.onCreate(savedInstanceState)
        binding = ActivityVideoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 设置透明沉浸式状态栏：深色模式下改用浅色图标，避免深底配深色图标看不见
        val isNightMode = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        com.tv.ball.utils.StatusBarUtils.setupTransparentStatusBar(this, lightStatusBar = !isNightMode)

        // 折叠工具栏：返回箭头，否则只能靠系统返回键
        binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val videoIdOrUrl = intent.getStringExtra("DETAIL_URL")
            ?: intent.getStringExtra("videoUrl") ?: ""
        coverUrl = intent.getStringExtra("COVER_URL")
            ?: intent.getStringExtra("coverUrl") ?: ""
        sourceKey = intent.getStringExtra("SOURCE_KEY") ?: "" // 获取来源信息

        detailUrl = resolveDetailUrl(videoIdOrUrl)
        // 播放器侧读的是 getLongExtra("VIDEO_ID", 0)，这里必须传 Long
        videoId = if (detailUrl.isNotEmpty()) detailUrl.hashCode().toLong()
        else videoIdOrUrl.hashCode().toLong()

        setupRecyclerView()
        setupActions()
        observeViewModel()

        // Load cover image
        ImageLoader.load(
            context = this,
            url = coverUrl,
            imageView = binding.ivCover,
            placeholder = R.drawable.ic_placeholder
        )

        if (detailUrl.isBlank()) {
            // 详情地址都没有就别发请求了，直接失败态（源被删 / 源不提供详情页都走这里）
            fatalUrlError = "详情地址无效"
            android.util.Log.w(TAG, "详情地址为空，放弃请求（源：${sourceKey.ifEmpty { "未指定" }}）")
            renderContentState()
            return
        }

        viewModel.loadVideoDetail(detailUrl)
    }

    /**
     * 收藏状态的唯一刷新时机：进页面、从播放器返回都会走到 onResume。
     *
     * 详情页与播放器各自持有 isFavorite（不做跨 Activity 的实时广播），
     * 但两边都以 FavoriteDao.isFavorite(videoId) 为唯一真源，返回时重读一次库就不会对不上。
     */
    override fun onResume() {
        super.onResume()
        syncFavoriteState()
    }

    /**
     * 目标地址：完整 URL 直接用；否则让源把 videoId 换成详情地址。
     *
     * sourceKey 指定了源却换不出地址时返回空串，由调用方走「详情地址无效」失败态 ——
     * 原来的实现会把空串直接丢给仓储，白跑一次网络请求再报一个看不懂的错误。
     */
    private fun resolveDetailUrl(raw: String): String {
        if (raw.isBlank()) return ""
        if (raw.startsWith("http")) return raw

        val currentSource = sourceKey.ifEmpty {
            com.tv.ball.data.VideoSourceManager.getCurrentSource()
        }
        val url = runCatching {
            com.tv.ball.data.VideoSourceManager.getDetailUrl(currentSource, raw)
        }.getOrElse { e ->
            android.util.Log.w(TAG, "源 $currentSource 生成详情地址失败: ${e.message}")
            ""
        }
        if (url.isBlank()) {
            android.util.Log.w(TAG, "源 $currentSource 未返回详情地址，videoId=$raw")
        }
        return url
    }

    private fun setupRecyclerView() {
        episodeAdapter = EpisodeAdapter { episode ->
            // 检查是否是网盘资源
            if (episode.isCloudStorage) {
                showCloudStorageDialog(episode)
            } else {
                playEpisode(episode)
            }
        }

        // 长按格子 = 复制这一集的播放地址（网盘集复制的是网盘链接，一样有用）
        episodeAdapter.onEpisodeLongClick = { episode -> copyToClipboard(episode.url) }

        binding.rvEpisodes.apply {
            // 使用固定6列网格布局，和播放器保持一致
            layoutManager = GridLayoutManager(this@VideoDetailActivity, GRID_COLUMNS)
            adapter = episodeAdapter
        }

        // 分批显示：默认前 60 集，超出时才出现「展开全部」
        binding.tvEpisodeExpand.setOnClickListener {
            episodesExpanded = !episodesExpanded
            // 展开先给一整批（200 条），之后滚到网格底部附近再继续追加；收起回到 60 条
            episodeVisibleLimit =
                if (episodesExpanded) EPISODE_PAGE_SIZE else COLLAPSED_EPISODE_COUNT
            showEpisodesForCurrentGroup()
            if (!episodesExpanded) {
                // 收起后内容变短，把视图拉回选集卡片顶部，别停在空白上
                scrollToView(binding.cardEpisodes, smooth = true)
            }
        }

        // 展开后按批追加：RecyclerView 是 wrap_content 铺在 NestedScrollView 里的，它自己不滚，
        // 所以只能监听外层 ScrollView —— 滚到网格底部附近再追加下一批。
        // 显式写成 View.OnScrollChangeListener：NestedScrollView 自己也有一个同名同参的重载，
        // 光用 lambda 会有重载歧义
        binding.scrollView.setOnScrollChangeListener(
            View.OnScrollChangeListener { _, _, _, _, _ -> maybeAppendMoreEpisodes() }
        )

        // 设置Tab切换监听
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.position?.let { position ->
                    currentGroupIndex = position
                    // 切线路回到收起态：换条线路集数完全不同，沿用上一条线路的展开状态没有意义
                    episodesExpanded = false
                    episodeVisibleLimit = COLLAPSED_EPISODE_COUNT
                    updateSourceTabColors(position)
                    showEpisodesForCurrentGroup()
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    /** 失败态重试、收藏、分享这一排 */
    private fun setupActions() {
        binding.btnRetry.setOnClickListener { retryLoadVideoDetail() }

        // 错误文案本身也可点重试（与短剧详情同一套交互）
        binding.tvError.setOnClickListener {
            if (viewModel.error.value != null) retryLoadVideoDetail()
        }

        // 收藏 / 取消收藏：走已有 FavoriteDao，与播放器同一张表、同一个 videoId 口径
        binding.btnFavorite.setOnClickListener { toggleFavorite() }

        // 分享：标题 + 详情地址，纯文本
        binding.btnShare.setOnClickListener { shareVideo() }
    }

    /**
     * 重试：loadVideoDetail 内部会先把 _error 置空，这里不用手动清。
     * 地址本来就无效时，重试也只是再报一次同样的文案。
     */
    private fun retryLoadVideoDetail() {
        if (detailUrl.isBlank()) {
            fatalUrlError = "详情地址无效"
            renderContentState()
            return
        }
        fatalUrlError = null
        viewModel.loadVideoDetail(detailUrl)
    }

    // ---------------- 收藏 / 分享 ----------------

    private fun syncFavoriteState() {
        if (videoId == 0L) return
        lifecycleScope.launch {
            isFavorite = runCatching { favoriteDao.isFavorite(videoId) }
                .getOrDefault(false)
            updateFavoriteButton()
        }
    }

    private fun toggleFavorite() {
        if (videoId == 0L) {
            android.widget.Toast.makeText(this, "数据还没加载完，稍后再试", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                // 切换前现查一次库：内存里的 isFavorite 可能已经是旧的（播放器那边刚改过收藏）
                val favoriteNow = runCatching { favoriteDao.isFavorite(videoId) }
                    .getOrDefault(isFavorite)
                isFavorite = favoriteNow
                if (favoriteNow) {
                    favoriteDao.deleteFavoriteByVideoId(videoId)
                    isFavorite = false
                    android.widget.Toast.makeText(this@VideoDetailActivity, "已取消收藏", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    favoriteDao.insertFavorite(
                        FavoriteEntity(
                            videoId = videoId,
                            title = binding.tvTitle.text.toString(),
                            thumbnailUrl = coverUrl.ifEmpty { null },
                            detailUrl = detailUrl,
                            // 原始剧集列表才能让收藏页直接连播；一集都没加载时留 null，别塞个 "[]"
                            episodesJson = originalEpisodes
                                .takeIf { it.isNotEmpty() }
                                ?.let { buildEpisodesJson(it) }
                        )
                    )
                    isFavorite = true
                    android.widget.Toast.makeText(this@VideoDetailActivity, "已添加到收藏", android.widget.Toast.LENGTH_SHORT).show()
                }
                updateFavoriteButton()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "收藏状态切换失败", e)
                android.widget.Toast.makeText(this@VideoDetailActivity, "操作失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateFavoriteButton() {
        binding.btnFavorite.text = if (isFavorite) "已收藏" else "收藏"
    }

    private fun shareVideo() {
        val title = binding.tvTitle.text?.toString().orEmpty()
        val shareUrl = detailUrl.ifBlank {
            intent.getStringExtra("DETAIL_URL") ?: intent.getStringExtra("videoUrl") ?: ""
        }
        if (shareUrl.isBlank()) {
            android.widget.Toast.makeText(this, "暂无可分享的详情地址", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, if (title.isBlank()) shareUrl else "$title\n$shareUrl")
        }
        runCatching { startActivity(Intent.createChooser(send, "分享到")) }
            .onFailure { android.util.Log.w(TAG, "分享失败: ${it.message}") }
    }

    // ---------------- 线路分组 ----------------

    /**
     * 线路分组。
     *
     * 1) 采集源在剧集名里带了 "[线路名]" 前缀时（VideoDetailParser 就是这么拼的），直接按前缀分组；
     * 2) 拿不到前缀时，老实现会把所有集全塞进「默认线路」——多条线路被静默合并、Tab 也藏掉了。
     *    [Episode] 只有 name/url/isCloudStorage/cloudType/durationMinutes，**没有线路字段**，
     *    所以退一步按 url 的 host 兜底：不同 host 视为不同线路，保证线路不会被并成一条；
     *    只有一个 host 时才真正单组展示，并在日志里写明「采集源未提供线路信息，无法分线路」。
     */
    private fun buildEpisodeGroups(episodes: List<Episode>): List<EpisodeGroup> {
        if (episodes.isEmpty()) return emptyList()

        val groups = groupByLinePrefix(episodes)
        if (groups != null) return sortGroups(groups)

        // 没有任何 "[线路]" 前缀：按 host 兜底，绝不凭空把多条线路合并掉
        val byHost = LinkedHashMap<String, MutableList<Episode>>()
        episodes.forEach { episode ->
            val host = runCatching { java.net.URI(episode.url).host }.getOrNull()
            val key = if (host.isNullOrEmpty()) DEFAULT_LINE_NAME else host
            byHost.getOrPut(key) { mutableListOf() }.add(episode)
        }

        if (byHost.size <= 1) {
            android.util.Log.w(
                TAG,
                "采集源未提供线路信息，无法分线路（共 ${episodes.size} 集，单一 host），按原顺序单组展示"
            )
            return sortGroups(listOf(EpisodeGroup(DEFAULT_LINE_NAME, episodes)))
        }

        android.util.Log.w(TAG, "采集源未提供线路名，按 url host 兜底分成 ${byHost.size} 条线路")
        return sortGroups(byHost.map { (host, list) -> EpisodeGroup(host, list) })
    }

    /** 按 "[线路名]" 前缀分组并清掉前缀；一个前缀都没有时返回 null 交给 host 兜底 */
    private fun groupByLinePrefix(episodes: List<Episode>): List<EpisodeGroup>? {
        val lineRegex = """\[([^\]]+)]""".toRegex()
        val prefixRegex = """\[[^\]]+]\s*""".toRegex()

        val byLine = LinkedHashMap<String, MutableList<Episode>>()
        var matchedAny = false
        episodes.forEach { episode ->
            val lineName = lineRegex.find(episode.name)?.groupValues?.get(1)
            if (lineName != null) matchedAny = true
            byLine.getOrPut(lineName ?: DEFAULT_LINE_NAME) { mutableListOf() }.add(episode)
        }
        if (!matchedAny) return null

        return byLine.map { (sourceName, list) ->
            // 移除剧集名称中的线路前缀
            val cleaned = list.map { episode ->
                episode.copy(name = episode.name.replace(prefixRegex, "").trim().ifEmpty { episode.name })
            }
            EpisodeGroup(sourceName, cleaned)
        }
    }

    /**
     * 线路排序：m3u8 > 高清 > mp4，同分按线路名稳定排序（老实现只 sortedByDescending，
     * 同分线路之间的顺序随分组遍历顺序漂移）；有 http 直链的组再 +100，
     * 避免用户一进来就点到一个根本没地址可播的线路。
     */
    private fun sortGroups(groups: List<EpisodeGroup>): List<EpisodeGroup> {
        return groups.sortedWith(
            compareByDescending<EpisodeGroup> { scoreOf(it) }
                .thenBy { it.sourceName }
        )
    }

    private fun scoreOf(group: EpisodeGroup): Int {
        var score = 0
        val lowerName = group.sourceName.lowercase()

        // M3U8线路最高优先级
        if (lowerName.contains("m3u8")) score += 1000

        // 高清线路次优先
        if (lowerName.contains("hd") || lowerName.contains("高清") ||
            lowerName.contains("1080p") || lowerName.contains("720p")
        ) {
            score += 500
        }

        // MP4线路第三优先
        if (lowerName.contains("mp4")) score += 300

        // 有 http 直链才算「能播」，没有的往后排
        if (group.episodes.any { it.url.startsWith("http") }) score += 100

        return score
    }

    /**
     * 线路 Tab 的自定义视图：名字再长也只占一行、超过 160dp 打省略号，
     * 否则一条「XX线路-高清m3u8专用」就能把整排 Tab 撑到屏外。
     */
    private fun createSourceTabView(sourceName: String): android.widget.TextView {
        val padding = dp(8)
        return android.widget.TextView(this).apply {
            text = sourceName
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(160)
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(padding, padding, padding, padding)
        }
    }

    /**
     * 自定义 Tab 视图不会自动跟着 TabLayout 的 tabSelectedTextColor 变色，这里手动同步。
     */
    private fun updateSourceTabColors(selectedPosition: Int) {
        for (index in 0 until binding.tabLayout.tabCount) {
            val textView = binding.tabLayout.getTabAt(index)?.customView as? android.widget.TextView
                ?: continue
            textView.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (index == selectedPosition) R.color.theme_primary else R.color.theme_text_secondary
                )
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * 把 [target] 在滚动内容里的绝对 top 累加出来：卡片套卡片时直接读 `getTop()` 会差一大截。
     */
    private fun contentTopOf(target: View): Int {
        var offset = 0
        var current: View? = target
        while (current != null && current !== binding.scrollView) {
            offset += current.top
            current = current.parent as? View
        }
        return offset
    }

    /**
     * 让 NestedScrollView 滚到某个 View。
     *
     * [extraOffset] 用于「滚到第 N 行」这种行内定位（见 [contentTopOf]）。
     */
    private fun scrollToView(target: View, smooth: Boolean, extraOffset: Int = 0) {
        binding.scrollView.post {
            val y = (contentTopOf(target) + extraOffset).coerceAtLeast(0)
            if (smooth) {
                binding.scrollView.smoothScrollTo(0, y)
            } else {
                binding.scrollView.scrollTo(0, y)
            }
        }
    }

    // ---------------- 剧集展示 ----------------

    /**
     * 显示当前选中线路的剧集。
     *
     * 100+ 集的片源一次全铺，RecyclerView 在 NestedScrollView 里要量到很高，首屏明显卡，
     * 所以默认只铺前 60 集；点「展开全部」也不是一次给全部，而是按 [EPISODE_PAGE_SIZE] 一批，
     * 滚到网格底部附近再追加（见 [maybeAppendMoreEpisodes]）。
     */
    private fun showEpisodesForCurrentGroup() {
        val group = episodeGroups.getOrNull(currentGroupIndex)
        if (group == null) {
            android.util.Log.w(
                TAG,
                "无法显示剧集：线路索引越界 index=$currentGroupIndex, groups=${episodeGroups.size}"
            )
            return
        }

        val needExpand = group.episodes.size > COLLAPSED_EPISODE_COUNT
        val limit = if (episodesExpanded) episodeVisibleLimit else COLLAPSED_EPISODE_COUNT
        val visible = if (!needExpand) group.episodes else group.episodes.take(limit)
        episodeAdapter.submitList(visible)

        binding.tvEpisodeExpand.visibility = if (needExpand) View.VISIBLE else View.GONE
        binding.tvEpisodeExpand.text = when {
            !episodesExpanded -> "展开全部（共${group.episodes.size}集）"
            visible.size < group.episodes.size -> "收起（已显示${visible.size}/${group.episodes.size}集）"
            else -> "收起"
        }
    }

    /**
     * 展开状态下滚到网格底部附近就追加下一批。
     *
     * rvEpisodes 是 wrap_content 铺在 NestedScrollView 里的，RecyclerView 自身不滚动，
     * 所以「滚到底」只能按网格在滚动内容里的绝对位置判断，不能依赖 RecyclerView 的 onScrolled。
     * 距底部不足 [EPISODE_LOAD_MORE_THRESHOLD_DP] 时才追加，一次只追加一批。
     */
    private fun maybeAppendMoreEpisodes() {
        if (!episodesExpanded) return
        val group = episodeGroups.getOrNull(currentGroupIndex) ?: return
        if (episodeVisibleLimit >= group.episodes.size) return
        // 网格还没量过高度（0）时直接跳过，等下一次滚动
        val gridHeight = binding.rvEpisodes.height
        if (gridHeight <= 0) return

        val gridBottom = contentTopOf(binding.rvEpisodes) + gridHeight
        val viewportBottom = binding.scrollView.scrollY + binding.scrollView.height
        if (viewportBottom < gridBottom - dp(EPISODE_LOAD_MORE_THRESHOLD_DP)) return

        episodeVisibleLimit = (episodeVisibleLimit + EPISODE_PAGE_SIZE)
            .coerceAtMost(group.episodes.size)
        showEpisodesForCurrentGroup()
    }

    /**
     * 显示网盘资源处理对话框
     */
    private fun showCloudStorageDialog(episode: Episode) {
        val bottomSheetDialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_cloud_storage, null)

        // 设置标题和链接
        view.findViewById<android.widget.TextView>(R.id.cloudTitle).text = "${episode.cloudType} 网盘资源"
        view.findViewById<android.widget.TextView>(R.id.cloudSubtitle).text = "检测到这是网盘资源，请选择打开方式"
        view.findViewById<android.widget.TextView>(R.id.cloudUrl).text = episode.url

        // 图标颜色跟随主题强调色：原来按网盘类型写死一堆品牌色，夜间模式下又脏又撞色
        view.findViewById<android.widget.ImageView>(R.id.cloudIcon)
            .setColorFilter(ContextCompat.getColor(this, R.color.theme_accent))

        // 浏览器打开按钮
        view.findViewById<View>(R.id.btnOpenBrowser).setOnClickListener {
            openInBrowser(episode.url)
            bottomSheetDialog.dismiss()
        }

        // 复制链接按钮
        view.findViewById<View>(R.id.btnCopyLink).setOnClickListener {
            copyToClipboard(episode.url)
            bottomSheetDialog.dismiss()
        }

        // 取消按钮
        view.findViewById<View>(R.id.btnCancel).setOnClickListener {
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.setContentView(view)
        bottomSheetDialog.show()
    }

    /**
     * 在浏览器中打开
     */
    private fun openInBrowser(url: String) {
        try {
            val intent = Intent(this, com.tv.ball.presentation.webview.WebViewActivity::class.java)
            intent.putExtra("url", url)
            startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "打开失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 复制链接到剪贴板。
     * Android 13（API 33）起系统自己会弹「已复制」提示，再弹自己的 Toast 就是重复两条。
     */
    private fun copyToClipboard(url: String) {
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("视频链接", url)
        clipboard.setPrimaryClip(clip)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            android.widget.Toast.makeText(this, "链接已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 选中上次播放的集数：标题行给「上次看到第 N 集」，并切到对应线路 + 滚过去。
     */
    private fun selectLastPlayedEpisode(videoTitle: String, allEpisodes: List<Episode>) {
        if (videoTitle.isBlank() || allEpisodes.isEmpty()) return
        lifecycleScope.launch {
            try {
                // 历史记录走 DI 里已有的 HistoryRepository，不再每次 new 一个 LocalDataSource
                val history = historyRepository.getHistoryByTitle(videoTitle)
                val currentEpisode = history?.currentEpisode
                if (currentEpisode.isNullOrEmpty()) {
                    binding.tvLastWatched.visibility = View.GONE
                    return@launch
                }

                // 查找匹配的集数（在所有剧集中查找）
                val matchedEpisode = allEpisodes.find { episode ->
                    episode.name.contains(currentEpisode)
                } ?: allEpisodes.find { episode -> episode.url == history?.videoUrl }

                if (matchedEpisode == null) {
                    binding.tvLastWatched.visibility = View.GONE
                    return@launch
                }

                // 「看到第几集」的角标：先记在 Adapter 上，切线路重新 submitList 也不会丢
                episodeAdapter.setLastWatchedEpisode(matchedEpisode)

                // 找到匹配的集数，切换到对应的线路
                val groupIndex = episodeGroups.indexOfFirst { group ->
                    group.episodes.any { it.url == matchedEpisode.url }
                }
                if (groupIndex < 0) {
                    binding.tvLastWatched.visibility = View.GONE
                    return@launch
                }

                currentGroupIndex = groupIndex
                episodesExpanded = false
                // select() 已选中时不会回调 onTabSelected，所以再补一次渲染
                binding.tabLayout.getTabAt(groupIndex)?.select()
                updateSourceTabColors(groupIndex)
                showEpisodesForCurrentGroup()

                // 设置选中状态
                episodeAdapter.setSelectedEpisode(matchedEpisode)

                val group = episodeGroups[groupIndex]
                val position = group.episodes.indexOfFirst { it.url == matchedEpisode.url }
                if (position < 0) {
                    binding.tvLastWatched.visibility = View.GONE
                    return@launch
                }

                // 集号按当前线路内的顺序算，别拿原始列表的下标（带前缀时会对不上）
                binding.tvLastWatched.text = "上次看到第${position + 1}集"
                binding.tvLastWatched.visibility = View.VISIBLE

                // 目标集不在已铺开的范围里（60 集以内以外 / 批次还没顶到）就先自动展开，
                // 否则高亮会落在根本没渲染的位置上。展开后按批追加，这里把批次直接顶到能覆盖目标集
                if (position >= COLLAPSED_EPISODE_COUNT && position >= episodeVisibleLimit) {
                    episodesExpanded = true
                    episodeVisibleLimit = maxOf(
                        EPISODE_PAGE_SIZE,
                        ((position / EPISODE_PAGE_SIZE) + 1) * EPISODE_PAGE_SIZE
                    )
                    showEpisodesForCurrentGroup()
                }

                // 网格是 wrap_content 铺在 NestedScrollView 里，RecyclerView 自身不滚动，
                // 只能按「第几行 × 行高」估算外层 ScrollView 的目标位置
                binding.rvEpisodes.post {
                    val row = position / GRID_COLUMNS
                    val rowHeight = binding.rvEpisodes.getChildAt(0)?.height ?: dp(48)
                    scrollToView(binding.rvEpisodes, smooth = true, extraOffset = row * rowHeight - dp(16))
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "读取上次播放集数失败", e)
            }
        }
    }

    /**
     * 播放剧集
     */
    private fun playEpisode(episode: Episode) {
        // 更新选中状态
        episodeAdapter.setSelectedEpisode(episode)

        // 使用原始剧集列表（带线路前缀）来构建JSON和查找索引
        // 需要通过URL匹配找到原始剧集
        val originalEpisode = originalEpisodes.find { it.url == episode.url }
        val currentIndex = originalEpisodes.indexOf(originalEpisode)

        // 构建播放列表JSON（使用原始剧集列表）
        val episodesJson = buildEpisodesJson(originalEpisodes)

        val playerIntent = Intent(this, VideoPlayerActivity::class.java).apply {
            // 稳定 id：详情地址 hash。播放器读的是 getLongExtra("VIDEO_ID", 0)，必须给 Long
            putExtra("VIDEO_ID", videoId)
            putExtra("VIDEO_TITLE", binding.tvTitle.text.toString())
            putExtra("VIDEO_URL", episode.url)
            putExtra("THUMBNAIL_URL", coverUrl)
            putExtra("DETAIL_URL", detailUrl)
            putExtra("EPISODES_JSON", episodesJson)
            putExtra("START_INDEX", currentIndex)
        }
        startActivity(playerIntent)
    }

    /**
     * 拼播放列表 JSON。name/url 一律走 [JSONObject.quote] 转义 ——
     * 手拼裸字符串时，名字里一个引号就能把整个播放列表拼坏（切集直接失效）。
     */
    private fun buildEpisodesJson(episodes: List<Episode>): String {
        val jsonBuilder = StringBuilder("[")
        episodes.forEachIndexed { index, episode ->
            if (index > 0) jsonBuilder.append(",")
            jsonBuilder.append("{")
            jsonBuilder.append("\"name\":").append(JSONObject.quote(episode.name)).append(",")
            jsonBuilder.append("\"url\":").append(JSONObject.quote(episode.url))
            jsonBuilder.append("}")
        }
        jsonBuilder.append("]")
        return jsonBuilder.toString()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.videoDetail.collect { detail ->
                detail?.let {
                    hasDetail = true
                    bindDetail(it)
                    renderContentState()
                }
            }
        }

        lifecycleScope.launch {
            viewModel.isLoading.collect { renderContentState() }
        }

        lifecycleScope.launch {
            viewModel.error.collect { error ->
                // 只留错误日志；正常流程不再打 Log.d
                if (error != null) {
                    android.util.Log.w(TAG, "详情加载失败: $error")
                }
                renderContentState()
            }
        }
    }

    /** 元信息有值才显示，空值 GONE，别在标题下面留一串空白行 */
    private fun bindMeta(view: android.widget.TextView, value: String, prefix: String = "") {
        val text = value.trim()
        if (text.isEmpty()) {
            view.visibility = View.GONE
        } else {
            view.text = if (prefix.isEmpty()) text else prefix + text
            view.visibility = View.VISIBLE
        }
    }

    private fun bindDetail(detail: VideoDetail) {
        binding.tvTitle.text = detail.title
        // 折叠后的标题：折叠状态走 contentScrim（theme_primary）上的白字
        binding.collapsingToolbar.title = detail.title
        binding.tvDescription.text = detail.description

        // 保存原始剧集列表（带线路前缀）
        originalEpisodes = detail.episodes

        bindMeta(binding.tvYear, detail.year)
        bindMeta(binding.tvArea, detail.area)
        bindMeta(binding.tvType, detail.type)
        bindMeta(binding.tvDirector, detail.director, "导演: ")
        bindMeta(binding.tvActor, detail.actor, "主演: ")

        // 按线路分组
        val grouped = buildEpisodeGroups(detail.episodes)
        episodeGroups = grouped

        // 设置Tab
        binding.tabLayout.removeAllTabs()
        grouped.forEach { group ->
            binding.tabLayout.addTab(
                binding.tabLayout.newTab().setCustomView(createSourceTabView(group.sourceName))
            )
        }
        currentGroupIndex = 0
        episodesExpanded = false
        episodeVisibleLimit = COLLAPSED_EPISODE_COUNT
        updateSourceTabColors(currentGroupIndex)

        // 显示第一个线路的剧集
        if (grouped.isNotEmpty()) {
            showEpisodesForCurrentGroup()

            // 列表提交完成后,查找并选中上次播放的集数
            selectLastPlayedEpisode(detail.title, detail.episodes)
        }
    }

    /**
     * 进度条 / 空态 / 内容三者的互斥关系集中在这里，别在各个回调里各写一遍。
     *
     * - 首次加载中：只转圈，内容区 INVISIBLE（空卡片比空白更难看）；
     * - 已有内容时刷新：保留内容并压到 0.3 透明度，失败也不清屏，只在上方补一条错误；
     * - 失败且没内容：显示 tvError（可点重试），有重试价值时再给 btnRetry；
     * - 「详情地址无效」连请求都没发，重试没有意义，所以不给 btnRetry。
     */
    private fun renderContentState() {
        val loading = viewModel.isLoading.value
        val errorMsg = viewModel.error.value
        val fatal = fatalUrlError

        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE

        val contentVisible = hasDetail || (!loading && fatal == null && errorMsg == null)
        binding.cardInfo.visibility = if (contentVisible) View.VISIBLE else View.INVISIBLE
        binding.cardEpisodes.visibility = if (contentVisible) View.VISIBLE else View.INVISIBLE
        val contentAlpha = if (loading) 0.3f else 1f
        binding.cardInfo.alpha = contentAlpha
        binding.cardEpisodes.alpha = contentAlpha

        when {
            fatal != null -> {
                binding.tvError.text = fatal
                binding.tvError.visibility = View.VISIBLE
                binding.btnRetry.visibility = View.GONE
            }

            errorMsg != null -> {
                binding.tvError.text = "加载失败：$errorMsg"
                binding.tvError.visibility = View.VISIBLE
                binding.btnRetry.visibility = View.VISIBLE
            }

            else -> {
                binding.tvError.visibility = View.GONE
                binding.btnRetry.visibility = View.GONE
            }
        }
    }

    companion object {
        private const val TAG = "VideoDetailActivity"

        /** 网格列数，与播放器保持一致 */
        private const val GRID_COLUMNS = 6

        /** 默认铺多少集，超出走「展开全部」 */
        private const val COLLAPSED_EPISODE_COUNT = 60

        /** 展开后每批追加多少集（与播放器选集弹窗的分批口径一致） */
        private const val EPISODE_PAGE_SIZE = 200

        /** 距网格底部还剩多少 dp 就追加下一批 */
        private const val EPISODE_LOAD_MORE_THRESHOLD_DP = 200

        /** 采集源没给线路名时的兜底组名 */
        private const val DEFAULT_LINE_NAME = "默认线路"
    }
}
