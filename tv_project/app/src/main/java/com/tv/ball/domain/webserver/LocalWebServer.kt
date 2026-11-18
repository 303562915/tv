package com.tv.ball.domain.webserver

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.net.URLEncoder

/**
 * 本地 Web 服务器
 * 用于在局域网内提供视频播放页面
 */
class LocalWebServer(private val context: Context, port: Int = 8888) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "LocalWebServer"
        private var instance: LocalWebServer? = null

        // 远程代理地址（虚拟主机或Cloudflare Worker）
        // 例如：https://你的域名.com/video-proxy.php
        // 留空则使用本地代理模式
        var REMOTE_PROXY_URL = ""

        fun getInstance(context: Context): LocalWebServer {
            if (instance == null) {
                instance = LocalWebServer(context.applicationContext)
            }
            return instance!!
        }
    }

    private var currentVideoUrl: String = ""
    private var currentVideoTitle: String = ""
    private var currentReferer: String = ""
    private var currentUserAgent: String = ""
    private var currentEpisodes: List<Episode> = emptyList()
    private var currentEpisodeIndex: Int = 0

    data class Episode(
        val title: String,
        val url: String
    )

    /**
     * 设置当前视频信息
     */
    fun setVideoInfo(url: String, title: String, referer: String = "", userAgent: String = "") {
        this.currentVideoUrl = url
        this.currentVideoTitle = title
        this.currentReferer = referer
        this.currentUserAgent = userAgent
    }

    /**
     * 设置剧集列表信息
     */
    fun setVideoInfoWithEpisodes(
        url: String,
        title: String,
        episodes: List<Episode>,
        currentIndex: Int = 0,
        referer: String = "",
        userAgent: String = ""
    ) {
        this.currentVideoUrl = url
        this.currentVideoTitle = title
        this.currentEpisodes = episodes
        this.currentEpisodeIndex = currentIndex
        this.currentReferer = referer
        this.currentUserAgent = userAgent
    }

    /**
     * 获取本机 IP 地址
     */
    fun getLocalIpAddress(): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val ipInt = wifiInfo.ipAddress
        
        return if (ipInt != 0) {
            String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        } else {
            "127.0.0.1"
        }
    }

    /**
     * 获取访问 URL
     */
    fun getAccessUrl(): String {
        return "http://${getLocalIpAddress()}:$listeningPort"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        Log.d(TAG, "收到请求: $method $uri")
        Log.d(TAG, "当前视频URL: $currentVideoUrl")
        Log.d(TAG, "当前视频标题: $currentVideoTitle")
        Log.d(TAG, "剧集数量: ${currentEpisodes.size}")

        return when {
            uri == "/" || uri == "/index.html" -> {
                Log.d(TAG, "返回播放器页面")
                servePlayerPage()
            }
            uri == "/video" -> {
                Log.d(TAG, "处理视频代理请求")
                serveVideoProxy(session)
            }
            uri == "/debug" -> {
                // 调试信息页面
                val debugInfo = """
                    <!DOCTYPE html>
                    <html>
                    <head><meta charset="UTF-8"><title>调试信息</title></head>
                    <body style="font-family: monospace; padding: 20px; background: #000; color: #0f0;">
                        <h2>🔍 LocalWebServer 调试信息</h2>
                        <p><strong>服务器状态:</strong> 运行中</p>
                        <p><strong>监听端口:</strong> $listeningPort</p>
                        <p><strong>本机IP:</strong> ${getLocalIpAddress()}</p>
                        <p><strong>访问地址:</strong> ${getAccessUrl()}</p>
                        <hr>
                        <p><strong>当前视频URL:</strong> $currentVideoUrl</p>
                        <p><strong>当前视频标题:</strong> $currentVideoTitle</p>
                        <p><strong>Referer:</strong> $currentReferer</p>
                        <p><strong>User-Agent:</strong> $currentUserAgent</p>
                        <p><strong>剧集数量:</strong> ${currentEpisodes.size}</p>
                        <p><strong>当前集索引:</strong> $currentEpisodeIndex</p>
                        <p><strong>远程代理:</strong> ${if (REMOTE_PROXY_URL.isEmpty()) "未配置" else REMOTE_PROXY_URL}</p>
                        <hr>
                        <p><a href="/" style="color: #0ff;">返回播放器</a></p>
                    </body>
                    </html>
                """.trimIndent()
                newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", debugInfo)
            }
            else -> {
                Log.w(TAG, "未知请求路径: $uri")
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found: $uri")
            }
        }
    }

    /**
     * 提供播放器页面
     */
    private fun servePlayerPage(): Response {
        Log.d(TAG, "生成播放器页面")
        Log.d(TAG, "视频URL: $currentVideoUrl")
        Log.d(TAG, "视频标题: $currentVideoTitle")

        if (currentVideoUrl.isEmpty()) {
            Log.e(TAG, "错误：视频URL为空！")
            val errorHtml = """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"><title>错误</title></head>
                <body style="font-family: Arial; padding: 40px; text-align: center; background: #000; color: #fff;">
                    <h1>❌ 错误</h1>
                    <p>视频地址为空，请先在应用中选择要投屏的视频</p>
                    <p style="margin-top: 30px;"><a href="/debug" style="color: #0ff;">查看调试信息</a></p>
                </body>
                </html>
            """.trimIndent()
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", errorHtml)
        }

        val isM3u8 = currentVideoUrl.contains(".m3u8", ignoreCase = true)
        Log.d(TAG, "视频类型: ${if (isM3u8) "M3U8" else "MP4"}")

        val html = generatePlayerHtml(isM3u8)
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    /**
     * 视频代理（添加请求头）
     */
    private fun serveVideoProxy(session: IHTTPSession): Response {
        try {
            // 获取请求的具体路径（用于m3u8的分片请求）
            val params = session.parms
            val targetUrl = params["url"] ?: currentVideoUrl

            Log.d(TAG, "代理请求: $targetUrl")

            // 创建HTTP连接
            val url = java.net.URL(targetUrl)
            val connection = url.openConnection() as java.net.HttpURLConnection

            // 添加请求头
            if (currentReferer.isNotEmpty()) {
                connection.setRequestProperty("Referer", currentReferer)
            }
            if (currentUserAgent.isNotEmpty()) {
                connection.setRequestProperty("User-Agent", currentUserAgent)
            } else {
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }

            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.doInput = true

            // 获取响应
            val contentType = connection.contentType ?: "application/octet-stream"
            val contentLength = connection.contentLength.toLong()
            val inputStream = connection.inputStream

            // 返回代理响应
            val response = if (contentLength > 0) {
                newFixedLengthResponse(Response.Status.OK, contentType, inputStream, contentLength)
            } else {
                newChunkedResponse(Response.Status.OK, contentType, inputStream)
            }

            // 添加CORS头
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Access-Control-Allow-Methods", "GET, OPTIONS")
            response.addHeader("Access-Control-Allow-Headers", "*")

            return response

        } catch (e: Exception) {
            Log.e(TAG, "代理请求失败: ${e.message}", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "代理失败: ${e.message}"
            )
        }
    }

    /**
     * 生成播放器 HTML
     */
    private fun generatePlayerHtml(isM3u8: Boolean): String {
        // 不要对URL进行URLEncoder.encode，直接使用原始URL
        val encodedTitle = currentVideoTitle.replace("\"", "&quot;")
        val encodedReferer = currentReferer.replace("\"", "&quot;")
        val encodedUserAgent = currentUserAgent.replace("\"", "&quot;")

        // 判断使用哪种代理模式
        val useRemoteProxy = REMOTE_PROXY_URL.isNotEmpty()
        val proxyMode = if (useRemoteProxy) "远程代理" else "本地代理"

        return if (isM3u8) {
            // 使用 DPlayer + hls.js 播放 m3u8
            """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>$encodedTitle</title>
    <style>
        * { margin: 0; padding: 0; }
        body {
            background: #000;
            overflow: hidden;
            font-family: Arial, sans-serif;
        }
        #dplayer { width: 100vw; height: 100vh; }
        .info-bar {
            position: fixed;
            top: 10px;
            left: 10px;
            background: rgba(0,0,0,0.7);
            color: #fff;
            padding: 10px 15px;
            border-radius: 5px;
            font-size: 14px;
            z-index: 9999;
        }
        .info-bar h3 { margin: 0 0 5px 0; font-size: 16px; }
        .info-bar p { margin: 3px 0; font-size: 12px; opacity: 0.8; }

        /* 选集按钮 */
        .episode-btn {
            position: fixed;
            bottom: 80px;
            right: 20px;
            background: rgba(0,0,0,0.8);
            color: #fff;
            padding: 12px 20px;
            border-radius: 25px;
            font-size: 14px;
            cursor: pointer;
            z-index: 9999;
            border: 2px solid #667eea;
            transition: all 0.3s;
        }
        .episode-btn:hover {
            background: #667eea;
            transform: scale(1.05);
        }

        /* 选集面板 */
        .episode-panel {
            position: fixed;
            bottom: 0;
            right: 0;
            width: 100%;
            max-width: 400px;
            max-height: 60vh;
            background: rgba(0,0,0,0.95);
            border-top-left-radius: 20px;
            border-top-right-radius: 20px;
            z-index: 10000;
            transform: translateY(100%);
            transition: transform 0.3s;
            overflow: hidden;
            display: flex;
            flex-direction: column;
        }
        .episode-panel.show {
            transform: translateY(0);
        }
        .episode-header {
            padding: 15px 20px;
            border-bottom: 1px solid rgba(255,255,255,0.1);
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        .episode-header h3 {
            color: #fff;
            font-size: 16px;
            margin: 0;
        }
        .close-btn {
            color: #fff;
            font-size: 24px;
            cursor: pointer;
            background: none;
            border: none;
            padding: 0;
            width: 30px;
            height: 30px;
            line-height: 30px;
            text-align: center;
        }
        .episode-list {
            flex: 1;
            overflow-y: auto;
            padding: 10px;
        }
        .episode-item {
            background: rgba(255,255,255,0.1);
            color: #fff;
            padding: 12px 15px;
            margin: 5px 0;
            border-radius: 8px;
            cursor: pointer;
            transition: all 0.2s;
            border: 2px solid transparent;
        }
        .episode-item:hover {
            background: rgba(255,255,255,0.2);
        }
        .episode-item.active {
            background: #667eea;
            border-color: #fff;
        }
    </style>
</head>
<body>
    <div class="info-bar">
        <h3>📺 $encodedTitle</h3>
        <p>🎬 M3U8 在线播放</p>
        <p>✅ $proxyMode 模式</p>
    </div>
    <div id="dplayer"></div>

    ${if (currentEpisodes.isNotEmpty()) """
    <!-- 选集按钮 -->
    <div class="episode-btn" onclick="toggleEpisodePanel()">
        📋 选集 (${currentEpisodeIndex + 1}/${currentEpisodes.size})
    </div>

    <!-- 选集面板 -->
    <div class="episode-panel" id="episodePanel">
        <div class="episode-header">
            <h3>选择集数</h3>
            <button class="close-btn" onclick="toggleEpisodePanel()">×</button>
        </div>
        <div class="episode-list" id="episodeList"></div>
    </div>
    """ else ""}

    <script src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
    <script src="https://cdn.jsdelivr.net/npm/dplayer@latest/dist/DPlayer.min.js"></script>
    <script>
        console.log('========== 播放器初始化开始 ==========');

        const videoUrl = '$currentVideoUrl';
        const referer = '$encodedReferer';
        const userAgent = '$encodedUserAgent';
        const remoteProxy = '${REMOTE_PROXY_URL}';

        console.log('📹 原始视频地址:', videoUrl);
        console.log('🔗 Referer:', referer);
        console.log('🖥️ User-Agent:', userAgent);
        console.log('🌐 远程代理:', remoteProxy || '未配置');

        // 检查视频URL是否为空
        if (!videoUrl || videoUrl === '') {
            console.error('❌ 错误：视频URL为空！');
            document.body.innerHTML = '<div style="color:#fff;text-align:center;padding:50px;"><h1>❌ 错误</h1><p>视频地址为空</p></div>';
            throw new Error('视频URL为空');
        }

        // 剧集列表
        const episodes = ${if (currentEpisodes.isNotEmpty()) {
            "[" + currentEpisodes.joinToString(",") { ep ->
                "{title:'${ep.title.replace("'", "\\'")}',url:'${ep.url.replace("'", "\\'")}'}"
            } + "]"
        } else "[]"};
        let currentEpisodeIndex = $currentEpisodeIndex;

        console.log('📺 剧集数量:', episodes.length);
        if (episodes.length > 0) {
            console.log('📋 当前集索引:', currentEpisodeIndex);
            console.log('📋 当前集标题:', episodes[currentEpisodeIndex]?.title);
        }

        let retryCount = 0;
        const MAX_RETRIES = 3;
        let currentMode = 'direct';  // direct, remote, local

        // 构建播放URL
        function buildPlayUrl(url, mode) {
            if (mode === 'direct') {
                console.log('模式: 直接访问（浏览器原生请求）');
                return url;
            } else if (mode === 'remote' && remoteProxy) {
                console.log('模式: 远程代理');
                let proxyUrl = remoteProxy + '?url=' + encodeURIComponent(url);
                if (referer) proxyUrl += '&referer=' + encodeURIComponent(referer);
                if (userAgent) proxyUrl += '&ua=' + encodeURIComponent(userAgent);
                return proxyUrl;
            } else {
                console.log('模式: 本地代理');
                return '/video?url=' + encodeURIComponent(url);
            }
        }

        // 自定义 HLS.js loader（可添加自定义请求头）
        class CustomLoader extends Hls.DefaultConfig.loader {
            constructor(config) {
                super(config);
                const load = this.load.bind(this);
                this.load = function(context, config, callbacks) {
                    // 可以在这里添加自定义请求头（但 Referer/UA 仍然受浏览器限制）
                    // 如果视频源支持自定义头（如 X-Referer），可以这样：
                    if (referer && currentMode === 'direct') {
                        context.xhr = new XMLHttpRequest();
                        context.xhr.open('GET', context.url, true);
                        // 尝试添加自定义头（某些视频源可能支持）
                        try {
                            context.xhr.setRequestHeader('X-Referer', referer);
                            context.xhr.setRequestHeader('X-User-Agent', userAgent);
                        } catch(e) {
                            console.log('无法设置自定义头:', e.message);
                        }
                    }
                    return load(context, config, callbacks);
                };
            }
        }

        // 初始化播放器
        let playUrl = buildPlayUrl(videoUrl, currentMode);
        console.log('🎬 最终播放地址:', playUrl);
        console.log('🔧 开始初始化 DPlayer...');

        try {
            const dp = new DPlayer({
                container: document.getElementById('dplayer'),
                video: {
                    url: playUrl,
                    type: 'customHls',
                    customType: {
                        customHls: function(video, player) {
                            console.log('🔧 初始化 HLS.js...');
                            const hls = new Hls({
                                loader: CustomLoader,
                                enableWorker: true,
                                lowLatencyMode: true,
                                debug: false
                            });
                            hls.on(Hls.Events.MANIFEST_PARSED, function() {
                                console.log('✅ M3U8 解析成功');
                            });
                            hls.on(Hls.Events.ERROR, function(event, data) {
                                console.error('❌ HLS 错误:', data.type, data.details);
                            });
                            hls.loadSource(video.src);
                            hls.attachMedia(video);
                        }
                    }
                },
                autoplay: true,
                volume: 1.0,
                screenshot: true,
                hotkey: true,
                preload: 'auto',
                lang: 'zh-cn'
            });

            console.log('✅ DPlayer 初始化完成');

            // 监听播放事件
            dp.on('play', function() {
                console.log('▶️ 开始播放');
            });

            dp.on('pause', function() {
                console.log('⏸️ 暂停播放');
            });

            dp.on('canplay', function() {
                console.log('✅ 视频可以播放');
            });

            dp.on('loadstart', function() {
                console.log('⏳ 开始加载视频');
            });

        // 监听播放错误，智能降级
        dp.on('error', function() {
            console.error('❌ 播放错误，尝试降级...');
            console.log('当前模式:', currentMode, '重试次数:', retryCount);

            if (retryCount < MAX_RETRIES) {
                retryCount++;
                console.log('🔄 重试次数:', retryCount + '/' + MAX_RETRIES);

                // 降级策略：direct → remote → local
                if (retryCount === 1 && currentMode === 'direct') {
                    currentMode = remoteProxy ? 'remote' : 'local';
                    console.log('⚠️ 直接访问失败，切换到', currentMode === 'remote' ? '远程代理' : '本地代理');
                } else if (retryCount === 2 && currentMode === 'remote') {
                    currentMode = 'local';
                    console.log('⚠️ 远程代理失败，切换到本地代理');
                }

                const currentUrl = episodes.length > 0 ? episodes[currentEpisodeIndex].url : videoUrl;
                playUrl = buildPlayUrl(currentUrl, currentMode);
                setTimeout(() => {
                    console.log('🔄 重新加载视频:', playUrl);
                    dp.switchVideo({
                        url: playUrl,
                        type: 'customHls',
                        customType: {
                            customHls: function(video, player) {
                                const hls = new Hls({ loader: CustomLoader });
                                hls.loadSource(video.src);
                                hls.attachMedia(video);
                            }
                        }
                    });
                }, 2000);
            } else {
                console.error('❌ 所有方案都失败了');
                dp.notice('播放失败：所有播放方案均已尝试', 5000);
            }
        });

        // 监听视频播放结束，自动播放下一集
        dp.on('ended', function() {
            if (episodes.length > 0 && currentEpisodeIndex < episodes.length - 1) {
                console.log('当前集播放完毕，3秒后自动播放下一集');
                dp.notice('3秒后自动播放下一集...', 3000);
                setTimeout(() => {
                    playEpisode(currentEpisodeIndex + 1);
                }, 3000);
            } else {
                console.log('已播放完所有集数');
                dp.notice('已播放完所有集数', 3000);
            }
        });

        // 切换集数
        function playEpisode(index) {
            if (index < 0 || index >= episodes.length) return;

            currentEpisodeIndex = index;
            retryCount = 0;
            currentMode = 'direct';

            const episode = episodes[index];
            playUrl = buildPlayUrl(episode.url, currentMode);

            console.log('切换到:', episode.title, playUrl);
            dp.notice('正在播放: ' + episode.title, 2000);

            dp.switchVideo({
                url: playUrl,
                type: 'customHls',
                customType: {
                    customHls: function(video, player) {
                        const hls = new Hls({ loader: CustomLoader });
                        hls.loadSource(video.src);
                        hls.attachMedia(video);
                    }
                }
            });

            updateEpisodeList();
            updateEpisodeButton();
        }

        // 切换选集面板
        function toggleEpisodePanel() {
            const panel = document.getElementById('episodePanel');
            if (panel) {
                panel.classList.toggle('show');
            }
        }

        // 更新选集按钮文字
        function updateEpisodeButton() {
            const btn = document.querySelector('.episode-btn');
            if (btn && episodes.length > 0) {
                btn.textContent = '📋 选集 (' + (currentEpisodeIndex + 1) + '/' + episodes.length + ')';
            }
        }

        // 渲染选集列表
        function updateEpisodeList() {
            const listEl = document.getElementById('episodeList');
            if (!listEl || episodes.length === 0) return;

            listEl.innerHTML = episodes.map((ep, index) =>
                '<div class="episode-item ' + (index === currentEpisodeIndex ? 'active' : '') + '" onclick="playEpisode(' + index + '); toggleEpisodePanel();">' +
                    ep.title +
                '</div>'
            ).join('');
        }

        // 初始化选集列表
        if (episodes.length > 0) {
            updateEpisodeList();
        }

        console.log('========== 播放器初始化完成 ==========');

        } catch (error) {
            console.error('❌ 播放器初始化失败:', error);
            document.body.innerHTML = '<div style="color:#fff;text-align:center;padding:50px;"><h1>❌ 初始化失败</h1><p>' + error.message + '</p><p><a href="/debug" style="color:#0ff;">查看调试信息</a></p></div>';
        }

        // 3秒后隐藏信息栏
        setTimeout(() => {
            const infoBar = document.querySelector('.info-bar');
            if (infoBar) {
                infoBar.style.transition = 'opacity 0.5s';
                infoBar.style.opacity = '0';
                setTimeout(() => infoBar.remove(), 500);
            }
        }, 3000);
    </script>
</body>
</html>
            """.trimIndent()
        } else {
            // 直接使用 HTML5 video 播放 mp4
            """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>$encodedTitle</title>
    <style>
        * { margin: 0; padding: 0; }
        body { 
            background: #000; 
            display: flex; 
            justify-content: center; 
            align-items: center; 
            height: 100vh;
            font-family: Arial, sans-serif;
        }
        video { 
            max-width: 100%; 
            max-height: 100%;
            outline: none;
        }
        .info-bar {
            position: fixed;
            top: 10px;
            left: 10px;
            background: rgba(0,0,0,0.7);
            color: #fff;
            padding: 10px 15px;
            border-radius: 5px;
            font-size: 14px;
            z-index: 9999;
        }
        .info-bar h3 { margin: 0 0 5px 0; font-size: 16px; }
        .info-bar p { margin: 3px 0; font-size: 12px; opacity: 0.8; }
    </style>
</head>
<body>
    <div class="info-bar">
        <h3>📺 $encodedTitle</h3>
        <p>🎬 MP4 直接播放</p>
        <p>✅ 支持快进/快退</p>
    </div>
    <video controls autoplay>
        <source src="$currentVideoUrl" type="video/mp4">
        您的浏览器不支持视频播放
    </video>
    <script>
        // 3秒后隐藏信息栏
        setTimeout(() => {
            const infoBar = document.querySelector('.info-bar');
            if (infoBar) {
                infoBar.style.transition = 'opacity 0.5s';
                infoBar.style.opacity = '0';
                setTimeout(() => infoBar.remove(), 500);
            }
        }, 3000);
    </script>
</body>
</html>
            """.trimIndent()
        }
    }

    /**
     * 启动服务器
     */
    fun startServer(): Boolean {
        return try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.d(TAG, "服务器启动成功: ${getAccessUrl()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "服务器启动失败: ${e.message}", e)
            false
        }
    }

    /**
     * 停止服务器
     */
    fun stopServer() {
        try {
            stop()
            Log.d(TAG, "服务器已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止服务器失败: ${e.message}", e)
        }
    }
}
