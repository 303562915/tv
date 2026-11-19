package com.tv.ball.presentation.player

import android.content.Context
import android.util.Log
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.C
import com.tv.ball.util.VideoFlowLogger


class ExoPlayerManager constructor(
    private val context: Context
) {
    
    companion object {
        private const val TAG = "ExoPlayerManager"
        
        // 缓存配置 - 30MB 缓存（约30秒视频）
        private const val CACHE_SIZE = 2L * 1024 * 1024 * 1024 // 2GB 播放缓存（边看边缓存，可离线回看）
    }
    
    private var player: ExoPlayer? = null
    private var playerListener: PlayerListener? = null
    
    // 缓存实例
    private val cache: com.google.android.exoplayer2.upstream.cache.SimpleCache by lazy {
        val cacheDir = java.io.File(context.cacheDir, "exoplayer_cache")
        val evictor = com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor(CACHE_SIZE)
        val databaseProvider = com.google.android.exoplayer2.database.StandaloneDatabaseProvider(context)
        com.google.android.exoplayer2.upstream.cache.SimpleCache(cacheDir, evictor, databaseProvider)
    }
    
    fun initializePlayer(): ExoPlayer {
        if (player == null) {
            // 创建缓冲控制 - 优化配置以确保丝滑播放
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    10000,  // 最小缓冲时间 10秒（确保流畅播放的基础）
                    60000,  // 最大缓冲时间 60秒（提供充足的缓冲空间）
                    2500,   // 开始播放的最小缓冲 2.5秒（平衡启动速度和流畅度）
                    5000    // seek后重新缓冲 5秒（避免seek后立即卡顿）
                )
                .setTargetBufferBytes(C.LENGTH_UNSET) // 不限制缓冲大小
                .setPrioritizeTimeOverSizeThresholds(true) // 优先时间而非大小（保证流畅）
                .setBackBuffer(10000, true) // 保留10秒后向缓冲（支持快速后退）
                .setAllocator(
                    // 使用更大的分配器，支持更多并发下载
                    com.google.android.exoplayer2.upstream.DefaultAllocator(
                        true,  // 使用trim on reset
                        512 * 1024  // 每个分配块512KB，更快的数据传输和更大的缓冲
                    )
                )
                .build()

            player = ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .setSeekBackIncrementMs(10000) // 后退10秒
                .setSeekForwardIncrementMs(10000) // 前进10秒
                .build()
                .apply {
                    // 设置播放参数以优化本地文件播放
                    playWhenReady = true
                    
                    // 设置seek参数 - 使用精确seek，不等待关键帧（秒播）
                    setSeekParameters(com.google.android.exoplayer2.SeekParameters.EXACT)
                    
                    // 设置视频缩放模式 - 快速渲染
                    videoScalingMode = com.google.android.exoplayer2.C.VIDEO_SCALING_MODE_SCALE_TO_FIT

                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            val stateStr = when (playbackState) {
                                Player.STATE_IDLE -> "IDLE"
                                Player.STATE_BUFFERING -> "BUFFERING"
                                Player.STATE_READY -> "READY"
                                Player.STATE_ENDED -> "ENDED"
                                else -> "UNKNOWN"
                            }
                            Log.d(TAG, "播放状态变化: $stateStr")
                            Log.d(TAG, "播放器详情: isPlaying=${player?.isPlaying}, playWhenReady=${player?.playWhenReady}")
                            Log.d(TAG, "网络状态: bufferedPosition=${getBufferedPosition()}ms, currentPosition=${getCurrentPosition()}ms")
                            
                            // ========== 日志：播放器状态变化 ==========
                            when (playbackState) {
                                Player.STATE_IDLE -> {
                                    VideoFlowLogger.debug(
                                        VideoFlowLogger.FlowStage.PLAYER_INIT,
                                        "播放器空闲状态"
                                    )
                                }
                                Player.STATE_BUFFERING -> {
                                    VideoFlowLogger.debug(
                                        VideoFlowLogger.FlowStage.PLAYER_LOAD,
                                        "正在缓冲",
                                        "Position: ${getCurrentPosition()}ms\nBuffered: ${getBufferedPosition()}ms"
                                    )
                                }
                                Player.STATE_READY -> {
                                    VideoFlowLogger.success(
                                        VideoFlowLogger.FlowStage.PLAYER_LOAD,
                                        "缓冲完成，准备播放",
                                        "Duration: ${getDuration()}ms\nPosition: ${getCurrentPosition()}ms\nVideo: ${getVideoWidth()}x${getVideoHeight()}"
                                    )
                                }
                                Player.STATE_ENDED -> {
                                    VideoFlowLogger.info(
                                        VideoFlowLogger.FlowStage.PLAYER_PLAY,
                                        "播放结束"
                                    )
                                }
                            }
                            // ====================================
                            
                            when (playbackState) {
                                Player.STATE_READY -> {
                                    Log.d(TAG, "✓ 播放器就绪 - 当前位置: ${getCurrentPosition()}ms, 缓冲位置: ${getBufferedPosition()}ms")
                                    Log.d(TAG, "✓ 视频信息: ${getVideoWidth()}x${getVideoHeight()}, 时长: ${getDuration()}ms")
                                    playerListener?.onReady()
                                }
                                Player.STATE_BUFFERING -> {
                                    val bufferedMs = getBufferedPosition()
                                    val currentMs = getCurrentPosition()
                                    val bufferedPercent = if (getDuration() > 0) {
                                        (bufferedMs.toFloat() / getDuration() * 100).toInt()
                                    } else {
                                        0
                                    }
                                    Log.d(TAG, "⏳ 正在缓冲 - 当前: ${currentMs}ms, 已缓冲: ${bufferedMs}ms (${bufferedPercent}%)")
                                    
                                    // 检查是否长时间卡在缓冲状态
                                    if (bufferedMs == 0L && currentMs == 0L) {
                                        Log.w(TAG, "⚠️ 警告: 缓冲位置为0，可能是网络问题或URL无法访问")
                                    }
                                    
                                    playerListener?.onBuffering()
                                }
                                Player.STATE_ENDED -> {
                                    Log.d(TAG, "✓ 播放结束")
                                    playerListener?.onEnded()
                                }
                                Player.STATE_IDLE -> {
                                    Log.d(TAG, "播放器空闲状态")
                                }
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            val errorType = when (error.errorCode) {
                                PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> "网络IO错误"
                                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连接失败"
                                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络连接超时"
                                PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "无效的HTTP内容类型"
                                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "HTTP状态错误"
                                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "文件未找到"
                                PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "无权限访问"
                                PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "不允许明文传输"
                                PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> "读取位置超出范围"
                                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "容器格式错误"
                                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "清单文件格式错误"
                                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "不支持的容器格式"
                                PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "不支持的清单格式"
                                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "解码器初始化失败"
                                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "解码器查询失败"
                                PlaybackException.ERROR_CODE_DECODING_FAILED -> "解码失败"
                                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "解码格式超出能力"
                                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "不支持的解码格式"
                                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> "音频轨道初始化失败"
                                PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "音频轨道写入失败"
                                PlaybackException.ERROR_CODE_DRM_UNSPECIFIED -> "DRM错误"
                                else -> "未知错误(${error.errorCode})"
                            }
                            
                            val detailedMessage = buildString {
                                append("播放错误: $errorType\n")
                                append("错误码: ${error.errorCode}\n")
                                append("错误信息: ${error.message}\n")
                                error.cause?.let { cause ->
                                    append("原因: ${cause.message}\n")
                                    append("异常类型: ${cause.javaClass.simpleName}")
                                }
                            }
                            
                            // ========== 日志：播放错误 ==========
                            VideoFlowLogger.error(
                                VideoFlowLogger.FlowStage.PLAYER_ERROR,
                                "播放错误",
                                detailedMessage,
                                error
                            )
                            // ====================================
                            
                            Log.e(TAG, detailedMessage, error)
                            Log.e(TAG, "播放器状态: ${player?.playbackState}")
                            Log.e(TAG, "当前位置: ${getCurrentPosition()}ms")
                            Log.e(TAG, "缓冲位置: ${getBufferedPosition()}ms")
                            
                            playerListener?.onError("$errorType: ${error.message}")
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            Log.d(TAG, "播放状态改变: isPlaying=$isPlaying")
                            
                            // ========== 日志：播放状态改变 ==========
                            if (isPlaying) {
                                VideoFlowLogger.success(
                                    VideoFlowLogger.FlowStage.PLAYER_PLAY,
                                    "开始播放",
                                    "Position: ${getCurrentPosition()}ms"
                                )
                            } else {
                                VideoFlowLogger.info(
                                    VideoFlowLogger.FlowStage.PLAYER_PLAY,
                                    "播放暂停",
                                    "Position: ${getCurrentPosition()}ms"
                                )
                            }
                            // ====================================
                            playerListener?.onPlayingChanged(isPlaying)
                        }
                        
                        override fun onPositionDiscontinuity(
                            oldPosition: Player.PositionInfo,
                            newPosition: Player.PositionInfo,
                            reason: Int
                        ) {
                            val reasonStr = when (reason) {
                                Player.DISCONTINUITY_REASON_SEEK -> "SEEK"
                                Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "SEEK_ADJUSTMENT"
                                Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "AUTO_TRANSITION"
                                Player.DISCONTINUITY_REASON_REMOVE -> "REMOVE"
                                Player.DISCONTINUITY_REASON_SKIP -> "SKIP"
                                Player.DISCONTINUITY_REASON_INTERNAL -> "INTERNAL"
                                else -> "UNKNOWN"
                            }
                            Log.d(TAG, "位置不连续: ${oldPosition.positionMs}ms -> ${newPosition.positionMs}ms, 原因: $reasonStr")
                        }
                    })
                }
        }
        return player!!
    }
    
    fun setPlayerListener(listener: PlayerListener) {
        this.playerListener = listener
    }
    
    fun prepareMedia(url: String, headers: Map<String, String>? = null) {
        try {
            // 清理URL中的转义字符
            val cleanUrl = url.replace("\\/", "/")
            Log.d(TAG, "=== 准备播放媒体 ===")
            Log.d(TAG, "URL: $cleanUrl")
            Log.d(TAG, "URL长度: ${cleanUrl.length}")
            Log.d(TAG, "是否HTTPS: ${cleanUrl.startsWith("https://")}")
            Log.d(TAG, "是否HTTP: ${cleanUrl.startsWith("http://")}")
            Log.d(TAG, "是否本地文件: ${cleanUrl.startsWith("/") || cleanUrl.startsWith("file://")}")
            
            if (headers != null && headers.isNotEmpty()) {
                Log.d(TAG, "使用自定义请求头:")
                headers.forEach { (key, value) ->
                    Log.d(TAG, "  $key: $value")
                }
            } else {
                Log.d(TAG, "无自定义请求头")
            }
            
            val player = initializePlayer()
            Log.d(TAG, "播放器已初始化")
            
            val mediaSource = createMediaSource(cleanUrl, headers)
            Log.d(TAG, "媒体源已创建")
            
            player.setMediaSource(mediaSource)
            Log.d(TAG, "媒体源已设置")
            
            player.prepare()
            Log.d(TAG, "媒体准备完成")
            Log.d(TAG, "=== 准备播放媒体完成 ===")
        } catch (e: Exception) {
            Log.e(TAG, "=== 准备媒体失败 ===")
            Log.e(TAG, "异常类型: ${e.javaClass.simpleName}")
            Log.e(TAG, "异常信息: ${e.message}")
            Log.e(TAG, "堆栈跟踪:", e)
            playerListener?.onError("准备媒体失败: ${e.message}")
            throw e
        }
    }
    
    private fun createMediaSource(url: String, customHeaders: Map<String, String>? = null): MediaSource {
        try {
            Log.d(TAG, "=== 创建媒体源 ===")
            Log.d(TAG, "原始URL: $url")
            
            // 处理本地文件路径
            val uri = if (url.startsWith("/")) {
                "file://$url"
            } else {
                url
            }
            
            Log.d(TAG, "处理后URI: $uri")
            
            // 判断是否是本地文件
            val isLocalFile = url.startsWith("/") || url.startsWith("file://")
            Log.d(TAG, "是否本地文件: $isLocalFile")
            
            // 为本地文件使用简单的 DataSource.Factory，为网络文件使用 HTTP + 缓存
            val dataSourceFactory = if (isLocalFile) {
                Log.d(TAG, "使用本地文件数据源")
                // 本地文件使用默认的 DataSource，速度更快
                DefaultDataSource.Factory(context)
            } else {
                Log.d(TAG, "使用网络数据源（带缓存和连接池优化）")
                
                // 判断是否是 libvio 或 vbing 域名
                val referer = if (url.contains("libvio", ignoreCase = true) ||
                                  url.contains("vbing", ignoreCase = true)) {
                    "https://www.libvio.link"
                } else if (url.contains("maowushi", ignoreCase = true)) {
                    "https://www.maowushi.com"
                } else if (url.contains("iqiyi", ignoreCase = true) || 
                           url.contains("qiyi", ignoreCase = true)) {
                    "https://www.iqiyi.com"
                } else {
                    try {
                        val uri = android.net.Uri.parse(url)
                        "${uri.scheme}://${uri.host}"
                    } catch (e: Exception) {
                        Log.w(TAG, "解析Referer失败: ${e.message}")
                        url.substringBefore("?")
                    }
                }

                Log.d(TAG, "设置 Referer: $referer")

                // 添加常用的请求头
                val defaultRequestProperties = mutableMapOf(
                    "Accept" to "*/*",
                    "Accept-Encoding" to "identity",
                    "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
                    "Connection" to "keep-alive",
                    "Referer" to referer,
                    "Origin" to referer
                )
                
                // 合并自定义请求头（自定义请求头优先级更高）
                if (customHeaders != null && customHeaders.isNotEmpty()) {
                    Log.d(TAG, "合并自定义请求头:")
                    customHeaders.forEach { (key, value) ->
                        Log.d(TAG, "  $key: $value")
                        defaultRequestProperties[key] = value
                    }
                }

                // 使用优化的HTTP数据源工厂（支持HTTP/2多路复用和连接池）
                val httpDataSourceFactory = OkHttpDataSourceFactory.createFactory(
                    userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                    defaultRequestProperties = defaultRequestProperties
                )
                
                Log.d(TAG, "HTTP数据源工厂类型: ${httpDataSourceFactory.javaClass.name}")

                // 创建带缓存的 DataSource.Factory
                // 关键修复：之前 setCacheWriteDataSinkFactory(null) 等于「只读不写」，
                // 播完什么都没缓存下来；这里显式给出 CacheDataSink，边播边写盘。
                val cacheSinkFactory = com.google.android.exoplayer2.upstream.cache.CacheDataSink.Factory()
                    .setCache(cache)
                    .setFragmentSize(5 * 1024 * 1024L)
                val cacheDataSourceFactory = com.google.android.exoplayer2.upstream.cache.CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(httpDataSourceFactory)
                    .setCacheWriteDataSinkFactory(cacheSinkFactory)
                    .setFlags(com.google.android.exoplayer2.upstream.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                
                Log.d(TAG, "缓存数据源工厂创建完成")

                // 直接使用缓存数据源工厂，不再包装
                cacheDataSourceFactory
            }
            
            // 根据URL判断媒体类型
            val mediaType = when {
                url.contains(".m3u8") || url.contains("/m3u8") || url.contains("/index.m3u8") -> "HLS"
                url.contains(".mpd") -> "DASH"
                url.contains(".mp4") || url.contains(".mkv") || url.contains(".avi") || 
                url.contains(".flv") || url.contains(".webm") || url.contains(".mov") -> "Progressive"
                else -> "Progressive (默认)"
            }
            Log.d(TAG, "检测到媒体类型: $mediaType")
            Log.d(TAG, "完整URL: $url")
            Log.d(TAG, "URL长度: ${url.length}")
            
            // 检查URL是否可疑
            if (url.length < 20) {
                Log.w(TAG, "⚠️ URL太短，可能不是有效的视频链接")
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                Log.w(TAG, "⚠️ URL不是HTTP/HTTPS协议")
            }
            
            val mediaSource = when {
                url.contains(".m3u8") || url.contains("/m3u8") || url.contains("/index.m3u8") -> {
                    Log.d(TAG, "=== 创建 HLS 媒体源（M3U8）===")
                    Log.d(TAG, "M3U8 URL: $url")
                    Log.d(TAG, "URI: $uri")
                    Log.d(TAG, "数据源工厂类型: ${dataSourceFactory.javaClass.name}")
                    Log.d(TAG, "数据源工厂: ${dataSourceFactory.javaClass.simpleName}")
                    
                    // 创建HLS媒体源
                    val hlsFactory = HlsMediaSource.Factory(dataSourceFactory)
                        .setAllowChunklessPreparation(true)
                    
                    Log.d(TAG, "HLS Factory创建完成")
                    
                    val mediaItem = MediaItem.fromUri(uri)
                    Log.d(TAG, "MediaItem创建完成: $mediaItem")
                    
                    val source = hlsFactory.createMediaSource(mediaItem)
                    Log.d(TAG, "HLS MediaSource创建完成")
                    
                    source
                }
                url.contains(".mpd") -> {
                    Log.d(TAG, "创建 DASH 媒体源")
                    // DASH格式支持
                    ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(uri))
                }
                url.contains(".mp4") || url.contains(".mkv") || url.contains(".avi") || 
                url.contains(".flv") || url.contains(".webm") || url.contains(".mov") -> {
                    Log.d(TAG, "创建 Progressive 媒体源（带缓存）")
                    ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(uri))
                }
                else -> {
                    Log.d(TAG, "创建 Progressive 媒体源（默认，带缓存）")
                    // 默认尝试 Progressive，如果失败会触发错误
                    ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(uri))
                }
            }
            
            Log.d(TAG, "媒体源创建成功（已启用缓存）")
            Log.d(TAG, "=== 创建媒体源完成 ===")
            return mediaSource
        } catch (e: Exception) {
            Log.e(TAG, "=== 创建媒体源失败 ===")
            Log.e(TAG, "异常类型: ${e.javaClass.simpleName}")
            Log.e(TAG, "异常信息: ${e.message}")
            Log.e(TAG, "堆栈跟踪:", e)
            throw e
        }
    }
    
    fun play() {
        player?.play()
    }
    
    fun pause() {
        player?.pause()
    }
    
    fun seekTo(position: Long) {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "=== seekTo 开始 ===")
        Log.d(TAG, "目标位置: ${position}ms (${formatTime(position)})")
        Log.d(TAG, "当前位置: ${getCurrentPosition()}ms")
        Log.d(TAG, "播放器状态: ${player?.playbackState}")
        Log.d(TAG, "是否正在播放: ${player?.isPlaying}")
        Log.d(TAG, "缓冲位置: ${getBufferedPosition()}ms")
        
        player?.seekTo(position)
        
        val endTime = System.currentTimeMillis()
        Log.d(TAG, "seekTo 调用完成，耗时: ${endTime - startTime}ms")
        Log.d(TAG, "=== seekTo 结束 ===")
    }
    
    private fun formatTime(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes % 60, seconds % 60)
        } else {
            String.format("%d:%02d", minutes, seconds % 60)
        }
    }
    
    fun getCurrentPosition(): Long {
        return player?.currentPosition ?: 0L
    }
    
    fun getDuration(): Long {
        return player?.duration ?: 0L
    }
    
    fun isPlaying(): Boolean {
        return player?.isPlaying ?: false
    }
    
    fun isPlayerReady(): Boolean {
        return player?.playbackState == Player.STATE_READY
    }
    
    fun isBuffering(): Boolean {
        return player?.playbackState == Player.STATE_BUFFERING
    }
    
    fun togglePlayPause() {
        player?.let {
            if (it.isPlaying) {
                it.pause()
            } else {
                it.play()
            }
        }
    }
    
    fun setPlaybackSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
    }
    
    fun getPlaybackSpeed(): Float {
        return player?.playbackParameters?.speed ?: 1.0f
    }
    
    fun getVideoWidth(): Int {
        return player?.videoSize?.width ?: 0
    }
    
    fun getVideoHeight(): Int {
        return player?.videoSize?.height ?: 0
    }

    fun getBufferedPosition(): Long {
        return player?.bufferedPosition ?: 0L
    }
    
    fun releasePlayer() {
        player?.release()
        player = null
        playerListener = null
    }
    
    fun getPlayer(): ExoPlayer? = player
    
    fun setRepeatMode(repeatMode: Int) {
        player?.repeatMode = repeatMode
    }
    
    fun getRepeatMode(): Int {
        return player?.repeatMode ?: Player.REPEAT_MODE_OFF
    }
    
    /**
     * 获取缓存大小（字节）
     */
    fun getCacheSize(): Long {
        return try {
            cache.cacheSpace
        } catch (e: Exception) {
            0L
        }
    }
    
    /**
     * 清理缓存
     */
    fun clearCache() {
        try {
            cache.keys.forEach { key ->
                cache.removeResource(key)
            }
            Log.d(TAG, "缓存已清理")
        } catch (e: Exception) {
            Log.e(TAG, "清理缓存失败", e)
        }
    }
    
    interface PlayerListener {
        fun onReady()
        fun onBuffering()
        fun onEnded()
        fun onError(message: String)
        fun onPlayingChanged(isPlaying: Boolean)
    }
}


