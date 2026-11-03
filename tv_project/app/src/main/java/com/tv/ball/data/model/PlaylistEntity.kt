package com.tv.ball.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val videoId: Long,
    val videoTitle: String,
    val videoUrl: String,
    val thumbnailUrl: String?,
    val position: Int = 0,
    val addedAt: Long = System.currentTimeMillis()
)
