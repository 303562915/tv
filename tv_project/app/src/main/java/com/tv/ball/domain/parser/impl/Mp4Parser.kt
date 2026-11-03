package com.tv.ball.domain.parser.impl

import com.tv.ball.data.remote.NetworkDataSource
import com.tv.ball.domain.model.ParseResult
import com.tv.ball.domain.parser.BaseParser


class Mp4Parser constructor(
    networkDataSource: NetworkDataSource
) : BaseParser(networkDataSource) {
    
    override val parserName: String = "Mp4Parser"
    
    override fun canParse(url: String): Boolean {
        return url.contains(".mp4", ignoreCase = true)
    }
    
    override suspend fun parse(url: String): ParseResult {
        return createSuccessResult(url, "mp4")
    }
}
