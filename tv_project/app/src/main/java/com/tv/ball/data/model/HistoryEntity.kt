package com.tv.ball.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "history",
    indices = [Index(value = ["videoTitle", "currentEpisode", "videoSource"], unique = true)]
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val videoTitle: String,  // 视频标题
    val videoUrl: String,  // 原始播放页URL（用于重新解析）
    val parsedVideoUrl: String? = null,  // 解析后的真实视频链接（直接播放）
    val videoHeaders: String? = null,  // 保存请求头（JSON格式）
    val thumbnailUrl: String?,  // 封面图片
    val watchedPosition: Long = 0,  // 播放位置
    val duration: Long = 0,  // 视频总时长
    val lastWatchedAt: Long = System.currentTimeMillis(),  // 最后观看时间
    val detailUrl: String? = null,  // 详情页URL
    val episodesJson: String? = null,  // 播放列表JSON
    val currentEpisode: String? = null,  // 当前集数名称，如"第1集"（用于区分不同集数）
    val videoSource: String? = null  // 视频源标识（如"iqiyi", "hongniu"等），用于区分不同源的播放记录
)
