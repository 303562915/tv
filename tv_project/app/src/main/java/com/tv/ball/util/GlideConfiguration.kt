package com.tv.ball.util

import android.content.Context
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.engine.executor.GlideExecutor
import com.bumptech.glide.module.AppGlideModule

/**
 * Glide配置 - 优化多线程加载性能
 */
@GlideModule
class GlideConfiguration : AppGlideModule() {
    
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        // 配置磁盘缓存线程池 - 增加并发数
        builder.setDiskCacheExecutor(
            GlideExecutor.newDiskCacheBuilder()
                .setThreadCount(4) // 磁盘缓存线程数：4
                .setName("glide-disk-cache")
                .build()
        )
        
        // 配置源数据加载线程池 - 增加并发数
        builder.setSourceExecutor(
            GlideExecutor.newSourceBuilder()
                .setThreadCount(6) // 网络加载线程数：6（支持更多并发）
                .setName("glide-source")
                .build()
        )
        
        // 配置动画线程池
        builder.setAnimationExecutor(
            GlideExecutor.newAnimationBuilder()
                .setThreadCount(2)
                .setName("glide-animation")
                .build()
        )
        
        android.util.Log.d("GlideConfiguration", "Glide多线程配置完成: 磁盘缓存4线程, 网络加载6线程")
    }
    
    override fun isManifestParsingEnabled(): Boolean {
        // 禁用清单解析，提升初始化速度
        return false
    }
}
