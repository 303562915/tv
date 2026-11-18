package com.tv.ball.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "download_tasks")
data class DownloadTaskEntity(
    @PrimaryKey
    val id: Long,
    val url: String,
    val title: String,
    val fileName: String,
    val savePath: String,
    val totalSize: Long = 0,
    val downloadedSize: Long = 0,
    val status: String = "PENDING", // PENDING, DOWNLOADING, PAUSED, COMPLETED, FAILED, MERGING
    val progress: Int = 0,
    val createTime: Long = System.currentTimeMillis(),
    val updateTime: Long = System.currentTimeMillis(),
    val isM3u8: Boolean = false,
    val m3u8SegmentsJson: String = "[]", // JSON array of segment URLs
    val errorMessage: String? = null,
    val customHeadersJson: String = "{}", // JSON object of custom headers
    val threadCount: Int? = 0, // 多线程下载的线程数
    val partProgressesJson: String? = "[]" // JSON array of part progresses
)
