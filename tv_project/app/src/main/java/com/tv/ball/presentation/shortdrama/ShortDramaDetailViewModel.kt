package com.tv.ball.presentation.shortdrama

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 短剧详情页。
 *
 * 分集播放地址在详情接口里就有（采集源的 vod_play_url 就是 m3u8 直链），
 * 所以这里不再有「点一集再去问接口要地址」那一步，点开即播。
 *
 * 播放源切换也放在这里：详情接口一次就把所有源的分集都带回来了（sources），
 * 切源只做内存里的列表替换，不再走网络——否则点一下源又要转圈，体验比不切更差。
 */
class ShortDramaDetailViewModel : ViewModel() {

    companion object {
        private const val TAG = "ShortDramaDetailVM"

        // 简单的内存缓存 - 避免重复请求
        private val detailCache = mutableMapOf<String, ShortDramaDetailData>()
        private const val CACHE_EXPIRY_MS = 5 * 60 * 1000L // 5分钟缓存
        private val cacheTimestamps = mutableMapOf<String, Long>()
    }

    private val _detail = MutableStateFlow<ShortDramaItem?>(null)
    val detail: StateFlow<ShortDramaItem?> = _detail.asStateFlow()

    private val _episodes = MutableStateFlow<List<EpisodeItem>>(emptyList())
    val episodes: StateFlow<List<EpisodeItem>> = _episodes.asStateFlow()

    /** 全部播放源；只有一个（或没有）源时 UI 不显示切源那一排 */
    private val _sources = MutableStateFlow<List<ShortDramaPlaySource>>(emptyList())
    val sources: StateFlow<List<ShortDramaPlaySource>> = _sources.asStateFlow()

    /** 当前选中的源下标，和 _episodes 永远同步 */
    private val _selectedSourceIndex = MutableStateFlow(0)
    val selectedSourceIndex: StateFlow<Int> = _selectedSourceIndex.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun loadDetail(bookId: String) {
        Log.d(TAG, "loadDetail: bookId=$bookId")

        // 检查缓存
        val cachedTime = cacheTimestamps[bookId]
        val now = System.currentTimeMillis()
        if (cachedTime != null && (now - cachedTime) < CACHE_EXPIRY_MS) {
            detailCache[bookId]?.let { cached ->
                Log.d(TAG, "⚡ 使用缓存数据，缓存时间: ${(now - cachedTime) / 1000}秒前")
                applyData(cached)
                _isLoading.value = false
                return
            }
        }

        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            try {
                val data = ShortDramaRepository.fetchDetail(bookId)
                val cost = System.currentTimeMillis() - startTime
                Log.d(TAG, "⏱️ 详情加载完成，耗时 ${cost}ms，${data.episodes.size} 集，${data.sources.size} 个播放源")

                applyData(data)
                detailCache[bookId] = data
                cacheTimestamps[bookId] = System.currentTimeMillis()
            } catch (e: Exception) {
                val cost = System.currentTimeMillis() - startTime
                Log.e(TAG, "⏱️ 详情加载失败，耗时 ${cost}ms: ${e.message}", e)
                _error.value = e.message ?: "短剧详情请求失败"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 统一下发一份详情数据：detail / episodes / sources / 选中源 一起对齐，
     * 免得 UI 层收到「详情换了但分集还是旧的」这种中间态。
     */
    private fun applyData(data: ShortDramaDetailData) {
        _detail.value = data.detail
        _sources.value = data.sources
        _selectedSourceIndex.value = 0
        // 第一源没有分集时退回到 data.episodes（老接口只给了第一源的分集）
        val first = data.sources.firstOrNull()?.episodes.orEmpty()
        _episodes.value = (first.ifEmpty { data.episodes }).sortedBy { it.episodeNum }
    }

    /**
     * 切换播放源。直接取已加载的 sources，不发请求。
     */
    fun selectSource(index: Int) {
        val sourceList = _sources.value
        if (index !in sourceList.indices) return
        if (index == _selectedSourceIndex.value) return

        val target = sourceList[index]
        if (target.episodes.isEmpty()) {
            // 空源切过去只会得到一片空白，保持原样更安全
            Log.w(TAG, "播放源 ${target.name} 没有分集，忽略切换")
            return
        }

        Log.d(TAG, "切换播放源 -> ${target.name}（${target.episodes.size} 集）")
        _selectedSourceIndex.value = index
        _episodes.value = target.episodes.sortedBy { it.episodeNum }
    }

    /** 分集地址详情接口已经给了，直接透传，不再发请求。 */
    fun getVideoUrl(videoId: String, callback: (String) -> Unit) {
        callback(ShortDramaRepository.resolvePlayUrl(videoId))
    }
}

data class EpisodeItem(
    val videoId: String,
    val episodeNum: Int,
    val videoUrl: String
)