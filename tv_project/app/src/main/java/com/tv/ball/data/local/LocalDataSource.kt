package com.tv.ball.data.local

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalDataSource(
    private val context: Context,
    private val historyDao: HistoryDao,
    private val playlistDao: PlaylistDao
) {
    
    private val sharedPreferences: SharedPreferences by lazy {
        context.getSharedPreferences("video_player_prefs", Context.MODE_PRIVATE)
    }
    
    // History operations
    fun getAllHistory() = historyDao.getAllHistory()

    suspend fun getRecentHistory(limit: Int) = withContext(Dispatchers.IO) {
        historyDao.getRecentHistory(limit)
    }
    
    suspend fun getHistoryByTitleAndEpisode(videoTitle: String, currentEpisode: String?, videoSource: String?) = withContext(Dispatchers.IO) {
        historyDao.getHistoryByTitleAndEpisode(videoTitle, currentEpisode, videoSource)
    }
    
    suspend fun getHistoryByTitle(videoTitle: String) = withContext(Dispatchers.IO) {
        historyDao.getHistoryByTitle(videoTitle)
    }
    
    suspend fun insertHistory(history: com.tv.ball.data.model.HistoryEntity) = withContext(Dispatchers.IO) {
        historyDao.insertHistory(history)
    }
    
    suspend fun deleteHistoryByTitle(videoTitle: String) = withContext(Dispatchers.IO) {
        historyDao.deleteHistoryByTitle(videoTitle)
    }
    
    suspend fun deleteAllHistory() = withContext(Dispatchers.IO) {
        historyDao.deleteAllHistory()
    }
    
    // Playlist operations
    fun getAllPlaylistItems() = playlistDao.getAllPlaylistItems()
    
    suspend fun insertPlaylistItem(item: com.tv.ball.data.model.PlaylistEntity) = withContext(Dispatchers.IO) {
        playlistDao.insertPlaylistItem(item)
    }
    
    suspend fun deletePlaylistItem(item: com.tv.ball.data.model.PlaylistEntity) = withContext(Dispatchers.IO) {
        playlistDao.deletePlaylistItem(item)
    }
    
    suspend fun deleteAllPlaylistItems() = withContext(Dispatchers.IO) {
        playlistDao.deleteAllPlaylistItems()
    }
    
    // SharedPreferences operations
    fun getString(key: String, defaultValue: String = ""): String {
        return sharedPreferences.getString(key, defaultValue) ?: defaultValue
    }
    
    fun putString(key: String, value: String) {
        sharedPreferences.edit().putString(key, value).apply()
    }
    
    fun getInt(key: String, defaultValue: Int = 0): Int {
        return sharedPreferences.getInt(key, defaultValue)
    }
    
    fun putInt(key: String, value: Int) {
        sharedPreferences.edit().putInt(key, value).apply()
    }
    
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return sharedPreferences.getBoolean(key, defaultValue)
    }
    
    fun putBoolean(key: String, value: Boolean) {
        sharedPreferences.edit().putBoolean(key, value).apply()
    }
    
    fun getLong(key: String, defaultValue: Long = 0L): Long {
        return sharedPreferences.getLong(key, defaultValue)
    }
    
    fun putLong(key: String, value: Long) {
        sharedPreferences.edit().putLong(key, value).apply()
    }
    
    fun clear() {
        sharedPreferences.edit().clear().apply()
    }
}
