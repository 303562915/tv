package com.tv.ball.presentation.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tv.ball.presentation.home.VideoItem
import com.tv.ball.domain.repository.HistoryRepository
import com.tv.ball.di.Dependencies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class HistoryItem(
    val videoItem: VideoItem,
    val detailUrl: String?,
    val episodesJson: String?,
    val currentEpisode: String?,
    val watchedPosition: Long,
    val duration: Long,
    val parsedVideoUrl: String?,
    val videoHeaders: String?,
    val lastWatchedAt: Long = System.currentTimeMillis()
)

/**
 * 历史记录分组
 */
data class HistoryGroup(
    val title: String,
    val items: List<HistoryItem>
)

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
            historyRepository.getAllHistory().collect { historyEntities ->
                val items = historyEntities.map { entity ->
                    HistoryItem(
                        videoItem = VideoItem(
                            title = entity.videoTitle,
                            coverUrl = entity.thumbnailUrl ?: "",
                            detailUrl = entity.detailUrl ?: entity.videoUrl,
                            quality = "HD",
                            sourceKey = ""
                        ),
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

                // 分组处理
                _groupedHistory.value = groupHistoryByDate(items)
            }
        }
    }

    /**
     * 按日期分组历史记录
     */
    private fun groupHistoryByDate(items: List<HistoryItem>): List<HistoryGroup> {
        val calendar = Calendar.getInstance()
        val today = calendar.apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val yesterday = calendar.apply {
            add(Calendar.DAY_OF_YEAR, -1)
        }.timeInMillis

        val groups = mutableListOf<HistoryGroup>()

        val todayItems = items.filter { it.lastWatchedAt >= today }
        if (todayItems.isNotEmpty()) {
            groups.add(HistoryGroup("今天", todayItems))
        }

        val yesterdayItems = items.filter { it.lastWatchedAt >= yesterday && it.lastWatchedAt < today }
        if (yesterdayItems.isNotEmpty()) {
            groups.add(HistoryGroup("昨天", yesterdayItems))
        }

        val olderItems = items.filter { it.lastWatchedAt < yesterday }
        if (olderItems.isNotEmpty()) {
            groups.add(HistoryGroup("更早", olderItems))
        }

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
                videoTitle = historyItem.videoItem.title,
                videoUrl = historyItem.videoItem.detailUrl,
                parsedVideoUrl = historyItem.parsedVideoUrl,
                videoHeaders = historyItem.videoHeaders,
                thumbnailUrl = historyItem.videoItem.coverUrl,
                watchedPosition = historyItem.watchedPosition,
                duration = historyItem.duration,
                detailUrl = historyItem.detailUrl,
                episodesJson = historyItem.episodesJson,
                currentEpisode = historyItem.currentEpisode,
                videoSource = null
            )
        }
    }

    /**
     * 切换选择模式
     */
    fun toggleSelectionMode() {
        _isSelectionMode.value = !_isSelectionMode.value
        if (!_isSelectionMode.value) {
            _selectedItems.value = emptySet()
        }
    }

    /**
     * 切换项目选择状态
     */
    fun toggleItemSelection(videoTitle: String) {
        val current = _selectedItems.value.toMutableSet()
        if (current.contains(videoTitle)) {
            current.remove(videoTitle)
        } else {
            current.add(videoTitle)
        }
        _selectedItems.value = current
    }

    /**
     * 全选
     */
    fun selectAll() {
        _selectedItems.value = _historyList.value.map { it.videoItem.title }.toSet()
    }

    /**
     * 删除选中的项目
     */
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

