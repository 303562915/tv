package com.tv.ball.util

/**
 * 视频流程日志集成示例
 * 
 * 这个文件展示了如何在各个关键流程中集成日志记录
 */

// ============================================
// 1. 在 VideoPlayerActivity 中集成
// ============================================

/*
在 VideoPlayerActivity.onCreate() 开始时：

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // 获取视频信息
    videoTitle = intent.getStringExtra("title") ?: "未知视频"
    val videoUrl = intent.getStringExtra("url") ?: ""
    
    // 开始新的日志会话
    VideoFlowLogger.startSession(videoTitle, videoUrl)
    VideoFlowLogger.info(
        VideoFlowLogger.FlowStage.INIT,
        "初始化播放器",
        "视频标题: $videoTitle\n视频URL: $videoUrl"
    )
    
    // ... 原有代码
}

在 VideoPlayerActivity.onDestroy() 中：

override fun onDestroy() {
    VideoFlowLogger.info(VideoFlowLogger.FlowStage.OTHER, "播放器销毁")
    VideoFlowLogger.endSession()
    super.onDestroy()
}
*/

// ============================================
// 2. 在资源嗅探中集成 (ResourceSnifferWebViewClient)
// ============================================

/*
在 ResourceSnifferWebViewClient.shouldInterceptRequest() 中：

override fun shouldInterceptRequest(
    view: WebView?,
    request: WebResourceRequest?
): WebResourceResponse? {
    val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
    
    // 检测到视频资源
    if (isVideoResource(url)) {
        VideoFlowLogger.success(
            VideoFlowLogger.FlowStage.SNIFF,
            "嗅探到视频资源",
            "URL: $url\nType: ${getResourceType(url)}"
        )
        
        // 添加到资源列表
        addResource(url)
    }
    
    return super.shouldInterceptRequest(view, request)
}

在页面加载开始时：

override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
    super.onPageStarted(view, url, favicon)
    VideoFlowLogger.info(
        VideoFlowLogger.FlowStage.SNIFF,
        "开始加载页面",
        "URL: $url"
    )
}

在页面加载完成时：

override fun onPageFinished(view: WebView?, url: String?) {
    super.onPageFinished(view, url)
    VideoFlowLogger.success(
        VideoFlowLogger.FlowStage.SNIFF,
        "页面加载完成",
        "URL: $url\n嗅探到 ${resourceCount} 个资源"
    )
}
*/

// ============================================
// 3. 在视频解析中集成 (BaseParser 及其实现类)
// ============================================

/*
在 BaseParser.parse() 开始时：

suspend fun parse(url: String): ParseResult {
    VideoFlowLogger.info(
        VideoFlowLogger.FlowStage.PARSE,
        "开始解析视频链接",
        "Parser: ${this::class.simpleName}\nURL: $url"
    )
    
    try {
        val result = parseInternal(url)
        
        VideoFlowLogger.success(
            VideoFlowLogger.FlowStage.PARSE,
            "解析成功",
            "找到 ${result.urls.size} 个播放链接\n类型: ${result.type}"
        )
        
        return result
    } catch (e: Exception) {
        VideoFlowLogger.error(
            VideoFlowLogger.FlowStage.PARSE,
            "解析失败",
            "Parser: ${this::class.simpleName}\nURL: $url",
            e
        )
        throw e
    }
}
*/

// ============================================
// 4. 在网络请求中集成 (NetworkDataSource)
// ============================================

/*
在 NetworkDataSource.fetchHtml() 中：

suspend fun fetchHtml(url: String): String {
    VideoFlowLogger.debug(
        VideoFlowLogger.FlowStage.NETWORK,
        "发起网络请求",
        "URL: $url\nMethod: GET"
    )
    
    try {
        val response = client.get(url)
        
        VideoFlowLogger.success(
            VideoFlowLogger.FlowStage.NETWORK,
            "网络请求成功",
            "URL: $url\nStatus: ${response.status}\nSize: ${response.body.length} bytes"
        )
        
        return response.body
    } catch (e: Exception) {
        VideoFlowLogger.error(
            VideoFlowLogger.FlowStage.NETWORK,
            "网络请求失败",
            "URL: $url",
            e
        )
        throw e
    }
}
*/

