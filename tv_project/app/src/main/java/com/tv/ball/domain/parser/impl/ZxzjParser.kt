package com.tv.ball.domain.parser.impl

import android.util.Log
import com.tv.ball.data.remote.NetworkDataSource
import com.tv.ball.domain.model.ParseResult
import com.tv.ball.domain.parser.BaseParser

/**
 * 在线之家(zxzjhd.com)视频解析器
 * 
 * 特点：
 * 1. 播放页面URL格式：/video/{id}-{source}-{episode}.html
 * 2. 可能使用iframe嵌套播放器
 * 3. 视频资源可能来自第三方CDN
 */
class ZxzjParser(
    networkDataSource: NetworkDataSource
) : BaseParser(networkDataSource) {
    
    companion object {
        private const val TAG = "ZxzjParser"
    }
    
    override val parserName: String = "ZxzjParser"
    
    override fun canParse(url: String): Boolean {
        return url.contains("zxzjhd.com", ignoreCase = true)
    }
    
    override suspend fun parse(url: String): ParseResult {
        Log.d(TAG, "开始解析在线之家播放页面: $url")
        
        val html = fetchHtml(url) ?: return createErrorResult("无法获取播放页面")
        
        Log.d(TAG, "HTML长度: ${html.length}")
        
        // 1. 尝试提取iframe URL
        val iframeUrl = extractIframeUrlInternal(html)
        if (iframeUrl != null) {
            Log.d(TAG, "找到iframe: $iframeUrl")
            
            // 解析iframe内容
            val iframeHtml = fetchHtml(iframeUrl)
            if (iframeHtml != null) {
                Log.d(TAG, "Iframe HTML长度: ${iframeHtml.length}")
                
                // 从iframe中提取视频URL
                val videoUrl = extractVideoUrlFromIframe(iframeHtml)
                if (videoUrl != null) {
                    Log.d(TAG, "从iframe中找到视频URL: $videoUrl")
                    val quality = if (videoUrl.contains(".m3u8")) "hls" else "mp4"
                    return createSuccessResult(videoUrl, quality)
                }
            }
        }
        
        // 2. 尝试直接从页面提取视频URL
        val videoUrl = extractVideoUrlFromPage(html)
        if (videoUrl != null) {
            Log.d(TAG, "从页面直接找到视频URL: $videoUrl")
            val quality = if (videoUrl.contains(".m3u8")) "hls" else "mp4"
            return createSuccessResult(videoUrl, quality)
        }
        
        // 3. 尝试提取播放器配置
        val playerConfig = extractPlayerConfig(html)
        if (playerConfig != null) {
            Log.d(TAG, "找到播放器配置: $playerConfig")
            val quality = if (playerConfig.contains(".m3u8")) "hls" else "mp4"
            return createSuccessResult(playerConfig, quality)
        }
        
        Log.e(TAG, "所有解析方法都失败了")
        Log.d(TAG, "HTML示例: ${html.take(1000)}")
        
        return createErrorResult("未找到视频资源")
    }
    
    /**
     * 提取iframe URL
     */
    private fun extractIframeUrlInternal(html: String): String? {
        val patterns = listOf(
            """<iframe[^>]*src=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE),
            """<iframe[^>]*data-src=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE),
            """player_aaaa=\{[^}]*"url":"([^"]+)"""".toRegex(),
            """player_data=\{[^}]*"url":"([^"]+)"""".toRegex()
        )
        
        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null) {
                var url = match.groupValues[1]
                
                // 处理相对URL
                url = when {
                    url.startsWith("http") -> url
                    url.startsWith("//") -> "https:$url"
                    url.startsWith("/") -> "https://www.zxzjhd.com$url"
                    else -> "https://www.zxzjhd.com/$url"
                }
                
                return url
            }
        }
        
        return null
    }
    
    /**
     * 从iframe内容中提取视频URL
     */
    private fun extractVideoUrlFromIframe(html: String): String? {
        val patterns = listOf(
            // M3U8链接
            """"url":"([^"]+\.m3u8[^"]*?)"""".toRegex(),
            """url:\s*["']([^"']+\.m3u8[^"']*)["']""".toRegex(),
            """https?://[^"'\s]+\.m3u8[^"'\s]*""".toRegex(),
            
            // MP4链接
            """"url":"([^"]+\.mp4[^"]*?)"""".toRegex(),
            """url:\s*["']([^"']+\.mp4[^"']*)["']""".toRegex(),
            """https?://[^"'\s]+\.mp4[^"'\s]*""".toRegex(),
            
            // 通用视频链接
            """src=["']([^"']+\.m3u8[^"']*)["']""".toRegex(),
            """src=["']([^"']+\.mp4[^"']*)["']""".toRegex(),
            
            // 播放器配置
            """player\s*=\s*\{[^}]*url:\s*["']([^"']+)["']""".toRegex(),
            """var\s+url\s*=\s*["']([^"']+\.m3u8[^"']*)["']""".toRegex(),
            """var\s+url\s*=\s*["']([^"']+\.mp4[^"']*)["']""".toRegex()
        )
        
        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null) {
                var url = match.groupValues[1]
                
                // 解码URL
                url = url.replace("\\u002F", "/")
                    .replace("\\/", "/")
                    .replace("\\", "")
                
                // 确保是完整URL
                if (!url.startsWith("http")) {
                    if (url.startsWith("//")) {
                        url = "https:$url"
                    }
                }
                
                // 验证是否是有效的视频URL
                if (url.contains(".m3u8") || url.contains(".mp4")) {
                    return url
                }
            }
        }
        
        return null
    }
    
    /**
     * 从页面直接提取视频URL
     */
    private fun extractVideoUrlFromPage(html: String): String? {
        return extractVideoUrlFromIframe(html)
    }
    
    /**
     * 提取播放器配置
     */
    private fun extractPlayerConfig(html: String): String? {
        // 尝试提取MacPlayer配置
        val macPlayerPattern = """MacPlayer\s*\(\s*\{[^}]*url:\s*["']([^"']+)["']""".toRegex()
        val macMatch = macPlayerPattern.find(html)
        if (macMatch != null) {
            var url = macMatch.groupValues[1]
            url = url.replace("\\u002F", "/").replace("\\/", "/")
            return url
        }
        
        // 尝试提取DPlayer配置
        val dplayerPattern = """new\s+DPlayer\s*\(\s*\{[^}]*url:\s*["']([^"']+)["']""".toRegex()
        val dplayerMatch = dplayerPattern.find(html)
        if (dplayerMatch != null) {
            var url = dplayerMatch.groupValues[1]
            url = url.replace("\\u002F", "/").replace("\\/", "/")
            return url
        }
        
        return null
    }
}
