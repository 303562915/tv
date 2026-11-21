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
    val addedAt: Long = System.currentTimeMillis(),
    /**
     * 用户自定义分类名（自由文本，没有独立分类表）。
     * null 或空串都表示「未分类」；分类列表由 `SELECT DISTINCT category` 反推，
     * 所以「只建了名字、里面没有收藏」的分类不会存在。
     */
    val category: String? = null
)
