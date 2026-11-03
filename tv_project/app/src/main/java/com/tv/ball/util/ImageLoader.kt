package com.tv.ball.util

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 图片加载工具类 - 支持多线程预加载和优先级管理
 */
object ImageLoader {
    
    // 预加载缓存，避免重复预加载
    private val preloadCache = ConcurrentHashMap<String, Boolean>()
    
    /**
     * 加载图片到ImageView
     * @param priority 加载优先级，默认NORMAL
     */
    fun load(
        context: Context,
        url: String?,
        imageView: ImageView,
        placeholder: Int,
        priority: Priority = Priority.NORMAL
    ) {
        // 如果URL为空，清空ImageView，不显示占位图
        if (url.isNullOrBlank()) {
            imageView.setImageDrawable(null)
            return
        }

        if (url != null) {
            ImageLoadMonitor.onLoadStart(url)
        }

        Glide.with(context)
            .load(url)
            .override(400, 600) // 添加缩略图尺寸，加快加载
            .centerCrop()
            .diskCacheStrategy(DiskCacheStrategy.ALL) // 缓存原图和转换后的图
            .priority(priority)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    // 获取详细错误信息
                    val errorDetail = buildString {
                        append(e?.message ?: "未知错误")
                        e?.rootCauses?.firstOrNull()?.let { cause ->
                            append(" | ")
                            append(cause.javaClass.simpleName)
                            append(": ")
                            append(cause.message)
                        }
                    }
                    url?.let { ImageLoadMonitor.onLoadFailure(it, errorDetail) }
                    // 加载失败时清空ImageView，不显示占位图
                    imageView.setImageDrawable(null)
                    return true
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    url?.let { ImageLoadMonitor.onLoadSuccess(it) }
                    return false
                }
            })
            .into(imageView)
    }
    
    /**
     * 预加载图片列表（多线程并发）
     * @param urls 图片URL列表
     * @param maxConcurrent 最大并发数，默认4
     */
    fun preloadImages(
        context: Context,
        urls: List<String>,
        maxConcurrent: Int = 4,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ) {
        if (urls.isEmpty()) return
        
        CoroutineScope(Dispatchers.IO).launch {
            // 过滤已预加载的URL
            val urlsToLoad = urls.filter { !preloadCache.containsKey(it) }
            if (urlsToLoad.isEmpty()) return@launch
            
            android.util.Log.d("ImageLoader", "开始预加载 ${urlsToLoad.size} 张图片，并发数: $maxConcurrent")
            ImageLoadMonitor.onPreload(urlsToLoad.size)
            
            // 分批并发加载
            urlsToLoad.chunked(maxConcurrent).forEachIndexed { batchIndex, batch ->
                val jobs = batch.map { url ->
                    launch {
                        try {
                            preloadSingle(context, url)
                            preloadCache[url] = true
                            
                            val current = batchIndex * maxConcurrent + batch.indexOf(url) + 1
                            withContext(Dispatchers.Main) {
                                onProgress?.invoke(current, urlsToLoad.size)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("ImageLoader", "预加载失败: $url", e)
                        }
                    }
                }
                jobs.forEach { it.join() }
            }
            
            android.util.Log.d("ImageLoader", "预加载完成")
        }
    }
    
    /**
     * 预加载单张图片
     */
    private suspend fun preloadSingle(context: Context, url: String) {
        withContext(Dispatchers.Main) {
            Glide.with(context)
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .priority(Priority.LOW) // 预加载使用低优先级
                .preload()
        }
    }
    
    /**
     * 清除预加载缓存记录
     */
    fun clearPreloadCache() {
        preloadCache.clear()
    }
    
    /**
     * 加载logo图片（失败时自动隐藏）
     */
    fun loadLogo(
        context: Context,
        url: String?,
        imageView: ImageView
    ) {
        if (url.isNullOrBlank()) {
            imageView.visibility = android.view.View.GONE
            return
        }

        imageView.visibility = android.view.View.VISIBLE

        Glide.with(context)
            .load(url)
            .override(200, 200)
            .centerCrop()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .priority(Priority.LOW)
            .timeout(5000) // 5秒超时
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    val errorDetail = buildString {
                        append(e?.message ?: "未知错误")
                        e?.rootCauses?.firstOrNull()?.let { cause ->
                            append(" | ")
                            append(cause.javaClass.simpleName)
                            append(": ")
                            append(cause.message)
                        }
                    }
                    url.let { ImageLoadMonitor.onLoadFailure(it, errorDetail) }
                    // Logo加载失败时隐藏ImageView
                    imageView.visibility = android.view.View.GONE
                    return true
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    url.let { ImageLoadMonitor.onLoadSuccess(it) }
                    return false
                }
            })
            .into(imageView)
    }

    /**
     * 加载轮播图（高优先级）
     */
    fun loadBanner(
        context: Context,
        url: String,
        imageView: ImageView,
        placeholder: Int,
        onSuccess: (() -> Unit)? = null,
        onError: (() -> Unit)? = null
    ) {
        // 如果URL为空或无效，清空ImageView，不显示占位图
        if (url.isBlank()) {
            android.util.Log.d("ImageLoader", "轮播图URL为空，显示空白")
            imageView.setImageDrawable(null)
            onError?.invoke()
            return
        }
        
        Glide.with(context)
            .load(url)
            .override(800, 400)
            .centerCrop()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .priority(Priority.HIGH) // 轮播图使用高优先级
            .timeout(10000) // 设置10秒超时
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    android.util.Log.e("ImageLoader", "轮播图加载失败: $url, 错误: ${e?.message}")
                    e?.logRootCauses("BannerAdapter")
                    // 加载失败时清空ImageView，不显示占位图
                    imageView.setImageDrawable(null)
                    onError?.invoke()
                    return true
                }
                
                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    android.util.Log.d("ImageLoader", "轮播图加载成功: $url")
                    onSuccess?.invoke()
                    return false
                }
            })
            .into(imageView)
    }
}
