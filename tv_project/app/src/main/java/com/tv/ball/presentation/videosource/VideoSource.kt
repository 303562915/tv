package com.tv.ball.presentation.videosource

data class VideoSource(
    val name: String,
    val iconUrl: String?,
    val sourceKey: String,
    val responseTime: Int? = null, // 响应时间（毫秒）
    val isSelected: Boolean = false
)
