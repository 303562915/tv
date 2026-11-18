package com.tv.ball.domain.parser.impl

import com.tv.ball.data.remote.NetworkDataSource
import com.tv.ball.domain.model.ParseResult
import com.tv.ball.domain.parser.BaseParser


class IqiyiParser constructor(
    networkDataSource: NetworkDataSource
) : BaseParser(networkDataSource) {
    
    override val parserName: String = "IqiyiParser"
    
    override fun canParse(url: String): Boolean {
        return url.contains("iqiyi.com", ignoreCase = true) ||
               url.contains("qiyi.com", ignoreCase = true)
    }
    
    override suspend fun parse(url: String): ParseResult {
        android.util.Log.d("IqiyiParser", "开始解析爱奇艺URL: $url")
        
        // 添加爱奇艺专用请求头
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
            "Referer" to "https://www.iqiyi.com/",
            "Origin" to "https://www.iqiyi.com"
        )
        
        val html = fetchHtml(url, headers)
        if (html == null) {
            android.util.Log.e("IqiyiParser", "获取页面失败")
            return createErrorResult("Failed to fetch page")
        }
        
        android.util.Log.d("IqiyiParser", "页面获取成功，长度: ${html.length}")
        
        val videoUrlPattern = """"url":"([^"]+\.m3u8[^"]*?)"""".toRegex()
        val videoUrl = extractVideoUrl(html, videoUrlPattern)
        
        if (videoUrl == null) {
            android.util.Log.e("IqiyiParser", "未找到m3u8链接")
            // 尝试打印部分HTML内容用于调试
            android.util.Log.d("IqiyiParser", "HTML片段: ${html.take(500)}")
            return createErrorResult("No video URL found")
        }
        
        val decodedUrl = videoUrl.replace("\\u002F", "/").replace("\\/", "/")
        android.util.Log.d("IqiyiParser", "解析成功，m3u8链接: $decodedUrl")
        
        // 返回结果时带上必要的请求头
        return createSuccessResult(decodedUrl, "hls", headers)
    }
}
