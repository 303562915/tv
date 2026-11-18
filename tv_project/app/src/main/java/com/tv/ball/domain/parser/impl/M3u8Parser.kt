package com.tv.ball.domain.parser.impl

import com.tv.ball.data.remote.NetworkDataSource
import com.tv.ball.domain.model.ParseResult
import com.tv.ball.domain.parser.BaseParser


class M3u8Parser constructor(
    networkDataSource: NetworkDataSource
) : BaseParser(networkDataSource) {
    
    override val parserName: String = "M3u8Parser"
    
    override fun canParse(url: String): Boolean {
        return url.contains(".m3u8", ignoreCase = true)
    }
    
    override suspend fun parse(url: String): ParseResult {
        return createSuccessResult(url, "hls")
    }
}
