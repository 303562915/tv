package com.tv.ball.domain.parser

import com.tv.ball.domain.model.ParseResult

interface VideoSourceParser {
    suspend fun parse(url: String): ParseResult
    fun canParse(url: String): Boolean
    val parserName: String
}
