package com.tv.ball.domain.parser.impl

import com.tv.ball.data.remote.NetworkDataSource
import com.tv.ball.domain.model.ParseResult
import com.tv.ball.domain.parser.BaseParser


class GenericParser constructor(
    networkDataSource: NetworkDataSource
) : BaseParser(networkDataSource) {
    
    override val parserName: String = "GenericParser"
    
    override fun canParse(url: String): Boolean {
        return true
    }
    
    override suspend fun parse(url: String): ParseResult {
        val html = fetchHtml(url) ?: return createErrorResult("Failed to fetch page")
        
        val patterns = listOf(
            """"url":"([^"]+\.m3u8[^"]*?)"""".toRegex(),
            """"src":"([^"]+\.mp4[^"]*?)"""".toRegex(),
            """src=["']([^"']+\.m3u8[^"']*)["']""".toRegex(),
            """src=["']([^"']+\.mp4[^"']*)["']""".toRegex(),
            """https?://[^"'\s]+\.m3u8[^"'\s]*""".toRegex(),
            """https?://[^"'\s]+\.mp4[^"'\s]*""".toRegex()
        )
        
        for (pattern in patterns) {
            val videoUrl = extractVideoUrl(html, pattern)
            if (videoUrl != null) {
                val decodedUrl = videoUrl.replace("\\u002F", "/").replace("\\/", "/")
                val quality = if (decodedUrl.contains(".m3u8")) "hls" else "mp4"
                return createSuccessResult(decodedUrl, quality)
            }
        }
        
        return createErrorResult("No video URL found in page")
    }
}
