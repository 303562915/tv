package com.tv.ball.domain.parser

import com.tv.ball.domain.model.ParseResult




class ParserChain constructor() {
    
    private val parsers = mutableListOf<VideoSourceParser>()
    
    fun registerParser(parser: VideoSourceParser) {
        parsers.add(parser)
    }
    
    fun registerParsers(vararg parsers: VideoSourceParser) {
        this.parsers.addAll(parsers)
    }
    
    suspend fun parse(url: String): ParseResult {
        val parser = parsers.firstOrNull { it.canParse(url) }
        
        return if (parser != null) {
            try {
                parser.parse(url)
            } catch (e: Exception) {
                ParseResult.Error("Parser ${parser.parserName} failed: ${e.message}", e)
            }
        } else {
            ParseResult.Error("No parser found for URL: $url")
        }
    }
    
    suspend fun parseWithFallback(url: String, fallbackParsers: List<VideoSourceParser>): ParseResult {
        val result = parse(url)
        
        if (result is ParseResult.Success) {
            return result
        }
        
        for (fallbackParser in fallbackParsers) {
            if (fallbackParser.canParse(url)) {
                try {
                    val fallbackResult = fallbackParser.parse(url)
                    if (fallbackResult is ParseResult.Success) {
                        return fallbackResult
                    }
                } catch (e: Exception) {
                    continue
                }
            }
        }
        
        return result
    }
}
