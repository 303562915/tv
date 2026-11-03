package com.tv.ball.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "videos")
data class VideoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val url: String,
    val thumbnailUrl: String?,
    val description: String?,
    val duration: Long = 0,
    val sourceType: String,
    val createdAt: Long = System.currentTimeMillis()
)
