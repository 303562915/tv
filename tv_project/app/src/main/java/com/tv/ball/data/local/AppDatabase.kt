package com.tv.ball.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tv.ball.data.model.FavoriteEntity
import com.tv.ball.data.model.HistoryEntity
import com.tv.ball.data.model.PlaylistEntity
import com.tv.ball.data.model.VideoEntity

@Database(
    entities = [
        VideoEntity::class,
        HistoryEntity::class,
        PlaylistEntity::class,
        FavoriteEntity::class,
        com.tv.ball.data.model.DownloadTaskEntity::class
    ],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
    abstract fun historyDao(): HistoryDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun downloadTaskDao(): DownloadTaskDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 添加新字段到 history 表
                database.execSQL("ALTER TABLE history ADD COLUMN detailUrl TEXT")
                database.execSQL("ALTER TABLE history ADD COLUMN episodesJson TEXT")
                database.execSQL("ALTER TABLE history ADD COLUMN currentEpisode TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 创建收藏表
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS favorites (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        videoId INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        thumbnailUrl TEXT,
                        detailUrl TEXT,
                        episodesJson TEXT,
                        addedAt INTEGER NOT NULL
                    )
                """)
            }
        }
        
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 创建下载任务表
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS download_tasks (
                        id INTEGER PRIMARY KEY NOT NULL,
                        url TEXT NOT NULL,
                        title TEXT NOT NULL,
                        fileName TEXT NOT NULL,
                        savePath TEXT NOT NULL,
                        totalSize INTEGER NOT NULL DEFAULT 0,
                        downloadedSize INTEGER NOT NULL DEFAULT 0,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        progress INTEGER NOT NULL DEFAULT 0,
                        createTime INTEGER NOT NULL,
                        updateTime INTEGER NOT NULL,
                        isM3u8 INTEGER NOT NULL DEFAULT 0,
                        m3u8SegmentsJson TEXT NOT NULL DEFAULT '[]',
                        errorMessage TEXT
                    )
                """)
            }
        }
        
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 重建 history 表，使用 videoTitle 作为唯一标识
                // 1. 创建新表
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS history_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        videoTitle TEXT NOT NULL,
                        videoUrl TEXT NOT NULL,
                        thumbnailUrl TEXT,
                        watchedPosition INTEGER NOT NULL DEFAULT 0,
                        duration INTEGER NOT NULL DEFAULT 0,
                        lastWatchedAt INTEGER NOT NULL,
                        detailUrl TEXT,
                        episodesJson TEXT,
                        currentEpisode TEXT
                    )
                """)
                
                // 2. 创建唯一索引
                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_history_videoTitle 
                    ON history_new(videoTitle)
                """)
                
                // 3. 迁移数据（只保留每个标题的最新记录）
                database.execSQL("""
                    INSERT INTO history_new (id, videoTitle, videoUrl, thumbnailUrl, 
                        watchedPosition, duration, lastWatchedAt, detailUrl, episodesJson, currentEpisode)
                    SELECT id, videoTitle, videoUrl, thumbnailUrl, 
                        watchedPosition, duration, lastWatchedAt, detailUrl, episodesJson, currentEpisode
                    FROM history
                    WHERE id IN (
                        SELECT MAX(id) FROM history GROUP BY videoTitle
                    )
                """)
                
                // 4. 删除旧表
                database.execSQL("DROP TABLE history")
                
                // 5. 重命名新表
                database.execSQL("ALTER TABLE history_new RENAME TO history")
            }
        }
        
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 添加 parsedVideoUrl 字段，并修改索引为 videoTitle + currentEpisode
                // 1. 创建新表
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS history_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        videoTitle TEXT NOT NULL,
                        videoUrl TEXT NOT NULL,
                        parsedVideoUrl TEXT,
                        thumbnailUrl TEXT,
                        watchedPosition INTEGER NOT NULL DEFAULT 0,
                        duration INTEGER NOT NULL DEFAULT 0,
                        lastWatchedAt INTEGER NOT NULL,
                        detailUrl TEXT,
                        episodesJson TEXT,
                        currentEpisode TEXT
                    )
                """)
                
                // 2. 创建复合唯一索引（videoTitle + currentEpisode）
                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_history_videoTitle_currentEpisode 
                    ON history_new(videoTitle, currentEpisode)
                """)
                
                // 3. 迁移数据 - 只保留每个 videoTitle 的最新记录（按 lastWatchedAt 排序）
                database.execSQL("""
                    INSERT OR IGNORE INTO history_new (id, videoTitle, videoUrl, parsedVideoUrl, thumbnailUrl, 
                        watchedPosition, duration, lastWatchedAt, detailUrl, episodesJson, currentEpisode)
                    SELECT id, videoTitle, videoUrl, NULL, thumbnailUrl, 
                        watchedPosition, duration, lastWatchedAt, detailUrl, episodesJson, currentEpisode
                    FROM history
                    WHERE id IN (
                        SELECT MAX(id) FROM history GROUP BY videoTitle
                    )
                """)
                
                // 4. 删除旧表
                database.execSQL("DROP TABLE history")
                
                // 5. 重命名新表
                database.execSQL("ALTER TABLE history_new RENAME TO history")
            }
        }
        
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 清理重复的历史记录，只保留每个标题的最新记录
                // 1. 创建临时表
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS history_temp (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        videoTitle TEXT NOT NULL,
                        videoUrl TEXT NOT NULL,
                        parsedVideoUrl TEXT,
                        thumbnailUrl TEXT,
                        watchedPosition INTEGER NOT NULL DEFAULT 0,
                        duration INTEGER NOT NULL DEFAULT 0,
                        lastWatchedAt INTEGER NOT NULL,
                        detailUrl TEXT,
                        episodesJson TEXT,
                        currentEpisode TEXT
                    )
                """)
                
                // 2. 创建复合唯一索引
                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_history_temp_videoTitle_currentEpisode 
                    ON history_temp(videoTitle, currentEpisode)
                """)
                
                // 3. 只保留每个 videoTitle 的最新记录
                database.execSQL("""
                    INSERT OR IGNORE INTO history_temp (videoTitle, videoUrl, parsedVideoUrl, thumbnailUrl, 
                        watchedPosition, duration, lastWatchedAt, detailUrl, episodesJson, currentEpisode)
                    SELECT videoTitle, videoUrl, parsedVideoUrl, thumbnailUrl, 
                        watchedPosition, duration, MAX(lastWatchedAt), detailUrl, episodesJson, currentEpisode
                    FROM history
                    GROUP BY videoTitle
                    ORDER BY lastWatchedAt DESC
                """)
                
                // 4. 删除旧表
                database.execSQL("DROP TABLE history")
                
                // 5. 重命名临时表
                database.execSQL("ALTER TABLE history_temp RENAME TO history")
            }
        }
        
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 添加 videoHeaders 字段到 history 和 favorites 表
                database.execSQL("ALTER TABLE history ADD COLUMN videoHeaders TEXT")
                database.execSQL("ALTER TABLE favorites ADD COLUMN parsedVideoUrl TEXT")
                database.execSQL("ALTER TABLE favorites ADD COLUMN videoHeaders TEXT")
            }
        }
        
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 添加多线程下载相关字段到 download_tasks 表
                database.execSQL("ALTER TABLE download_tasks ADD COLUMN customHeadersJson TEXT NOT NULL DEFAULT '{}'")
                database.execSQL("ALTER TABLE download_tasks ADD COLUMN threadCount INTEGER DEFAULT 0")
                database.execSQL("ALTER TABLE download_tasks ADD COLUMN partProgressesJson TEXT DEFAULT '[]'")
            }
        }
        
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 添加 videoSource 字段到 history 表，用于区分不同视频源的播放记录
                // 1. 创建新表
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS history_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        videoTitle TEXT NOT NULL,
                        videoUrl TEXT NOT NULL,
                        parsedVideoUrl TEXT,
                        videoHeaders TEXT,
                        thumbnailUrl TEXT,
                        watchedPosition INTEGER NOT NULL DEFAULT 0,
                        duration INTEGER NOT NULL DEFAULT 0,
                        lastWatchedAt INTEGER NOT NULL,
                        detailUrl TEXT,
                        episodesJson TEXT,
                        currentEpisode TEXT,
                        videoSource TEXT
                    )
                """)
                
                // 2. 创建复合唯一索引（videoTitle + currentEpisode + videoSource）
                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_history_videoTitle_currentEpisode_videoSource 
                    ON history_new(videoTitle, currentEpisode, videoSource)
                """)
                
                // 3. 迁移数据（保留所有现有记录，videoSource 默认为 NULL）
                database.execSQL("""
                    INSERT INTO history_new (id, videoTitle, videoUrl, parsedVideoUrl, videoHeaders, thumbnailUrl, 
                        watchedPosition, duration, lastWatchedAt, detailUrl, episodesJson, currentEpisode, videoSource)
                    SELECT id, videoTitle, videoUrl, parsedVideoUrl, videoHeaders, thumbnailUrl, 
                        watchedPosition, duration, lastWatchedAt, detailUrl, episodesJson, currentEpisode, NULL
                    FROM history
                """)
                
                // 4. 删除旧表
                database.execSQL("DROP TABLE history")
                
                // 5. 重命名新表
                database.execSQL("ALTER TABLE history_new RENAME TO history")
            }
        }
    }
}
