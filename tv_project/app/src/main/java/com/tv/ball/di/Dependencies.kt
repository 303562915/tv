package com.tv.ball.di

import android.content.Context
import com.tv.ball.VideoPlayerApplication
import com.tv.ball.data.local.LocalDataSource
import com.tv.ball.data.local.UserPreferences
import com.tv.ball.domain.repository.*
import com.tv.ball.presentation.player.ExoPlayerManager

object Dependencies {

    private val app: VideoPlayerApplication
        get() = VideoPlayerApplication.instance

    val context: Context
        get() = app.applicationContext

    val okHttpClient by lazy { app.okHttpClient }
    val database by lazy { app.database }

    val historyDao by lazy { database.historyDao() }
    val playlistDao by lazy { database.playlistDao() }
    val favoriteDao by lazy { database.favoriteDao() }

    val localDataSource by lazy {
        LocalDataSource(context, historyDao, playlistDao)
    }

    val userPreferences by lazy {
        UserPreferences(context)
    }

    val historyRepository by lazy {
        HistoryRepository(localDataSource)
    }

    val playlistRepository by lazy {
        PlaylistRepository(localDataSource)
    }

    val configRepository by lazy {
        ConfigRepository(localDataSource)
    }

    val localVideoProgressManager by lazy {
        LocalVideoProgressManager(context)
    }

    val exoPlayerManager by lazy {
        ExoPlayerManager(context)
    }
}
