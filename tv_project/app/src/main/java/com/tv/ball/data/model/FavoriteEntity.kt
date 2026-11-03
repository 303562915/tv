package com.tv.ball.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val videoId: Long,
    val title: String,
    val thumbnailUrl: String?,
    val detailUrl: String?,
    val episodesJson: String?,
    val parsedVideoUrl: String? = null,  // 保存解析后的视频链接
    val videoHeaders: String? = null,  // 保存请求头（JSON格式）
    val addedAt: Long = System.currentTimeMillis()
)
