package com.tv.ball.di

import android.content.Context
import com.tv.ball.VideoPlayerApplication
import com.tv.ball.data.local.LocalDataSource
import com.tv.ball.data.local.UserPreferences
import com.tv.ball.data.remote.NetworkDataSource
import com.tv.ball.data.remote.MemberApiService
import com.tv.ball.domain.parser.*
import com.tv.ball.domain.parser.impl.*
import com.tv.ball.domain.repository.*
import com.tv.ball.presentation.player.ExoPlayerManager

object Dependencies {

    private val app: VideoPlayerApplication
        get() = VideoPlayerApplication.instance

    val context: Context
        get() = app.applicationContext

    val okHttpClient by lazy { app.okHttpClient }
    val database by lazy { app.database }

    val videoDao by lazy { database.videoDao() }
    val historyDao by lazy { database.historyDao() }
    val playlistDao by lazy { database.playlistDao() }

    val localDataSource by lazy {
        LocalDataSource(context, videoDao, historyDao, playlistDao)
    }

    val networkDataSource by lazy {
        NetworkDataSource(okHttpClient, context)
    }

    val userPreferences by lazy {
        UserPreferences(context)
    }

    val memberApiService by lazy {
        // 为会员API创建独立的OkHttpClient，超时时间更长
        val memberOkHttpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()

        MemberApiService(memberOkHttpClient)
    }

    val videoSourceAutoSwitcher by lazy {
        com.tv.ball.data.VideoSourceAutoSwitcher(context, networkDataSource)
    }
    
    val parserChain by lazy { ParserChain() }
    
    val videoRepository by lazy {
        VideoRepository(localDataSource, networkDataSource, videoDao)
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
