package com.tv.ball.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.tv.ball.data.model.FavoriteEntity
import com.tv.ball.data.model.HistoryEntity
import com.tv.ball.data.model.PlaylistEntity

@Database(
    entities = [
        HistoryEntity::class,
        PlaylistEntity::class,
        FavoriteEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun favoriteDao(): FavoriteDao
}
