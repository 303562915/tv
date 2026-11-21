package com.tv.ball.presentation.shortdrama

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.tv.ball.R
import com.tv.ball.databinding.ActivityShortDramaDetailBinding
import com.tv.ball.presentation.player.VideoPlayerActivity
import com.tv.ball.util.ImageLoader
import kotlinx.coroutines.launch
import org.json.JSONObject

class ShortDramaDetailActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ShortDramaDetail"

        /** 默认先铺 30 集：200+ 集一次性铺满会又卡又长，绝大多数人只看最近几集 */
        private const val COLLAPSED_EPISODE_COUNT = 30
        private const val GRID_COLUMNS = 6
    }

    private lateinit var binding: ActivityShortDramaDetailBinding
    private lateinit var viewModel: ShortDramaDetailViewModel
    private lateinit var episodeAdapter: EpisodeAdapter

    private var bookId: String = ""
    private var coverUrl: String = ""

    /** 当前源的完整分集（不截断），UI 只显示其中一部分 */
    private var allEpisodes: List<EpisodeItem> = emptyList()
    private var episodesExpanded = false
    private var introExpanded = false
    private var progressEntryApplied = false

    /** 列表页传进来的「直接定位到第 N 集」 */
    private var pendingStartEpisode = 0

    /** 当前 chip 的「名字+集数」指纹，用来判断要不要重建那一排 */
    private var sourceChipSignature = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        // 夜间模式与其它页一致：必须在 super.onCreate 之前应用
        val nightMode = com.tv.ball.di.Dependencies.configRepository.getNightMode()
        com.tv.ball.utils.ThemeUtils.applyNightMode(nightMode)
        super.onCreate(savedInstanceState)
        binding = ActivityShortDramaDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.tv.ball.utils.StatusBarUtils.setupTransparentStatusBar(this)

        // 详情页顶部是折叠 Toolbar：给它一个返回箭头，否则只能靠系统返回键
        binding.toolbar.setNavigationIcon(com.tv.ball.R.drawable.ic_back)
        binding.toolbar.setNavigationOnClickListener { finish() }

        viewModel = ViewModelProvider(this)[ShortDramaDetailViewModel::class.java]

        bookId = intent.getStringExtra("BOOK_ID") ?: return finish()
        val title = intent.getStringExtra("TITLE") ?: ""
        coverUrl = intent.getStringExtra("COVER") ?: ""
        pendingStartEpisode = readIntExtra("START_EPISODE")

        setupUI(title)
        setupRecyclerView()
        setupInteractions()
        observeViewModel()

        viewModel.loadDetail(bookId)
    }

    /**
     * START_EPISODE 可能是 Int（列表页正常传）也可能是 String（其它入口顺手写死），
     * 这里统一容错，别因为类型不对就整页解析失败。
     */
    private fun readIntExtra(key: String): Int {
        return when (val raw = intent?.extras?.get(key)) {
            is Int -> raw
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull() ?: 0
            else -> 0
        }
    }

    private fun setupUI(title: String) {
        binding.tvTitle.text = title

        ImageLoader.load(
            context = this,
            url = coverUrl,
            imageView = binding.ivCover,
            placeholder = R.drawable.ic_placeholder
        )

        applyIntroState()
    }

    private fun setupRecyclerView() {
        episodeAdapter = EpisodeAdapter { episode ->
            playEpisode(episode)
        }

        // 使用6列网格布局，和播放器保持一致
        binding.recyclerView.layoutManager = GridLayoutManager(this, GRID_COLUMNS)
        binding.recyclerView.adapter = episodeAdapter
    }

    private fun setupInteractions() {
        // 简介折叠 / 展开
        binding.tvIntroToggle.setOnClickListener {
            introExpanded = !introExpanded
            applyIntroState()
        }

        // 剧集分批：展开全部 / 收起
        binding.tvEpisodeExpand.setOnClickListener {
            episodesExpanded = !episodesExpanded
            renderEpisodes()
            if (!episodesExpanded) {
                // 收起后剩下的内容变短，把视图拉回选集区，别停在空白处
                binding.scrollView.smoothScrollTo(0, binding.recyclerView.top)
            }
        }

        // 空态那块在失败时可点重试；成功时不响应点击
        binding.emptyView.setOnClickListener {
            if (viewModel.error.value != null) {
                binding.emptyView.visibility = View.GONE
                viewModel.loadDetail(bookId)
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.detail.collect { detail ->
                detail?.let { bindDetail(it) }
            }
        }

        lifecycleScope.launch {
            viewModel.episodes.collect { episodes ->
                allEpisodes = episodes
                renderEpisodes()
                // 第一份分集到了才谈「上次看到第几集」，否则没法定位
                if (episodes.isNotEmpty()) applyProgressEntry()
            }
        }

        // 播放源列表 / 选中源：两条流分别收，都只是重绘那一排 chip
        lifecycleScope.launch {
            viewModel.sources.collect { renderSourceChips() }
        }
        lifecycleScope.launch {
            viewModel.selectedSourceIndex.collect { renderSourceChips() }
        }

        lifecycleScope.launch {
            viewModel.isLoading.collect { updatePlaceholderState() }
        }

        lifecycleScope.launch {
            viewModel.error.collect { updatePlaceholderState() }
        }
    }

    // ---------------- 详情信息 ----------------

    private fun bindDetail(detail: ShortDramaItem) {
        binding.tvAuthor.text = "演员: ${detail.author}"
        binding.tvType.text = detail.type
        binding.tvPlayCount.text =
            if (detail.playCnt == "0" || detail.playCnt.isEmpty()) "" else "${formatPlayCount(detail.playCnt)}播放"
        binding.tvEpisodeCount.text = if (detail.episodeCnt.isNotEmpty()) "共${detail.episodeCnt}集" else ""
        binding.tvIntro.text = detail.intro
        renderMetaInfo(detail)
        applyIntroState()
    }

    /**
     * 年份 / 评分 / 完结状态。字段缺失（null、空串、0 分）就跳过这一项，全缺整行不显示。
     *
     * 采集源里 vod_director / vod_area 目前没有落到 ShortDramaItem 上，
     * 所以这里只展示能拿到的 year / score / finished，不硬造导演和地区。
     */
    private fun renderMetaInfo(detail: ShortDramaItem) {
        val parts = ArrayList<String>(3)

        val year = detail.year.trim()
        if (year.isNotEmpty() && year != "0") parts.add(year)

        val score = detail.score.trim()
        if (score.isNotEmpty()) {
            // 采集源的评分经常是 "0.0" / "0"，这种等于没评分，不显示
            score.toDoubleOrNull()?.let {
                if (it > 0.0) parts.add("评分 ${trimScore(score)}")
            }
        }

        if (detail.finished) parts.add("已完结")

        if (parts.isEmpty()) {
            binding.tvMetaInfo.visibility = View.GONE
        } else {
            binding.tvMetaInfo.text = parts.joinToString(" · ")
            binding.tvMetaInfo.visibility = View.VISIBLE
        }
    }

    /** 8.0 这种就别显示成 8.000000，顺手把尾零去掉 */
    private fun trimScore(raw: String): String {
        val value = raw.toDoubleOrNull() ?: return raw
        return if (value % 1.0 == 0.0) value.toInt().toString() else String.format("%.1f", value)
    }

    // ---------------- 播放源 ----------------

    private fun renderSourceChips() {
        val sources = viewModel.sources.value
        val selected = viewModel.selectedSourceIndex.value

        // 只有一个源时这一排就是纯噪音
        if (sources.size <= 1) {
            binding.sourceScroll.visibility = View.GONE
            if (binding.sourceChipRow.childCount > 0) binding.sourceChipRow.removeAllViews()
            sourceChipSignature = ""
            return
        }

        binding.sourceScroll.visibility = View.VISIBLE

        // 源的名字/数量变了才重建 View；只是换选中态就只改颜色，避免每次高亮都重新 new TextView
        val signature = sources.joinToString("|") { it.name + "#" + it.episodes.size }
        if (signature != sourceChipSignature) {
            sourceChipSignature = signature
            binding.sourceChipRow.removeAllViews()
            sources.forEachIndexed { index, source ->
                val chip = TextView(this).apply {
                    text = source.name.ifBlank { "播放源${index + 1}" }
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(dp(14), dp(6), dp(14), dp(6))
                    setOnClickListener { viewModel.selectSource(index) }
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                binding.sourceChipRow.addView(chip, lp)
            }
        }

        // 复用收藏页的 chip 背景：主色底白字 / 常规底次要色字，不新增 drawable
        for (i in 0 until binding.sourceChipRow.childCount) {
            val chip = binding.sourceChipRow.getChildAt(i) as? TextView ?: continue
            val isSelected = i == selected
            chip.background = ContextCompat.getDrawable(
                this,
                if (isSelected) R.drawable.bg_fav_chip_selected else R.drawable.bg_fav_chip_normal
            )
            chip.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (isSelected) android.R.color.white else R.color.theme_text_secondary
                )
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---------------- 剧集列表 ----------------

    private fun visibleEpisodes(): List<EpisodeItem> {
        return if (episodesExpanded || allEpisodes.size <= COLLAPSED_EPISODE_COUNT) {
            allEpisodes
        } else {
            allEpisodes.take(COLLAPSED_EPISODE_COUNT)
        }
    }

    private fun renderEpisodes() {
        val visible = visibleEpisodes()
        episodeAdapter.submitList(visible)

        val needExpand = allEpisodes.size > COLLAPSED_EPISODE_COUNT
        binding.tvEpisodeExpand.visibility = if (needExpand) View.VISIBLE else View.GONE
        binding.tvEpisodeExpand.text =
            if (episodesExpanded) "收起" else "展开全部（共${allEpisodes.size}集）"

        updatePlaceholderState()
    }

    // ---------------- 视频进度 ----------------

    /**
     * 进详情时读本地进度：标题旁给个角标，并（在列表页没指定 START_EPISODE 时）
     * 自动滚到那一集。只做一次，后面切源/展开不再跳。
     */
    private fun applyProgressEntry() {
        if (progressEntryApplied) return
        progressEntryApplied = true

        val lastWatched = runCatching { ShortDramaProgressStore.progressOf(bookId)?.episodeNum ?: 0 }
            .onFailure { Log.w(TAG, "读取观看进度失败: ${it.message}") }
            .getOrDefault(0)

        if (lastWatched > 0) {
            binding.tvProgressHint.text = "上次看到第${lastWatched}集"
            binding.tvProgressHint.visibility = View.VISIBLE
            // 点角标 = 手动跳回去，比只提示更有用
            binding.tvProgressHint.setOnClickListener { focusEpisode(lastWatched) }
        } else {
            binding.tvProgressHint.visibility = View.GONE
        }

        // 列表页明确指定了集数就听它的，否则回落到本地进度
        val target = if (pendingStartEpisode > 0) pendingStartEpisode else lastWatched
        if (target > 0) {
            binding.root.post { focusEpisode(target) }
        }
    }

    /**
     * 把某一集高亮 + 滚到可见。集不在当前源里就安静放弃（切源后集号可能对不上）。
     */
    private fun focusEpisode(episodeNum: Int) {
        val indexInAll = allEpisodes.indexOfFirst { it.episodeNum == episodeNum }
        if (indexInAll < 0) {
            Log.d(TAG, "第${episodeNum}集不在当前播放源里，跳过定位")
            return
        }

        // 目标集被「默认 30 集」挡住了，先自动展开，否则用户看不到高亮
        if (!episodesExpanded && indexInAll >= COLLAPSED_EPISODE_COUNT) {
            episodesExpanded = true
            renderEpisodes()
        }

        val visible = visibleEpisodes()
        val index = visible.indexOfFirst { it.episodeNum == episodeNum }
        if (index < 0) return

        episodeAdapter.setSelectedEpisodeNum(episodeNum)

        // 网格是 wrap_content 铺在 NestedScrollView 里，RecyclerView 自身不滚动，
        // 只能拿到 item 位置后让外层 ScrollView 滚过去
        binding.recyclerView.post {
            (binding.recyclerView.layoutManager as? GridLayoutManager)
                ?.scrollToPositionWithOffset(index, 0)
            binding.recyclerView.post {
                val child = binding.recyclerView.findViewHolderForAdapterPosition(index)?.itemView
                if (child != null) {
                    binding.scrollView.smoothScrollTo(0, binding.recyclerView.top + child.top - dp(16))
                }
            }
        }
    }

    // ---------------- 简介 ----------------

    private fun applyIntroState() {
        val collapsed = !introExpanded
        binding.tvIntro.maxLines = if (collapsed) 3 else Int.MAX_VALUE
        binding.tvIntro.ellipsize = if (collapsed) TextUtils.TruncateAt.END else null
        binding.tvIntroToggle.text = if (collapsed) "展开" else "收起"

        // 「展开」只在真的超过 3 行时出现，否则是纯噪音；
        // 判定靠第 3 行有没有被省略号截断，所以必须等布局跑完（套两层 post 更稳）
        binding.tvIntro.post {
            binding.tvIntro.post { updateIntroToggleVisibility() }
        }
    }

    private fun updateIntroToggleVisibility() {
        val hasText = binding.tvIntro.text.isNotBlank()
        val layout = binding.tvIntro.layout
        val truncated = layout != null &&
            layout.lineCount >= 3 &&
            layout.getEllipsisCount(2) > 0

        binding.tvIntroToggle.visibility =
            if (hasText && (introExpanded || truncated)) View.VISIBLE else View.GONE
    }

    // ---------------- 空态 / 失败重试 ----------------

    private fun updatePlaceholderState() {
        val loading = viewModel.isLoading.value
        val errorMsg = viewModel.error.value
        val hasEpisodes = allEpisodes.isNotEmpty()

        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE

        when {
            // 首次加载中：别急着喊「暂无剧集」，等结果
            loading && !hasEpisodes -> binding.emptyView.visibility = View.GONE

            errorMsg != null -> {
                binding.emptyView.text = "加载失败，点我重试"
                binding.emptyView.setTextColor(ContextCompat.getColor(this, R.color.theme_accent))
                binding.emptyView.visibility = View.VISIBLE
            }

            !hasEpisodes -> {
                binding.emptyView.text = "暂无剧集"
                binding.emptyView.setTextColor(
                    ContextCompat.getColor(this, R.color.theme_text_secondary)
                )
                binding.emptyView.visibility = View.VISIBLE
            }

            else -> binding.emptyView.visibility = View.GONE
        }
    }

    // ---------------- 播放 ----------------

    private fun playEpisode(episode: EpisodeItem) {
        // 详情接口已经把 m3u8 直链带回来了，直接用；不再「点一集再问接口要地址」
        val videoUrl = episode.videoUrl.ifEmpty { ShortDramaRepository.resolvePlayUrl(episode.videoId) }
        if (videoUrl.isEmpty()) {
            Toast.makeText(this, "这一集没有可用的播放地址", Toast.LENGTH_SHORT).show()
            return
        }

        // 构建剧集列表 JSON（当前源的全部分集，不管 UI 是否折叠）
        val episodesJson = buildEpisodesJson()
        val currentIndex = viewModel.episodes.value.indexOf(episode)

        // 记进度：providerKey 就是 bookId 里 ":" 之前那段（如 "360zy"）。
        // 存失败不能挡住起播，所以只记日志。
        runCatching {
            ShortDramaProgressStore.record(
                bookId,
                binding.tvTitle.text.toString(),
                coverUrl,
                episode.episodeNum,
                allEpisodes.size,
                bookId.substringBefore(":")
            )
        }.onFailure { Log.w(TAG, "记录观看进度失败: ${it.message}") }

        // 详情页自己的高亮跟上，回来后一眼看到看到哪了
        episodeAdapter.setSelectedEpisodeNum(episode.episodeNum)

        val intent = Intent(this, VideoPlayerActivity::class.java).apply {
            putExtra("VIDEO_ID", System.currentTimeMillis())
            putExtra("VIDEO_URL", videoUrl)
            putExtra("VIDEO_TITLE", binding.tvTitle.text.toString())
            putExtra("THUMBNAIL_URL", getIntent().getStringExtra("COVER"))
            putExtra("EPISODES_JSON", episodesJson)
            putExtra("START_INDEX", currentIndex)
            putExtra("CURRENT_EPISODE", "第${episode.episodeNum}集")
            putExtra("IS_SHORT_DRAMA", true)  // 标记为短剧
        }
        startActivity(intent)
    }

    /**
     * 构建剧集列表 JSON（url 直接写直链，播放器切集不必再联网解析）
     */
    private fun buildEpisodesJson(): String {
        val episodes = viewModel.episodes.value
        val jsonBuilder = StringBuilder("[")

        episodes.forEachIndexed { index, episode ->
            if (index > 0) jsonBuilder.append(",")
            jsonBuilder.append("{")
            jsonBuilder.append("\"name\":").append(JSONObject.quote("第${episode.episodeNum}集")).append(",")
            jsonBuilder.append("\"videoId\":").append(JSONObject.quote(episode.videoId)).append(",")
            jsonBuilder.append("\"url\":").append(JSONObject.quote(episode.videoUrl))
            jsonBuilder.append("}")
        }

        jsonBuilder.append("]")
        return jsonBuilder.toString()
    }

    private fun formatPlayCount(count: String): String {
        return try {
            val num = count.toLong()
            when {
                num >= 100000000 -> String.format("%.1f亿", num / 100000000.0)
                num >= 10000 -> String.format("%.1f万", num / 10000.0)
                else -> count
            }
        } catch (e: Exception) {
            count
        }
    }
}