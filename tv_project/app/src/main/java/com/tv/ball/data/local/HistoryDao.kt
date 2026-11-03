package com.tv.ball.data.local

import androidx.room.*
import com.tv.ball.data.model.HistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    // 只显示每部剧的最新观看记录（用于历史列表）
    @Query("""
        SELECT * FROM history
        WHERE id IN (
            SELECT MAX(id) FROM history GROUP BY videoTitle
        )
        ORDER BY lastWatchedAt DESC
    """)
    fun getAllHistory(): Flow<List<HistoryEntity>>

    // 获取最近播放记录（用于继续播放功能）
    @Query("SELECT * FROM history ORDER BY lastWatchedAt DESC LIMIT :limit")
    suspend fun getRecentHistory(limit: Int): List<HistoryEntity>

    // 查询特定剧集的播放记录（用于恢复播放进度）
    // 优先查询指定源的记录，如果没有则查询任意源的记录
    @Query("""
        SELECT * FROM history 
        WHERE videoTitle = :videoTitle 
        AND currentEpisode = :currentEpisode 
        AND (videoSource = :videoSource OR :videoSource IS NULL)
        ORDER BY CASE WHEN videoSource = :videoSource THEN 0 ELSE 1 END, lastWatchedAt DESC
        LIMIT 1
    """)
    suspend fun getHistoryByTitleAndEpisode(videoTitle: String, currentEpisode: String?, videoSource: String?): HistoryEntity?
    
    // 查询某部剧的最新观看记录
    @Query("SELECT * FROM history WHERE videoTitle = :videoTitle ORDER BY lastWatchedAt DESC LIMIT 1")
    suspend fun getHistoryByTitle(videoTitle: String): HistoryEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: HistoryEntity): Long
    
    @Update
    suspend fun updateHistory(history: HistoryEntity)
    
    @Delete
    suspend fun deleteHistory(history: HistoryEntity)
    
    @Query("DELETE FROM history WHERE videoTitle = :videoTitle")
    suspend fun deleteHistoryByTitle(videoTitle: String)
    
    @Query("DELETE FROM history")
    suspend fun deleteAllHistory()
    
    @Query("DELETE FROM history WHERE lastWatchedAt < :timestamp")
    suspend fun deleteOldHistory(timestamp: Long)
}
