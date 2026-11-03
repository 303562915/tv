package com.tv.ball.domain.repository

import com.tv.ball.data.local.LocalDataSource
import com.tv.ball.data.model.PlaylistEntity
import kotlinx.coroutines.flow.Flow




class PlaylistRepository constructor(
    private val localDataSource: LocalDataSource
) {
    
    fun getAllPlaylistItems(): Flow<List<PlaylistEntity>> {
        return localDataSource.getAllPlaylistItems()
    }
    
    suspend fun addToPlaylist(
        videoId: Long,
        videoTitle: String,
        videoUrl: String,
        thumbnailUrl: String?
    ): Long {
        val item = PlaylistEntity(
            videoId = videoId,
            videoTitle = videoTitle,
            videoUrl = videoUrl,
            thumbnailUrl = thumbnailUrl
        )
        return localDataSource.insertPlaylistItem(item)
    }
    
    suspend fun removeFromPlaylist(item: PlaylistEntity) {
        localDataSource.deletePlaylistItem(item)
    }
    
    suspend fun clearPlaylist() {
        localDataSource.deleteAllPlaylistItems()
    }
}
