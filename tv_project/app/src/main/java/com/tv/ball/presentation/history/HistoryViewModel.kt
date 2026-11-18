package com.tv.ball.presentation.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tv.ball.di.Dependencies
import com.tv.ball.domain.model.HistoryGroup
import com.tv.ball.domain.model.HistoryItem
import com.tv.ball.domain.repository.HistoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

class HistoryViewModel(
    private val historyRepository: HistoryRepository = Dependencies.historyRepository
) : ViewModel() {

    private val _historyList = MutableStateFlow<List<HistoryItem>>(emptyList())
    val historyList: StateFlow<List<HistoryItem>> = _historyList

    private val _groupedHistory = MutableStateFlow<List<HistoryGroup>>(emptyList())
    val groupedHistory: StateFlow<List<HistoryGroup>> = _groupedHistory

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode

    private val _selectedItems = MutableStateFlow<Set<String>>(emptySet())
    val selectedItems: StateFlow<Set<String>> = _selectedItems

    fun loadHistory() {
        viewModelScope.launch {
            historyRepository.getAllHistory().collect { entities ->
                val items = entities.map { entity ->
                    HistoryItem(
                        title = entity.videoTitle,
                        coverUrl = entity.thumbnailUrl ?: "",
                        videoUrl = entity.videoUrl,
                        detailUrl = entity.detailUrl,
                        episodesJson = entity.episodesJson,
                        currentEpisode = entity.currentEpisode,
                        watchedPosition = entity.watchedPosition,
                        duration = entity.duration,
                        parsedVideoUrl = entity.parsedVideoUrl,
                        videoHeaders = entity.videoHeaders,
                        lastWatchedAt = entity.lastWatchedAt
                    )
                }
                _historyList.value = items
                _groupedHistory.value = groupHistoryByDate(items)
            }
        }
    }

    private fun groupHistoryByDate(items: List<HistoryItem>): List<HistoryGroup> {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val today = calendar.timeInMillis

        calendar.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = calendar.timeInMillis

        val groups = mutableListOf<HistoryGroup>()

        items.filter { it.lastWatchedAt >= today }
            .takeIf { it.isNotEmpty() }
            ?.let { groups.add(HistoryGroup("今天", it)) }

        items.filter { it.lastWatchedAt >= yesterday && it.lastWatchedAt < today }
            .takeIf { it.isNotEmpty() }
            ?.let { groups.add(HistoryGroup("昨天", it)) }

        items.filter { it.lastWatchedAt < yesterday }
            .takeIf { it.isNotEmpty() }
            ?.let { groups.add(HistoryGroup("更早", it)) }

        return groups
    }

    fun deleteHistory(videoTitle: String) {
        viewModelScope.launch {
            historyRepository.deleteHistoryByTitle(videoTitle)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            historyRepository.clearHistory()
        }
    }

    fun addHistory(historyItem: HistoryItem) {
        viewModelScope.launch {
            historyRepository.addOrUpdateHistory(
                videoTitle = historyItem.title,
                videoUrl = historyItem.videoUrl,
                parsedVideoUrl = historyItem.parsedVideoUrl,
                videoHeaders = historyItem.videoHeaders,
                thumbnailUrl = historyItem.coverUrl,
                watchedPosition = historyItem.watchedPosition,
                duration = historyItem.duration,
                detailUrl = historyItem.detailUrl,
                episodesJson = historyItem.episodesJson,
                currentEpisode = historyItem.currentEpisode,
                videoSource = null
            )
        }
    }

    fun toggleSelectionMode() {
        _isSelectionMode.value = !_isSelectionMode.value
        if (!_isSelectionMode.value) {
            _selectedItems.value = emptySet()
        }
    }

    fun toggleItemSelection(title: String) {
        val current = _selectedItems.value.toMutableSet()
        if (current.contains(title)) current.remove(title) else current.add(title)
        _selectedItems.value = current
    }

    fun selectAll() {
        _selectedItems.value = _historyList.value.map { it.title }.toSet()
    }

    fun deleteSelected() {
        viewModelScope.launch {
            _selectedItems.value.forEach { title ->
                historyRepository.deleteHistoryByTitle(title)
            }
            _selectedItems.value = emptySet()
            _isSelectionMode.value = false
        }
    }
}
