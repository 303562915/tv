package com.tv.ball.presentation.player

import com.tv.ball.util.VideoFlowLogger

/**
 * VideoPlayerActivity 日志集成补丁
 * 
 * 将以下代码添加到 VideoPlayerActivity.kt 中以启用日志记录
 */

/*
===========================================
在 VideoPlayerActivity.onCreate() 开始处添加：
===========================================

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // 获取视频信息
    videoTitle = intent.getStringExtra("title") ?: "未知视频"
    val videoUrl = intent.getStringExtra("url") ?: ""
    videoId = intent.getLongExtra("videoId", 0L)
    
    // ========== 日志：开始新会话 ==========
    VideoFlowLogger.startSession(videoTitle, videoUrl)
    VideoFlowLogger.info(
        VideoFlowLogger.FlowStage.INIT,
        "初始化播放器",
        "视频ID: $videoId\n视频标题: $videoTitle\n视频URL: $videoUrl"
    )
    // ====================================
    
    // ... 原有代码继续
}

===========================================
在 VideoPlayerActivity.onDestroy() 中添加：
===========================================

override fun onDestroy() {
    // ========== 日志：结束会话 ==========
    VideoFlowLogger.info(
        VideoFlowLogger.FlowStage.OTHER,
        "播放器销毁",
        "播放时长: ${exoPlayerManager.player?.currentPosition}ms"
    )
    VideoFlowLogger.endSession()
    // ====================================
    
    // ... 原有代码继续
    super.onDestroy()
}

===========================================
在播放视频的方法中添加（例如 playVideo()）：
===========================================

private fun playVideo(url: String) {
    // ========== 日志：准备播放 ==========
    VideoFlowLogger.info(
        VideoFlowLogger.FlowStage.PLAYER_INIT,
        "准备播放视频",
        "URL: $url"
    )
    // ====================================
    
    try {
        currentPlayingUrl = url
        
        // ========== 日志：开始加载 ==========
        VideoFlowLogger.debug(
            VideoFlowLogger.FlowStage.PLAYER_LOAD,
            "开始加载视频源"
        )
        // ====================================
        
        exoPlayerManager.prepare(url)
        
        // ========== 日志：加载成功 ==========
        VideoFlowLogger.success(
            VideoFlowLogger.FlowStage.PLAYER_LOAD,
            "视频源加载成功"
        )
        // ====================================
        
    } catch (e: Exception) {
        // ========== 日志：加载失败 ==========
        VideoFlowLogger.error(
            VideoFlowLogger.FlowStage.PLAYER_ERROR,
            "视频加载失败",
            "URL: $url",
            e
        )
        // ====================================
        
        Toast.makeText(this, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

===========================================
在 ExoPlayer 监听器中添加：
===========================================

// 在设置 ExoPlayer 监听器的地方
exoPlayerManager.player?.addListener(object : Player.Listener {
    
    override fun onPlaybackStateChanged(playbackState: Int) {
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
                    "Position: ${exoPlayerManager.player?.currentPosition}ms"
                )
            }
            Player.STATE_READY -> {
                VideoFlowLogger.success(
                    VideoFlowLogger.FlowStage.PLAYER_LOAD,
                    "缓冲完成，准备播放",
                    "Duration: ${exoPlayerManager.player?.duration}ms\nPosition: ${exoPlayerManager.player?.currentPosition}ms"
                )
            }
            Player.STATE_ENDED -> {
                VideoFlowLogger.info(
                    VideoFlowLogger.FlowStage.PLAYER_PLAY,
                    "播放结束"
                )
            }
        }
    }
    
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            VideoFlowLogger.success(
                VideoFlowLogger.FlowStage.PLAYER_PLAY,
                "开始播放",
                "Position: ${exoPlayerManager.player?.currentPosition}ms\nSpeed: ${exoPlayerManager.player?.playbackParameters?.speed}x"
            )
        } else {
            VideoFlowLogger.info(
                VideoFlowLogger.FlowStage.PLAYER_PLAY,
                "播放暂停",
                "Position: ${exoPlayerManager.player?.currentPosition}ms"
            )
        }
    }
    
    override fun onPlayerError(error: PlaybackException) {
        VideoFlowLogger.error(
            VideoFlowLogger.FlowStage.PLAYER_ERROR,
            "播放错误",
            "Error code: ${error.errorCode}\nURL: $currentPlayingUrl",
            error
        )
    }
})

===========================================
在切换链接时添加：
===========================================

private fun switchToNextLink() {
    if (currentLinkIndex < sniffedVideoLinks.size - 1) {
        currentLinkIndex++
        val nextUrl = sniffedVideoLinks[currentLinkIndex]
        
        // ========== 日志：切换链接 ==========
        VideoFlowLogger.info(
            VideoFlowLogger.FlowStage.PLAYER_INIT,
            "切换到下一个链接",
            "Index: $currentLinkIndex/${sniffedVideoLinks.size}\nURL: $nextUrl"
        )
        // ====================================
        
        playVideo(nextUrl)
    } else {
        // ========== 日志：无更多链接 ==========
        VideoFlowLogger.warning(
            VideoFlowLogger.FlowStage.PLAYER_ERROR,
            "没有更多可用链接",
            "已尝试 ${sniffedVideoLinks.size} 个链接"
        )
        // ====================================
    }
}

===========================================
在网络状态变化时添加：
===========================================

private fun handleNetworkChange(networkType: String, message: String) {
    // ========== 日志：网络状态变化 ==========
    VideoFlowLogger.info(
        VideoFlowLogger.FlowStage.NETWORK,
        "网络状态变化",
        "Type: $networkType\nMessage: $message"
    )
    // ====================================
    
    // ... 原有处理逻辑
}

===========================================
完整示例：在嗅探到视频链接时
===========================================

// 假设在某个方法中接收到嗅探的链接
private fun onVideoLinkSniffed(url: String) {
    // ========== 日志：嗅探到链接 ==========
    VideoFlowLogger.success(
        VideoFlowLogger.FlowStage.SNIFF,
        "嗅探到视频链接",
        "URL: $url\nTotal: ${sniffedVideoLinks.size + 1}"
    )
    // ====================================
    
    sniffedVideoLinks.add(url)
    
    // 如果是第一个链接，自动播放
    if (!hasStartedPlaying && sniffedVideoLinks.size == 1) {
        hasStartedPlaying = true
        playVideo(url)
    }
}
*/

/**
 * 快速集成检查清单
 * 
 * □ 在 onCreate() 中调用 VideoFlowLogger.startSession()
 * □ 在 onDestroy() 中调用 VideoFlowLogger.endSession()
 * □ 在播放视频时记录 PLAYER_INIT 和 PLAYER_LOAD
 * □ 在 ExoPlayer 监听器中记录状态变化
 * □ 在错误处理中记录 PLAYER_ERROR
 * □ 在嗅探到链接时记录 SNIFF
 * □ 在网络状态变化时记录 NETWORK
 * 
 * 完成后，在设置页面点击"视频流程日志"即可查看完整日志
 */
