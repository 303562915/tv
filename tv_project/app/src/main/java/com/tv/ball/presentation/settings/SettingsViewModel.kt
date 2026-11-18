package com.tv.ball.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RecentPlayInfo(
    val title: String,
    val episode: String,
    val videoId: String? = null,
    val episodeIndex: Int? = null
)

class SettingsViewModel : ViewModel() {
    
    private val _recentPlay = MutableStateFlow<RecentPlayInfo?>(null)
    val recentPlay: StateFlow<RecentPlayInfo?> = _recentPlay.asStateFlow()
    
    private val historyRepository: com.tv.ball.domain.repository.HistoryRepository =
        com.tv.ball.di.Dependencies.historyRepository

    fun loadRecentPlay() {
        viewModelScope.launch {
            try {
                // 从数据库加载最近播放记录
                val recentHistory = historyRepository.getRecentHistory(limit = 1)
                _recentPlay.value = if (recentHistory.isNotEmpty()) {
                    val history = recentHistory.first()
                    RecentPlayInfo(
                        title = history.videoTitle,
                        episode = history.currentEpisode ?: "第1集",
                        videoId = history.id.toString(),
                        episodeIndex = null
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _recentPlay.value = null
            }
        }
    }
}
