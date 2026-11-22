package com.tv.ball.presentation.home

data class BannerItem(
    val id: Int = 0,
    val imageUrl: String,
    val linkUrl: String,
    val description: String,
    /** 详情页地址：首页轮播点击时走和列表项一致的详情跳转，为空则不响应点击 */
    val detailUrl: String = "",
    val visible: Boolean = true
)
