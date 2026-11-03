package com.tv.ball.domain.repository

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地视频播放进度管理器
 * 用于保存和恢复本地视频的播放位置，不混入历史播放列表
 */
class LocalVideoProgressManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "local_video_progress", 
        Context.MODE_PRIVATE
    )
    
    /**
     * 保存本地视频的播放进度
     * @param videoPath 视频文件的完整路径
     * @param position 播放位置（毫秒）
     * @param duration 视频总时长（毫秒）
     */
    fun saveProgress(videoPath: String, position: Long, duration: Long) {
        if (position <= 0 || duration <= 0) return
        
        // 如果播放进度超过95%，认为已看完，清除进度
        if (position >= duration * 0.95) {
            clearProgress(videoPath)
            return
        }
        
        prefs.edit().apply {
            putLong(getPositionKey(videoPath), position)
            putLong(getDurationKey(videoPath), duration)
            putLong(getTimestampKey(videoPath), System.currentTimeMillis())
            apply()
        }
    }
    
    /**
     * 获取本地视频的播放进度
     * @param videoPath 视频文件的完整路径
     * @return 播放位置（毫秒），如果没有记录则返回0
     */
    fun getProgress(videoPath: String): Long {
        return prefs.getLong(getPositionKey(videoPath), 0L)
    }
    
    /**
     * 获取本地视频的总时长
     * @param videoPath 视频文件的完整路径
     * @return 视频总时长（毫秒），如果没有记录则返回0
     */
    fun getDuration(videoPath: String): Long {
        return prefs.getLong(getDurationKey(videoPath), 0L)
    }
    
    /**
     * 清除本地视频的播放进度
     * @param videoPath 视频文件的完整路径
     */
    fun clearProgress(videoPath: String) {
        prefs.edit().apply {
            remove(getPositionKey(videoPath))
            remove(getDurationKey(videoPath))
            remove(getTimestampKey(videoPath))
            apply()
        }
    }
    
    /**
     * 清除所有本地视频的播放进度
     */
    fun clearAllProgress() {
        prefs.edit().clear().apply()
    }
    
    /**
     * 清除超过30天未观看的播放进度
     */
    fun clearOldProgress() {
        val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        val allKeys = prefs.all.keys
        
        prefs.edit().apply {
            allKeys.filter { it.endsWith("_timestamp") }.forEach { timestampKey ->
                val timestamp = prefs.getLong(timestampKey, 0L)
                if (timestamp < thirtyDaysAgo) {
                    val videoPath = timestampKey.removeSuffix("_timestamp")
                    remove(getPositionKey(videoPath))
                    remove(getDurationKey(videoPath))
                    remove(timestampKey)
                }
            }
            apply()
        }
    }
    
    private fun getPositionKey(videoPath: String): String {
        return "${videoPath.hashCode()}_position"
    }
    
    private fun getDurationKey(videoPath: String): String {
        return "${videoPath.hashCode()}_duration"
    }
    
    private fun getTimestampKey(videoPath: String): String {
        return "${videoPath.hashCode()}_timestamp"
    }
}
