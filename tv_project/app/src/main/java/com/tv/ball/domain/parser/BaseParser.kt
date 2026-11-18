package com.tv.ball.domain.parser

import com.tv.ball.data.remote.NetworkDataSource
import com.tv.ball.data.remote.NetworkResult
import com.tv.ball.domain.model.ParseResult
import com.tv.ball.domain.model.VideoUrl
import com.tv.ball.utils.constants.NetworkConstants

abstract class BaseParser(
    protected val networkDataSource: NetworkDataSource
) : VideoSourceParser {
    
    protected suspend fun fetchHtml(
        url: String,
        headers: Map<String, String> = getDefaultHeaders()
    ): String? {
        return when (val result = networkDataSource.get(url, headers)) {
            is NetworkResult.Success -> result.data
            is NetworkResult.Error -> null
        }
    }
    
    protected fun getDefaultHeaders(): Map<String, String> {
        return mapOf(
            NetworkConstants.HEADER_USER_AGENT to NetworkConstants.DEFAULT_USER_AGENT
        )
    }
    
    protected fun extractIframeUrl(html: String): String? {
        val iframeRegex = """<iframe[^>]+src=["']([^"']+)["']""".toRegex()
        return iframeRegex.find(html)?.groupValues?.get(1)
    }
    
    protected fun extractVideoUrl(html: String, pattern: Regex): String? {
        return pattern.find(html)?.groupValues?.get(1)
    }
    
    protected fun createSuccessResult(
        url: String,
        quality: String = "default",
        headers: Map<String, String> = emptyMap()
    ): ParseResult {
        return ParseResult.Success(VideoUrl(url, quality, headers))
    }
    
    protected fun createErrorResult(message: String, exception: Throwable? = null): ParseResult {
        return ParseResult.Error(message, exception)
    }
}
