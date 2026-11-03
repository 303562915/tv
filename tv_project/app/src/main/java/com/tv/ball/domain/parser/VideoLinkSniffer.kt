package com.tv.ball.domain.parser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.resume

class VideoLinkSniffer(
    private val context: Context,
    private val onProgressUpdate: ((String) -> Unit)? = null,
    private val onLinkFound: ((String) -> Unit)? = null  // 实时回调找到的链接
) {

    companion object {
        private const val TAG = "VideoLinkSniffer"
        private const val SNIFF_TIMEOUT = 15000L // 15秒超时，给更多时间加载
        private const val PROGRESS_TIMEOUT = 5000L // 5秒无新进展才算超时

        // 视频文件扩展名
        private val VIDEO_EXTENSIONS = listOf(
            "m3u8", "mp4", "flv", "mkv", "mov", "m4v",
            "f4v", "webm", "avi", "wmv", "mpg", "mpeg", "3gp"
            // 注意：不包含 "ts"，因为 TS 分片不是完整视频
        )

        // 视频相关关键词
        private val VIDEO_KEYWORDS = listOf(
            "video", "m3u8", "mp4", "stream", "playlist", "cdn",
            "cloud", "vod", "hls", "dash", "manifest", "media"
        )

        // 自定义优先级关键词（按优先级排序）
        private val PRIORITY_KEYWORDS = listOf(
            ".m3u8",        // 最高优先级：以 .m3u8 结尾
            "index.m3u8",   // 索引文件
            "playlist.m3u8", // 播放列表
            ".mp4"          // MP4 文件
        )

        // 黑名单 - 明确排除的文件类型
        private val BLACKLIST = listOf(
            ".js", ".css", ".ico", ".png", ".jpg", ".webp", ".gif",
            ".html", ".htm", "adservice", "doubleclick", "analytics",
            "tracking", "ads", "adx", "googleads", "playerconfig", "player.js",
            ".ts?",  // TS 分片（带查询参数）
            "/ts/",  // TS 分片目录
            "segment", "chunk"  // 分片关键词
        )
    }

    /**
     * 嗅探视频链接
     * @param url 播放页面URL
     * @return 视频链接列表，按优先级排序
     */
    suspend fun sniffVideoLinks(url: String): List<String> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val videoLinks = mutableSetOf<String>()
            val handler = Handler(Looper.getMainLooper())
            var webView: WebView? = null
            var lastProgressTime = System.currentTimeMillis() // 记录最后一次有进展的时间
            
            // 提前定义超时回调，以便在WebViewClient中引用
            val timeoutRunnable = Runnable {
                Log.d(TAG, "=== 嗅探完成 ===")
                Log.d(TAG, "总共找到 ${videoLinks.size} 个视频链接")
                onProgressUpdate?.invoke("嗅探完成，找到 ${videoLinks.size} 个链接")

                if (continuation.isActive) {
                    val sortedLinks = sortVideoLinks(videoLinks.toList())
                    sortedLinks.forEachIndexed { index, link ->
                        Log.d(TAG, "链接 #${index + 1}: $link")
                    }
                    continuation.resume(sortedLinks)
                }

                webView?.destroy()
            }

            try {
                Log.d(TAG, "=== 开始嗅探视频链接 ===")
                Log.d(TAG, "目标URL: $url")
                onProgressUpdate?.invoke("开始嗅探: $url")

                webView = WebView(context).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        blockNetworkImage = true // 不加载图片
                        userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): android.webkit.WebResourceResponse? {
                            val requestUrl = request?.url?.toString() ?: return null

                            // 检查是否是视频链接
                            if (isVideoLink(requestUrl)) {
                                Log.d(TAG, "✓ 发现潜在视频链接: $requestUrl")
                                Log.d(TAG, "  - URL长度: ${requestUrl.length}")
                                Log.d(TAG, "  - 协议: ${if (requestUrl.startsWith("https")) "HTTPS" else if (requestUrl.startsWith("http")) "HTTP" else "其他"}")
                                
                                if (videoLinks.add(requestUrl)) {
                                    // 新链接，立即回调
                                    lastProgressTime = System.currentTimeMillis() // 更新进展时间
                                    onLinkFound?.invoke(requestUrl)
                                    onProgressUpdate?.invoke("发现视频链接 #${videoLinks.size}")
                                    
                                    // 优化：找到第一个视频链接后，再等待2秒看是否有更多链接
                                    if (videoLinks.size == 1) {
                                        handler.postDelayed({
                                            if (continuation.isActive) {
                                                Log.d(TAG, "找到 ${videoLinks.size} 个视频链接，结束嗅探")
                                                handler.removeCallbacks(timeoutRunnable)
                                                handler.post(timeoutRunnable)
                                            }
                                        }, 2000)
                                    }
                                }
                            }

                            return null
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            lastProgressTime = System.currentTimeMillis() // 页面开始加载也算进展
                            Log.d(TAG, "页面开始加载: $url")
                            onProgressUpdate?.invoke("页面加载中...")
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            lastProgressTime = System.currentTimeMillis() // 页面加载完成也算进展
                            Log.d(TAG, "页面加载完成: $url")
                            onProgressUpdate?.invoke("页面加载完成，分析中...")

                            // 优化：如果已经找到视频链接，立即返回，不再执行JS
                            if (videoLinks.isNotEmpty()) {
                                Log.d(TAG, "已找到 ${videoLinks.size} 个视频链接，跳过JS分析")
                                handler.removeCallbacks(timeoutRunnable)
                                handler.post(timeoutRunnable)
                                return
                            }

                            // 使用 JavaScript 嗅探视频链接
                            val jsCode = """
                                (function() {
                                    // 1. 直接获取视频元素
                                    var video = document.querySelector('video');
                                    if (video && video.src) return video.src;

                                    // 2. 获取source标签
                                    var sources = document.querySelectorAll('source[src]');
                                    for (var i = 0; i < sources.length; i++) {
                                        if (sources[i].src) return sources[i].src;
                                    }
                                    
                                    // 3. 检查iframe中的视频
                                    var iframes = document.querySelectorAll('iframe');
                                    for (var i = 0; i < iframes.length; i++) {
                                        try {
                                            var iframeDoc = iframes[i].contentDocument || iframes[i].contentWindow.document;
                                            if (iframeDoc) {
                                                var iframeVideo = iframeDoc.querySelector('video');
                                                if (iframeVideo && iframeVideo.src) return iframeVideo.src;
                                            }
                                        } catch(e) {
                                            // 跨域iframe无法访问，获取iframe的src
                                            if (iframes[i].src && iframes[i].src.length > 10) {
                                                return 'IFRAME:' + iframes[i].src;
                                            }
                                        }
                                    }

                                    // 4. 查找script标签中的m3u8/mp4链接
                                    var scripts = document.querySelectorAll('script');
                                    for (var i = 0; i < scripts.length; i++) {
                                        var content = scripts[i].innerHTML;
                                        if (!content) continue;

                                        // 匹配 m3u8 链接
                                        var m3u8Match = content.match(/(https?:[^'"]+\.m3u8[^'"]*)/i);
                                        if (m3u8Match) return m3u8Match[1];

                                        // 匹配 mp4 链接
                                        var mp4Match = content.match(/(https?:[^'"]+\.mp4[^'"]*)/i);
                                        if (mp4Match) return mp4Match[1];
                                        
                                        // 匹配 url: "xxx" 格式
                                        var urlMatch = content.match(/url\s*[:=]\s*['"](https?:[^'"]+)['"]/i);
                                        if (urlMatch && (urlMatch[1].includes('.m3u8') || urlMatch[1].includes('.mp4'))) {
                                            return urlMatch[1];
                                        }
                                    }

                                    return '';
                                })();
                            """.trimIndent()

                            Log.d(TAG, "执行JavaScript嗅探...")
                            onProgressUpdate?.invoke("执行JavaScript分析...")

                            view?.evaluateJavascript(jsCode) { result ->
                                if (result != null && result != "null" && result.length > 10) {
                                    // 清理 URL：移除引号和转义字符
                                    val cleanUrl = result.trim('"').replace("\\/", "/")
                                    
                                    // 检查是否是iframe链接
                                    if (cleanUrl.startsWith("IFRAME:")) {
                                        val iframeUrl = cleanUrl.substring(7)
                                        Log.d(TAG, "✓ JavaScript发现iframe: $iframeUrl")
                                        onProgressUpdate?.invoke("发现iframe，需要进一步解析")
                                        // 这里可以递归嗅探iframe，但为了简单起见，先记录
                                    } else if (isVideoLink(cleanUrl)) {
                                        Log.d(TAG, "✓ JavaScript发现视频链接: $cleanUrl")
                                        if (videoLinks.add(cleanUrl)) {
                                            // 新链接，立即回调并提前结束
                                            onLinkFound?.invoke(cleanUrl)
                                            onProgressUpdate?.invoke("JavaScript发现视频链接")
                                            
                                            // 优化：找到链接后立即返回，不等待超时
                                            handler.removeCallbacks(timeoutRunnable)
                                            handler.post(timeoutRunnable)
                                        }
                                    }
                                }
                            }
                        }

                        override fun onLoadResource(view: WebView?, url: String?) {
                            super.onLoadResource(view, url)
                            url?.let {
                                if (isVideoLink(it)) {
                                    Log.d(TAG, "✓ 资源加载发现视频: $it")
                                    if (videoLinks.add(it)) {
                                        // 新链接，立即回调
                                        lastProgressTime = System.currentTimeMillis() // 更新进展时间
                                        onLinkFound?.invoke(it)
                                        onProgressUpdate?.invoke("发现视频资源 #${videoLinks.size}")
                                    }
                                }
                            }
                        }
                    }
                }

                // 设置超时检查（定期检查是否有进展）
                val timeoutRunnable = Runnable {
                    Log.d(TAG, "=== 嗅探完成 ===")
                    Log.d(TAG, "总共找到 ${videoLinks.size} 个视频链接")
                    onProgressUpdate?.invoke("嗅探完成，找到 ${videoLinks.size} 个链接")

                    if (continuation.isActive) {
                        val sortedLinks = sortVideoLinks(videoLinks.toList())
                        sortedLinks.forEachIndexed { index, link ->
                            Log.d(TAG, "链接 #${index + 1}: $link")
                        }
                        continuation.resume(sortedLinks)
                    }

                    webView?.destroy()
                }

                // 定期检查进展的Runnable
                val progressCheckRunnable = object : Runnable {
                    override fun run() {
                        val timeSinceLastProgress = System.currentTimeMillis() - lastProgressTime
                        
                        if (timeSinceLastProgress > PROGRESS_TIMEOUT) {
                            // 超过3秒无新进展，结束嗅探
                            Log.d(TAG, "超过${PROGRESS_TIMEOUT}ms无新进展，结束嗅探")
                            handler.removeCallbacks(this)
                            handler.post(timeoutRunnable)
                        } else {
                            // 还有进展，继续检查
                            handler.postDelayed(this, 1000) // 每秒检查一次
                        }
                    }
                }

                handler.postDelayed(progressCheckRunnable, 1000) // 1秒后开始检查
                handler.postDelayed(timeoutRunnable, SNIFF_TIMEOUT) // 最大超时时间

                continuation.invokeOnCancellation {
                    Log.d(TAG, "嗅探被取消")
                    handler.removeCallbacks(timeoutRunnable)
                    handler.removeCallbacks(progressCheckRunnable)
                    webView?.destroy()
                }

                // 加载页面
                Log.d(TAG, "开始加载页面...")
                onProgressUpdate?.invoke("正在加载页面...")
                webView.loadUrl(url)

            } catch (e: Exception) {
                Log.e(TAG, "嗅探异常: ${e.message}", e)
                onProgressUpdate?.invoke("嗅探失败: ${e.message}")
                if (continuation.isActive) {
                    continuation.resume(emptyList())
                }
                webView?.destroy()
            }
        }
    }

    /**
     * 判断是否是视频链接
     */
    private fun isVideoLink(url: String): Boolean {
        val lowerUrl = url.lowercase()

        // 先检查黑名单 - 明确排除
        for (pattern in BLACKLIST) {
            if (lowerUrl.contains(pattern)) {
                Log.d(TAG, "✗ 黑名单过滤: $pattern")
                return false
            }
        }
        
        // 排除 TS 分片文件（但保留 .ts 结尾的完整视频文件）
        if (lowerUrl.matches(Regex(".*\\d+\\.ts(\\?.*)?$"))) {
            // 匹配类似 "123.ts" 或 "seg-1-v1-a1.ts" 的分片文件
            Log.d(TAG, "✗ TS 分片文件: $url")
            return false
        }

        // 检查视频扩展名 - 最高优先级
        for (ext in VIDEO_EXTENSIONS) {
            if (lowerUrl.endsWith(".$ext") || lowerUrl.contains(".$ext?")) {
                Log.d(TAG, "✓ 匹配视频扩展名: .$ext")
                return true
            }
        }

        // 检查视频关键词 - 但要求更严格
        var keywordMatches = 0
        for (keyword in VIDEO_KEYWORDS) {
            if (lowerUrl.contains(keyword)) {
                keywordMatches++
            }
        }
        
        // 至少匹配2个关键词才认为是视频链接（避免误判）
        if (keywordMatches >= 2) {
            Log.d(TAG, "✓ 匹配 $keywordMatches 个视频关键词")
            return true
        }
        
        // 单个关键词但URL很短，可能是误判
        if (keywordMatches == 1 && url.length < 50) {
            Log.d(TAG, "✗ 只匹配1个关键词且URL太短，可能不是视频: $url")
            return false
        }

        return false
    }

    /**
     * 对视频链接进行排序，优先级高的在前
     */
    private fun sortVideoLinks(links: List<String>): List<String> {
        if (links.isEmpty()) return links

        return links.sortedByDescending { link ->
            val lowerLink = link.lowercase()
            var score = 0

            // 最高优先级：纯净的 .m3u8 结尾（没有参数）
            if (lowerLink.endsWith(".m3u8")) {
                score += 10000
                Log.d(TAG, "✓✓✓ 最高优先级（纯净.m3u8）: $link, score=$score")
            }
            // 次高优先级：带参数的 .m3u8?xxx
            else if (lowerLink.matches(Regex(".*\\.m3u8\\?.*"))) {
                score += 9500
                Log.d(TAG, "✓✓ 次高优先级（.m3u8带参数）: $link, score=$score")
            }
            
            // 第三优先级：纯净的 .mp4 结尾
            if (lowerLink.endsWith(".mp4")) {
                score += 9000
            }
            // 第四优先级：带参数的 .mp4?xxx
            else if (lowerLink.matches(Regex(".*\\.mp4\\?.*"))) {
                score += 8500
            }

            // 优先级关键词得分（index.m3u8, playlist.m3u8 等）
            for ((index, keyword) in PRIORITY_KEYWORDS.withIndex()) {
                if (lowerLink.contains(keyword)) {
                    score += 1000 - (index * 100)
                }
            }

            // 视频扩展名得分
            for (ext in VIDEO_EXTENSIONS) {
                if (lowerLink.contains(".$ext")) {
                    score += 100
                    break
                }
            }

            // 关键词得分
            for (keyword in VIDEO_KEYWORDS) {
                if (lowerLink.contains(keyword)) {
                    score += 50
                }
            }

            // 排除不良链接
            for (pattern in BLACKLIST) {
                if (lowerLink.contains(pattern)) {
                    score -= 10000
                }
            }
            
            // URL 长度合理性（太短或太长都减分）
            if (link.length < 30) {
                score -= 500
            } else if (link.length > 500) {
                score -= 200
            }

            score
        }.also { sorted ->
            Log.d(TAG, "=== 视频链接排序结果 ===")
            sorted.forEachIndexed { index, link ->
                Log.d(TAG, "${index + 1}. $link")
            }
        }
    }
}
