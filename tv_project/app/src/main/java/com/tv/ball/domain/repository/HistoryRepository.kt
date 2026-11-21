package com.tv.ball.domain.repository

import com.tv.ball.data.local.LocalDataSource
import com.tv.ball.data.model.HistoryEntity
import kotlinx.coroutines.flow.Flow

class HistoryRepository(
    private val localDataSource: LocalDataSource
) {
    
    fun getAllHistory(): Flow<List<HistoryEntity>> {
        return localDataSource.getAllHistory()
    }

    suspend fun getRecentHistory(limit: Int = 10): List<HistoryEntity> {
        return localDataSource.getRecentHistory(limit)
    }
    
    suspend fun getHistoryByTitleAndEpisode(videoTitle: String, currentEpisode: String?, videoSource: String?): HistoryEntity? {
        return localDataSource.getHistoryByTitleAndEpisode(videoTitle, currentEpisode, videoSource)
    }
    
    suspend fun getHistoryByTitle(videoTitle: String): HistoryEntity? {
        return localDataSource.getHistoryByTitle(videoTitle)
    }
    
    suspend fun addOrUpdateHistory(
        videoTitle: String,
        videoUrl: String,
        parsedVideoUrl: String? = null,
        videoHeaders: String? = null,
        thumbnailUrl: String?,
        watchedPosition: Long,
        duration: Long,
        detailUrl: String? = null,
        episodesJson: String? = null,
        currentEpisode: String? = null,
        videoSource: String? = null,
        // 传入原始观看时间用于「撤销删除」这类回填：不传就按当前时间算
        lastWatchedAt: Long? = null
    ): Long {
        val stamp = lastWatchedAt ?: System.currentTimeMillis()
        // 查询是否已存在该剧该集该源的历史记录
        val existingHistory = localDataSource.getHistoryByTitleAndEpisode(videoTitle, currentEpisode, videoSource)
        
        val history = if (existingHistory != null) {
            // 更新现有记录，保留id以便REPLACE策略生效
            existingHistory.copy(
                videoUrl = videoUrl,
                parsedVideoUrl = parsedVideoUrl ?: existingHistory.parsedVideoUrl, // 保留已有的解析链接
                videoHeaders = videoHeaders ?: existingHistory.videoHeaders, // 保留已有的请求头
                thumbnailUrl = thumbnailUrl,
                watchedPosition = watchedPosition,
                duration = duration,
                lastWatchedAt = stamp,
                detailUrl = detailUrl,
                episodesJson = episodesJson,
                currentEpisode = currentEpisode,
                videoSource = videoSource
            )
        } else {
            // 创建新记录
            HistoryEntity(
                videoTitle = videoTitle,
                videoUrl = videoUrl,
                parsedVideoUrl = parsedVideoUrl,
                videoHeaders = videoHeaders,
                thumbnailUrl = thumbnailUrl,
                watchedPosition = watchedPosition,
                duration = duration,
                lastWatchedAt = stamp,
                detailUrl = detailUrl,
                episodesJson = episodesJson,
                currentEpisode = currentEpisode,
                videoSource = videoSource
            )
        }
        
        return localDataSource.insertHistory(history)
    }
    
    suspend fun deleteHistoryByTitle(videoTitle: String) {
        localDataSource.deleteHistoryByTitle(videoTitle)
    }
    
    suspend fun clearHistory() {
        localDataSource.deleteAllHistory()
    }
}
