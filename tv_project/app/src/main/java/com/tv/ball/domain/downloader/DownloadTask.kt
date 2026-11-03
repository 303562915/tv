package com.tv.ball.domain.downloader

data class DownloadTask(
    val id: Long = 0,
    val url: String,
    val title: String,
    val fileName: String,
    val savePath: String,
    val totalSize: Long = 0,
    val downloadedSize: Long = 0,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val progress: Int = 0,
    val speed: Long = 0, // bytes per second
    val createTime: Long = System.currentTimeMillis(),
    val updateTime: Long = System.currentTimeMillis(),
    val isM3u8: Boolean = false,
    val m3u8Segments: List<String> = emptyList(),
    val errorMessage: String? = null,
    val customHeaders: Map<String, String> = emptyMap(), // 自定义请求头
    val threadCount: Int = 0, // 多线程下载的线程数
    val partProgresses: List<Int> = emptyList() // 每个线程的下载进度 (0-100)
)

enum class DownloadStatus {
    PENDING,      // 等待下载
    DOWNLOADING,  // 下载中
    PAUSED,       // 已暂停
    COMPLETED,    // 已完成
    FAILED,       // 下载失败
    MERGING       // M3U8 合并中
}


// 扩展函数：DownloadTask 转 Entity
fun DownloadTask.toEntity(): com.tv.ball.data.model.DownloadTaskEntity {
    return com.tv.ball.data.model.DownloadTaskEntity(
        id = id,
        url = url,
        title = title,
        fileName = fileName,
        savePath = savePath,
        totalSize = totalSize,
        downloadedSize = downloadedSize,
        status = status.name,
        progress = progress,
        createTime = createTime,
        updateTime = updateTime,
        isM3u8 = isM3u8,
        m3u8SegmentsJson = com.google.gson.Gson().toJson(m3u8Segments),
        errorMessage = errorMessage,
        customHeadersJson = com.google.gson.Gson().toJson(customHeaders),
        threadCount = threadCount,
        partProgressesJson = com.google.gson.Gson().toJson(partProgresses)
    )
}

// 扩展函数：Entity 转 DownloadTask
fun com.tv.ball.data.model.DownloadTaskEntity.toDownloadTask(): DownloadTask {
    val segments = try {
        com.google.gson.Gson().fromJson(m3u8SegmentsJson, Array<String>::class.java).toList()
    } catch (e: Exception) {
        emptyList()
    }
    
    val headers = try {
        com.google.gson.Gson().fromJson(customHeadersJson, Map::class.java) as? Map<String, String> ?: emptyMap()
    } catch (e: Exception) {
        emptyMap()
    }
    
    val progresses = try {
        com.google.gson.Gson().fromJson(partProgressesJson ?: "[]", Array<Int>::class.java).toList()
    } catch (e: Exception) {
        emptyList()
    }
    
    return DownloadTask(
        id = id,
        url = url,
        title = title,
        fileName = fileName,
        savePath = savePath,
        totalSize = totalSize,
        downloadedSize = downloadedSize,
        status = try { DownloadStatus.valueOf(status) } catch (e: Exception) { DownloadStatus.PENDING },
        progress = progress,
        speed = 0, // 速度不持久化，重启后重新计算
        createTime = createTime,
        updateTime = updateTime,
        isM3u8 = isM3u8,
        m3u8Segments = segments,
        errorMessage = errorMessage,
        customHeaders = headers,
        threadCount = threadCount ?: 0,
        partProgresses = progresses
    )
}
