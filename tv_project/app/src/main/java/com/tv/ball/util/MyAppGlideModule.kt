package com.tv.ball.util

import android.content.Context
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.load.engine.executor.GlideExecutor
import com.bumptech.glide.module.AppGlideModule

/**
 * Glide 全局配置
 *
 * 注意：Glide 4.x 规定一个应用只能有一个 AppGlideModule，
 * 因此缓存与线程池配置统一在这里完成。
 */
@GlideModule
class MyAppGlideModule : AppGlideModule() {

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        // 内存缓存 50MB
        builder.setMemoryCache(LruResourceCache(1024 * 1024 * 50L))

        // 磁盘缓存 500MB
        builder.setDiskCache(InternalCacheDiskCacheFactory(context, 1024 * 1024 * 500L))

        // 磁盘缓存线程池
        builder.setDiskCacheExecutor(
            GlideExecutor.newDiskCacheBuilder()
                .setThreadCount(4)
                .setName("glide-disk-cache")
                .build()
        )

        // 网络加载线程池
        builder.setSourceExecutor(
            GlideExecutor.newSourceBuilder()
                .setThreadCount(6)
                .setName("glide-source")
                .build()
        )

        // 动画线程池
        builder.setAnimationExecutor(
            GlideExecutor.newAnimationBuilder()
                .setThreadCount(2)
                .setName("glide-animation")
                .build()
        )
    }

    override fun isManifestParsingEnabled(): Boolean = false
}