// ============================================
// 5. 在播放器中集成 (ExoPlayerManager)
// ============================================

/*
在 ExoPlayerManager.prepare() 中：

fun prepare(url: String) {
    VideoFlowLogger.info(
        VideoFlowLogger.FlowStage.PLAYER_INIT,
        "准备播放器",
        "URL: $url"
    )
    
    try {
        val mediaItem = MediaItem.fromUri(url)
        player.setMediaItem(mediaItem)
        player.prepare()
        
        VideoFlowLogger.success(
            VideoFlowLogger.FlowStage.PLAYER_INIT,
            "播放器准备完成"
        )
    } catch (e: Exception) {
        VideoFlowLogger.error(
            VideoFlowLogger.FlowStage.PLAYER_INIT,
            "播放器准备失败",
            error = e
        )
    }
}

在播放器监听器中：

player.addListener(object : Player.Listener {
    override fun onPlaybackStateChanged(state: Int) {
        when (state) {
            Player.STATE_BUFFERING -> {
                VideoFlowLogger.debug(
                    VideoFlowLogger.FlowStage.PLAYER_LOAD,
                    "正在缓冲"
                )
            }
            Player.STATE_READY -> {
                VideoFlowLogger.success(
                    VideoFlowLogger.FlowStage.PLAYER_LOAD,
                    "缓冲完成，准备播放",
                    "Duration: ${player.duration}ms"
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
                "Position: ${player.currentPosition}ms"
            )
        } else {
            VideoFlowLogger.info(
                VideoFlowLogger.FlowStage.PLAYER_PLAY,
                "播放暂停",
                "Position: ${player.currentPosition}ms"
            )
        }
    }
    
    override fun onPlayerError(error: PlaybackException) {
        VideoFlowLogger.error(
            VideoFlowLogger.FlowStage.PLAYER_ERROR,
            "播放错误",
            "Error code: ${error.errorCode}",
            error
        )
    }
})
*/

// ============================================
// 6. 在下载中集成 (VideoDownloader)
// ============================================

/*
在 VideoDownloader.download() 中：

suspend fun download(url: String, outputPath: String) {
    VideoFlowLogger.info(
        VideoFlowLogger.FlowStage.DOWNLOAD,
        "开始下载",
        "URL: $url\nOutput: $outputPath"
    )
    
    try {
        // 下载逻辑
        downloadInternal(url, outputPath) { progress ->
            if (progress % 10 == 0) {  // 每10%记录一次
                VideoFlowLogger.debug(
                    VideoFlowLogger.FlowStage.DOWNLOAD,
                    "下载进度: $progress%"
                )
            }
        }
        
        VideoFlowLogger.success(
            VideoFlowLogger.FlowStage.DOWNLOAD,
            "下载完成",
            "Output: $outputPath"
        )
    } catch (e: Exception) {
        VideoFlowLogger.error(
            VideoFlowLogger.FlowStage.DOWNLOAD,
            "下载失败",
            "URL: $url",
            e
        )
        throw e
    }
}
*/

// ============================================
// 7. 在设置界面添加日志入口
// ============================================

/*
在 SettingsFragment 或 MainActivity 的菜单中添加：

// 在菜单项点击事件中
R.id.menu_video_flow_log -> {
    val intent = Intent(this, VideoFlowLogActivity::class.java)
    startActivity(intent)
    true
}

// 或者在设置列表中添加一项
PreferenceScreen {
    Preference {
        title = "视频流程日志"
        summary = "查看视频播放的详细日志"
        onClick {
            val intent = Intent(context, VideoFlowLogActivity::class.java)
            context.startActivity(intent)
        }
    }
}
*/
