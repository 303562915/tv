package com.tv.ball.domain.parser

import android.util.LruCache

/**
 * 视频URL缓存
 * 用于缓存已解析的视频链接，避免重复解析
 */
object VideoUrlCache {
    private const val MAX_CACHE_SIZE = 50 // 最多缓存50个视频链接
    
    private val cache = LruCache<String, CachedVideoUrl>(MAX_CACHE_SIZE)
    
    data class CachedVideoUrl(
        val parsedUrl: String,
        val headers: Map<String, String>? = null,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * 保存解析后的视频链接
     */
    fun put(sourceUrl: String, parsedUrl: String, headers: Map<String, String>? = null) {
        cache.put(sourceUrl, CachedVideoUrl(parsedUrl, headers))
        android.util.Log.d("VideoUrlCache", "缓存视频链接: $sourceUrl -> $parsedUrl")
    }
    
    /**
     * 获取缓存的视频链接
     * @param maxAge 最大缓存时间（毫秒），默认30分钟
     */
    fun get(sourceUrl: String, maxAge: Long = 30 * 60 * 1000): CachedVideoUrl? {
        val cached = cache.get(sourceUrl) ?: return null
        
        // 检查缓存是否过期
        val age = System.currentTimeMillis() - cached.timestamp
        if (age > maxAge) {
            android.util.Log.d("VideoUrlCache", "缓存已过期: $sourceUrl (${age}ms)")
            cache.remove(sourceUrl)
            return null
        }
        
        android.util.Log.d("VideoUrlCache", "使用缓存链接: $sourceUrl -> ${cached.parsedUrl}")
        return cached
    }
    
    /**
     * 清除指定URL的缓存
     */
    fun remove(sourceUrl: String) {
        cache.remove(sourceUrl)
        android.util.Log.d("VideoUrlCache", "清除缓存: $sourceUrl")
    }
    
    /**
     * 清除所有缓存
     */
    fun clear() {
        cache.evictAll()
        android.util.Log.d("VideoUrlCache", "清除所有缓存")
    }
    
    /**
     * 获取缓存大小
     */
    fun size(): Int = cache.size()
}
