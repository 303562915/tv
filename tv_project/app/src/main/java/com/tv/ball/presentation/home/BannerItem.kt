package com.tv.ball.presentation.home

data class BannerItem(
    val id: Int = 0,
    val imageUrl: String,
    val linkUrl: String,
    val description: String,
    val visible: Boolean = true
)
