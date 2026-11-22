package com.tv.ball.presentation.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tv.ball.data.local.HistoryDao
import com.tv.ball.data.model.HistoryEntity
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
    val videoSource: String? = null,
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
    private val historyRepository: HistoryRepository = Dependencies.historyRepository,
    /** 按唯一键删单条时走 DAO 的 @Delete（主键 id），不连带删掉同名同剧的其它记录。 */
    private val historyDao: HistoryDao = Dependencies.historyDao
) : ViewModel() {

    private val _historyList = MutableStateFlow<List<HistoryItem>>(emptyList())
    val historyList: StateFlow<List<HistoryItem>> = _historyList

    private val _groupedHistory = MutableStateFlow<List<HistoryGroup>>(emptyList())
    val groupedHistory: StateFlow<List<HistoryGroup>> = _groupedHistory

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode

    private val _selectedItems = MutableStateFlow<Set<String>>(emptySet())
    val selectedItems: StateFlow<Set<String>> = _selectedItems

    /**
     * 列表当前对应的「删除唯一键 → 实体」索引。
     * 列表只显示每部剧的最新一条（DAO 里按 videoTitle 取 MAX(id)），但库中同名多集/多线路仍在，
     * 所以「删一条」必须落到主键 id 上，不能拿标题去下发。
     */
    private var entitiesByDeleteKey: Map<String, HistoryEntity> = emptyMap()

    init {
        // 只在 ViewModel 创建时订阅一次：Fragment 每次重建都调 loadHistory() 会累积永不结束的 collector
        loadHistory()
    }

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
                        videoSource = entity.videoSource,
                        lastWatchedAt = entity.lastWatchedAt
                    )
                }
                entitiesByDeleteKey = historyEntities.associateBy { entityDeleteKey(it) }
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

    /**
     * 删一条历史：按唯一键（detailUrl + currentEpisode，缺失 detailUrl 时退到标题）命中实体，
     * 再按主键 id 删这一行。同名不同线路 / 不同集不会被一起删掉。
     * 实体索引还没回填（列表首帧尚未到）时才退回按标题删，保证操作仍然生效。
     */
    fun deleteHistory(item: HistoryItem) {
        val entity = entitiesByDeleteKey[historyDeleteKey(item)]
        viewModelScope.launch {
            if (entity != null) {
                historyDao.deleteHistory(entity)
            } else {
                historyRepository.deleteHistoryByTitle(item.videoItem.title)
            }
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
                videoSource = historyItem.videoSource,
                // 撤销删除时沿用原观看时间，记录不会跳回列表最前面
                lastWatchedAt = historyItem.lastWatchedAt
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
     * 切换项目选择状态。[selectionKey] 用 [historyDeleteKey] 的口径，选中集与删除口径一致。
     */
    fun toggleItemSelection(selectionKey: String) {
        val current = _selectedItems.value.toMutableSet()
        if (current.contains(selectionKey)) {
            current.remove(selectionKey)
        } else {
            current.add(selectionKey)
        }
        _selectedItems.value = current
    }

    /**
     * 全选
     */
    fun selectAll() {
        _selectedItems.value = _historyList.value.map { historyDeleteKey(it) }.toSet()
    }

    /**
     * 删除选中的项目：与 [deleteHistory] 同口径，按唯一键命中实体后按主键删。
     */
    fun deleteSelected() {
        val keys = _selectedItems.value
        val index = entitiesByDeleteKey
        viewModelScope.launch {
            keys.forEach { key ->
                index[key]?.let { historyDao.deleteHistory(it) }
            }
            _selectedItems.value = emptySet()
            _isSelectionMode.value = false
        }
    }
}

/**
 * 列表一行的身份键（[HistoryAdapter] 的 DiffUtil.areItemsTheSame 用）。
 *
 * 历史条目没有稳定 videoId，所以 detailUrl 优先；缺失时退到详情页 URL / 标题。
 * 同一部剧的不同线路、不同集不会再被当成同一条。
 */
internal fun historyIdentityKey(item: HistoryItem): String =
    routeKey(item.detailUrl, item.videoItem.detailUrl) ?: item.videoItem.title

/**
 * 删除唯一键：详情页 URL（缺失时退到标题）+ 当前集数。
 * [HistoryViewModel.deleteHistory] 与适配器身份键都用它派生，避免「按标题删」误伤同名条目。
 */
internal fun historyDeleteKey(item: HistoryItem): String =
    (routeKey(item.detailUrl, item.videoItem.detailUrl) ?: item.videoItem.title) +
        KEY_SEPARATOR + item.currentEpisode.orEmpty()

/** 实体的同一把钥匙：字段来源与 [historyDeleteKey] 一一对应（videoItem.detailUrl 即 detailUrl ?: videoUrl）。 */
private fun entityDeleteKey(entity: HistoryEntity): String =
    (routeKey(entity.detailUrl, entity.videoUrl) ?: entity.videoTitle) +
        KEY_SEPARATOR + entity.currentEpisode.orEmpty()

/** 取第一个非空白的 URL 当身份键。 */
private fun routeKey(vararg candidates: String?): String? =
    candidates.firstOrNull { !it.isNullOrBlank() }

/** 键的分隔符：用不可见字符，标题里出现也不会有歧义。 */
private const val KEY_SEPARATOR = "\u0000"

