package com.tv.ball.presentation.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.util.Log
import android.view.View
import android.widget.Toast

/**
 * 短剧管理器
 * 处理短剧特有的功能：竖屏全屏、自动下一集等
 */
class ShortDramaManager(
    private val activity: Activity
) {
    
    companion object {
        private const val TAG = "ShortDramaManager"
    }
    
    private var isShortDrama = false
    private var currentEpisodeIndex = 0
    private var episodeList = mutableListOf<ShortDramaEpisode>()
    private var autoPlayNext = true  // 默认自动播放下一集
    
    /**
     * 初始化短剧模式
     */
    fun initShortDramaMode(
        isShortDrama: Boolean,
        episodesJson: String?,
        startIndex: Int
    ) {
        this.isShortDrama = isShortDrama
        this.currentEpisodeIndex = startIndex
        
        Log.d(TAG, "初始化短剧模式: isShortDrama=$isShortDrama, startIndex=$startIndex")
        Log.d(TAG, "episodesJson: $episodesJson")
        
        if (isShortDrama && !episodesJson.isNullOrEmpty()) {
            parseEpisodes(episodesJson)
            Log.d(TAG, "短剧模式已启用，共 ${episodeList.size} 集，当前第 ${startIndex + 1} 集")
            
            // 打印所有剧集信息
            episodeList.forEachIndexed { index, episode ->
                Log.d(TAG, "剧集[$index]: name=${episode.name}, videoId=${episode.videoId}, url=${episode.url}")
            }
        } else {
            Log.w(TAG, "短剧模式未启用或剧集列表为空")
        }
    }
    
    /**
     * 解析剧集列表
     */
    private fun parseEpisodes(json: String) {
        try {
            val jsonArray = org.json.JSONArray(json)
            episodeList.clear()
            
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                episodeList.add(
                    ShortDramaEpisode(
                        name = obj.getString("name"),
                        videoId = obj.optString("videoId", ""),
                        url = obj.optString("url", "")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析剧集列表失败: ${e.message}", e)
        }
    }
    
    /**
     * 检查视频尺寸，判断是否应该竖屏全屏
     */
    fun checkVideoOrientation(videoWidth: Int, videoHeight: Int): Boolean {
        if (!isShortDrama) return false
        
        // 如果视频高度大于宽度，说明是竖屏视频
        val isPortraitVideo = videoHeight > videoWidth
        
        if (isPortraitVideo) {
            Log.d(TAG, "检测到竖屏视频 (${videoWidth}x${videoHeight})，切换到竖屏全屏模式")
            setPortraitFullscreen()
            return true
        }
        
        return false
    }
    
    /**
     * 设置竖屏全屏
     */
    private fun setPortraitFullscreen() {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        
        // 隐藏系统UI
        activity.window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }
    
    /**
     * 播放完成时自动播放下一集
     */
    fun onPlaybackEnded(onPlayNext: (ShortDramaEpisode) -> Unit) {
        if (!isShortDrama || !autoPlayNext) {
            Log.d(TAG, "不自动播放: isShortDrama=$isShortDrama, autoPlayNext=$autoPlayNext")
            return
        }
        
        if (hasNextEpisode()) {
            currentEpisodeIndex++
            val nextEpisode = episodeList[currentEpisodeIndex]
            
            Log.d(TAG, "自动播放下一集: ${nextEpisode.name}, videoId=${nextEpisode.videoId}, url=${nextEpisode.url}")
            Toast.makeText(activity, "正在播放 ${nextEpisode.name}", Toast.LENGTH_SHORT).show()
            
            // 直接回调，让调用方处理URL获取逻辑
            onPlayNext(nextEpisode)
        } else {
            Log.d(TAG, "已经是最后一集")
            Toast.makeText(activity, "已播放完所有剧集", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 是否有下一集
     */
    fun hasNextEpisode(): Boolean {
        return currentEpisodeIndex < episodeList.size - 1
    }
    
    /**
     * 是否有上一集
     */
    fun hasPreviousEpisode(): Boolean {
        return currentEpisodeIndex > 0
    }
    
    /**
     * 播放下一集
     */
    fun playNextEpisode(onPlayNext: (ShortDramaEpisode) -> Unit) {
        if (hasNextEpisode()) {
            currentEpisodeIndex++
            val nextEpisode = episodeList[currentEpisodeIndex]
            
            Log.d(TAG, "手动播放下一集: ${nextEpisode.name}, videoId=${nextEpisode.videoId}")
            // 直接回调，让调用方处理URL获取逻辑
            onPlayNext(nextEpisode)
        }
    }
    
    /**
     * 播放上一集
     */
    fun playPreviousEpisode(onPlayPrevious: (ShortDramaEpisode) -> Unit) {
        if (hasPreviousEpisode()) {
            currentEpisodeIndex--
            val previousEpisode = episodeList[currentEpisodeIndex]
            
            Log.d(TAG, "播放上一集: ${previousEpisode.name}, videoId=${previousEpisode.videoId}")
            // 直接回调，让调用方处理URL获取逻辑
            onPlayPrevious(previousEpisode)
        }
    }
    
    /**
     * 切换自动播放下一集
     */
    fun toggleAutoPlayNext(): Boolean {
        autoPlayNext = !autoPlayNext
        return autoPlayNext
    }
    
    /**
     * 获取当前集数信息
     */
    fun getCurrentEpisodeInfo(): String {
        if (!isShortDrama || episodeList.isEmpty()) return ""
        return "${currentEpisodeIndex + 1}/${episodeList.size}"
    }
    
    /**
     * 获取当前剧集索引
     */
    fun getCurrentEpisodeIndex(): Int {
        return currentEpisodeIndex
    }
    
    /**
     * 获取指定索引的剧集（不改变当前索引）
     */
    fun getEpisodeAt(index: Int): ShortDramaEpisode? {
        return if (index >= 0 && index < episodeList.size) {
            episodeList[index]
        } else {
            null
        }
    }
    
    /**
     * 获取下一集信息（不改变当前索引）
     */
    fun getNextEpisode(): ShortDramaEpisode? {
        return if (hasNextEpisode()) {
            episodeList[currentEpisodeIndex + 1]
        } else {
            null
        }
    }
    
    /**
     * 移动到下一集（更新索引）
     */
    fun moveToNextEpisode() {
        if (hasNextEpisode()) {
            currentEpisodeIndex++
        }
    }
    
    /**
     * 获取当前剧集
     */
    fun getCurrentEpisode(): ShortDramaEpisode? {
        return if (currentEpisodeIndex >= 0 && currentEpisodeIndex < episodeList.size) {
            episodeList[currentEpisodeIndex]
        } else {
            null
        }
    }
}

/**
 * 短剧剧集数据类
 */
data class ShortDramaEpisode(
    val name: String,
    val videoId: String,
    var url: String
)
