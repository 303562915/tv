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

class ShortDramaDetailViewModel(
    private val networkDataSource: NetworkDataSource = createNetworkDataSource()
) : ViewModel() {

    companion object {
        private const val TAG = "ShortDramaDetailVM"
        private const val API_BASE = "https://api-v2.cenguigui.cn/api/duanju/api.php"
        
        // 简单的内存缓存 - 避免重复请求
        private val detailCache = mutableMapOf<String, Pair<ShortDramaItem, List<EpisodeItem>>>()
        private const val CACHE_EXPIRY_MS = 5 * 60 * 1000L // 5分钟缓存
        private val cacheTimestamps = mutableMapOf<String, Long>()
        
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
            detailCache[bookId]?.let { (detail, episodes) ->
                Log.d(TAG, "⚡ 使用缓存数据，缓存时间: ${(now - cachedTime) / 1000}秒前")
                _detail.value = detail
                _episodes.value = episodes
                _isLoading.value = false
                return
            }
        }
        
        _isLoading.value = true
        _error.value = null
        
        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            val url = "$API_BASE?book_id=$bookId"
            Log.d(TAG, "⏱️ [开始] 请求短剧详情: $url")
            
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36",
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "zh-CN,zh;q=0.9",
                "Referer" to "https://api-v2.cenguigui.cn/",
                "Connection" to "keep-alive",
                "Cache-Control" to "no-cache"
            )
            
            // 禁用智能网络切换，直接使用当前网络（更快）
            when (val result = networkDataSource.get(url, headers, useSmartNetwork = false)) {
                is NetworkResult.Success -> {
                    val networkTime = System.currentTimeMillis() - startTime
                    Log.d(TAG, "⏱️ [网络] 请求完成，耗时: ${networkTime}ms")
                    
                    val parseStartTime = System.currentTimeMillis()
                    parseDetailJson(result.data)
                    val parseTime = System.currentTimeMillis() - parseStartTime
                    
                    // 保存到缓存
                    _detail.value?.let { detail ->
                        detailCache[bookId] = Pair(detail, _episodes.value)
                        cacheTimestamps[bookId] = System.currentTimeMillis()
                        Log.d(TAG, "💾 数据已缓存")
                    }
                    
                    val totalTime = System.currentTimeMillis() - startTime
                    Log.d(TAG, "⏱️ [解析] 数据解析完成，耗时: ${parseTime}ms")
                    Log.d(TAG, "⏱️ [总计] 加载详情总耗时: ${totalTime}ms (网络: ${networkTime}ms, 解析: ${parseTime}ms)")
                }
                is NetworkResult.Error -> {
                    val totalTime = System.currentTimeMillis() - startTime
                    Log.e(TAG, "⏱️ [失败] 请求失败，耗时: ${totalTime}ms, 错误: ${result.message}")
                    _error.value = "网络错误: ${result.message}"
                }
            }
            
            _isLoading.value = false
        }
    }
    
    fun getVideoUrl(videoId: String, callback: (String) -> Unit) {
        viewModelScope.launch {
            val url = "$API_BASE?video_id=$videoId"
            Log.d(TAG, "Fetching video URL: $url")
            
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36",
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "zh-CN,zh;q=0.9",
                "Referer" to "https://api-v2.cenguigui.cn/"
            )
            
            when (val result = networkDataSource.get(url, headers)) {
                is NetworkResult.Success -> {
                    Log.d(TAG, "Response data: ${result.data}")
                    val videoUrl = parseVideoUrl(result.data)
                    Log.d(TAG, "Parsed video URL: $videoUrl")
                    callback(videoUrl)
                }
                is NetworkResult.Error -> {
                    Log.e(TAG, "Failed to get video URL: ${result.message}")
                    callback("")
                }
            }
        }
    }
    
    private fun parseVideoUrl(json: String): String {
        return try {
            Log.d(TAG, "Parsing video URL from JSON")
            
            // 方法1: 尝试从 "url":"xxx" 获取
            val urlPattern1 = """"url"\s*:\s*"([^"]+)"""".toRegex()
            val match1 = urlPattern1.find(json)
            if (match1 != null) {
                val url = match1.groupValues[1].replace("\\/", "/")
                Log.d(TAG, "Found URL (method 1): $url")
                if (url.isNotEmpty() && url.startsWith("http")) {
                    return url
                }
            }
            
            // 方法2: 尝试从 data 对象中的 url 获取
            val urlPattern2 = """"data"\s*:\s*\{[^}]*"url"\s*:\s*"([^"]+)"""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val match2 = urlPattern2.find(json)
            if (match2 != null) {
                val url = match2.groupValues[1].replace("\\/", "/")
                Log.d(TAG, "Found URL (method 2): $url")
                if (url.isNotEmpty() && url.startsWith("http")) {
                    return url
                }
            }
            
            // 方法3: 尝试使用 JSONObject 解析
            try {
                val jsonObj = org.json.JSONObject(json)
                if (jsonObj.has("url")) {
                    val url = jsonObj.getString("url")
                    Log.d(TAG, "Found URL (method 3): $url")
                    if (url.isNotEmpty() && url.startsWith("http")) {
                        return url
                    }
                }
                if (jsonObj.has("data")) {
                    val dataObj = jsonObj.getJSONObject("data")
                    if (dataObj.has("url")) {
                        val url = dataObj.getString("url")
                        Log.d(TAG, "Found URL (method 4): $url")
                        if (url.isNotEmpty() && url.startsWith("http")) {
                            return url
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "JSON parsing failed: ${e.message}")
            }
            
            Log.w(TAG, "No valid URL found in response")
            ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse video URL: ${e.message}", e)
            ""
        }
    }
    
    private fun parseDetailJson(json: String) {
        try {
            Log.d(TAG, "📝 [解析] 开始解析详情JSON, 长度: ${json.length}")
            Log.d(TAG, "📝 [解析] JSON内容: ${json.take(500)}...")  // 打印前500字符
            
            // 尝试使用JSONObject解析（更可靠）
            try {
                val jsonObj = org.json.JSONObject(json)
                
                // 检查是否有data字段
                val dataObj = if (jsonObj.has("data")) {
                    jsonObj.getJSONObject("data")
                } else {
                    jsonObj
                }
                
                // 解析基本信息
                val bookName = dataObj.optString("book_name", dataObj.optString("title", ""))
                val author = dataObj.optString("author", "未知作者")
                val category = dataObj.optString("category", dataObj.optString("type", ""))
                val desc = dataObj.optString("desc", dataObj.optString("intro", "暂无简介"))
                val total = dataObj.optString("total", dataObj.optString("episode_count", "0"))
                val playCnt = dataObj.optString("play_cnt", dataObj.optString("view_count", "0"))
                val cover = dataObj.optString("cover", dataObj.optString("pic", ""))
                
                Log.d(TAG, "✅ [解析] 书名: $bookName")
                Log.d(TAG, "✅ [解析] 作者: $author")
                Log.d(TAG, "✅ [解析] 分类: $category")
                Log.d(TAG, "✅ [解析] 总集数: $total")
                Log.d(TAG, "✅ [解析] 播放量: $playCnt")
                
                _detail.value = ShortDramaItem(
                    bookId = "",
                    title = bookName,
                    author = author,
                    type = category,
                    playCnt = playCnt,
                    episodeCnt = total,
                    cover = cover,
                    intro = desc
                )
                
                // 解析剧集列表
                val episodes = mutableListOf<EpisodeItem>()
                
                // 尝试从episodes数组解析
                if (dataObj.has("episodes")) {
                    val episodesArray = dataObj.getJSONArray("episodes")
                    for (i in 0 until episodesArray.length()) {
                        val episodeObj = episodesArray.getJSONObject(i)
                        val videoId = episodeObj.optString("video_id", episodeObj.optString("id", ""))
                        val title = episodeObj.optString("title", "第${i + 1}集")
                        
                        // 从标题中提取集数
                        val episodeNum = """第(\d+)集""".toRegex().find(title)?.groupValues?.get(1)?.toIntOrNull() ?: (i + 1)
                        
                        if (videoId.isNotEmpty()) {
                            episodes.add(
                                EpisodeItem(
                                    videoId = videoId,
                                    episodeNum = episodeNum,
                                    videoUrl = ""
                                )
                            )
                            Log.d(TAG, "✅ [解析] 剧集[$i]: 第${episodeNum}集, videoId=$videoId")
                        }
                    }
                }
                
                // 如果没有episodes数组，尝试从video_list解析
                if (episodes.isEmpty() && dataObj.has("video_list")) {
                    val videoListArray = dataObj.getJSONArray("video_list")
                    for (i in 0 until videoListArray.length()) {
                        val videoObj = videoListArray.getJSONObject(i)
                        val videoId = videoObj.optString("video_id", videoObj.optString("id", ""))
                        val title = videoObj.optString("title", "第${i + 1}集")
                        
                        val episodeNum = """第(\d+)集""".toRegex().find(title)?.groupValues?.get(1)?.toIntOrNull() ?: (i + 1)
                        
                        if (videoId.isNotEmpty()) {
                            episodes.add(
                                EpisodeItem(
                                    videoId = videoId,
                                    episodeNum = episodeNum,
                                    videoUrl = ""
                                )
                            )
                        }
                    }
                }
                
                // 按集数排序
                episodes.sortBy { it.episodeNum }
                
                _episodes.value = episodes
                Log.d(TAG, "✅ [解析] 成功解析 ${episodes.size} 集")
                
                if (episodes.isEmpty()) {
                    _error.value = "未找到剧集信息"
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ [解析] JSONObject解析失败，尝试正则: ${e.message}")
                
                // 降级到正则解析
                parseDetailJsonWithRegex(json)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ [解析] 解析失败: ${e.message}", e)
            _error.value = "解析数据失败"
        }
    }
    
    /**
     * 使用正则表达式解析（降级方案）
     */
    private fun parseDetailJsonWithRegex(json: String) {
        // 解析基本信息
        val bookNamePattern = """"book_name"\s*:\s*"([^"]+)"""".toRegex()
        val authorPattern = """"author"\s*:\s*"([^"]*)"""".toRegex()
        val categoryPattern = """"category"\s*:\s*"([^"]*)"""".toRegex()
        val descPattern = """"desc"\s*:\s*"([^"]*)"""".toRegex()
        val totalPattern = """"total"\s*:\s*"?(\d+)"?""".toRegex()
        val playCntPattern = """"play_cnt"\s*:\s*"?(\d+)"?""".toRegex()
        
        val bookName = bookNamePattern.find(json)?.groupValues?.get(1) ?: ""
        val author = authorPattern.find(json)?.groupValues?.get(1) ?: "未知作者"
        val category = categoryPattern.find(json)?.groupValues?.get(1) ?: ""
        val desc = descPattern.find(json)?.groupValues?.get(1) ?: "暂无简介"
        val total = totalPattern.find(json)?.groupValues?.get(1) ?: "0"
        val playCnt = playCntPattern.find(json)?.groupValues?.get(1) ?: "0"
        
        _detail.value = ShortDramaItem(
            bookId = "",
            title = bookName,
            author = author,
            type = category,
            playCnt = playCnt,
            episodeCnt = total,
            cover = "",
            intro = desc
        )
        
        // 解析剧集列表
        val episodes = mutableListOf<EpisodeItem>()
        
        // 匹配 "video_id":"xxx","title":"第N集"
        val videoPattern = """"video_id"\s*:\s*"([^"]+)"[^}]*"title"\s*:\s*"第(\d+)集"""".toRegex()
        
        for (match in videoPattern.findAll(json)) {
            val videoId = match.groupValues[1]
            val episodeNum = match.groupValues[2].toIntOrNull() ?: 0
            
            episodes.add(
                EpisodeItem(
                    videoId = videoId,
                    episodeNum = episodeNum,
                    videoUrl = ""
                )
            )
        }
        
        // 按集数排序
        episodes.sortBy { it.episodeNum }
        
        _episodes.value = episodes
        Log.d(TAG, "✅ [正则解析] 解析 ${episodes.size} 集")
        
        if (episodes.isEmpty()) {
            _error.value = "未找到剧集信息"
        }
    }
}

data class EpisodeItem(
    val videoId: String,
    val episodeNum: Int,
    val videoUrl: String
)
