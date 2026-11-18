package com.tv.ball.domain.model

/**
 * 播放历史条目（已扁平化，不再依赖影视聚合时代的 VideoItem 中间层）
 */
data class HistoryItem(
    val title: String,
    val coverUrl: String,
    val videoUrl: String,
    val detailUrl: String? = null,
    val episodesJson: String? = null,
    val currentEpisode: String? = null,
    val watchedPosition: Long = 0L,
    val duration: Long = 0L,
    val parsedVideoUrl: String? = null,
    val videoHeaders: String? = null,
    val lastWatchedAt: Long = System.currentTimeMillis()
)

data class HistoryGroup(
    val title: String,
    val items: List<HistoryItem>
)
