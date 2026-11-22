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
     * @param placeholder 加载中/失败时显示的占位图资源 id；传 0 表示「明确不要占位图」，
     *   此时加载失败会清空 ImageView（见 onLoadFailed 里的说明）
     * @param priority 加载优先级，默认NORMAL
     * @param width / height 解码尺寸（override）。放在 priority 之后且带默认值，
     *   仓库里已有的调用点不用改；需要贴合控件比例时由调用方传（例：3:4 封面传 360x480）
     */
    fun load(
        context: Context,
        url: String?,
        imageView: ImageView,
        placeholder: Int,
        priority: Priority = Priority.NORMAL,
        width: Int = 400,
        height: Int = 600
    ) {
        // URL 为空时：有占位图就画占位图，没有（placeholder = 0）才清空。
        // 原来无条件清空，等于把 placeholder 参数彻底废掉，短剧卡片加载中就是一片白。
        if (url.isNullOrBlank()) {
            if (placeholder != 0) {
                imageView.setImageResource(placeholder)
            } else {
                imageView.setImageDrawable(null)
            }
            return
        }

        if (url != null) {
            ImageLoadMonitor.onLoadStart(url)
        }

        Glide.with(context)
            .load(url)
            .override(width, height) // 解码尺寸由调用方给，避免和控件比例不一致被 centerCrop 裁掉
            .placeholder(placeholder) // 加载中先画占位图，别让卡片空着
            .error(placeholder) // 失败时也画占位图（0 = 不设，Glide 内部按 >0 判断，安全）
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
                    // 分两种情况，别一刀切清空：
                    // 1) 有占位图 → 返回 false 让 Glide 自己把 error 占位图（= placeholder）画上去；
                    //    这里若再 setImageDrawable(null)，等于占位图刚设就被抹掉，卡片还是白块。
                    // 2) placeholder = 0 → 调用方明确不要占位图（VideoListAdapter 传的就是 0，
                    //    BannerAdapter 走 loadBanner 同样传 0）。
                    //    必须清空：RecyclerView 复用 ViewHolder 时，不清就会残留上一部剧的封面。
                    if (placeholder != 0) {
                        return false
                    }
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
