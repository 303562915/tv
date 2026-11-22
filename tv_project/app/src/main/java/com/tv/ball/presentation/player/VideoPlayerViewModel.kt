package com.tv.ball.presentation.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tv.ball.domain.model.Episode
import com.tv.ball.domain.model.ParseResult
import com.tv.ball.domain.repository.ConfigRepository
import com.tv.ball.domain.repository.HistoryRepository
import com.tv.ball.domain.repository.LocalVideoProgressManager
import com.tv.ball.domain.repository.VideoRepository
import com.tv.ball.domain.parser.VideoLinkSniffer
import com.tv.ball.domain.parser.VideoUrlCache
import com.tv.ball.di.Dependencies
import com.tv.ball.util.VideoFlowLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

class VideoPlayerViewModel(
    private val videoRepository: VideoRepository = Dependencies.videoRepository,
    private val historyRepository: HistoryRepository = Dependencies.historyRepository,
    private val configRepository: ConfigRepository = Dependencies.configRepository,
    private val localVideoProgressManager: LocalVideoProgressManager = Dependencies.localVideoProgressManager,
    private var context: Context? = null
) : ViewModel() {
    
    private val _playerState = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()
    
    private val _videoUrl = MutableStateFlow<String?>(null)
    val videoUrl: StateFlow<String?> = _videoUrl.asStateFlow()
    
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    
    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()
    
    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()
    
    // 新增：线路切换事件
    private val _routeSwitchEvent = MutableStateFlow<Episode?>(null)
    val routeSwitchEvent: StateFlow<Episode?> = _routeSwitchEvent.asStateFlow()
    
    // 新增：线路测试状态
    private val _routeTestStatus = MutableStateFlow<String?>(null)
    val routeTestStatus: StateFlow<String?> = _routeTestStatus.asStateFlow()

    // 新增：发现更快路线的提示
    private val _fasterRouteFound = MutableStateFlow<FasterRouteInfo?>(null)
    val fasterRouteFound: StateFlow<FasterRouteInfo?> = _fasterRouteFound.asStateFlow()

    data class FasterRouteInfo(
        val episode: Episode,
        val routeName: String,
        val downloadSpeed: Long
    )
    
    private var currentVideoId: Long = 0
    private var currentVideoTitle: String = ""
    private var currentVideoSourceUrl: String = ""
    private var currentParsedVideoUrl: String? = null  // 新增：保存解析后的真实视频链接
    private var currentVideoHeaders: Map<String, String>? = null  // 新增：保存请求头
    private var currentThumbnailUrl: String? = null
    private var currentDetailUrl: String? = null
    private var currentEpisodesJson: String? = null
    private var currentEpisodeName: String? = null
    private var currentVideoSource: String? = null  // 新增：保存当前视频源
    
    // 新增：用于自动切换线路
    private var allEpisodes: List<Episode> = emptyList()  // 所有剧集列表
    private var currentEpisodeIndex: Int = -1  // 当前剧集在列表中的索引
    private var triedEpisodeUrls: MutableSet<String> = mutableSetOf()  // 已尝试过的URL
    
    fun loadVideo(
        videoId: Long, 
        videoTitle: String, 
        videoUrl: String, 
        thumbnailUrl: String?,
        detailUrl: String? = null,
        episodesJson: String? = null,
        episodeName: String? = null,
        parsedVideoUrl: String? = null,
        videoHeaders: String? = null,
        videoSource: String? = null,
        allEpisodesList: List<Episode>? = null,  // 新增：所有剧集列表
        episodeIndex: Int = -1  // 新增：当前剧集索引
    ) {
        // 清理URL中的转义字符
        val cleanVideoUrl = videoUrl.replace("\\/", "/")
        
        currentVideoId = videoId
        currentVideoTitle = videoTitle
        currentVideoSourceUrl = cleanVideoUrl
        currentThumbnailUrl = thumbnailUrl
        currentDetailUrl = detailUrl
        currentEpisodesJson = episodesJson
        currentEpisodeName = episodeName
        currentVideoSource = videoSource  // 保存视频源
        
        // 保存剧集列表信息
        if (allEpisodesList != null) {
            allEpisodes = allEpisodesList
            currentEpisodeIndex = episodeIndex
        }
        
        // 重置已尝试的URL列表
        triedEpisodeUrls.clear()
        triedEpisodeUrls.add(cleanVideoUrl)
        
        // ========== 日志：开始加载视频 ==========
        VideoFlowLogger.info(
            VideoFlowLogger.FlowStage.PARSE,
            "开始加载视频",
            "URL: $cleanVideoUrl\n集数: $episodeName\n视频源: $videoSource"
        )
        // ====================================
        
        android.util.Log.d("VideoPlayerViewModel", "=== 开始加载视频 ===")
        android.util.Log.d("VideoPlayerViewModel", "URL: $cleanVideoUrl")
        android.util.Log.d("VideoPlayerViewModel", "集数: $episodeName")
        android.util.Log.d("VideoPlayerViewModel", "视频源: $videoSource")
        android.util.Log.d("VideoPlayerViewModel", "剧集列表大小: ${allEpisodes.size}, 当前索引: $currentEpisodeIndex")
        
        // 如果Intent中传递了缓存的URL和请求头，优先使用
        if (parsedVideoUrl != null && parsedVideoUrl.isNotEmpty()) {
            android.util.Log.d("VideoPlayerViewModel", "使用Intent传递的缓存链接: $parsedVideoUrl")
            currentParsedVideoUrl = parsedVideoUrl
            
            // 解析请求头
            if (videoHeaders != null && videoHeaders.isNotEmpty()) {
                currentVideoHeaders = parseHeadersFromJson(videoHeaders)
                android.util.Log.d("VideoPlayerViewModel", "使用Intent传递的请求头: $currentVideoHeaders")
            }
            
            // 同时保存到内存缓存
            VideoUrlCache.put(cleanVideoUrl, parsedVideoUrl, currentVideoHeaders)
            
            _playerState.value = PlayerState.LoadingWithMessage("使用缓存链接，快速播放...")
            viewModelScope.launch {
                kotlinx.coroutines.delay(300)
                _videoUrl.value = parsedVideoUrl
                _playerState.value = PlayerState.Ready
                
                // 尝试恢复播放进度
                val history = historyRepository.getHistoryByTitleAndEpisode(currentVideoTitle, episodeName, videoSource)
                if (history != null) {
                    _currentPosition.value = history.watchedPosition
                }
            }
            return
        }
        
        // 先检查内存缓存
        val cachedUrl = VideoUrlCache.get(cleanVideoUrl)
        if (cachedUrl != null) {
            android.util.Log.d("VideoPlayerViewModel", "使用内存缓存链接: ${cachedUrl.parsedUrl}")
            currentParsedVideoUrl = cachedUrl.parsedUrl
            currentVideoHeaders = cachedUrl.headers
            
            _playerState.value = PlayerState.LoadingWithMessage("使用缓存链接，快速播放...")
            viewModelScope.launch {
                kotlinx.coroutines.delay(300)
                _videoUrl.value = cachedUrl.parsedUrl
                _playerState.value = PlayerState.Ready
                
                // 尝试恢复播放进度
                val history = historyRepository.getHistoryByTitleAndEpisode(currentVideoTitle, episodeName, videoSource)
                if (history != null) {
                    _currentPosition.value = history.watchedPosition
                }
            }
            return
        }
        
        // 先检查历史记录中是否有已保存的解析链接
        viewModelScope.launch {
            val history = historyRepository.getHistoryByTitleAndEpisode(currentVideoTitle, episodeName, videoSource)
            android.util.Log.d("VideoPlayerViewModel", "查询历史记录: title=$currentVideoTitle, episode=$episodeName, source=$videoSource, history=$history")
            
            if (history?.parsedVideoUrl != null && history.parsedVideoUrl.isNotEmpty()) {
                // 有已保存的解析链接，直接使用
                android.util.Log.d("VideoPlayerViewModel", "找到已保存的解析链接: ${history.parsedVideoUrl}")
                currentParsedVideoUrl = history.parsedVideoUrl
                
                // 恢复请求头
                if (history.videoHeaders != null) {
                    currentVideoHeaders = parseHeadersFromJson(history.videoHeaders)
                    android.util.Log.d("VideoPlayerViewModel", "恢复请求头: $currentVideoHeaders")
                }
                
                _playerState.value = PlayerState.LoadingWithMessage("使用缓存链接，快速播放...")
                kotlinx.coroutines.delay(300)
                _videoUrl.value = history.parsedVideoUrl
                _playerState.value = PlayerState.Ready
                _currentPosition.value = history.watchedPosition
                return@launch
            }
            
            // 没有保存的链接，需要解析
            android.util.Log.d("VideoPlayerViewModel", "没有缓存链接，开始解析")
            parseAndLoadVideo(cleanVideoUrl)
        }
    }
    
    private suspend fun parseAndLoadVideo(cleanVideoUrl: String) {
        // 检查是否是本地文件
        if (cleanVideoUrl.startsWith("/") || cleanVideoUrl.startsWith("file://")) {
            // 本地文件直接播放
            android.util.Log.d("VideoPlayerViewModel", "检测到本地文件")
            _playerState.value = PlayerState.LoadingWithMessage("检测到本地文件，准备播放...")
            _videoUrl.value = cleanVideoUrl
            _playerState.value = PlayerState.Ready
            
            // 从LocalVideoProgressManager恢复播放位置
            val savedPosition = localVideoProgressManager.getProgress(cleanVideoUrl)
            if (savedPosition > 0) {
                android.util.Log.d("VideoPlayerViewModel", "恢复本地视频播放位置: $savedPosition ms")
                _currentPosition.value = savedPosition
            }
        } else if (cleanVideoUrl.startsWith("http://") || cleanVideoUrl.startsWith("https://")) {
            // 网络URL - 检查是否是直接的视频链接
            if (isDirectVideoUrl(cleanVideoUrl)) {
                // 直接的视频链接，不需要解析
                android.util.Log.d("VideoPlayerViewModel", "检测到直接视频资源")
                _playerState.value = PlayerState.LoadingWithMessage("检测到视频资源，准备播放...")
                
                kotlinx.coroutines.delay(300)
                currentParsedVideoUrl = cleanVideoUrl  // 保存解析后的链接
                _videoUrl.value = cleanVideoUrl
                _playerState.value = PlayerState.Ready
                
                // 异步加载历史记录
                val history = historyRepository.getHistoryByTitleAndEpisode(currentVideoTitle, currentEpisodeName, currentVideoSource)
                android.util.Log.d("VideoPlayerViewModel", "查询历史记录: title=$currentVideoTitle, history=$history")
                if (history != null) {
                    android.util.Log.d("VideoPlayerViewModel", "设置播放位置: ${history.watchedPosition} ms")
                    _currentPosition.value = history.watchedPosition
                }
            } else {
                // 需要解析的URL - 这是一个网页
                android.util.Log.d("VideoPlayerViewModel", "检测到网页URL，需要解析")
                viewModelScope.launch {
                    _playerState.value = PlayerState.LoadingWithMessage("正在解析视频源...")
                    kotlinx.coroutines.delay(200)
                    
                    android.util.Log.d("VideoPlayerViewModel", "开始解析视频链接: $cleanVideoUrl")

                    // 优化：使用协程并行尝试多种解析方式，谁先成功用谁
                    var parseSuccess = false
                    
                    // 如果有 context，优先使用 WebView sniffing（但设置更短超时）
                    if (context != null) {
                        try {
                            _playerState.value = PlayerState.LoadingWithMessage("正在加载页面...")
                            
                            // ========== 日志：开始嗅探 ==========
                            VideoFlowLogger.info(
                                VideoFlowLogger.FlowStage.SNIFF,
                                "开始WebView嗅探",
                                "URL: $cleanVideoUrl"
                            )
                            // ====================================
                            
                            android.util.Log.d("VideoPlayerViewModel", "使用 WebView sniffing 嗅探视频链接")
                            
                            // 优化：使用实时回调，一旦找到链接立即播放
                            val sniffer = VideoLinkSniffer(
                                requireNotNull(context) { "context 未初始化，无法进行 WebView 嗅探" },
                                onProgressUpdate = { progress ->
                                    android.util.Log.d("VideoPlayerViewModel", "嗅探进度: $progress")
                                    _playerState.value = PlayerState.LoadingWithMessage(progress)
                                },
                                onLinkFound = { link ->
                                    // 优化：实时回调，找到第一个链接立即播放
                                    if (!parseSuccess) {
                                        parseSuccess = true
                                        
                                        // ========== 日志：嗅探成功 ==========
                                        VideoFlowLogger.success(
                                            VideoFlowLogger.FlowStage.SNIFF,
                                            "嗅探到视频链接",
                                            "URL: $link"
                                        )
                                        // ====================================
                                        
                                        android.util.Log.d("VideoPlayerViewModel", "实时发现视频链接，立即播放: $link")
                                        
                                        viewModelScope.launch {
                                            currentParsedVideoUrl = link
                                            // 保存到缓存
                                            VideoUrlCache.put(cleanVideoUrl, link)
                                            
                                            _playerState.value = PlayerState.LoadingWithMessage("找到视频链接，准备播放...")
                                            kotlinx.coroutines.delay(300)
                                            
                                            // ========== 日志：准备播放 ==========
                                            VideoFlowLogger.success(
                                                VideoFlowLogger.FlowStage.PLAYER_INIT,
                                                "准备播放视频",
                                                "解析后URL: $link"
                                            )
                                            // ====================================
                                            
                                            _videoUrl.value = link
                                            _playerState.value = PlayerState.Ready

                                            val history = historyRepository.getHistoryByTitleAndEpisode(currentVideoTitle, currentEpisodeName, currentVideoSource)
                                            if (history != null) {
                                                _currentPosition.value = history.watchedPosition
                                            }
                                        }
                                    }
                                }
                            )
                            
                            val videoLinks = sniffer.sniffVideoLinks(cleanVideoUrl)

                            android.util.Log.d("VideoPlayerViewModel", "嗅探完成，共找到 ${videoLinks.size} 个视频链接")

                            // 如果实时回调已经处理了，就不再重复处理
                            if (!parseSuccess && videoLinks.isNotEmpty()) {
                                parseSuccess = true
                                val finalUrl = videoLinks[0]
                                android.util.Log.d("VideoPlayerViewModel", "使用视频链接: $finalUrl")
                                currentParsedVideoUrl = finalUrl
                                // 保存到缓存
                                VideoUrlCache.put(cleanVideoUrl, finalUrl)
                                
                                _playerState.value = PlayerState.LoadingWithMessage("解析到视频链接，准备播放...")
                                kotlinx.coroutines.delay(300)
                                
                                _videoUrl.value = finalUrl
                                _playerState.value = PlayerState.Ready

                                val history = historyRepository.getHistoryByTitleAndEpisode(currentVideoTitle, currentEpisodeName, currentVideoSource)
                                if (history != null) {
                                    _currentPosition.value = history.watchedPosition
                                }
                                return@launch
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("VideoPlayerViewModel", "WebView sniffing 失败: ${e.message}", e)
                        }
                    }

                    // 如果 WebView sniffing 失败且还没成功，回退到原来的解析方式
                    if (!parseSuccess) {
                        android.util.Log.d("VideoPlayerViewModel", "回退到原解析方式")
                        _playerState.value = PlayerState.LoadingWithMessage("使用备用解析器...")
                        kotlinx.coroutines.delay(200)
                        
                        when (val result = videoRepository.parseVideoUrl(cleanVideoUrl)) {
                            is ParseResult.Success -> {
                                android.util.Log.d("VideoPlayerViewModel", "原解析方式成功: ${result.videoUrl.url}")
                                currentParsedVideoUrl = result.videoUrl.url
                                currentVideoHeaders = result.videoUrl.headers
                                // 保存到缓存
                                VideoUrlCache.put(cleanVideoUrl, result.videoUrl.url, result.videoUrl.headers)
                                
                                _playerState.value = PlayerState.LoadingWithMessage("解析成功，准备播放...")
                                kotlinx.coroutines.delay(300)
                                
                                _videoUrl.value = result.videoUrl.url
                                _playerState.value = PlayerState.Ready

                                val history = historyRepository.getHistoryByTitleAndEpisode(currentVideoTitle, currentEpisodeName, currentVideoSource)
                                if (history != null) {
                                    _currentPosition.value = history.watchedPosition
                                }
                            }
                            is ParseResult.Error -> {
                                android.util.Log.e("VideoPlayerViewModel", "原解析方式失败: ${result.message}")
                                
                                // ========== 日志：解析失败 ==========
                                VideoFlowLogger.error(
                                    VideoFlowLogger.FlowStage.PARSE,
                                    "视频解析失败",
                                    "错误: ${result.message}"
                                )
                                // ====================================
                                
                                // 尝试自动切换到下一个线路的相同集数
                                if (tryNextRouteForSameEpisode()) {
                                    android.util.Log.d("VideoPlayerViewModel", "解析失败，正在尝试切换到下一个线路...")
                                    return@launch
                                }
                                
                                _playerState.value = PlayerState.Error(result.message)
                            }
                        }
                    }
                }
            }
        } else {
            // 未知格式 - 尝试直接播放
            android.util.Log.d("VideoPlayerViewModel", "未知格式，尝试播放")
            _playerState.value = PlayerState.LoadingWithMessage("未知格式，尝试播放...")
            kotlinx.coroutines.delay(300)
            _videoUrl.value = cleanVideoUrl
            _playerState.value = PlayerState.Ready
        }
    }
    
    /**
     * 判断是否是直接的视频URL
     * 简单直接：以视频扩展名结尾的就是视频资源，否则就是需要解析的网页
     */
    private fun isDirectVideoUrl(url: String): Boolean {
        val lowerUrl = url.lowercase()
        val urlWithoutParams = lowerUrl.split("?")[0]  // 去掉查询参数
        
        // 视频文件扩展名列表
        val videoExtensions = listOf(
            ".m3u8", ".mp4", ".flv", ".mkv", ".avi", ".webm", ".mov"
        )
        
        // 检查URL路径是否以视频扩展名结尾
        val isVideoFile = videoExtensions.any { ext -> 
            urlWithoutParams.endsWith(ext)
        }
        
        if (isVideoFile) {
            android.util.Log.d("VideoPlayerViewModel", "✓ 直接视频资源: $url")
            return true
        }
        
        // 检查是否包含 .html 或 .htm（明确的网页标志）
        if (urlWithoutParams.endsWith(".html") || urlWithoutParams.endsWith(".htm")) {
            android.util.Log.d("VideoPlayerViewModel", "✗ HTML网页，需要解析: $url")
            return false
        }
        
        // 检查是否有流媒体路径特征（如 /hls/, /m3u8/）
        val hasStreamPattern = lowerUrl.matches(Regex(".*/m3u8/.*")) || 
                               lowerUrl.matches(Regex(".*/hls/.*"))
        
        if (hasStreamPattern) {
            android.util.Log.d("VideoPlayerViewModel", "✓ 流媒体路径特征: $url")
            return true
        }
        
        android.util.Log.d("VideoPlayerViewModel", "✗ 需要解析的URL: $url")
        return false
    }
    
    fun play() {
        android.util.Log.d("VideoPlayerViewModel", "▶️ play() 被调用")
        android.util.Log.d("VideoPlayerViewModel", "  调用堆栈: ${Thread.currentThread().stackTrace.take(8).joinToString("\n  ")}")
        _isPlaying.value = true
        _playerState.value = PlayerState.Playing
    }
    
    fun pause() {
        android.util.Log.d("VideoPlayerViewModel", "⏸️ pause() 被调用")
        android.util.Log.d("VideoPlayerViewModel", "  调用堆栈: ${Thread.currentThread().stackTrace.take(8).joinToString("\n  ")}")
        _isPlaying.value = false
        _playerState.value = PlayerState.Paused
    }
    
    fun seekTo(position: Long) {
        _currentPosition.value = position
    }
    
    fun updatePosition(position: Long) {
        _currentPosition.value = position
    }
    
    fun updateDuration(duration: Long) {
        _duration.value = duration
    }
    
    fun savePlaybackProgress() {
        android.util.Log.d("VideoPlayerViewModel", "保存播放进度: title=$currentVideoTitle, url=$currentVideoSourceUrl, position=${_currentPosition.value}, duration=${_duration.value}, source=$currentVideoSource")
        
        // 判断是否是本地视频
        val isLocalVideo = currentVideoSourceUrl.startsWith("/") || currentVideoSourceUrl.startsWith("file://")
        
        if (isLocalVideo) {
            // 本地视频：使用LocalVideoProgressManager保存进度，不记录到历史
            android.util.Log.d("VideoPlayerViewModel", "本地视频，保存到LocalVideoProgressManager")
            localVideoProgressManager.saveProgress(
                videoPath = currentVideoSourceUrl,
                position = _currentPosition.value,
                duration = _duration.value
            )
        } else {
            // 在线视频：保存到历史记录
            android.util.Log.d("VideoPlayerViewModel", "在线视频，保存到历史记录")
            viewModelScope.launch {
                historyRepository.addOrUpdateHistory(
                    videoTitle = currentVideoTitle,
                    videoUrl = currentVideoSourceUrl,
                    parsedVideoUrl = currentParsedVideoUrl,  // 保存解析后的链接
                    videoHeaders = currentVideoHeaders?.let { headersToJson(it) },  // 保存请求头
                    thumbnailUrl = currentThumbnailUrl,
                    watchedPosition = _currentPosition.value,
                    duration = _duration.value,
                    detailUrl = currentDetailUrl,
                    episodesJson = currentEpisodesJson,
                    currentEpisode = currentEpisodeName,
                    videoSource = currentVideoSource  // 保存视频源
                )
            }
        }
    }
    
    /**
     * 获取保存的播放位置
     */
    fun getSavedPosition(): Long {
        return _currentPosition.value
    }
    
    // 新增：设置解析后的视频链接
    fun setParsedVideoUrl(parsedUrl: String) {
        currentParsedVideoUrl = parsedUrl
        android.util.Log.d("VideoPlayerViewModel", "设置解析后的链接: $parsedUrl")
    }
    
    // 获取当前视频的请求头
    fun getVideoHeaders(): Map<String, String>? {
        return currentVideoHeaders
    }
    
    // 辅助方法：将请求头转换为JSON
    private fun headersToJson(headers: Map<String, String>): String {
        return headers.entries.joinToString(",", "{", "}") { (key, value) ->
            "\"$key\":\"${value.replace("\"", "\\\"")}\""
        }
    }
    
    // 辅助方法：从JSON解析请求头
    private fun parseHeadersFromJson(json: String): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        try {
            val content = json.trim().removePrefix("{").removeSuffix("}")
            val pairs = content.split(",")
            for (pair in pairs) {
                val keyValue = pair.split(":")
                if (keyValue.size == 2) {
                    val key = keyValue[0].trim().removeSurrounding("\"")
                    val value = keyValue[1].trim().removeSurrounding("\"").replace("\\\"", "\"")
                    headers[key] = value
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("VideoPlayerViewModel", "解析请求头失败: ${e.message}")
        }
        return headers
    }
    
    fun onPlaybackError(error: String) {
        android.util.Log.e("VideoPlayerViewModel", "=== 播放错误 ===")
        android.util.Log.e("VideoPlayerViewModel", "错误信息: $error")
        android.util.Log.e("VideoPlayerViewModel", "当前URL: $currentVideoSourceUrl")
        android.util.Log.e("VideoPlayerViewModel", "解析后URL: $currentParsedVideoUrl")
        android.util.Log.e("VideoPlayerViewModel", "视频标题: $currentVideoTitle")
        android.util.Log.e("VideoPlayerViewModel", "集数: $currentEpisodeName")
        
        // 尝试自动切换到下一个线路的相同集数
        if (tryNextRouteForSameEpisode()) {
            android.util.Log.d("VideoPlayerViewModel", "正在尝试切换到下一个线路...")
            return
        }
        
        // 检查是否是网络相关错误
        val isNetworkError = error.contains("网络", ignoreCase = true) ||
                            error.contains("connection", ignoreCase = true) ||
                            error.contains("timeout", ignoreCase = true) ||
                            error.contains("IO", ignoreCase = true)
        
        if (isNetworkError) {
            android.util.Log.w("VideoPlayerViewModel", "检测到网络错误，建议检查网络连接或尝试切换视频源")
            _playerState.value = PlayerState.Error("$error\n\n建议：\n1. 检查网络连接\n2. 尝试切换其他视频源\n3. 稍后重试")
        } else {
            _playerState.value = PlayerState.Error(error)
        }
    }
    
    /**
     * 尝试切换到下一个线路的相同集数
     * @return true 如果找到并开始加载下一个线路，false 如果没有更多线路可尝试
     */
    private fun tryNextRouteForSameEpisode(): Boolean {
        android.util.Log.d("VideoPlayerViewModel", "========== tryNextRouteForSameEpisode 开始 ==========")
        android.util.Log.d("VideoPlayerViewModel", "allEpisodes.size: ${allEpisodes.size}")
        android.util.Log.d("VideoPlayerViewModel", "currentEpisodeIndex: $currentEpisodeIndex")
        android.util.Log.d("VideoPlayerViewModel", "currentEpisodeName: $currentEpisodeName")
        
        if (allEpisodes.isEmpty() || currentEpisodeIndex < 0) {
            android.util.Log.w("VideoPlayerViewModel", "没有剧集列表信息，无法自动切换线路")
            android.util.Log.w("VideoPlayerViewModel", "  - allEpisodes.isEmpty: ${allEpisodes.isEmpty()}")
            android.util.Log.w("VideoPlayerViewModel", "  - currentEpisodeIndex: $currentEpisodeIndex")
            return false
        }
        
        // 提取当前集数的纯净名称（去掉线路前缀）
        val currentEpisodeCleanName = extractCleanEpisodeName(currentEpisodeName ?: "")
        if (currentEpisodeCleanName.isEmpty()) {
            android.util.Log.w("VideoPlayerViewModel", "无法提取集数名称，无法自动切换线路")
            return false
        }
        
        android.util.Log.d("VideoPlayerViewModel", "当前集数纯净名称: $currentEpisodeCleanName")
        android.util.Log.d("VideoPlayerViewModel", "已尝试的URL数量: ${triedEpisodeUrls.size}")
        
        // 查找所有相同集数但不同线路的剧集
        val sameEpisodeDifferentRoutes = allEpisodes.filter { episode ->
            val cleanName = extractCleanEpisodeName(episode.name)
            val isSameName = cleanName == currentEpisodeCleanName
            val notTried = !triedEpisodeUrls.contains(episode.url)
            android.util.Log.v("VideoPlayerViewModel", "  检查: ${episode.name} -> cleanName=$cleanName, isSameName=$isSameName, notTried=$notTried")
            isSameName && notTried
        }
        
        android.util.Log.d("VideoPlayerViewModel", "找到 ${sameEpisodeDifferentRoutes.size} 个未尝试的相同集数")
        
        if (sameEpisodeDifferentRoutes.isEmpty()) {
            android.util.Log.w("VideoPlayerViewModel", "没有更多线路可尝试")
            return false
        }
        
        // 选择第一个未尝试的线路
        val nextEpisode = sameEpisodeDifferentRoutes.first()
        android.util.Log.i("VideoPlayerViewModel", "✓ 切换到下一个线路: ${nextEpisode.name} -> ${nextEpisode.url}")
        
        // 更新当前信息
        currentVideoSourceUrl = nextEpisode.url
        currentEpisodeName = nextEpisode.name
        triedEpisodeUrls.add(nextEpisode.url)
        
        // 发送线路切换事件，通知UI更新
        _routeSwitchEvent.value = nextEpisode
        
        // 显示切换提示
        val routeName = extractRouteName(nextEpisode.name)
        android.util.Log.i("VideoPlayerViewModel", "显示切换提示: 切换到 $routeName")
        _playerState.value = PlayerState.LoadingWithMessage("当前线路无法播放，正在切换到 $routeName...")
        
        // 开始加载新线路
        viewModelScope.launch {
            kotlinx.coroutines.delay(500)  // 短暂延迟，让用户看到提示
            android.util.Log.d("VideoPlayerViewModel", "开始解析新线路: ${nextEpisode.url}")
            parseAndLoadVideo(nextEpisode.url)
        }
        
        android.util.Log.d("VideoPlayerViewModel", "========== tryNextRouteForSameEpisode 结束 (返回true) ==========")
        return true
    }
    
    /**
     * 提取集数的纯净名称（去掉线路前缀）
     * 例如: "[线路1] 第1集" -> "第1集"
     */
    private fun extractCleanEpisodeName(episodeName: String): String {
        return episodeName.replace("""\[[^\]]+\]\s*""".toRegex(), "").trim()
    }
    
    /**
     * 提取线路名称
     * 例如: "[线路1] 第1集" -> "线路1"
     */
    private fun extractRouteName(episodeName: String): String {
        val match = """\[([^\]]+)\]""".toRegex().find(episodeName)
        return match?.groupValues?.get(1) ?: "备用线路"
    }
    
    fun isAutoPlayNextEnabled(): Boolean {
        return configRepository.isAutoPlayNextEnabled()
    }
    
    /**
     * 测试所有线路并自动切换到最快的
     */
    fun testAndSwitchToFastestRoute() {
        if (allEpisodes.isEmpty() || currentEpisodeIndex < 0) {
            android.util.Log.d("VideoPlayerViewModel", "没有剧集列表，跳过线路测试")
            return
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                android.util.Log.d("VideoPlayerViewModel", "========== 开始测试所有线路 ==========")
                
                // 提取当前集数的纯净名称
                val currentEpisodeCleanName = extractCleanEpisodeName(currentEpisodeName ?: "")
                if (currentEpisodeCleanName.isEmpty()) {
                    android.util.Log.w("VideoPlayerViewModel", "无法提取集数名称，跳过线路测试")
                    return@launch
                }
                
                // 查找所有相同集数的不同线路（排除网盘资源）
                val sameEpisodeRoutes = allEpisodes.filter { episode ->
                    val cleanName = extractCleanEpisodeName(episode.name)
                    cleanName == currentEpisodeCleanName && !episode.isCloudStorage  // 排除网盘线路
                }
                
                // 统计排除的网盘线路数量
                val totalRoutes = allEpisodes.count { episode ->
                    val cleanName = extractCleanEpisodeName(episode.name)
                    cleanName == currentEpisodeCleanName
                }
                val excludedCloudRoutes = totalRoutes - sameEpisodeRoutes.size
                if (excludedCloudRoutes > 0) {
                    android.util.Log.i("VideoPlayerViewModel", "已排除 $excludedCloudRoutes 个网盘线路")
                }
                
                if (sameEpisodeRoutes.size <= 1) {
                    android.util.Log.d("VideoPlayerViewModel", "只有1个线路，无需测试")
                    return@launch
                }
                
                android.util.Log.i("VideoPlayerViewModel", "找到 ${sameEpisodeRoutes.size} 个线路，开始并行测试")
                
                // 创建测试器
                val tester = com.tv.ball.domain.network.RouteSpeedTester(
                    com.tv.ball.domain.network.UnsafeOkHttpClient.getUnsafeOkHttpClient()
                )
                
                // 准备测试数据
                val routesToTest = sameEpisodeRoutes.map { episode ->
                    episode.url to extractRouteName(episode.name)
                }
                
                // 并行测试所有线路
                _routeTestStatus.value = "正在测试 ${sameEpisodeRoutes.size} 个线路..."
                
                val results = tester.testRoutes(routesToTest) { progress ->
                    _routeTestStatus.value = progress
                }
                
                // 找到最快的线路
                val fastestResult = results.firstOrNull { it.success }
                if (fastestResult == null) {
                    android.util.Log.w("VideoPlayerViewModel", "所有线路测试都失败")
                    _routeTestStatus.value = null
                    return@launch
                }
                
                // 检查当前线路是否已经是最快的
                val currentUrl = currentVideoSourceUrl
                if (fastestResult.url == currentUrl) {
                    android.util.Log.i("VideoPlayerViewModel", "当前线路已是最快: ${fastestResult.routeName}")
                    _routeTestStatus.value = "当前线路最优"
                    kotlinx.coroutines.delay(2000)
                    _routeTestStatus.value = null
                    return@launch
                }
                
                // 找到对应的Episode
                val fastestEpisode = sameEpisodeRoutes.find { it.url == fastestResult.url }
                if (fastestEpisode == null) {
                    android.util.Log.w("VideoPlayerViewModel", "找不到最快线路的Episode")
                    _routeTestStatus.value = null
                    return@launch
                }
                
                // 显示切换提示 - 不自动切换，等待用户确认
                val message = "发现更快线路: ${fastestResult.routeName} (${fastestResult.downloadSpeed}KB/s)"
                android.util.Log.i("VideoPlayerViewModel", message)
                _routeTestStatus.value = message

                // 保存更快路线信息，等待用户点击
                _fasterRouteFound.value = FasterRouteInfo(
                    episode = fastestEpisode,
                    routeName = fastestResult.routeName,
                    downloadSpeed = fastestResult.downloadSpeed
                )

                kotlinx.coroutines.delay(2000) // 显示2秒
                _routeTestStatus.value = null

            } catch (e: Exception) {
                android.util.Log.e("VideoPlayerViewModel", "线路测试失败: ${e.message}", e)
                _routeTestStatus.value = null
            }
        }
    }

    /**
     * 用户确认切换到更快路线
     */
    fun switchToFasterRoute() {
        val fasterRoute = _fasterRouteFound.value ?: return

        viewModelScope.launch {
            try {
                android.util.Log.i("VideoPlayerViewModel", "用户确认切换到更快线路: ${fasterRoute.episode.name}")

                currentVideoSourceUrl = fasterRoute.episode.url
                currentEpisodeName = fasterRoute.episode.name
                triedEpisodeUrls.add(fasterRoute.episode.url)

                // 发送线路切换事件
                _routeSwitchEvent.value = fasterRoute.episode

                // 清除提示
                _fasterRouteFound.value = null

                // 开始加载新线路
                _playerState.value = PlayerState.LoadingWithMessage("切换到更快线路: ${fasterRoute.routeName}...")
                kotlinx.coroutines.delay(500)
                parseAndLoadVideo(fasterRoute.episode.url)

            } catch (e: Exception) {
                android.util.Log.e("VideoPlayerViewModel", "切换线路失败: ${e.message}", e)
            }
        }
    }

    /**
     * 用户取消切换路线
     */
    fun dismissFasterRoute() {
        _fasterRouteFound.value = null
    }
    
    override fun onCleared() {
        super.onCleared()
        savePlaybackProgress()
    }
}

sealed class PlayerState {
    object Idle : PlayerState()
    object Loading : PlayerState()
    data class LoadingWithMessage(val message: String) : PlayerState()
    object Ready : PlayerState()
    object Playing : PlayerState()
    object Paused : PlayerState()
    data class Error(val message: String) : PlayerState()
}
