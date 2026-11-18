package com.tv.ball.presentation.shortdrama

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tv.ball.data.remote.NetworkDataSource
import com.tv.ball.data.remote.NetworkResult
import com.tv.ball.di.Dependencies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ShortDramaViewModel(
    private val networkDataSource: NetworkDataSource = createNetworkDataSource()
) : ViewModel() {

    companion object {
        private const val TAG = "ShortDramaViewModel"
        private const val API_BASE = "https://api-v2.cenguigui.cn/api/duanju/api.php"
        
        /**
         * 创建专用的NetworkDataSource，使用更长的超时时间
         */
        private fun createNetworkDataSource(): NetworkDataSource {
            val okHttpClient = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)  // 连接超时10秒
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)     // 读取超时15秒
                .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)    // 写入超时10秒
                .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)     // 总超时20秒
                .retryOnConnectionFailure(true)  // 自动重试
                .build()
            
            return NetworkDataSource(okHttpClient, Dependencies.context)
        }
    }

    private val _dramas = MutableStateFlow<List<ShortDramaItem>>(emptyList())
    val dramas: StateFlow<List<ShortDramaItem>> = _dramas.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var currentCategory = "霸总"
    private var currentPage = 1
    private var isLoadingMore = false

    fun loadDramas(category: String, page: Int, refresh: Boolean = false) {
        Log.d(TAG, "loadDramas: category=$category, page=$page, refresh=$refresh")
        
        if (refresh) {
            currentPage = 1
            _dramas.value = emptyList()
        }
        
        if (isLoadingMore) {
            Log.d(TAG, "Already loading, skipping")
            return
        }
        
        currentCategory = category
        isLoadingMore = true
        _isLoading.value = true
        _error.value = null
        
        viewModelScope.launch {
            val url = "$API_BASE?name=$category&page=$page"
            Log.d(TAG, "Fetching: $url")
            
            // 添加请求头
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36",
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "zh-CN,zh;q=0.9",
                "Referer" to "https://api-v2.cenguigui.cn/"
            )
            
            when (val result = networkDataSource.get(url, headers)) {
                is NetworkResult.Success -> {
                    Log.d(TAG, "Request successful")
                    val dramaList = parseJson(result.data)
                    Log.d(TAG, "Parsed ${dramaList.size} dramas")
                    
                    if (dramaList.isEmpty()) {
                        _error.value = "未找到短剧内容"
                    } else {
                        if (refresh) {
                            _dramas.value = dramaList
                        } else {
                            _dramas.value = _dramas.value + dramaList
                        }
                        currentPage++
                    }
                }
                is NetworkResult.Error -> {
                    Log.e(TAG, "Request error: ${result.message}")
                    _error.value = "网络错误: ${result.message}"
                }
            }
            
            _isLoading.value = false
            isLoadingMore = false
        }
    }
    
    private fun parseJson(json: String): List<ShortDramaItem> {
        val dramas = mutableListOf<ShortDramaItem>()
        try {
            Log.d(TAG, "Raw JSON response length: ${json.length}")
            
            // 使用更简单的方式解析 JSON
            // 先找到 "data": [ 的位置
            val dataStart = json.indexOf("\"data\":")
            if (dataStart == -1) {
                Log.e(TAG, "No 'data' field found")
                return dramas
            }
            
            // 找到数组开始的位置
            val arrayStart = json.indexOf("[", dataStart)
            if (arrayStart == -1) {
                Log.e(TAG, "No array start found")
                return dramas
            }
            
            // 简单的正则匹配每个对象
            val itemRegex = """"book_id"\s*:\s*"([^"]+)"[^}]*"title"\s*:\s*"([^"]+)"[^}]*"author"\s*:\s*"([^"]*)"[^}]*"type"\s*:\s*"([^"]*)"[^}]*"play_cnt"\s*:\s*"([^"]*)"[^}]*"episode_cnt"\s*:\s*"([^"]*)"[^}]*"cover"\s*:\s*"([^"]+)"[^}]*"intro"\s*:\s*"([^"]*)"""".toRegex()
            
            var matchCount = 0
            for (match in itemRegex.findAll(json)) {
                matchCount++
                val bookId = match.groupValues[1]
                val title = match.groupValues[2]
                val author = match.groupValues[3]
                val type = match.groupValues[4]
                val playCnt = match.groupValues[5]
                val episodeCnt = match.groupValues[6]
                val cover = match.groupValues[7]
                val intro = match.groupValues[8]
                
                Log.d(TAG, "Parsed drama: $title (ID: $bookId)")
                
                dramas.add(
                    ShortDramaItem(
                        bookId = bookId,
                        title = title,
                        author = author,
                        type = type,
                        playCnt = playCnt,
                        episodeCnt = episodeCnt,
                        cover = cover,
                        intro = intro
                    )
                )
            }
            
            Log.d(TAG, "Total matches found: $matchCount, dramas added: ${dramas.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON: ${e.message}", e)
        }
        return dramas
    }
    
    fun loadMore() {
        if (!isLoadingMore && !_isLoading.value) {
            loadDramas(currentCategory, currentPage, refresh = false)
        }
    }
}

data class ShortDramaItem(
    val bookId: String,
    val title: String,
    val author: String,
    val type: String,
    val playCnt: String,
    val episodeCnt: String,
    val cover: String,
    val intro: String
)
