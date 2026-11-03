package com.tv.ball.data

import android.content.Context
import android.util.Log
import com.tv.ball.data.remote.NetworkDataSource
import com.tv.ball.data.remote.NetworkResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 视频源自动切换器
 * 当当前视频源加载失败时，自动尝试其他可用的视频源
 */
class VideoSourceAutoSwitcher(
    private val context: Context,
    private val networkDataSource: NetworkDataSource
) {
    companion object {
        private const val TAG = "VideoSourceAutoSwitcher"
        private const val PREFS_NAME = "video_source_auto_switch"
        private const val KEY_FAILED_SOURCES = "failed_sources"
        private const val KEY_LAST_SWITCH_TIME = "last_switch_time"
        private const val RETRY_COOLDOWN = 5 * 60 * 1000L // 5分钟后重试失败的源
    }
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * 测试视频源是否可用
     */
    private suspend fun testSource(sourceKey: String, category: Int = 6): Boolean = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            Log.d(TAG, "测试视频源: $sourceKey")
            
            val url = VideoSourceManager.getSourceApiUrl(sourceKey, category, 1)
            if (url.isEmpty()) {
                Log.w(TAG, "视频源 $sourceKey URL为空")
                return@withContext false
            }
            
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            
            // 极速测试 - 2秒内必须响应
            val result = withContext(Dispatchers.IO) {
                try {
                    // 使用withTimeout确保快速失败 - 2秒硬超时
                    kotlinx.coroutines.withTimeout(2000L) {
                        networkDataSource.get(url, headers, useSmartNetwork = true)
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.w(TAG, "✗ 视频源 $sourceKey 超时 (${elapsed}ms) - 立即切换")
                    NetworkResult.Error("超时", e)
                } catch (e: Exception) {
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.e(TAG, "✗ 视频源 $sourceKey 异常 (${elapsed}ms): ${e.message}")
                    NetworkResult.Error("测试失败: ${e.message}", e)
                }
            }
            
            val elapsed = System.currentTimeMillis() - startTime
            
            when (result) {
                is NetworkResult.Success -> {
                    val hasContent = result.data.isNotEmpty() && 
                        (result.data.contains("\"list\"") || // JSON API
                         result.data.contains("<video>") || // XML API
                         result.data.contains("v-item") || // 可可影视
                         result.data.contains("stui-vodlist") || // LiB影视
                         result.data.contains("/detail/")) // 在线之家
                    
                    if (hasContent) {
                        Log.d(TAG, "✓ 视频源 $sourceKey 可用 (${elapsed}ms)")
                    } else {
                        Log.w(TAG, "✗ 视频源 $sourceKey 响应无效内容 (${elapsed}ms)")
                    }
                    hasContent
                }
                is NetworkResult.Error -> {
                    Log.w(TAG, "✗ 视频源 $sourceKey 不可用 (${elapsed}ms): ${result.message}")
                    false
                }
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.e(TAG, "✗ 视频源 $sourceKey 失败 (${elapsed}ms): ${e.message}", e)
            false
        }
    }
    
    /**
     * 获取失败的视频源列表
     */
    private fun getFailedSources(): Set<String> {
        val failedSourcesStr = prefs.getString(KEY_FAILED_SOURCES, "") ?: ""
        return if (failedSourcesStr.isEmpty()) {
            emptySet()
        } else {
            failedSourcesStr.split(",").toSet()
        }
    }
    
    /**
     * 标记视频源为失败
     */
    private fun markSourceAsFailed(sourceKey: String) {
        val failedSources = getFailedSources().toMutableSet()
        failedSources.add(sourceKey)
        prefs.edit()
            .putString(KEY_FAILED_SOURCES, failedSources.joinToString(","))
            .putLong(KEY_LAST_SWITCH_TIME, System.currentTimeMillis())
            .apply()
        Log.d(TAG, "标记视频源 $sourceKey 为失败")
    }
    
    /**
     * 清除失败记录（在冷却时间后）
     */
    private fun clearFailedSourcesIfNeeded() {
        val lastSwitchTime = prefs.getLong(KEY_LAST_SWITCH_TIME, 0)
        val currentTime = System.currentTimeMillis()
        
        if (currentTime - lastSwitchTime > RETRY_COOLDOWN) {
            prefs.edit()
                .remove(KEY_FAILED_SOURCES)
                .remove(KEY_LAST_SWITCH_TIME)
                .apply()
            Log.d(TAG, "清除失败视频源记录（冷却时间已过）")
        }
    }
    
    /**
     * 自动切换到可用的视频源
     * @param currentSourceKey 当前失败的视频源
     * @param category 要测试的分类
     * @return 切换后的视频源key，如果没有可用源则返回null
     */
    suspend fun switchToAvailableSource(
        currentSourceKey: String,
        category: Int = 6
    ): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "========== 开始自动切换视频源 ==========")
        Log.d(TAG, "当前失败的源: $currentSourceKey")
        
        // 清除过期的失败记录
        clearFailedSourcesIfNeeded()
        
        // 标记当前源为失败
        markSourceAsFailed(currentSourceKey)
        
        val failedSources = getFailedSources()
        Log.d(TAG, "已失败的源: $failedSources")
        
        // 获取所有可用的视频源，排除已失败的
        val availableSources = VideoSourceConfigs.sources
            .filter { it.key != currentSourceKey && it.key !in failedSources }
        
        Log.d(TAG, "待测试的源: ${availableSources.map { it.key }}")
        
        if (availableSources.isEmpty()) {
            Log.w(TAG, "没有可用的备用视频源")
            return@withContext null
        }
        
        // 按优先级测试视频源
        // 优先级：API源 > WEB源
        val sortedSources = availableSources.sortedBy {
            when (it.type) {
                SourceType.API, SourceType.API_XML -> 0
                SourceType.WEB -> 1
            }
        }
        
        for (source in sortedSources) {
            Log.d(TAG, "测试视频源: ${source.name} (${source.key})")
            
            if (testSource(source.key, category)) {
                Log.d(TAG, "✓ 找到可用视频源: ${source.name}")
                
                // 切换到新的视频源
                VideoSourceManager.setCurrentSource(source.key)
                
                Log.d(TAG, "========== 视频源切换完成 ==========")
                return@withContext source.key
            }
        }
        
        Log.w(TAG, "所有备用视频源都不可用")
        Log.d(TAG, "========== 视频源切换失败 ==========")
        null
    }
    
    /**
     * 测试当前视频源是否可用
     */
    suspend fun testCurrentSource(category: Int = 6): Boolean {
        val currentSource = VideoSourceManager.getCurrentSource()
        return testSource(currentSource, category)
    }
    
    /**
     * 重置所有失败记录
     */
    fun resetFailedSources() {
        prefs.edit()
            .remove(KEY_FAILED_SOURCES)
            .remove(KEY_LAST_SWITCH_TIME)
            .apply()
        Log.d(TAG, "已重置所有失败视频源记录")
    }
}
