package com.tv.ball.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tv.ball.presentation.home.VideoItem
import com.tv.ball.domain.repository.VideoRepository
import com.tv.ball.di.Dependencies
import com.tv.ball.presentation.ranking.RankingItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SearchViewModel(
    private val videoRepository: VideoRepository = Dependencies.videoRepository
) : ViewModel() {

    companion object {
        private const val TAG = "SearchViewModel"
    }

    private val _searchResults = MutableStateFlow<List<VideoItem>>(emptyList())
    val searchResults: StateFlow<List<VideoItem>> = _searchResults
    
    private val _rankingList = MutableStateFlow<List<RankingItem>>(emptyList())
    val rankingList: StateFlow<List<RankingItem>> = _rankingList

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _selectedSources = MutableStateFlow<Set<String>>(setOf(
        "iqiyi", "360zy", "ffzy5", "dbzy", "niuniuzy3", "jisuzy"
    ))
    val selectedSources: StateFlow<Set<String>> = _selectedSources

    fun search(query: String) {
        android.util.Log.d(TAG, "搜索关键字: $query")
        viewModelScope.launch {
            _isLoading.value = true

            try {
                android.util.Log.d(TAG, "开始搜索...")
                val results = videoRepository.searchVideos(query)
                android.util.Log.d(TAG, "搜索结果: ${results.size} 个视频")
                _searchResults.value = results
            } catch (e: Exception) {
                android.util.Log.e(TAG, "搜索失败: ${e.message}", e)
                _searchResults.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 多站点搜索
     */
    fun searchMultiSites(query: String) {
        android.util.Log.d(TAG, "多站点搜索关键字: $query, 站点: ${_selectedSources.value}")
        viewModelScope.launch {
            _isLoading.value = true

            try {
                val sources = _selectedSources.value.toList()
                if (sources.isEmpty()) {
                    android.util.Log.w(TAG, "未选择任何站点")
                    _searchResults.value = emptyList()
                    return@launch
                }

                android.util.Log.d(TAG, "开始在 ${sources.size} 个站点搜索...")
                val results = videoRepository.searchMultiSites(query, sources)
                android.util.Log.d(TAG, "搜索结果: ${results.size} 个视频")
                _searchResults.value = results
            } catch (e: Exception) {
                android.util.Log.e(TAG, "多站点搜索失败: ${e.message}", e)
                _searchResults.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * 加载榜单
     */
    fun loadRanking() {
        android.util.Log.d(TAG, "加载榜单...")
        viewModelScope.launch {
            _isLoading.value = true

            try {
                val rankings = fetchRankingFromApi()
                android.util.Log.d(TAG, "榜单结果: ${rankings.size} 个")
                _rankingList.value = rankings
            } catch (e: Exception) {
                android.util.Log.e(TAG, "加载榜单失败: ${e.message}", e)
                _rankingList.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    private suspend fun fetchRankingFromApi(): List<RankingItem> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val request = okhttp3.Request.Builder()
                    .url("https://api.52vmy.cn/api/wl/top/tv?type=text")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val text = response.body?.string() ?: ""
                    parseRankingText(text)
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "获取榜单API失败: ${e.message}", e)
                emptyList()
            }
        }
    }
    
    private fun parseRankingText(text: String): List<RankingItem> {
        val rankings = mutableListOf<RankingItem>()
        
        // 按行分割
        val lines = text.split("\n")
        var currentRank = 0
        var currentTitle = ""
        var currentPlatform = ""
        var currentOnlineDays = ""
        var currentHeat = ""
        var currentPlayCount = ""
        
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            
            // 匹配 "Top X -> 标题"
            val topMatch = """Top (\d+) -> (.+)""".toRegex().find(trimmed)
            if (topMatch != null) {
                // 保存上一个条目
                if (currentRank > 0) {
                    rankings.add(
                        RankingItem(
                            rank = currentRank,
                            title = currentTitle,
                            platform = currentPlatform,
                            onlineDays = currentOnlineDays,
                            heat = currentHeat,
                            playCount = currentPlayCount
                        )
                    )
                }
                
                // 开始新条目
                currentRank = topMatch.groupValues[1].toInt()
                currentTitle = topMatch.groupValues[2]
                currentPlatform = ""
                currentOnlineDays = ""
                currentHeat = ""
                currentPlayCount = ""
            } else if (trimmed.contains("独播") || trimmed.contains("播放")) {
                // 平台和上线天数
                val parts = trimmed.split(" ")
                if (parts.size >= 2) {
                    currentPlatform = parts[0]
                    currentOnlineDays = parts[1]
                }
            } else if (trimmed.startsWith("实时热量")) {
                currentHeat = trimmed.replace("实时热量 ", "")
            } else if (trimmed.startsWith("实时播放量")) {
                currentPlayCount = trimmed.replace("实时播放量 ", "")
            }
        }
        
        // 保存最后一个条目
        if (currentRank > 0) {
            rankings.add(
                RankingItem(
                    rank = currentRank,
                    title = currentTitle,
                    platform = currentPlatform,
                    onlineDays = currentOnlineDays,
                    heat = currentHeat,
                    playCount = currentPlayCount
                )
            )
        }
        
        return rankings
    }

    /**
     * 切换站点选择
     */
    fun toggleSource(sourceKey: String) {
        val current = _selectedSources.value.toMutableSet()
        if (current.contains(sourceKey)) {
            current.remove(sourceKey)
        } else {
            current.add(sourceKey)
        }
        _selectedSources.value = current
        android.util.Log.d(TAG, "已选择站点: $current")
    }

    /**
     * 选择所有站点
     */
    fun selectAllSources() {
        _selectedSources.value = setOf("iqiyi", "libvod", "keke")
    }

    /**
     * 清除所有站点选择
     */
    fun clearAllSources() {
        _selectedSources.value = emptySet()
    }
}
