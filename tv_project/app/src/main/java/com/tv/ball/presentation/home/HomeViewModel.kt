package com.tv.ball.presentation.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tv.ball.data.remote.NetworkDataSource
import com.tv.ball.data.remote.NetworkResult
import com.tv.ball.di.Dependencies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(
    private val networkDataSource: NetworkDataSource = Dependencies.networkDataSource,
    private val historyRepository: com.tv.ball.domain.repository.HistoryRepository = Dependencies.historyRepository,
    private val videoSourceAutoSwitcher: com.tv.ball.data.VideoSourceAutoSwitcher = Dependencies.videoSourceAutoSwitcher
) : ViewModel() {

    companion object {
        private const val TAG = "HomeViewModel"
        private const val MAX_AUTO_SWITCH_ATTEMPTS = 3 // 最多自动切换3次
    }

    private val _videos = MutableStateFlow<List<VideoItem>>(emptyList())
    val videos: StateFlow<List<VideoItem>> = _videos.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _continuePlayingVideo = MutableStateFlow<com.tv.ball.data.model.HistoryEntity?>(null)
    val continuePlayingVideo: StateFlow<com.tv.ball.data.model.HistoryEntity?> = _continuePlayingVideo.asStateFlow()

    private var currentCategory = 6 // 默认国产剧
    private var currentPage = 1
    private var isLoadingMore = false
    private var hasMorePages = true
    private var isPreloading = false
    private var autoSwitchAttempts = 0 // 自动切换尝试次数
    
    // 可可影视筛选参数
    private var currentType = ""
    private var currentArea = ""
    private var currentYear = ""
    private var currentLang = ""
    
    // 预加载阈值：当剩余未显示的项少于此值时触发预加载
    private val PRELOAD_THRESHOLD = 6

    init {
        Log.d(TAG, "HomeViewModel initialized")
        loadContinuePlayingVideo()
    }

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
    
    fun loadVideos(
        category: Int = 6, 
        refresh: Boolean = false,
        type: String = "",
        area: String = "",
        year: String = "",
        lang: String = ""
    ) {
        Log.d(TAG, "========== loadVideos 开始 ==========")
        Log.d(TAG, "category=$category, refresh=$refresh")
        Log.d(TAG, "筛选参数: type=$type, area=$area, year=$year, lang=$lang")
        Log.d(TAG, "currentPage=$currentPage, hasMorePages=$hasMorePages, isLoadingMore=$isLoadingMore")
        
        if (refresh) {
            currentPage = 1
            _videos.value = emptyList()
            hasMorePages = true
            autoSwitchAttempts = 0 // 重置自动切换计数
            currentType = type
            currentArea = area
            currentYear = year
            currentLang = lang
            Log.d(TAG, "刷新模式：重置页码和视频列表，更新筛选参数")
        }
        
        if (isLoadingMore || !hasMorePages) {
            Log.d(TAG, "跳过加载：isLoadingMore=$isLoadingMore, hasMorePages=$hasMorePages")
            return
        }
        
        currentCategory = category
        isLoadingMore = true
        _isLoading.value = true
        _error.value = null
        
        viewModelScope.launch {
            try {
                // 使用当前选择的视频源
                val currentSource = com.tv.ball.data.VideoSourceManager.getCurrentSource()
                val isApiSource = com.tv.ball.data.VideoSourceManager.isApiSource(currentSource)
                val url = com.tv.ball.data.VideoSourceManager.getSourceApiUrl(
                    currentSource, 
                    currentCategory, 
                    currentPage,
                    currentType,
                    currentArea,
                    currentYear,
                    currentLang
                )
                
                Log.d(TAG, "========== 请求信息 ==========")
                Log.d(TAG, "视频源: $currentSource")
                Log.d(TAG, "类型: ${if (isApiSource) "API" else "WEB"}")
                Log.d(TAG, "URL: $url")
                Log.d(TAG, "页码: $currentPage")
                Log.d(TAG, "分类: $currentCategory")
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
                                parseApiJson(result.data)
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
                        
                        if (videoList.isEmpty()) {
                            hasMorePages = false
                            if (refresh) {
                                _error.value = "未找到视频内容"
                            }
                            Log.w(TAG, "⚠ 未解析到任何视频")
                        } else {
                            if (refresh) {
                                _videos.value = videoList
                            } else {
                                _videos.value = _videos.value + videoList
                            }
                            currentPage++
                            
                            // 根据视频源类型判断是否还有更多页面
                            // API源：如果返回少于20个视频，可能没有更多页面
                            // WEB源：继续尝试加载，直到返回空列表
                            if (isApiSource && videoList.size < 20) {
                                hasMorePages = false
                                Log.d(TAG, "API源视频数少于20，可能没有更多页面")
                            } else if (!isApiSource && videoList.size < 5) {
                                // WEB源如果返回少于5个，可能是最后一页
                                hasMorePages = false
                                Log.d(TAG, "WEB源视频数少于5，可能没有更多页面")
                            } else {
                                // 继续允许加载更多
                                hasMorePages = true
                                Log.d(TAG, "继续允许加载更多页面")
                            }
                            
                            Log.d(TAG, "✓ 视频列表已更新，总数: ${_videos.value.size}")
                        }
                    }
                    is NetworkResult.Error -> {
                        Log.e(TAG, "========== 网络请求失败 ==========")
                        Log.e(TAG, "错误信息: ${result.message}")
                        Log.e(TAG, "异常: ${result.exception}")
                        Log.e(TAG, "================================")
                        
                        // 如果是刷新操作且未超过最大尝试次数，尝试自动切换视频源
                        if (refresh && autoSwitchAttempts < MAX_AUTO_SWITCH_ATTEMPTS) {
                            autoSwitchAttempts++
                            Log.d(TAG, "尝试自动切换视频源 (第 $autoSwitchAttempts 次)")
                            
                            val newSource = videoSourceAutoSwitcher.switchToAvailableSource(
                                currentSource,
                                currentCategory
                            )
                            
                            if (newSource != null) {
                                val newSourceName = com.tv.ball.data.VideoSourceManager.getSourceName(newSource)
                                _error.value = "当前源不可用，已自动切换到: $newSourceName"
                                
                                // 重置加载状态，允许重新加载
                                _isLoading.value = false
                                isLoadingMore = false
                                
                                // 延迟一下再重新加载，让用户看到提示
                                kotlinx.coroutines.delay(500)
                                
                                // 重新加载数据
                                loadVideos(category, refresh = true, type, area, year, lang)
                                return@launch
                            } else {
                                _error.value = "网络错误: ${result.message}，所有视频源都不可用"
                            }
                        } else {
                            if (autoSwitchAttempts >= MAX_AUTO_SWITCH_ATTEMPTS) {
                                _error.value = "已尝试多个视频源，请检查网络连接"
                            } else {
                                _error.value = "网络错误: ${result.message}"
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "========== loadVideos 异常 ==========")
                Log.e(TAG, "异常类型: ${e.javaClass.simpleName}")
                Log.e(TAG, "异常信息: ${e.message}")
                Log.e(TAG, "堆栈跟踪:", e)
                Log.e(TAG, "================================")
                
                // 如果是刷新操作且未超过最大尝试次数，尝试自动切换视频源
                if (refresh && autoSwitchAttempts < MAX_AUTO_SWITCH_ATTEMPTS) {
                    autoSwitchAttempts++
                    Log.d(TAG, "尝试自动切换视频源 (第 $autoSwitchAttempts 次)")
                    
                    val currentSource = com.tv.ball.data.VideoSourceManager.getCurrentSource()
                    val newSource = videoSourceAutoSwitcher.switchToAvailableSource(
                        currentSource,
                        currentCategory
                    )
                    
                    if (newSource != null) {
                        val newSourceName = com.tv.ball.data.VideoSourceManager.getSourceName(newSource)
                        _error.value = "当前源不可用，已自动切换到: $newSourceName"
                        
                        // 重置加载状态，允许重新加载
                        _isLoading.value = false
                        isLoadingMore = false
                        
                        // 延迟一下再重新加载，让用户看到提示
                        kotlinx.coroutines.delay(500)
                        
                        // 重新加载数据
                        loadVideos(category, refresh = true, type, area, year, lang)
                        return@launch
                    } else {
                        _error.value = "加载失败: ${e.message}，所有视频源都不可用"
                    }
                } else {
                    if (autoSwitchAttempts >= MAX_AUTO_SWITCH_ATTEMPTS) {
                        _error.value = "已尝试多个视频源，请检查网络连接"
                    } else {
                        _error.value = "加载失败: ${e.message}"
                    }
                }
            } finally {
                _isLoading.value = false
                isLoadingMore = false
                Log.d(TAG, "========== loadVideos 结束 ==========")
            }
        }
    }
    
    private fun parseApiJson(json: String): List<VideoItem> {
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
                        val shouldInclude = when (currentCategory) {
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
                    
                    // 在线之家HTML结构：
                    // <a href="/detail/4561.html">
                    //   <img data-original="//img9.doubanio.com/view/photo/s_ratio_poster/public/p2885787484.jpg" ...>
                    //   <h4>标题</h4>
                    // </a>
                    
                    // 完整解析模式：包含图片和标题
                    val fullPattern = """<a[^>]*href="(/detail/\d+\.html)"[^>]*>.*?<img[^>]*data-original="([^"]+)".*?<h4>([^<]+)</h4>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                    
                    var matchCount = 0
                    
                    // 先尝试完整模式（包含图片）
                    for (match in fullPattern.findAll(cleanHtml)) {
                        val link = match.groupValues[1]
                        var imageUrl = match.groupValues[2].trim()
                        val title = match.groupValues[3].trim()
                        
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
                        val titleOnlyPattern = """<a[^>]*href="(/detail/\d+\.html)"[^>]*>.*?<h4>([^<]+)</h4>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                        
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
                        android.util.Log.e(TAG, "包含 /detail/: ${cleanHtml.contains("/detail/")}")
                        android.util.Log.e(TAG, "包含 <h4>: ${cleanHtml.contains("<h4>")}")
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
    
    fun loadMore() {
        if (!isLoadingMore && !_isLoading.value && hasMorePages) {
            loadVideos(currentCategory, refresh = false)
        }
    }
    
    /**
     * 智能预加载：当用户滚动到接近底部时自动加载下一页
     * @param lastVisiblePosition 最后可见项的位置
     * @param totalItemCount 总项数
     */
    fun checkAndPreload(lastVisiblePosition: Int, totalItemCount: Int) {
        // 计算剩余未显示的项数
        val remainingItems = totalItemCount - lastVisiblePosition - 1
        
        // 当剩余项少于阈值且未在加载时，触发预加载
        if (remainingItems <= PRELOAD_THRESHOLD && !isLoadingMore && !isPreloading && hasMorePages) {
            Log.d(TAG, "Preloading next page: remaining=$remainingItems, threshold=$PRELOAD_THRESHOLD")
            isPreloading = true
            loadVideos(currentCategory, refresh = false)
            isPreloading = false
        }
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
        
        // Pattern from Lua code - 使用 DOT_MATCHES_ALL 选项
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
