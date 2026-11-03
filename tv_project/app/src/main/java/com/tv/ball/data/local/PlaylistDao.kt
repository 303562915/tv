package com.tv.ball.data.local

import androidx.room.*
import com.tv.ball.data.model.PlaylistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlist ORDER BY position ASC")
    fun getAllPlaylistItems(): Flow<List<PlaylistEntity>>
    
    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun getPlaylistItemById(id: Long): PlaylistEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistItem(item: PlaylistEntity): Long
    
    @Update
    suspend fun updatePlaylistItem(item: PlaylistEntity)
    
    @Delete
    suspend fun deletePlaylistItem(item: PlaylistEntity)
    
    @Query("DELETE FROM playlist")
    suspend fun deleteAllPlaylistItems()
    
    @Query("UPDATE playlist SET position = :newPosition WHERE id = :id")
    suspend fun updatePosition(id: Long, newPosition: Int)
}
