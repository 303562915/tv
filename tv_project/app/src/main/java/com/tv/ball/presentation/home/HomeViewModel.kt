package com.tv.ball.presentation.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tv.ball.data.remote.NetworkDataSource
import com.tv.ball.data.remote.NetworkResult
import com.tv.ball.di.Dependencies
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 一个分类页的分页状态。
 *
 * 首页每个分类各持一份，互不干扰：不再用一套全局 currentPage / hasMorePages
 * 让「切 Tab」「下拉刷新」「翻页」互相踩。
 */
data class HomePageState(
    val items: List<VideoItem> = emptyList(),
    /** 下一次要请求的页码（从 1 开始） */
    val nextPage: Int = 1,
    /** 是否还可能翻到新内容；判据是「上一页有没有带来新条目」，不再靠页大小猜 */
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    /** 首屏 / 下拉刷新进行中；翻页不走这个，翻页用 footer 的进度条 */
    val refreshing: Boolean = false,
    /** 加载更多失败时的提示，footer 上给「点击重试」用 */
    val error: String? = null
)

/** 每个分类各自记住筛选条件，切 Tab / 刷新时不会互相覆盖 */
private data class PageFilters(
    val type: String = "",
    val area: String = "",
    val year: String = "",
    val lang: String = ""
)

