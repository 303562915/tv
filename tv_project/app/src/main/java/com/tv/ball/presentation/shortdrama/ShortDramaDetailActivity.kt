package com.tv.ball.presentation.shortdrama

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.tv.ball.R
import com.tv.ball.databinding.ActivityShortDramaDetailBinding
import com.tv.ball.presentation.shortdrama.player.ShortDramaPlayerExtras
import com.tv.ball.presentation.shortdrama.player.ShortDramaQueueItem
import com.tv.ball.presentation.shortdrama.player.ShortDramaQueueLine
import com.tv.ball.util.ImageLoader
import kotlinx.coroutines.launch
import org.json.JSONObject

class ShortDramaDetailActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ShortDramaDetail"

        /** 默认先铺 30 集：200+ 集一次性铺满会又卡又长，绝大多数人只看最近几集 */
        private const val COLLAPSED_EPISODE_COUNT = 30
        private const val GRID_COLUMNS = 6

        /** 简介折叠时允许的行数；「展开」按钮的显隐按这个值算，别散落在各处写 3 */
        private const val COLLAPSED_INTRO_LINES = 3

        /**
         * 单行格子的估算高度：item_episode_tencent 是 minHeight 60dp + 上下各 4dp margin。
         * 取 64dp 是因为 calculatePosition 允许 ±几像素误差，但绝不能算出「不用滚」；
         * 间距单独 +8dp，见 EPISODE_ROW_GAP_DP。
         */
        private const val EPISODE_ROW_HEIGHT_DP = 64
        private const val EPISODE_ROW_GAP_DP = 8

        /** 骨架块呼吸动画：0.4 ↔ 1.0，块间错开 90ms，和 F090 的骨架保持同一套手感 */
        private const val SKELETON_MIN_ALPHA = 0.4f
        private const val SKELETON_FADE_MS = 700L
        private const val SKELETON_STAGGER_MS = 90L

        /** 失败原因截断长度：一行放不下更长的，再长会把「点我重试」挤出屏幕 */
        private const val ERROR_REASON_MAX_CHARS = 40

        private const val STATE_EPISODES_EXPANDED = "STATE_EPISODES_EXPANDED"
        private const val STATE_INTRO_EXPANDED = "STATE_INTRO_EXPANDED"
        private const val STATE_PROGRESS_HINT_HIDDEN = "STATE_PROGRESS_HINT_HIDDEN"
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

    /**
     * 本地进度的集号，0 = 没有进度。
     * 主按钮文案、角标文案、切源后的重新定位都以它为准，所以在 applyProgressEntry 里存一份，
     * 不要每次去 store 里现读（读盘 + 解析 JSON，切个源就读一遍没必要）。
     */
    private var lastWatchedEpisodeNum = 0

    /** 用户长按角标主动隐藏了本次提示；只影响这一页的角标，不动全局进度库 */
    private var progressHintHidden = false

    /** 上一次把分集对齐到哪个源下标，-1 = 还没对齐过。用来判断「源真的换了」 */
    private var alignedSourceIndex = -1

    /** 正在跑的骨架呼吸动画，一次收干净，别留野动画跑在已销毁的 View 上 */
    private val skeletonAnimators = mutableListOf<ObjectAnimator>()

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
        // 恢复必须在 recycler/adapter 就绪之后：renderEpisodes() 会直接往 adapter 里提列表
        restoreUiState(savedInstanceState)
        observeViewModel()

        viewModel.loadDetail(bookId)
    }

    /**
     * 「展开全部」「展开简介」是纯 UI 状态，ViewModel 不管，只能自己存。
     * 不存的话转个屏就变回「3 行 + 前 30 集」，用户正看到第 80 集，一旋转就被折叠回去。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_EPISODES_EXPANDED, episodesExpanded)
        outState.putBoolean(STATE_INTRO_EXPANDED, introExpanded)
        outState.putBoolean(STATE_PROGRESS_HINT_HIDDEN, progressHintHidden)
    }

    /** 旋转回来先按保存的状态重画一遍，别等下一次数据回调才纠正 */
    private fun restoreUiState(savedInstanceState: Bundle?) {
        savedInstanceState ?: return
        episodesExpanded = savedInstanceState.getBoolean(STATE_EPISODES_EXPANDED, false)
        introExpanded = savedInstanceState.getBoolean(STATE_INTRO_EXPANDED, false)
        progressHintHidden = savedInstanceState.getBoolean(STATE_PROGRESS_HINT_HIDDEN, false)
        applyIntroState()
        renderEpisodes()
    }

    override fun onDestroy() {
        // 骨架动画是无限循环，不停掉会一直跑在已经 destroy 的 View 上
        stopSkeletonBreathing()
        super.onDestroy()
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
        applyTitle(title)

        ImageLoader.load(
            context = this,
            url = coverUrl,
            imageView = binding.ivCover,
            placeholder = R.drawable.ic_placeholder
        )

        applyIntroState()
    }

    /**
     * 标题要同时落到三处：
     * 1. 折叠工具栏 —— 整页收起后只剩这条工具栏，原来写死「短剧详情」，收起后根本看不到剧名；
     * 2. 内容区大字；
     * 3. 封面的 contentDescription —— 封面是纯图无文字，读屏用户只能靠它知道这是哪部剧。
     *
     * 空标题直接跳过：setTitle("") 会把工具栏刷成空白，比留旧标题更糟。
     */
    private fun applyTitle(title: String) {
        val display = title.trim()
        if (display.isEmpty()) return
        binding.tvTitle.text = display
        binding.collapsingToolbar.title = display
        binding.ivCover.contentDescription = display
    }

    private fun setupRecyclerView() {
        episodeAdapter = EpisodeAdapter { episode ->
            playEpisode(episode)
        }

        // 长按格子 = 复制这一集的直链。剪贴板与 Toast 都是页面级资源，
        // 所以接线放在这里，Adapter 只管把事件抛上来。
        episodeAdapter.onEpisodeLongClick = { episode -> copyEpisodeLink(episode) }

        // 使用6列网格布局，和播放器保持一致
        binding.recyclerView.layoutManager = GridLayoutManager(this, GRID_COLUMNS)
        binding.recyclerView.adapter = episodeAdapter
    }

    /**
     * 长按选集格子的动作：把这一集能用的地址抄进剪贴板。
     * 地址优先取详情接口直接带回来的直链，没有才回落到按 videoId 解析。
     */
    private fun copyEpisodeLink(episode: EpisodeItem) {
        val url = episode.videoUrl.ifEmpty { ShortDramaRepository.resolvePlayUrl(episode.videoId) }
        if (url.isEmpty()) {
            Toast.makeText(this, "这一集没有可用的播放地址", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("第${episode.episodeNum}集", url)
        )
        Toast.makeText(this, "已复制第${episode.episodeNum}集链接", Toast.LENGTH_SHORT).show()
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
            if (viewModel.error.value != null) retryLoad()
        }

        // 主操作：有进度就接着看那一集，没进度就从第 1 集起。
        // 「找不到第 N 集」是常态（换过源、采集源集号不连续），这时退回第 1 集，别让按钮点了没反应
        binding.btnPrimaryPlay.setOnClickListener {
            val targetNum = if (lastWatchedEpisodeNum > 0) lastWatchedEpisodeNum else 1
            val episode = allEpisodes.firstOrNull { it.episodeNum == targetNum }
                ?: allEpisodes.firstOrNull { it.episodeNum == 1 }
                ?: allEpisodes.firstOrNull()
            episode?.let { playEpisode(it) }
        }

        // 跳集：百集以上靠「展开全部」翻根本翻不动，输个集号直接定位
        binding.tvJumpEpisode.setOnClickListener { showJumpEpisodeDialog() }
    }

    /**
     * 重试：点了得立刻有反馈，所以先把失败态收掉、把转圈亮出来，再发请求。
     *
     * 顺序不能反：loadDetail 里有缓存命中分支，命中时 isLoading 不会真的翻一次
     * （它本来就是 false），光等流回调的话这一下点击在缓存场景里会像没响应。
     */
    private fun retryLoad() {
        binding.emptyView.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
        updateSkeletonVisibility(true)
        viewModel.loadDetail(bookId)
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

                val sourceIndex = viewModel.selectedSourceIndex.value
                when {
                    // 第一份分集到了才谈「上次看到第几集」，否则没法定位。这段只跑一次
                    !progressEntryApplied && episodes.isNotEmpty() -> {
                        applyProgressEntry()
                        alignedSourceIndex = sourceIndex
                    }

                    // 源换了：selectSource 之后 episodes 是新的，但进度还停在旧源上，
                    // 得按新列表重新对齐（原来只有 chip 高亮变了，进度角标和滚动位置全对不上）
                    alignedSourceIndex >= 0 && sourceIndex != alignedSourceIndex -> {
                        alignedSourceIndex = sourceIndex
                        realignProgressForSource()
                    }
                }
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
        // 列表页传进来的标题可能是空的、或者是被截断的别名；详情接口给了正式剧名就覆盖一次，
        // 折叠工具栏用的是同一个来源，所以这里一并同步
        val detailTitle = detail.title.trim()
        if (detailTitle.isNotEmpty() && detailTitle != binding.tvTitle.text.toString()) {
            applyTitle(detailTitle)
        }

        // 标签式信息（演员 / 类型 / 播放量 / 集数）没有数据就把对应的那块 GONE，
        // 整行全空时连行一起收掉，别在标题下面留一片空白
        val author = detail.author.trim()
        if (author.isNotEmpty()) {
            binding.tvAuthor.text = "演员: $author"
            binding.tvAuthor.visibility = View.VISIBLE
        } else {
            binding.tvAuthor.visibility = View.GONE
        }

        val type = detail.type.trim()
        if (type.isNotEmpty()) {
            binding.tvType.text = type
            binding.tvType.visibility = View.VISIBLE
        } else {
            binding.tvType.visibility = View.GONE
        }
        binding.rowAuthorType.visibility =
            if (author.isEmpty() && type.isEmpty()) View.GONE else View.VISIBLE

        val playCount = detail.playCnt.trim()
        if (playCount.isEmpty() || playCount == "0") {
            binding.tvPlayCount.visibility = View.GONE
        } else {
            binding.tvPlayCount.text = "${formatPlayCount(playCount)}播放"
            binding.tvPlayCount.visibility = View.VISIBLE
        }

        if (detail.episodeCnt.isNotEmpty()) {
            binding.tvEpisodeCount.text = "共${detail.episodeCnt}集"
            binding.tvEpisodeCount.visibility = View.VISIBLE
        } else {
            binding.tvEpisodeCount.visibility = View.GONE
        }
        binding.rowPlayStat.visibility =
            if (binding.tvPlayCount.visibility == View.GONE &&
                binding.tvEpisodeCount.visibility == View.GONE
            ) View.GONE else View.VISIBLE

        binding.tvIntro.text = detail.intro
        renderMetaInfo(detail)
        applyIntroState()
        // 详情到了，骨架/占位要跟着收掉；这一步不能只靠 isLoading，
        // 因为缓存命中时 isLoading 从没翻成 true，占位不会自己消失
        updatePlaceholderState()
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
                    // 线路名再长也只占一行、最宽 160dp，剩下的打省略号，否则一条长名字就撑爆这一排
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    maxWidth = dp(160)
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

        // 跳集入口跟着分集走：只有一集时它没有意义
        binding.tvJumpEpisode.visibility = if (allEpisodes.size > 1) View.VISIBLE else View.GONE

        updatePrimaryAction()
        refreshProgressHint()
        updatePlaceholderState()
    }

    /**
     * 主操作按钮：有本地进度 → 「继续看第 N 集」；没有 → 「播放第 1 集」。
     * 分集没加载出来 / 当前源一集都没有时整块 GONE——留一个点了没反应的按钮比看不见更糟。
     */
    private fun updatePrimaryAction() {
        if (allEpisodes.isEmpty()) {
            binding.btnPrimaryPlay.visibility = View.GONE
            return
        }
        val lastWatched = lastWatchedEpisodeNum
        binding.btnPrimaryPlay.text =
            if (lastWatched > 0) "继续看第${lastWatched}集" else "播放第1集"
        binding.btnPrimaryPlay.visibility = View.VISIBLE
    }

    // ---------------- 视频进度 ----------------

    /**
     * 进详情时读本地进度：标题旁给个角标，并（在列表页没指定 START_EPISODE 时）
     * 自动滚到那一集。只做一次，后面切源/展开不再走这里（切源走 realignProgressForSource）。
     */
    private fun applyProgressEntry() {
        if (progressEntryApplied) return
        progressEntryApplied = true

        val lastWatched = runCatching { ShortDramaProgressStore.progressOf(bookId)?.episodeNum ?: 0 }
            .onFailure { Log.w(TAG, "读取观看进度失败: ${it.message}") }
            .getOrDefault(0)
        lastWatchedEpisodeNum = lastWatched

        if (lastWatched > 0) {
            // 选集格子里给那一格描个边，光有标题旁的角标还是得自己找格子
            episodeAdapter.setLastWatchedEpisodeNum(lastWatched)
        }
        refreshProgressHint()
        updatePrimaryAction()

        // 列表页明确指定了集数就听它的，否则回落到本地进度
        val target = if (pendingStartEpisode > 0) pendingStartEpisode else lastWatched
        if (target > 0) {
            binding.root.post { focusEpisode(target) }
        }
    }

    /**
     * 切源后的重新对齐。
     *
     * 同一部剧换一条线路，集号不一定一一对应（有的源少几集、有的从 0 开始），
     * 所以「上次看到第 N 集」在新源里存在才定位，不存在就把高亮清掉——
     * 留着旧高亮会把用户引到错误的格子上。
     */
    private fun realignProgressForSource() {
        updatePrimaryAction()
        refreshProgressHint()

        if (lastWatchedEpisodeNum <= 0) {
            episodeAdapter.setSelectedEpisodeNum(-1)
            return
        }
        if (allEpisodes.any { it.episodeNum == lastWatchedEpisodeNum }) {
            focusEpisode(lastWatchedEpisodeNum)
        } else {
            Log.d(TAG, "新播放源里没有第${lastWatchedEpisodeNum}集，清掉高亮")
            episodeAdapter.setSelectedEpisodeNum(-1)
        }
    }

    /**
     * 「上次看到第 N 集」角标：文案带上比例（N/总数），用户能一眼判断还剩多少。
     *
     * 长按 = 只隐藏本次提示：ShortDramaProgressStore.clear() 是全局清空（所有剧一起没），
     * 为了藏一个角标去动全局进度库代价太大，所以这里只把角标和格子描边收掉。
     */
    private fun refreshProgressHint() {
        val lastWatched = lastWatchedEpisodeNum
        if (lastWatched <= 0 || progressHintHidden) {
            binding.tvProgressHint.visibility = View.GONE
            return
        }

        val total = allEpisodes.size
        binding.tvProgressHint.text =
            if (total > 0) "上次看到第${lastWatched}集 · $lastWatched/$total"
            else "上次看到第${lastWatched}集"
        binding.tvProgressHint.visibility = View.VISIBLE
        // 点角标 = 手动跳回去，比只提示更有用
        binding.tvProgressHint.setOnClickListener { focusEpisode(lastWatched) }
        binding.tvProgressHint.setOnLongClickListener {
            progressHintHidden = true
            binding.tvProgressHint.visibility = View.GONE
            // 格子上那圈描边属于同一份提示，一起收掉，否则「隐藏了」但格子上还亮着
            episodeAdapter.setLastWatchedEpisodeNum(-1)
            Toast.makeText(this, "已隐藏本次提示", Toast.LENGTH_SHORT).show()
            true
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

        // 网格是 wrap_content 铺在 NestedScrollView 里，RecyclerView 自身不滚动。
        // 原来靠 findViewHolderForAdapterPosition 拿 item 再滚，但目标集不在第一屏时它返回 null，
        // 高亮出来了、页面却停在原地（无声失败）。现在改成自己算 Y：
        // 行号 × 行高，行高取实测值，拿不到就用 64dp+8dp 估算——宁可差几像素，也不能不滚。
        binding.recyclerView.post {
            (binding.recyclerView.layoutManager as? GridLayoutManager)
                ?.scrollToPositionWithOffset(index, 0)

            val row = index / GRID_COLUMNS
            val measuredRowHeight = binding.recyclerView.getChildAt(0)?.height ?: 0
            val rowHeight = if (measuredRowHeight > 0) {
                measuredRowHeight + dp(EPISODE_ROW_GAP_DP)
            } else {
                dp(EPISODE_ROW_HEIGHT_DP + EPISODE_ROW_GAP_DP)
            }
            // 减 dp(16)：别让目标行死死贴着屏幕顶边，留一点上下文更好认
            val targetY = binding.recyclerView.top + row * rowHeight - dp(16)
            binding.scrollView.smoothScrollTo(0, targetY.coerceAtLeast(0))
        }
    }

    // ---------------- 简介 ----------------

    private fun applyIntroState() {
        val collapsed = !introExpanded
        binding.tvIntro.maxLines = if (collapsed) COLLAPSED_INTRO_LINES else Int.MAX_VALUE
        binding.tvIntro.ellipsize = if (collapsed) TextUtils.TruncateAt.END else null
        binding.tvIntroToggle.text = if (collapsed) "展开" else "收起"

        // 「展开」只在真的超过 3 行时出现，否则是纯噪音；
        // 判定要等布局跑完才有宽高可比（套两层 post 更稳）
        binding.tvIntro.post {
            binding.tvIntro.post { updateIntroToggleVisibility() }
        }
    }

    /**
     * 「展开」判定：文本实际高度 > 折叠时允许的高度。
     *
     * 原来的 getEllipsisCount(2) > 0 不可靠——折叠态下第 3 行有没有省略号取决于
     * TextView 的断行结果（同一段文本换个行宽就不省略了），按钮会时有时无。
     *
     * 也不能临时把 tvIntro 的 maxLines 放开再量：TextView 在宽度不变时会复用已有的
     * Layout（受 maxLines 影响的那份），量出来还是 3 行。所以这里用同样宽度、同样行距
     * 离线建一份不限行的 StaticLayout，只拿它算「真实高度」和「第 3 行底边」，
     * 真实控件一个属性都不动，不会打断正在跑的布局。
     */
    private fun updateIntroToggleVisibility() {
        val text = binding.tvIntro.text
        if (text.isBlank()) {
            binding.tvIntroToggle.visibility = View.GONE
            return
        }

        val availableWidth = binding.tvIntro.width -
            binding.tvIntro.compoundPaddingLeft - binding.tvIntro.compoundPaddingRight
        if (availableWidth <= 0) {
            // 还没量到宽度，按当前状态保守处理，下次 post 会再算一遍
            binding.tvIntroToggle.visibility = if (introExpanded) View.VISIBLE else View.GONE
            return
        }

        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, TextPaint(binding.tvIntro.paint), availableWidth)
            .setLineSpacing(binding.tvIntro.lineSpacingExtra, binding.tvIntro.lineSpacingMultiplier)
            .build()

        // 行数已经超过折叠行数 → 一定溢出；正好卡在 3 行时才比高度
        // （lineSpacingExtra 会让 height 略大于 getLineBottom(2)，所以用「大于」而不是「不等」）
        val overflowing = when {
            layout.lineCount > COLLAPSED_INTRO_LINES -> true
            layout.lineCount == COLLAPSED_INTRO_LINES ->
                layout.height > layout.getLineBottom(COLLAPSED_INTRO_LINES - 1).toInt()
            else -> false
        }

        binding.tvIntroToggle.visibility =
            if (introExpanded || overflowing) View.VISIBLE else View.GONE
    }

    // ---------------- 首屏骨架 ----------------

    /** 骨架块顺序与布局里 sd_detail_skeleton_block1..3 一致 */
    private fun skeletonBlocks(): List<View> = listOf(
        binding.sdDetailSkeletonBlock1,
        binding.sdDetailSkeletonBlock2,
        binding.sdDetailSkeletonBlock3
    )

    /**
     * 骨架只在「正在加载且详情还没到」时露脸。
     *
     * 判 detail 而不是判 episodes：详情回来之后副信息区就有内容了，
     * 这时还挂着骨架，会和真实文字叠在一起，看着像加载了两次。
     */
    private fun updateSkeletonVisibility(loading: Boolean) {
        val show = loading && viewModel.detail.value == null
        binding.sdDetailSkeleton.visibility = if (show) View.VISIBLE else View.GONE
        if (show) startSkeletonBreathing() else stopSkeletonBreathing()
    }

    /** 3 个占位块的 alpha 呼吸：0.4 → 1.0 → 0.4，无限循环，块间错开 90ms */
    private fun startSkeletonBreathing() {
        if (skeletonAnimators.isNotEmpty()) return
        skeletonBlocks().forEachIndexed { index, block ->
            block.alpha = SKELETON_MIN_ALPHA
            val animator = ObjectAnimator.ofFloat(block, View.ALPHA, SKELETON_MIN_ALPHA, 1f)
            animator.duration = SKELETON_FADE_MS
            animator.startDelay = index * SKELETON_STAGGER_MS
            animator.repeatCount = ValueAnimator.INFINITE
            animator.repeatMode = ValueAnimator.REVERSE
            animator.interpolator = AccelerateDecelerateInterpolator()
            animator.start()
            skeletonAnimators += animator
        }
    }

    /** 停掉呼吸动画并把 alpha 复位，否则骨架会停在半透明上，下次加载看着像没动 */
    private fun stopSkeletonBreathing() {
        skeletonAnimators.forEach { it.cancel() }
        skeletonAnimators.clear()
        skeletonBlocks().forEach { it.alpha = 1f }
    }

    // ---------------- 空态 / 失败重试 ----------------

    private fun updatePlaceholderState() {
        val loading = viewModel.isLoading.value
        val errorMsg = viewModel.error.value
        val hasEpisodes = allEpisodes.isNotEmpty()

        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        updateSkeletonVisibility(loading)

        when {
            // 首次加载中：别急着喊「暂无剧集」，等结果
            loading && !hasEpisodes -> binding.emptyView.visibility = View.GONE

            // 失败要把原因带出来：只说「加载失败」等于让用户猜（断网 / 源挂了 / 接口 500 一个样）。
            // 截到 40 字：TV 上一行放不下更长的，再多会把「点我重试」挤出屏幕
            errorMsg != null -> {
                val reason = errorMsg.trim().replace(Regex("\\s+"), " ").take(ERROR_REASON_MAX_CHARS)
                binding.emptyView.text =
                    if (reason.isEmpty()) "加载失败，点我重试" else "加载失败：$reason，点我重试"
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

    // ---------------- 跳集 ----------------

    /**
     * 跳集对话框：百集以上靠「展开全部」翻不动，输集号直接定位最省事。
     *
     * 用 AlertDialog + 原生 EditText 而不是自建布局：为一行输入建 layout、加依赖都不划算，
     * 而且这两样本仓已经有了（appcompat + EditText），不需要新东西。
     */
    private fun showJumpEpisodeDialog() {
        val total = allEpisodes.size
        if (total <= 0) {
            Toast.makeText(this, "分集还没加载好", Toast.LENGTH_SHORT).show()
            return
        }

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "输入 1 - $total"
            setSingleLine(true)
        }
        // setView 默认贴边，套一层带 padding 的容器，输入框才不会顶着对话框边框
        val container = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(
                input,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("跳到指定集")
            .setView(container)
            .setPositiveButton("跳转", null)
            .setNegativeButton("取消", null)
            .create()

        // 覆盖确定键：输入不合法时留在原地让用户改，而不是关掉对话框重开一遍
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
                val num = input.text.toString().trim().toIntOrNull()
                if (num == null || num !in 1..total) {
                    Toast.makeText(this, "请输入 1 - $total 之间的集数", Toast.LENGTH_SHORT).show()
                } else {
                    dialog.dismiss()
                    // 目标集可能被「默认 30 集」挡住，focusEpisode 内部会自动展开
                    focusEpisode(num)
                    if (allEpisodes.none { it.episodeNum == num }) {
                        Toast.makeText(this, "当前播放源里没有第${num}集", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dialog.show()
    }

    // ---------------- 播放 ----------------

    private fun playEpisode(episode: EpisodeItem) {
        // 详情接口已经把 m3u8 直链带回来了，直接用；不再「点一集再问接口要地址」
        val videoUrl = episode.videoUrl.ifEmpty { ShortDramaRepository.resolvePlayUrl(episode.videoId) }
        if (videoUrl.isEmpty()) {
            // 只弹「没有播放地址」等于把用户堵死在这一集上。
            // 详情页拿得到全部线路，有别的源就顺手指一下，并把切源那一排滚进视野
            val multiSource = viewModel.sources.value.size > 1
            val message = if (multiSource) "这一集没有可用的播放地址，可换线路再试"
            else "这一集没有可用的播放地址"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            if (multiSource && binding.sourceScroll.visibility == View.VISIBLE) {
                binding.scrollView.smoothScrollTo(0, binding.sourceScroll.top)
            }
            return
        }

        // 记进度：providerKey 就是 bookId 里 ":" 之前那段（如 "360zy"）。
        // 存失败不能挡住起播，所以只记日志。
        runCatching {
            ShortDramaProgressStore.record(
                bookId,
                binding.tvTitle.text.toString(),
                coverUrl,
                episode.episodeNum,
                // totalEpisodes 记「全部线路里的最大集数」：只记当前源的话，
                // 切源后列表页的「共 N 集」与进度比例都会跟着线路变
                viewModel.sources.value.maxOfOrNull { it.episodes.size } ?: allEpisodes.size,
                bookId.substringBefore(":")
            )
        }.onFailure { Log.w(TAG, "记录观看进度失败: ${it.message}") }

        // 详情页自己的高亮跟上，回来后一眼看到看到哪了
        episodeAdapter.setSelectedEpisodeNum(episode.episodeNum)

        // 把「全部线路 + 每条线路全部分集」整体交给竖屏播放器：播放器自己按集号对齐、
        // 起播失败原地换线，用户不用退回详情页。这里保留原下标，是因为下面过滤空线路后
        // 下标会错位，回头要靠它找回「用户当前选中的那条线」。
        val linesWithOrigin = viewModel.sources.value.mapIndexed { sourceIndex, source ->
            sourceIndex to ShortDramaQueueLine(
                name = source.name,
                items = source.episodes.mapNotNull { ep ->
                    // 没有直链的集在这里就剔掉：ShortDramaLinesCodec.decode 会丢弃空 url 的集，
                    // 留着它们会让「编码前的下标」比「播放器看到的下标」大，startIndex 整体前移，
                    // 表现就是点第 20 集却从第 18 集开始播。
                    // 被点击的那一集如果自己没直链，只为它单个解析一次，不批量解析（否则点一下会卡一串请求）。
                    val url = when {
                        ep.videoUrl.isNotBlank() -> ep.videoUrl
                        ep.episodeNum == episode.episodeNum -> videoUrl
                        else -> ""
                    }
                    if (url.isBlank()) {
                        null
                    } else {
                        ShortDramaQueueItem(
                            episodeNum = ep.episodeNum,
                            label = "第${ep.episodeNum}集",
                            url = url
                        )
                    }
                }
            )
        }.filter { it.second.items.isNotEmpty() }

        val queueLines = linesWithOrigin.map { it.second }

        // 起播线路：用户可能已经在详情页切过线，以 selectedSourceIndex 为准；
        // 该线路被过滤掉 / 下标越界时兜底成 0，保证一定能起播。
        val startLine = linesWithOrigin
            .indexOfFirst { it.first == viewModel.selectedSourceIndex.value }
            .takeIf { it >= 0 } ?: 0

        // 起播集：必须是「起播线路内、集号等于被点击这一集」的下标。
        // 不能用 viewModel.episodes.value.indexOf(episode)，那是当前源的下标，
        // 一旦切过线路或线路间集号排列不同就对不上，会静默跳到别的集。
        val startIndex = queueLines.getOrNull(startLine)
            ?.items
            ?.indexOfFirst { it.episodeNum == episode.episodeNum }
            ?.takeIf { it >= 0 } ?: 0

        // 兜底：ViewModel 还没回数据（或整份线路都没有直链）时 queueLines 会是空的。
        // 用当前点击这一集的直链临时造一条单集线路，播放器照样能播，不会白屏也不会崩。
        val finalLines = if (queueLines.isEmpty()) {
            listOf(
                ShortDramaQueueLine(
                    name = "当前线路",
                    items = listOf(
                        ShortDramaQueueItem(
                            episodeNum = episode.episodeNum,
                            label = "第${episode.episodeNum}集",
                            url = videoUrl
                        )
                    )
                )
            )
        } else {
            queueLines
        }

        startActivity(
            ShortDramaPlayerExtras.newIntent(
                context = this,
                bookId = bookId,
                title = binding.tvTitle.text.toString(),
                cover = coverUrl,
                lines = finalLines,
                startLine = if (queueLines.isEmpty()) 0 else startLine,
                startIndex = if (queueLines.isEmpty()) 0 else startIndex
            )
        )
    }

    /**
     * 构建剧集列表 JSON（url 直接写直链，播放器切集不必再联网解析）。
     *
     * 注意：详情页已改为把全部线路交给新的竖屏播放器（ShortDramaPlayerExtras），
     * 走的是 ShortDramaLinesCodec，不再需要这份老格式（EPISODES_JSON）。
     * 方法刻意保留：这是老播放器的分集格式，详情页后续若还要给别的入口（比如
     * 继续看 / 收藏直跳）复用就不必重写一遍；留着只是多一个未调用警告，不影响编译。
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