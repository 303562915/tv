package com.tv.ball.domain.model

sealed class ParseResult {
    data class Success(val videoUrl: VideoUrl) : ParseResult()
    data class Error(val message: String, val exception: Throwable? = null) : ParseResult()
}

data class VideoUrl(
    val url: String,
    val quality: String = "default",
    val headers: Map<String, String> = emptyMap()
)