class HomeViewModel(
    private val networkDataSource: NetworkDataSource = Dependencies.networkDataSource,
    private val historyRepository: com.tv.ball.domain.repository.HistoryRepository = Dependencies.historyRepository,
    private val videoSourceAutoSwitcher: com.tv.ball.data.VideoSourceAutoSwitcher = Dependencies.videoSourceAutoSwitcher
) : ViewModel() {

    companion object {
        private const val TAG = "HomeViewModel"
        private const val MAX_AUTO_SWITCH_ATTEMPTS = 3 // 最多自动切换3次
        /** 保护性上限：源站若一直重复返回，hasMore=false 已经能停，这层再兜一次 */
        private const val MAX_PAGE = 100
    }

    /** 分类 id -> 分页状态；首页所有分类页共用这一个 StateFlow */
    private val _pages = MutableStateFlow<Map<Int, HomePageState>>(emptyMap())
    val pages: StateFlow<Map<Int, HomePageState>> = _pages.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _continuePlayingVideo = MutableStateFlow<com.tv.ball.data.model.HistoryEntity?>(null)
    val continuePlayingVideo: StateFlow<com.tv.ball.data.model.HistoryEntity?> = _continuePlayingVideo.asStateFlow()

    private val pageFilters = mutableMapOf<Int, PageFilters>()
    private val pageJobs = mutableMapOf<Int, Job>()
    private val pageGeneration = mutableMapOf<Int, Int>()
    private var autoSwitchAttempts = 0 // 自动切换尝试次数

    init {
        Log.d(TAG, "HomeViewModel initialized")
        loadContinuePlayingVideo()
    }

    fun pageState(category: Int): HomePageState = _pages.value[category] ?: HomePageState()

    private fun loadContinuePlayingVideo() {
        viewModelScope.launch {
            historyRepository.getAllHistory().collect { historyList ->
                // 获取最近播放且未播放完成的视频
                val recentVideo = historyList.firstOrNull { history ->
                    history.duration > 0 && history.watchedPosition < history.duration * 0.95
                }
                _continuePlayingVideo.value = recentVideo
                Log.d(TAG, "Continue playing video: ${recentVideo?.videoTitle}")
            }
        }
    }
    
    /** 首屏 / 下拉刷新 / 换筛选：重置分类页并从第 1 页重新拉 */
    fun loadFirstPage(
        category: Int = 6,
        type: String = "",
        area: String = "",
        year: String = "",
        lang: String = ""
    ) {
        autoSwitchAttempts = 0
        pageFilters[category] = PageFilters(type, area, year, lang)
        requestPage(category, page = 1, reset = true)
    }

    /** 下拉刷新：沿用该分类自己记住的筛选条件 */
    fun refreshPage(category: Int) {
        val filters = pageFilters[category] ?: PageFilters()
        loadFirstPage(category, filters.type, filters.area, filters.year, filters.lang)
    }

    /** 滑到接近底部：请求下一页。在途 / 到底 / 上次失败未重试时直接跳过，避免滚动一次发一串请求 */
    fun loadNextPage(category: Int) {
        val state = pageState(category)
        if (state.loading || !state.hasMore || state.error != null) return
        if (state.items.isEmpty()) return
        if (state.nextPage > MAX_PAGE) {
            updatePage(category) { it.copy(hasMore = false) }
            return
        }
        requestPage(category, page = state.nextPage, reset = false)
    }

    /** footer 上的「点击重试」：上一次是首屏 / 刷新失败的，就重新拉第 1 页；否则续拉下一页 */
    fun retryPage(category: Int) {
        val state = pageState(category)
        if (state.loading) return
        // nextPage<=1 说明上次重置没走完（刷新失败），此时不能拿第 1 页当「下一页」追加，
        // 否则去重后一条新增都没有，hasMore 会被永久判成 false
        if (state.items.isEmpty() || state.nextPage <= 1) {
            refreshPage(category)
            return
        }
        if (state.hasMore) requestPage(category, page = state.nextPage, reset = false)
    }

    /**
     * 请求某一页。
     *
     * 同一分类只保留一个在途请求：新请求会取消旧的，旧请求的迟到结果靠 generation 丢弃，
     * 这样「刷新时正好有翻页在飞」不会再互相覆盖，也不会出现清空列表后被挡回去的白屏。
     */
    private fun requestPage(category: Int, page: Int, reset: Boolean) {
        pageJobs[category]?.cancel()
        val generation = (pageGeneration[category] ?: 0) + 1
        pageGeneration[category] = generation

        val filters = pageFilters[category] ?: PageFilters()
        if (reset) _error.value = null

        // reset 时保留旧列表，等新结果回来再整体替换，下拉刷新不闪白
        updatePage(category) { prev ->
            if (reset) prev.copy(nextPage = 1, hasMore = true, loading = true, refreshing = true, error = null)
            else prev.copy(loading = true, error = null)
        }

        Log.d(TAG, "========== 请求分类 $category 第 $page 页 ==========")
        Log.d(TAG, "reset=$reset, 筛选参数: type=${filters.type}, area=${filters.area}, year=${filters.year}, lang=${filters.lang}")

        pageJobs[category] = viewModelScope.launch {
            try {
                // 使用当前选择的视频源
                val currentSource = com.tv.ball.data.VideoSourceManager.getCurrentSource()
                val isApiSource = com.tv.ball.data.VideoSourceManager.isApiSource(currentSource)
                val url = com.tv.ball.data.VideoSourceManager.getSourceApiUrl(
                    currentSource,
                    category,
                    page,
                    filters.type,
                    filters.area,
                    filters.year,
                    filters.lang
                )
                
                Log.d(TAG, "========== 请求信息 ==========")
                Log.d(TAG, "视频源: $currentSource")
                Log.d(TAG, "类型: ${if (isApiSource) "API" else "WEB"}")
                Log.d(TAG, "URL: $url")
                Log.d(TAG, "页码: $page")
                Log.d(TAG, "分类: $category")
                Log.d(TAG, "================================")
                
                when (val result = networkDataSource.get(url, getHeaders())) {
                    is NetworkResult.Success -> {
                        Log.d(TAG, "✓ 网络请求成功")
                        Log.d(TAG, "响应长度: ${result.data.length} 字符")
                        Log.d(TAG, "响应前200字符: ${result.data.take(200)}")

                        val videoList = if (isApiSource) {
                            // 检查是否是XML格式
                            if (result.data.trim().startsWith("<?xml")) {
                                parseApiXml(result.data, currentSource)
                            } else {
                                parseApiJson(result.data, category)
                            }
                        } else {
                            parseWebHtml(result.data, currentSource)
                        }

                        Log.d(TAG, "========== 解析结果 ==========")
                        Log.d(TAG, "解析到视频数: ${videoList.size}")
                        if (videoList.isNotEmpty()) {
                            Log.d(TAG, "第一个视频: ${videoList.first().title}")
                            Log.d(TAG, "详情链接: ${videoList.first().detailUrl}")
                        }
                        Log.d(TAG, "================================")

                        applyPageResult(category, page, reset, videoList, generation, paginated = currentSource != "taopian")
                    }
                    is NetworkResult.Error -> {
                        Log.e(TAG, "========== 网络请求失败 ==========")
                        Log.e(TAG, "错误信息: ${result.message}")
                        Log.e(TAG, "异常: ${result.exception}")
                        Log.e(TAG, "================================")
                        handleFailure(category, reset, "网络错误: ${result.message}", generation)
                    }
                }
            } catch (e: Exception) {
                // 取消不是失败：被新请求顶掉时安静退出，别在取消路径上再跑一轮换源/报错
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "========== 请求异常 ==========")
                Log.e(TAG, "异常类型: ${e.javaClass.simpleName}")
                Log.e(TAG, "异常信息: ${e.message}")
                Log.e(TAG, "堆栈跟踪:", e)
                Log.e(TAG, "================================")
                handleFailure(category, reset, "加载失败: ${e.message}", generation)
            } finally {
                // 只有还是当前这次请求才允许收尾，否则会把新请求刚设上的 loading 抹掉
                if (isCurrent(category, generation)) {
                    updatePage(category) { it.copy(loading = false) }
                }
                Log.d(TAG, "========== 分类 $category 第 $page 页请求结束 ==========")
            }
        }
    }

    /**
     * 合并这一页结果并推算是否还有下一页。
     *
     * 到底的判据改成「这一页没有带来任何新条目」：源站每页条数各不相同，
     * 原来按「少于 20 / 少于 5 条」判断会提前认定到底，用户滑到底就再也刷不出内容。
     */
    private fun applyPageResult(
        category: Int,
        page: Int,
        reset: Boolean,
        videoList: List<VideoItem>,
        generation: Int,
        paginated: Boolean
    ) {
        if (!isCurrent(category, generation)) return
        val prevItems = pageState(category).items
        val mergeKey: (VideoItem) -> String = { item -> item.detailUrl.ifBlank { item.title } }
        val merged = if (reset) {
            videoList.distinctBy(mergeKey)
        } else {
            (prevItems + videoList).distinctBy(mergeKey)
        }
        val newCount = merged.size - prevItems.size
        // 淘片这类「一页就是全部」的源没有翻页概念，一次拿完直接收工，不用再白打一次重复请求
        val reachedEnd = !paginated || (when {
            videoList.isEmpty() -> true
            page >= MAX_PAGE -> true
            reset -> false
            else -> newCount <= 0
        })

        _pages.update { map ->
            map + (category to HomePageState(
                items = merged,
                nextPage = page + 1,
                hasMore = !reachedEnd,
                loading = false,
                error = null
            ))
        }

        if (videoList.isEmpty() && reset) {
            _error.value = "未找到视频内容"
        }
        Log.d(TAG, "分类 $category 第 $page 页：解析 ${videoList.size} 条，新增 $newCount 条，共 ${merged.size} 条，hasMore=${!reachedEnd}")
        if (videoList.isEmpty()) Log.w(TAG, "⚠ 未解析到任何视频")
    }

    /** 失败收尾：首屏失败先自动换源（最多 3 次），其它情况只记错误等用户点重试，避免滚动一次打一次 */
    private suspend fun handleFailure(
        category: Int,
        reset: Boolean,
        message: String,
        generation: Int
    ) {
        if (!isCurrent(category, generation)) return

        if (reset && autoSwitchAttempts < MAX_AUTO_SWITCH_ATTEMPTS) {
            autoSwitchAttempts++
            Log.d(TAG, "尝试自动切换视频源 (第 $autoSwitchAttempts 次)")
            val currentSource = com.tv.ball.data.VideoSourceManager.getCurrentSource()
            val newSource = videoSourceAutoSwitcher.switchToAvailableSource(currentSource, category)
            if (newSource != null) {
                val newSourceName = com.tv.ball.data.VideoSourceManager.getSourceName(newSource)
                _error.value = "当前源不可用，已自动切换到: $newSourceName"
                // 先摘掉自己的登记，换源后的新请求才登记得进去
                pageJobs.remove(category)
                // 这 500ms 里用户可能自己点了刷新 / 换了分类，那就别再插一脚把新请求顶掉
                kotlinx.coroutines.delay(500)
                if (pageGeneration[category] != generation) return
                requestPage(category, page = 1, reset = true)
                return
            }
        }

        val finalMessage = if (autoSwitchAttempts >= MAX_AUTO_SWITCH_ATTEMPTS) {
            "已尝试多个视频源，请检查网络连接"
        } else {
            message
        }
        _error.value = finalMessage
        updatePage(category) { it.copy(loading = false, refreshing = false, error = finalMessage) }
    }

    private fun updatePage(category: Int, transform: (HomePageState) -> HomePageState) {
        _pages.update { map ->
            map + (category to transform(map[category] ?: HomePageState()))
        }
    }

    private fun isCurrent(category: Int, generation: Int): Boolean =
        pageGeneration[category] == generation
    
    private fun parseApiJson(json: String, category: Int): List<VideoItem> {
        val videos = mutableListOf<VideoItem>()
        val currentSource = com.tv.ball.data.VideoSourceManager.getCurrentSource()
        try {
            val listPattern = """"list":\[(.*?)\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val listMatch = listPattern.find(json)

            if (listMatch != null) {
                val listContent = listMatch.groupValues[1]
                // 匹配每个完整的JSON对象
                val itemPattern = """\{[^}]*?"vod_id":\s*(\d+)[^}]*?\}""".toRegex()

                for (match in itemPattern.findAll(listContent)) {
                    val itemJson = match.value

                    // 分别提取各个字段
                    val vodId = """"vod_id":\s*(\d+)""".toRegex().find(itemJson)?.groupValues?.get(1) ?: continue
                    val vodName = """"vod_name":\s*"([^"]+)"""".toRegex().find(itemJson)?.groupValues?.get(1) ?: continue
                    val vodPic = """"vod_pic":\s*"([^"]*)"""".toRegex().find(itemJson)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
                    val vodRemarks = """"vod_remarks":\s*"([^"]*)"""".toRegex().find(itemJson)?.groupValues?.get(1) ?: ""

                    // 淘片资源特殊处理：根据 type_name 进行分类过滤
                    if (currentSource == "taopian") {
                        val typeName = """"type_name":\s*"([^"]+)"""".toRegex().find(itemJson)?.groupValues?.get(1) ?: ""

                        // 根据当前分类过滤
                        val shouldInclude = when (category) {
                            1 -> typeName.contains("电影")  // 电影
                            2 -> typeName.contains("电视剧") && !typeName.contains("国产") && !typeName.contains("韩国") && !typeName.contains("日本")  // 剧集
                            3 -> typeName.contains("综艺")  // 综艺
                            4 -> typeName.contains("动漫")  // 动漫
                            6 -> typeName.contains("国产剧")  // 国产剧
                            8 -> typeName.contains("韩国剧") || typeName.contains("日本剧")  // 日韩剧
                            else -> true
                        }

                        if (!shouldInclude) {
                            continue
                        }
                    }

                    Log.d(TAG, "Parsed video: id=$vodId, name=$vodName, pic=$vodPic, remarks=$vodRemarks")

                    videos.add(
                        VideoItem(
                            title = vodName,
                            coverUrl = vodPic,
                            detailUrl = vodId, // 存储ID，后续使用
                            quality = vodRemarks,
                            sourceKey = currentSource
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse API JSON: ${e.message}")
        }
        return videos
    }
    
    private fun parseApiXml(xml: String, sourceKey: String): List<VideoItem> {
        val videos = mutableListOf<VideoItem>()
        try {
            // 匹配每个<video>标签
            val videoPattern = """<video>(.*?)</video>""".toRegex(RegexOption.DOT_MATCHES_ALL)

            for (match in videoPattern.findAll(xml)) {
                val videoXml = match.groupValues[1]

                // 提取各个字段 - 支持 CDATA 和普通文本两种格式
                val vodId = """<id>(\d+)</id>""".toRegex().find(videoXml)?.groupValues?.get(1) ?: continue

                // 匹配 CDATA 格式: <![CDATA[...]]> 或普通文本
                val vodName = ("""<name><!\[CDATA\[(.*?)\]\]></name>""".toRegex().find(videoXml)?.groupValues?.get(1)
                    ?: """<name>(.*?)</name>""".toRegex().find(videoXml)?.groupValues?.get(1)) ?: continue

                val vodPic = ("""<pic><!\[CDATA\[(.*?)\]\]></pic>""".toRegex().find(videoXml)?.groupValues?.get(1)
                    ?: """<pic>(.*?)</pic>""".toRegex().find(videoXml)?.groupValues?.get(1)) ?: ""

                val vodRemarks = ("""<note><!\[CDATA\[(.*?)\]\]></note>""".toRegex().find(videoXml)?.groupValues?.get(1)
                    ?: """<note>(.*?)</note>""".toRegex().find(videoXml)?.groupValues?.get(1)) ?: ""

                // 补全图片URL
                val fullPicUrl = when {
                    vodPic.isEmpty() -> ""
                    vodPic.startsWith("http") -> vodPic
                    sourceKey == "uuzy" -> "https://uuzy.me$vodPic"
                    else -> vodPic
                }

                Log.d(TAG, "Parsed XML video: id=$vodId, name=$vodName, pic=$fullPicUrl, remarks=$vodRemarks")

                videos.add(
                    VideoItem(
                        title = vodName,
                        coverUrl = fullPicUrl,
                        detailUrl = vodId,
                        quality = vodRemarks,
                        sourceKey = sourceKey
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse API XML: ${e.message}")
        }
        return videos
    }
    
    private fun parseWebHtml(html: String, sourceKey: String): List<VideoItem> {
        val videos = mutableListOf<VideoItem>()
        val cleanHtml = html.replace("\r", "").replace("\n", "")
        
        try {
            when (sourceKey) {
                "libvod" -> {
                    // libvod 解析
                    val baseUrl = "https://www.libvio.cc"
                    
                    android.util.Log.d(TAG, "========== LiB影视解析开始 ==========")
                    android.util.Log.d(TAG, "HTML长度: ${cleanHtml.length}")
                    android.util.Log.d(TAG, "基础URL: $baseUrl")
                    
                    // 尝试多种解析模式
                    // 模式1: 标准的 stui-vodlist__thumb lazyload 结构
                    val pattern1 = """<a class="stui-vodlist__thumb lazyload"[^>]*href="([^"]+)"[^>]*title="([^"]+)"[^>]*data-original="([^"]+)"[^>]*>.*?<span class="pic-text text-right">([^<]+)</span>.*?<span class="pic-tag pic-tag-top">([^<]+)</span>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                    
                    // 模式2: 简化版，只匹配基本信息
                    val pattern2 = """<a[^>]*class="stui-vodlist__thumb[^"]*"[^>]*href="([^"]+)"[^>]*title="([^"]+)"[^>]*data-original="([^"]+)"""".toRegex(RegexOption.DOT_MATCHES_ALL)

                    var matchCount = 0
                    
                    // 先尝试完整模式
                    for (match in pattern1.findAll(cleanHtml)) {
                        var link = match.groupValues[1]
                        val title = match.groupValues[2]
                        var img = match.groupValues[3]
                        val quality = match.groupValues[4]

                        // 处理相对路径
                        if (!link.startsWith("http")) {
                            link = baseUrl + link
                        }
                        if (img.startsWith("//")) {
                            img = "https:$img"
                        } else if (!img.startsWith("http")) {
                            img = baseUrl + img
                        }

                        matchCount++
                        if (matchCount <= 3) {
                            android.util.Log.d(TAG, "匹配 #$matchCount: $title")
                            android.util.Log.d(TAG, "  图片: $img")
                            android.util.Log.d(TAG, "  链接: $link")
                        }

                        videos.add(
                            VideoItem(
                                title = title.trim(),
                                coverUrl = img,
                                detailUrl = link,
                                quality = quality.trim(),
                                sourceKey = sourceKey
                            )
                        )
                    }
                    
                    // 如果完整模式没匹配到，尝试简化模式
                    if (videos.isEmpty()) {
                        android.util.Log.d(TAG, "完整模式未匹配，尝试简化模式")
                        for (match in pattern2.findAll(cleanHtml)) {
                            var link = match.groupValues[1]
                            val title = match.groupValues[2]
                            var img = match.groupValues[3]

                            // 处理相对路径
                            if (!link.startsWith("http")) {
                                link = baseUrl + link
                            }
                            if (img.startsWith("//")) {
                                img = "https:$img"
                            } else if (!img.startsWith("http")) {
                                img = baseUrl + img
                            }

                            matchCount++
                            if (matchCount <= 3) {
                                android.util.Log.d(TAG, "匹配 #$matchCount: $title")
                                android.util.Log.d(TAG, "  图片: $img")
                                android.util.Log.d(TAG, "  链接: $link")
                            }

                            videos.add(
                                VideoItem(
                                    title = title.trim(),
                                    coverUrl = img,
                                    detailUrl = link,
                                    quality = "HD",
                                    sourceKey = sourceKey
                                )
                            )
                        }
                    }
                    
                    android.util.Log.d(TAG, "========== ${if (sourceKey == "libvod") "LiB" else "LK"}影视解析结束 ==========")
                    android.util.Log.d(TAG, "匹配数: $matchCount, 有效视频数: ${videos.size}")
                    
                    // 如果没有解析到视频，打印调试信息
                    if (videos.isEmpty()) {
                        val sample = cleanHtml.take(3000)
                        android.util.Log.e(TAG, "========== 解析失败！HTML示例 ==========")
                        android.util.Log.e(TAG, sample)
                        
                        // 检查关键元素
                        android.util.Log.e(TAG, "包含 stui-vodlist__thumb: ${cleanHtml.contains("stui-vodlist__thumb")}")
                        android.util.Log.e(TAG, "包含 data-original: ${cleanHtml.contains("data-original")}")
                        android.util.Log.e(TAG, "包含 lazyload: ${cleanHtml.contains("lazyload")}")
                        android.util.Log.e(TAG, "========================================")
                    }
                }
                "keke" -> {
                    // 可可影视解析 - IP地址版本
                    // 网站基础URL: https://103.194.185.51:51120
                    // 图片CDN URL: https://vres.bavdxfg.cn/
                    val baseUrl = "https://103.194.185.51:51120"
                    val imgBaseUrl = "https://vres.bavdxfg.cn"
                    
                    android.util.Log.d(TAG, "========== 可可影视解析开始 ==========")
                    android.util.Log.d(TAG, "HTML长度: ${cleanHtml.length}")
                    android.util.Log.d(TAG, "网站URL: $baseUrl")
                    android.util.Log.d(TAG, "图片CDN: $imgBaseUrl")
                    
                    // 实际HTML结构:
                    // <a href="/detail/333078.html" class="v-item">
                    //   <div class="v-item-main">
                    //     <div class="v-item-cover">
                    //       <img data-original="placeholder">  <!-- 第一个img是占位符 -->
                    //       <img data-original="/vod1/vod/cover/xxx.webp">  <!-- 第二个img是真实封面 -->
                    //     </div>
                    //   </div>
                    //   <div class="v-item-footer">
                    //     <div class="v-item-title" style="display: none">可可影视</div>  <!-- 隐藏的 -->
                    //     <div class="v-item-title">追恶</div>  <!-- 真实标题 -->
                    //     <div class="v-item-title" style="display: none">可可影视</div>  <!-- 隐藏的 -->
                    //   </div>
                    // </a>
                    
                    // 分步解析：先找到所有 v-item 链接，然后在每个块中提取信息
                    val itemPattern = """<a[^>]*href="(/detail/\d+\.html)"[^>]*class="v-item"[^>]*>(.*?)</a>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                    
                    var matchCount = 0
                    for (itemMatch in itemPattern.findAll(cleanHtml)) {
                        val link = itemMatch.groupValues[1]
                        val itemContent = itemMatch.groupValues[2]
                        
                        // 在这个item中查找所有图片的data-original
                        val imgPattern = """data-original="([^"]+)"""".toRegex()
                        val imgMatches = imgPattern.findAll(itemContent).toList()
                        
                        // 过滤掉占位符图片，找到真实封面
                        val validImgs = imgMatches.map { it.groupValues[1] }
                            .filter { it.isNotEmpty() && !it.contains("placeholder") && !it.contains("loading") }
                        
                        // 取第一个有效图片（如果有多个，优先取第二个，因为第一个可能是占位符）
                        val img = when {
                            validImgs.size >= 2 -> validImgs[1] // 有多个时取第二个
                            validImgs.isNotEmpty() -> validImgs[0] // 只有一个时取第一个
                            else -> "" // 没有有效图片
                        }
                        
                        // 在这个item中查找所有v-item-title
                        val titlePattern = """<div[^>]*class="v-item-title"[^>]*>([^<]+)</div>""".toRegex()
                        val titleMatches = titlePattern.findAll(itemContent).toList()
                        
                        // 找到不包含"可可影视"的标题
                        var title = ""
                        for (titleMatch in titleMatches) {
                            val t = titleMatch.groupValues[1].trim()
                            if (!t.contains("可可影视") && !t.contains("kekys.com") && t.isNotEmpty()) {
                                title = t
                                break
                            }
                        }
                        
                        if (title.isEmpty()) {
                            continue
                        }
                        
                        matchCount++
                        android.util.Log.d(TAG, "========== 匹配 #$matchCount ==========")
                        android.util.Log.d(TAG, "标题: $title")
                        android.util.Log.d(TAG, "链接: $link")
                        android.util.Log.d(TAG, "原始图片: $img")
                        
                        // 处理相对路径
                        var fullLink = baseUrl + link
                        var fullImg = when {
                            img.isEmpty() || img == "placeholder" -> {
                                android.util.Log.d(TAG, "图片类型: 占位符或空，跳过")
                                "" // 空图片URL，将使用占位图
                            }
                            img.startsWith("https://") || img.startsWith("http://") -> {
                                android.util.Log.d(TAG, "图片类型: 完整URL")
                                img // 已经是完整URL，直接使用
                            }
                            img.startsWith("//") -> {
                                android.util.Log.d(TAG, "图片类型: 协议相对路径")
                                "https:$img"
                            }
                            img.startsWith("/") -> {
                                android.util.Log.d(TAG, "图片类型: 绝对路径，使用图片CDN")
                                // 可可影视的图片托管在 https://vres.bdwtjw.com/ CDN上
                                imgBaseUrl + img
                            }
                            else -> {
                                android.util.Log.d(TAG, "图片类型: 相对路径，使用图片CDN")
                                imgBaseUrl + "/" + img
                            }
                        }
                        
                        android.util.Log.d(TAG, "完整图片URL: $fullImg")
                        android.util.Log.d(TAG, "完整链接URL: $fullLink")
                        android.util.Log.d(TAG, "========================================")

                        videos.add(
                            VideoItem(
                                title = title,
                                coverUrl = fullImg,
                                detailUrl = fullLink,
                                quality = "HD",
                                sourceKey = sourceKey
                            )
                        )
                    }
                    
                    android.util.Log.d(TAG, "========== 可可影视解析结束 ==========")
                    android.util.Log.d(TAG, "匹配数: $matchCount, 有效视频数: ${videos.size}")
                    
                    // 如果没有解析到视频，打印调试信息
                    if (videos.isEmpty()) {
                        val sample = cleanHtml.take(3000)
                        android.util.Log.e(TAG, "========== 解析失败！HTML示例 ==========")
                        android.util.Log.e(TAG, sample)
                        
                        // 检查关键元素
                        android.util.Log.e(TAG, "包含 v-item class: ${cleanHtml.contains("class=\"v-item\"")}")
                        android.util.Log.e(TAG, "包含 v-item-title: ${cleanHtml.contains("v-item-title")}")
                        android.util.Log.e(TAG, "包含 /detail/: ${cleanHtml.contains("/detail/")}")
                        android.util.Log.e(TAG, "包含 data-original: ${cleanHtml.contains("data-original")}")
                        android.util.Log.e(TAG, "========================================")
                    }
                }
                "zxzj" -> {
                    // 在线之家解析
                    val baseUrl = "https://www.zxzjhd.com"
                    
                    android.util.Log.d(TAG, "========== 在线之家解析开始 ==========")
                    android.util.Log.d(TAG, "HTML长度: ${cleanHtml.length}")
                    android.util.Log.d(TAG, "基础URL: $baseUrl")
                    
                    // 在线之家HTML结构（改版后的苹果CMS vii 模板）：
                    // <a class="stui-vodlist__thumb lazyload" href="/voddetail/4601.html"
                    //    title="标题" data-original="https://img1.doubanio.com/...">
                    //   <span class="pic-text">已完结</span>
                    // </a>
                    // <div class="stui-vodlist__detail"><h4 class="title"><a ...>标题</a></h4></div>
                    
                    // 完整解析模式：包含图片和标题（标题取 a 标签的 title 属性，最稳）
                    val fullPattern = """href="(/voddetail/\d+\.html)"[^>]*title="([^"]+)"[^>]*data-original="([^"]+)"""".toRegex()
                    
                    var matchCount = 0
                    
                    // 先尝试完整模式（包含图片）
                    for (match in fullPattern.findAll(cleanHtml)) {
                        val link = match.groupValues[1]
                        val title = match.groupValues[2].trim()
                        var imageUrl = match.groupValues[3].trim()
                        
                        if (title.isEmpty()) continue
                        
                        // 修复图片URL - 补全协议
                        if (imageUrl.startsWith("//")) {
                            imageUrl = "https:$imageUrl"
                        }
                        
                        matchCount++
                        if (matchCount <= 3) {
                            android.util.Log.d(TAG, "匹配 #$matchCount: $title")
                            android.util.Log.d(TAG, "  链接: $link")
                            android.util.Log.d(TAG, "  图片: $imageUrl")
                        }
                        
                        videos.add(
                            VideoItem(
                                title = title,
                                coverUrl = imageUrl,
                                detailUrl = baseUrl + link,
                                quality = "HD",
                                sourceKey = sourceKey
                            )
                        )
                    }
                    
                    // 如果完整模式没匹配到，尝试只匹配标题（无图片）
                    if (videos.isEmpty()) {
                        android.util.Log.d(TAG, "完整模式未匹配，尝试仅标题模式")
                        val titleOnlyPattern = """href="(/voddetail/\d+\.html)"[^>]*title="([^"]+)"""".toRegex()
                        
                        for (match in titleOnlyPattern.findAll(cleanHtml)) {
                            val link = match.groupValues[1]
                            val title = match.groupValues[2].trim()
                            
                            if (title.isEmpty()) continue
                            
                            matchCount++
                            if (matchCount <= 3) {
                                android.util.Log.d(TAG, "匹配 #$matchCount: $title")
                                android.util.Log.d(TAG, "  链接: $link")
                            }
                            
                            videos.add(
                                VideoItem(
                                    title = title,
                                    coverUrl = "",
                                    detailUrl = baseUrl + link,
                                    quality = "HD",
                                    sourceKey = sourceKey
                                )
                            )
                        }
                    }
                    
                    android.util.Log.d(TAG, "========== 在线之家解析结束 ==========")
                    android.util.Log.d(TAG, "匹配数: $matchCount, 有效视频数: ${videos.size}")
                    
                    // 如果没有解析到视频，打印调试信息
                    if (videos.isEmpty()) {
                        val sample = cleanHtml.take(3000)
                        android.util.Log.e(TAG, "========== 解析失败！HTML示例 ==========")
                        android.util.Log.e(TAG, sample)
                        android.util.Log.e(TAG, "包含 /voddetail/: ${cleanHtml.contains("/voddetail/")}")
                        android.util.Log.e(TAG, "包含 stui-vodlist__thumb: ${cleanHtml.contains("stui-vodlist__thumb")}")
                        android.util.Log.e(TAG, "包含 data-original: ${cleanHtml.contains("data-original")}")
                        android.util.Log.e(TAG, "========================================")
                    }
                }
                "dadaqu" -> {
                    // 达达趣解析
                    val baseUrl = "https://www.dadaqu.me"
                    
                    android.util.Log.d(TAG, "========== 达达趣解析开始 ==========")
                    android.util.Log.d(TAG, "HTML长度: ${cleanHtml.length}")
                    android.util.Log.d(TAG, "基础URL: $baseUrl")
                    
                    var matchCount = 0
                    
                    // 模式1: module-item 结构（苹果CMS常见格式）
                    val pattern1 = """<a[^>]*class="[^"]*module-item[^"]*"[^>]*href="([^"]+)"[^>]*>.*?<img[^>]*data-original="([^"]+)".*?<div[^>]*class="[^"]*module-item-text[^"]*"[^>]*>([^<]+)</div>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                    
                    for (match in pattern1.findAll(cleanHtml)) {
                        val link = match.groupValues[1]
                        var imageUrl = match.groupValues[2].trim()
                        val title = match.groupValues[3].trim()
                        
                        if (title.isEmpty()) continue
                        
                        if (imageUrl.startsWith("//")) {
                            imageUrl = "https:$imageUrl"
                        } else if (!imageUrl.startsWith("http")) {
                            imageUrl = baseUrl + imageUrl
                        }
                        
                        matchCount++
                        if (matchCount <= 3) {
                            android.util.Log.d(TAG, "模式1匹配 #$matchCount: $title")
                            android.util.Log.d(TAG, "  链接: $link")
                            android.util.Log.d(TAG, "  图片: $imageUrl")
                        }
                        
                        videos.add(
                            VideoItem(
                                title = title,
                                coverUrl = imageUrl,
                                detailUrl = if (link.startsWith("http")) link else baseUrl + link,
                                quality = "HD",
                                sourceKey = sourceKey
                            )
                        )
                    }
                    
                    // 模式2: stui-vodlist__thumb 结构
                    if (videos.isEmpty()) {
                        android.util.Log.d(TAG, "模式1未匹配，尝试模式2")
                        val pattern2 = """<a[^>]*href="([^"]+)"[^>]*class="[^"]*stui-vodlist__thumb[^"]*"[^>]*>.*?<img[^>]*data-original="([^"]+)".*?<h4[^>]*class="[^"]*title[^"]*"[^>]*>([^<]+)</h4>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                        
                        for (match in pattern2.findAll(cleanHtml)) {
                            val link = match.groupValues[1]
                            var imageUrl = match.groupValues[2].trim()
                            val title = match.groupValues[3].trim()
                            
                            if (title.isEmpty()) continue
                            
                            if (imageUrl.startsWith("//")) {
                                imageUrl = "https:$imageUrl"
                            } else if (!imageUrl.startsWith("http")) {
                                imageUrl = baseUrl + imageUrl
                            }
                            
                            matchCount++
                            if (matchCount <= 3) {
                                android.util.Log.d(TAG, "模式2匹配 #$matchCount: $title")
                                android.util.Log.d(TAG, "  链接: $link")
                                android.util.Log.d(TAG, "  图片: $imageUrl")
                            }
                            
                            videos.add(
                                VideoItem(
                                    title = title,
                                    coverUrl = imageUrl,
                                    detailUrl = if (link.startsWith("http")) link else baseUrl + link,
                                    quality = "HD",
                                    sourceKey = sourceKey
                                )
                            )
                        }
                    }
                    
                    // 模式3: 通用的视频列表项（更宽松的匹配）
                    if (videos.isEmpty()) {
                        android.util.Log.d(TAG, "模式2未匹配，尝试模式3（通用）")
                        // 匹配任何包含 /detail/ 或 /play/ 链接的项
                        val pattern3 = """<a[^>]*href="(/(?:detail|play)/\d+[^"]*\.html)"[^>]*>""".toRegex()
                        
                        for (match in pattern3.findAll(cleanHtml)) {
                            val link = match.groupValues[1]
                            
                            // 在这个链接附近查找标题和图片
                            val startPos = match.range.first
                            val endPos = minOf(startPos + 1000, cleanHtml.length)
                            val context = cleanHtml.substring(maxOf(0, startPos - 200), endPos)
                            
                            // 查找标题（多种可能的标签）
                            val titlePatterns = listOf(
                                """<h\d[^>]*>([^<]+)</h\d>""".toRegex(),
                                """<div[^>]*class="[^"]*title[^"]*"[^>]*>([^<]+)</div>""".toRegex(),
                                """<span[^>]*class="[^"]*title[^"]*"[^>]*>([^<]+)</span>""".toRegex(),
                                """<p[^>]*class="[^"]*title[^"]*"[^>]*>([^<]+)</p>""".toRegex(),
                                """title="([^"]+)"""".toRegex()
                            )
                            
                            var title = ""
                            for (titlePattern in titlePatterns) {
                                val titleMatch = titlePattern.find(context)
                                if (titleMatch != null) {
                                    title = titleMatch.groupValues[1].trim()
                                    if (title.isNotEmpty() && title.length >= 2 && !title.matches(Regex("^[\\d\\s]+$"))) {
                                        break
                                    }
                                }
                            }
                            
                            if (title.isEmpty()) continue
                            
                            // 查找图片
                            val imgPattern = """<img[^>]*(?:data-original|src)="([^"]+)"""".toRegex()
                            val imgMatch = imgPattern.find(context)
                            var imageUrl = imgMatch?.groupValues?.get(1) ?: ""
                            
                            if (imageUrl.startsWith("//")) {
                                imageUrl = "https:$imageUrl"
                            } else if (imageUrl.isNotEmpty() && !imageUrl.startsWith("http")) {
                                imageUrl = baseUrl + imageUrl
                            }
                            
                            matchCount++
                            if (matchCount <= 3) {
                                android.util.Log.d(TAG, "模式3匹配 #$matchCount: $title")
                                android.util.Log.d(TAG, "  链接: $link")
                                android.util.Log.d(TAG, "  图片: $imageUrl")
                            }
                            
                            videos.add(
                                VideoItem(
                                    title = title,
                                    coverUrl = imageUrl,
                                    detailUrl = baseUrl + link,
                                    quality = "HD",
                                    sourceKey = sourceKey
                                )
                            )
                            
                            // 限制最多解析50个，避免重复
                            if (matchCount >= 50) break
                        }
                    }
                    
                    android.util.Log.d(TAG, "========== 达达趣解析结束 ==========")
                    android.util.Log.d(TAG, "匹配数: $matchCount, 有效视频数: ${videos.size}")
                    
                    // 如果没有解析到视频，打印调试信息
                    if (videos.isEmpty()) {
                        val sample = cleanHtml.take(3000)
                        android.util.Log.e(TAG, "========== 解析失败！HTML示例 ==========")
                        android.util.Log.e(TAG, sample)
                        android.util.Log.e(TAG, "包含 module-item: ${cleanHtml.contains("module-item")}")
                        android.util.Log.e(TAG, "包含 stui-vodlist__thumb: ${cleanHtml.contains("stui-vodlist__thumb")}")
                        android.util.Log.e(TAG, "包含 /detail/: ${cleanHtml.contains("/detail/")}")
                        android.util.Log.e(TAG, "包含 /play/: ${cleanHtml.contains("/play/")}")
                        android.util.Log.e(TAG, "包含 data-original: ${cleanHtml.contains("data-original")}")
                        android.util.Log.e(TAG, "包含 <img: ${cleanHtml.contains("<img")}")
                        android.util.Log.e(TAG, "========================================")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse web HTML: ${e.message}")
        }
        
        return videos
    }
    
    private fun parseIqiyiJson(json: String): List<VideoItem> {
        val videos = mutableListOf<VideoItem>()
        try {
            // 简单的 JSON 解析
            val listPattern = """"list":\[(.*?)\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val listMatch = listPattern.find(json)
            
            if (listMatch != null) {
                val listContent = listMatch.groupValues[1]
                
                // 解析每个视频项
                val itemPattern = """\{[^}]*"vod_id":(\d+)[^}]*"vod_name":"([^"]+)"[^}]*"vod_pic":"([^"]+)"[^}]*"vod_remarks":"([^"]*)"[^}]*\}""".toRegex()
                
                for (match in itemPattern.findAll(listContent)) {
                    val vodId = match.groupValues[1]
                    val vodName = match.groupValues[2]
                    val vodPic = match.groupValues[3].replace("\\/", "/") // 移除转义字符
                    val vodRemarks = match.groupValues[4]
                    
                    videos.add(
                        VideoItem(
                            title = vodName,
                            coverUrl = vodPic,
                            detailUrl = "https://iqiyizyapi.com/index.php/vod/detail/id/$vodId.html",
                            quality = vodRemarks,
                            sourceKey = "iqiyi"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse iqiyi JSON: ${e.message}")
        }
        return videos
    }
    
    private fun parseVideoList(html: String): List<VideoItem> {
        val videos = mutableListOf<VideoItem>()
        val cleanHtml = html.replace("\r", "").replace("\n", "")
        
        Log.d(TAG, "Parsing HTML, length: ${cleanHtml.length}")
        
        // Check if HTML contains video items
        val hasModulePoster = cleanHtml.contains("module-poster-item")
        Log.d(TAG, "HTML contains 'module-poster-item': $hasModulePoster")
        
        if (!hasModulePoster) {
            Log.d(TAG, "HTML does not contain expected video item structure")
            return videos
        }
        
        // 打印 HTML 片段用于调试
        val sampleIndex = cleanHtml.indexOf("module-poster-item")
        if (sampleIndex > 0) {
            val sample = cleanHtml.substring(
                maxOf(0, sampleIndex - 50),
                minOf(cleanHtml.length, sampleIndex + 500)
            )
            Log.d(TAG, "HTML sample around module-poster-item: $sample")
        }
        
        // 使用 DOT_MATCHES_ALL 选项
        val pattern = """<a\s+href="([^"]+)"[^>]*class="module-poster-item\s+module-item"[^>]*>.*?<div\s+class="module-item-note">\s*([^<]+)\s*</div>.*?data-original="([^"]+)".*?<div\s+class="module-poster-item-title">\s*([^<]+)\s*</div>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        
        var count = 0
        for (match in pattern.findAll(cleanHtml)) {
            if (count >= 24) break
            
            val link = match.groupValues[1]
            val quality = match.groupValues[2]
            val img = match.groupValues[3]
            val title = match.groupValues[4]
            
            Log.d(TAG, "Matched video: title=$title, link=$link, img=$img, quality=$quality")
            
            val imgUrl = when {
                img.startsWith("//") -> "https:$img"
                !img.matches(Regex("^https?://.*")) -> "https://www.freeok.mobi$img"
                else -> img
            }
            
            videos.add(
                VideoItem(
                    title = title.trim(),
                    coverUrl = imgUrl,
                    detailUrl = "https://www.freeok.mobi$link",
                    quality = quality.trim(),
                    sourceKey = "freeok"
                )
            )
            count++
        }
        
        Log.d(TAG, "Pattern matched $count videos")
        return videos
    }
    
    private fun getHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13; SM-T837A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6",
            // 不设置 Accept-Encoding,让 OkHttp 自动处理 gzip 解压缩
            "Cache-Control" to "no-cache"
        )
    }
}

data class VideoItem(
    val title: String,
    val coverUrl: String,
    val detailUrl: String,
    val quality: String,
    val sourceKey: String = "" // 视频来源标识，用于多站点搜索时区分来源
)
