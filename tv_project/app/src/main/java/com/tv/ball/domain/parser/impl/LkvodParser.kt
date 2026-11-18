package com.tv.ball.domain.parser.impl

import com.tv.ball.data.remote.NetworkDataSource
import com.tv.ball.domain.model.ParseResult
import com.tv.ball.domain.parser.BaseParser


class LkvodParser constructor(
    networkDataSource: NetworkDataSource
) : BaseParser(networkDataSource) {
    
    override val parserName: String = "LkvodParser"
    
    override fun canParse(url: String): Boolean {
        return url.contains("lkvod", ignoreCase = true)
    }
    
    override suspend fun parse(url: String): ParseResult {
        val html = fetchHtml(url) ?: return createErrorResult("Failed to fetch page")
        
        val videoUrlPattern = """url["\s:=]+["']?([^"'\s]+)""".toRegex()
        val videoUrl = extractVideoUrl(html, videoUrlPattern)
            ?: return createErrorResult("No video URL found")
        
        return createSuccessResult(videoUrl)
    }
}
