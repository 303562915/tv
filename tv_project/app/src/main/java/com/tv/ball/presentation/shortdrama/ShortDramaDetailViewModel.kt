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
                _detail.value = cached.detail
                _episodes.value = cached.episodes
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
                Log.d(TAG, "⏱️ 详情加载完成，耗时 ${cost}ms，${data.episodes.size} 集")

                _detail.value = data.detail
                _episodes.value = data.episodes
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