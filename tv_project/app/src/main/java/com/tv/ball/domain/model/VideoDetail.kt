package com.tv.ball.domain.model

data class VideoDetail(
    val title: String,
    val description: String,
    val coverUrl: String,
    val episodes: List<Episode>,
    val year: String = "",
    val area: String = "",
    val type: String = "",
    val director: String = "",
    val actor: String = ""
)

data class Episode(
    val name: String,
    val url: String,
    val isCloudStorage: Boolean = false,  // 是否是网盘资源
    val cloudType: String = "",  // 网盘类型：quark(夸克)、aliyun(阿里云)、baidu(百度网盘)等
    val durationMinutes: Int = 0  // 视频时长（分钟），用于区分电影(≥60)和普通影视(<60)
)
