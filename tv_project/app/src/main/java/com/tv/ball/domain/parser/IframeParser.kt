package com.tv.ball.domain.parser

import com.tv.ball.data.remote.NetworkDataSource
import com.tv.ball.domain.model.ParseResult


class IframeParser constructor(
    networkDataSource: NetworkDataSource,
    private val parserChain: ParserChain
) : BaseParser(networkDataSource) {
    
    override val parserName: String = "IframeParser"
    
    override fun canParse(url: String): Boolean {
        return url.contains("iframe", ignoreCase = true) || 
               url.contains("embed", ignoreCase = true)
    }
    
    override suspend fun parse(url: String): ParseResult {
        val html = fetchHtml(url) ?: return createErrorResult("Failed to fetch iframe page")
        
        val iframeUrl = extractIframeUrl(html) 
            ?: return createErrorResult("No iframe found in page")
        
        val absoluteIframeUrl = if (iframeUrl.startsWith("http")) {
            iframeUrl
        } else if (iframeUrl.startsWith("//")) {
            "https:$iframeUrl"
        } else {
            val baseUrl = url.substringBefore("?").substringBeforeLast("/")
            "$baseUrl/$iframeUrl"
        }
        
        return parserChain.parse(absoluteIframeUrl)
    }
}
