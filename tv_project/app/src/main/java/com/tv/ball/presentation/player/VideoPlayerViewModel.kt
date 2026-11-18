package com.tv.ball.presentation.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tv.ball.di.Dependencies
import com.tv.ball.domain.model.Episode
import com.tv.ball.domain.repository.ConfigRepository
import com.tv.ball.domain.repository.HistoryRepository
import com.tv.ball.domain.repository.LocalVideoProgressManager
import com.tv.ball.util.VideoFlowLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 播放器 ViewModel
 *
 * 职责：接收视频地址并交给播放器、记录播放进度、线路（同一集数的多个地址）测速与切换。
 * 本应用不做网页解析，传入的地址必须是可直接播放的媒体地址（m3u8/mp4/本地文件等）。
 */
class VideoPlayerViewModel(
    private val historyRepository: HistoryRepository = Dependencies.historyRepository,
    private val configRepository: ConfigRepository = Dependencies.configRepository,
    private val localVideoProgressManager: LocalVideoProgressManager = Dependencies.localVideoProgressManager,
    private val context: Context? = null
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

    /** 线路切换事件 */
    private val _routeSwitchEvent = MutableStateFlow<Episode?>(null)
    val routeSwitchEvent: StateFlow<Episode?> = _routeSwitchEvent.asStateFlow()

    /** 线路测速状态文案 */
    private val _routeTestStatus = MutableStateFlow<String?>(null)
    val routeTestStatus: StateFlow<String?> = _routeTestStatus.asStateFlow()

    /** 发现更快线路时的提示 */
    private val _fasterRouteFound = MutableStateFlow<FasterRouteInfo?>(null)
    val fasterRouteFound: StateFlow<FasterRouteInfo?> = _fasterRouteFound.asStateFlow()

    data class FasterRouteInfo(
        val episode: Episode,
        val routeName: String,
        val downloadSpeed: Long
    )

    private var currentVideoTitle: String = ""
    private var currentVideoSourceUrl: String = ""
    private var currentParsedVideoUrl: String? = null
    private var currentVideoHeaders: Map<String, String>? = null
    private var currentThumbnailUrl: String? = null
    private var currentDetailUrl: String? = null
    private var currentEpisodesJson: String? = null
    private var currentEpisodeName: String? = null
    private var currentVideoSource: String? = null

    private var allEpisodes: List<Episode> = emptyList()
    private var currentEpisodeIndex: Int = -1
    private val triedEpisodeUrls: MutableSet<String> = mutableSetOf()

    /** 本次播放会话内的地址缓存，避免重复切换时反复重建播放器 */
    private val urlCache = mutableMapOf<String, String>()

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
        allEpisodesList: List<Episode>? = null,
        episodeIndex: Int = -1
    ) {
        val cleanVideoUrl = videoUrl.replace("\\/", "/")

        currentVideoTitle = videoTitle
        currentVideoSourceUrl = cleanVideoUrl
        currentThumbnailUrl = thumbnailUrl
        currentDetailUrl = detailUrl
        currentEpisodesJson = episodesJson
        currentEpisodeName = episodeName
        currentVideoSource = videoSource

        if (allEpisodesList != null) {
            allEpisodes = allEpisodesList
            currentEpisodeIndex = episodeIndex
        }

        triedEpisodeUrls.clear()
        triedEpisodeUrls.add(cleanVideoUrl)

        VideoFlowLogger.info(
            VideoFlowLogger.FlowStage.INIT,
            "开始播放",
            "标题: $videoTitle\n地址: $cleanVideoUrl\n集数: $episodeName"
        )

        // 优先使用调用方传入的直链与请求头
        if (!parsedVideoUrl.isNullOrEmpty()) {
            currentParsedVideoUrl = parsedVideoUrl
            if (!videoHeaders.isNullOrEmpty()) {
                currentVideoHeaders = parseHeadersFromJson(videoHeaders)
            }
            urlCache[cleanVideoUrl] = parsedVideoUrl
            playUrl(parsedVideoUrl, episodeName)
            return
        }

        // 命中会话内缓存
        val cached = urlCache[cleanVideoUrl]
        if (cached != null) {
            currentParsedVideoUrl = cached
            playUrl(cached, episodeName)
            return
        }

        // 回落到历史记录里保存过的地址
        viewModelScope.launch {
            val history = historyRepository.getHistoryByTitleAndEpisode(
                currentVideoTitle, episodeName, videoSource
            )
            val savedUrl = history?.parsedVideoUrl
            if (!savedUrl.isNullOrEmpty()) {
                currentParsedVideoUrl = savedUrl
                val savedHeaders = history?.videoHeaders
                if (!savedHeaders.isNullOrEmpty()) {
                    currentVideoHeaders = parseHeadersFromJson(savedHeaders)
                }
                playUrl(savedUrl, episodeName)
                history?.let { _currentPosition.value = it.watchedPosition }
                return@launch
            }

            loadDirect(cleanVideoUrl, episodeName)
        }
    }

    /**
     * 直接播放给定地址：本地文件读取本地进度，网络地址直接交给播放器。
     */
    private suspend fun loadDirect(url: String, episodeName: String?) {
        val isLocal = url.startsWith("/") || url.startsWith("file://")

        if (isLocal) {
            _playerState.value = PlayerState.LoadingWithMessage("正在打开本地视频...")
            _videoUrl.value = url
            _playerState.value = PlayerState.Ready

            val savedPosition = localVideoProgressManager.getProgress(url)
            if (savedPosition > 0) {
                _currentPosition.value = savedPosition
            }
            return
        }

        currentParsedVideoUrl = url
        _playerState.value = PlayerState.LoadingWithMessage("准备播放...")
        val history = historyRepository.getHistoryByTitleAndEpisode(
            currentVideoTitle, episodeName, currentVideoSource
        )
        _videoUrl.value = url
        _playerState.value = PlayerState.Ready
        if (history != null) {
            _currentPosition.value = history.watchedPosition
        }
    }

    private fun playUrl(url: String, episodeName: String?) {
        viewModelScope.launch {
            _playerState.value = PlayerState.LoadingWithMessage("准备播放...")
            _videoUrl.value = url
            _playerState.value = PlayerState.Ready

            val history = historyRepository.getHistoryByTitleAndEpisode(
                currentVideoTitle, episodeName, currentVideoSource
            )
            if (history != null) {
                _currentPosition.value = history.watchedPosition
            }
        }
    }

    fun play() {
        _isPlaying.value = true
        _playerState.value = PlayerState.Playing
    }

    fun pause() {
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

    fun setParsedVideoUrl(parsedUrl: String) {
        currentParsedVideoUrl = parsedUrl
        urlCache[currentVideoSourceUrl] = parsedUrl
    }

    fun getVideoHeaders(): Map<String, String>? = currentVideoHeaders

    fun getSavedPosition(): Long = _currentPosition.value

    fun savePlaybackProgress() {
        val isLocalVideo = currentVideoSourceUrl.startsWith("/") ||
            currentVideoSourceUrl.startsWith("file://")

        if (isLocalVideo) {
            localVideoProgressManager.saveProgress(
                videoPath = currentVideoSourceUrl,
                position = _currentPosition.value,
                duration = _duration.value
            )
        } else {
            viewModelScope.launch {
                historyRepository.addOrUpdateHistory(
                    videoTitle = currentVideoTitle,
                    videoUrl = currentVideoSourceUrl,
                    parsedVideoUrl = currentParsedVideoUrl,
                    videoHeaders = currentVideoHeaders?.let { headersToJson(it) },
                    thumbnailUrl = currentThumbnailUrl,
                    watchedPosition = _currentPosition.value,
                    duration = _duration.value,
                    detailUrl = currentDetailUrl,
                    episodesJson = currentEpisodesJson,
                    currentEpisode = currentEpisodeName,
                    videoSource = currentVideoSource
                )
            }
        }
    }

    private fun headersToJson(headers: Map<String, String>): String {
        return headers.entries.joinToString(",", "{", "}") { (key, value) ->
            "\"$key\":\"${value.replace("\"", "\\\"")}\""
        }
    }

    private fun parseHeadersFromJson(json: String): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        try {
            val content = json.trim().removePrefix("{").removeSuffix("}")
            content.split(",").forEach { pair ->
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
        android.util.Log.e("VideoPlayerViewModel", "播放错误: $error, url=$currentVideoSourceUrl")

        if (tryNextRouteForSameEpisode()) {
            return
        }

        val isNetworkError = listOf("网络", "connection", "timeout", "IO")
            .any { error.contains(it, ignoreCase = true) }

        _playerState.value = PlayerState.Error(
            if (isNetworkError) "$error\n\n建议：\n1. 检查网络连接\n2. 尝试切换其他线路\n3. 稍后重试" else error
        )
    }

    /**
     * 播放失败时，尝试同一集数的下一条线路。
     */
    private fun tryNextRouteForSameEpisode(): Boolean {
        if (allEpisodes.isEmpty() || currentEpisodeIndex < 0) return false

        val cleanName = extractCleanEpisodeName(currentEpisodeName ?: "")
        if (cleanName.isEmpty()) return false

        val next = allEpisodes.firstOrNull { episode ->
            extractCleanEpisodeName(episode.name) == cleanName &&
                !triedEpisodeUrls.contains(episode.url)
        } ?: return false

        currentVideoSourceUrl = next.url
        currentEpisodeName = next.name
        triedEpisodeUrls.add(next.url)
        _routeSwitchEvent.value = next

        val routeName = extractRouteName(next.name)
        _playerState.value = PlayerState.LoadingWithMessage("当前线路无法播放，正在切换到 $routeName...")

        viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            loadDirect(next.url, next.name)
        }
        return true
    }

    private fun extractCleanEpisodeName(episodeName: String): String {
        return episodeName.replace("""\[[^\]]+\]\s*""".toRegex(), "").trim()
    }

    private fun extractRouteName(episodeName: String): String {
        val match = """\[([^\]]+)\]""".toRegex().find(episodeName)
        return match?.groupValues?.get(1) ?: "备用线路"
    }

    fun isAutoPlayNextEnabled(): Boolean = configRepository.isAutoPlayNextEnabled()

    /**
     * 对同一集数的所有线路做测速，提示最快的线路（由用户确认后切换）。
     */
    fun testAndSwitchToFastestRoute() {
        if (allEpisodes.isEmpty() || currentEpisodeIndex < 0) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cleanName = extractCleanEpisodeName(currentEpisodeName ?: "")
                if (cleanName.isEmpty()) return@launch

                val routes = allEpisodes.filter {
                    extractCleanEpisodeName(it.name) == cleanName && !it.isCloudStorage
                }
                if (routes.size <= 1) return@launch

                val tester = com.tv.ball.domain.network.RouteSpeedTester(
                    com.tv.ball.domain.network.UnsafeOkHttpClient.getUnsafeOkHttpClient()
                )

                _routeTestStatus.value = "正在测试 ${routes.size} 条线路..."
                val results = tester.testRoutes(routes.map { it.url to extractRouteName(it.name) }) { progress ->
                    _routeTestStatus.value = progress
                }

                val fastest = results.firstOrNull { it.success }
                if (fastest == null) {
                    _routeTestStatus.value = null
                    return@launch
                }

                if (fastest.url == currentVideoSourceUrl) {
                    _routeTestStatus.value = "当前线路最优"
                    kotlinx.coroutines.delay(2000)
                    _routeTestStatus.value = null
                    return@launch
                }

                val fastestEpisode = routes.firstOrNull { it.url == fastest.url }
                if (fastestEpisode == null) {
                    _routeTestStatus.value = null
                    return@launch
                }

                _routeTestStatus.value = "发现更快线路: ${fastest.routeName} (${fastest.downloadSpeed}KB/s)"
                _fasterRouteFound.value = FasterRouteInfo(
                    episode = fastestEpisode,
                    routeName = fastest.routeName,
                    downloadSpeed = fastest.downloadSpeed
                )

                kotlinx.coroutines.delay(2000)
                _routeTestStatus.value = null
            } catch (e: Exception) {
                android.util.Log.e("VideoPlayerViewModel", "线路测速失败: ${e.message}", e)
                _routeTestStatus.value = null
            }
        }
    }

    fun switchToFasterRoute() {
        val fasterRoute = _fasterRouteFound.value ?: return

        viewModelScope.launch {
            currentVideoSourceUrl = fasterRoute.episode.url
            currentEpisodeName = fasterRoute.episode.name
            triedEpisodeUrls.add(fasterRoute.episode.url)
            _routeSwitchEvent.value = fasterRoute.episode
            _fasterRouteFound.value = null

            _playerState.value = PlayerState.LoadingWithMessage("切换到更快线路: ${fasterRoute.routeName}...")
            kotlinx.coroutines.delay(500)
            loadDirect(fasterRoute.episode.url, fasterRoute.episode.name)
        }
    }

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
