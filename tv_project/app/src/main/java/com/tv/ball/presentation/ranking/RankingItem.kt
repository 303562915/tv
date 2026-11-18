package com.tv.ball.presentation.ranking

data class RankingItem(
    val rank: Int,
    val title: String,
    val platform: String,
    val onlineDays: String,
    val heat: String,
    val playCount: String = ""
)
