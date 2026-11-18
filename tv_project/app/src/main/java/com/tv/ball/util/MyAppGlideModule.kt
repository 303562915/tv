package com.tv.ball.util

import android.content.Context
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.module.AppGlideModule

@GlideModule
class MyAppGlideModule : AppGlideModule() {

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        // 增加内存缓存大小到 50MB
        val memoryCacheSizeBytes = 1024 * 1024 * 50L
        builder.setMemoryCache(LruResourceCache(memoryCacheSizeBytes))

        // 增加磁盘缓存大小到 500MB
        val diskCacheSizeBytes = 1024 * 1024 * 500L
        builder.setDiskCache(InternalCacheDiskCacheFactory(context, diskCacheSizeBytes))
    }

    override fun isManifestParsingEnabled(): Boolean {
        return false
    }
}
