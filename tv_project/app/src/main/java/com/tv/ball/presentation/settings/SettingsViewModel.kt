package com.tv.ball.presentation.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tv.ball.di.Dependencies
import com.tv.ball.feature.f057.F057Engine
import com.tv.ball.feature.f081.F081Engine
import com.tv.ball.presentation.player.AspectRatioManager
import com.tv.ball.presentation.player.SkipIntroManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 播放设置页的 ViewModel。
 *
 * 页面本身是「读一遍 → 改 → 立刻落盘」，不需要异步数据流，所以这里只保留一件跨界面重建
 * 也不该丢的事：把本页所有设置一把恢复成出厂值。
 *
 * 原来那一套「最近播放 / 继续播放」的代码已经删掉：对应界面早在旧版就被整段注释掉，
 * 数据流没有任何界面在收，留着只是死代码。
 */
class SettingsViewModel : ViewModel() {

    private val prefs by lazy { Dependencies.userPreferences }

    /**
     * 恢复默认播放设置。
     *
     * 覆盖本页四组设置的全部落点：
     * · [com.tv.ball.data.local.UserPreferences] 里的 playback_* 键；
     * · F081 播放默认值中心（倍速 / 比例 / 自动连播 / 快进 / 长按 / 字幕）；
     * · F057 缓冲档位（回到均衡）；
     * · 片头片尾跳过（skip_intro）；
     * · 播放页的 `player_settings`（长按锁定倍速）；
     * · 画面比例（回到自适应）。
     *
     * 只动播放相关配置：登录态、会员信息、观看记录一律不碰。
     */
    fun resetPlaybackSettings() {
        val context = Dependencies.context

        // 1. 本页新加的播放设置键
        prefs.resetPlaybackSettings()

        // 2. 播放默认值中心（倍速等）回到出厂组合
        runCatching { F081Engine.save(context, F081Engine.defaults()) }

        // 3. 缓冲档位回到均衡
        runCatching { F057Engine.reset(context) }

        // 4. 片头片尾：默认显示片头跳过按钮、不自动跳片尾，时长回 90s / 60s
        runCatching {
            val skip = SkipIntroManager(context)
            skip.setSkipIntroEnabled(true)
            skip.setSkipOutroEnabled(false)
            skip.setIntroDuration(90_000L)
            skip.setOutroDuration(60_000L)
        }

        // 5. 播放页自管的长按锁定开关
        runCatching {
            context.getSharedPreferences("player_settings", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("long_press_lock_enabled", true)
                .apply()
        }

        // 6. 画面比例回到自适应
        runCatching {
            AspectRatioManager(context).setMode(AspectRatioManager.AspectRatioMode.FIT)
        }
    }

    // ============================================================
    // 缓存：统计 / 清理
    // ============================================================

    /**
     * 缓存占用快照。
     *
     * @param totalBytes 缓存合计：`cacheDir`（含播放缓存 `exoplayer_cache`）加 `externalCacheDir`
     * @param playerBytes ExoPlayer 播放缓存目录自己的体积
     *
     * [playerBytes] 是 [totalBytes] 的**子集**（播放缓存就落在 `cacheDir/exoplayer_cache`），
     * 显示时只当拆分看，不能相加——加上去就把这部分算了两遍。
     */
    data class CacheUsage(val totalBytes: Long, val playerBytes: Long) {

        companion object {
            /** 字节数 → 人能读的大小；统计与 Toast 用同一套口径，不让两处显示对不上。 */
            fun formatBytes(bytes: Long): String = when {
                bytes < 1024L -> "$bytes B"
                bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
                bytes < 1024L * 1024L * 1024L ->
                    String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
                else ->
                    String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            }
        }
    }

    /** 清理结果：清前 / 清后体积；[freedBytes] 是两者之差，负数一律归零（文件被系统占着删不掉时会出现）。 */
    data class CacheClearResult(val beforeBytes: Long, val afterBytes: Long) {
        val freedBytes: Long get() = (beforeBytes - afterBytes).coerceAtLeast(0L)
    }

    /** 正在清理的标记：连点时第二次直接丢掉，不重复扫盘、不重复弹 Toast。 */
    private var cacheClearing = false

    /**
     * 后台统计缓存占用，结果回主线程交给 [onReady]。
     *
     * 统计（`File.walkTopDown().sumOf { length }`）放在 [Dispatchers.IO]：
     * 播放缓存上限是 2 GB，几万个条目在弱 CPU 的盒子上能扫上百毫秒，不能压在主线程。
     */
    fun measureCache(onReady: (CacheUsage) -> Unit) {
        val context = Dependencies.context
        viewModelScope.launch {
            val usage = withContext(Dispatchers.IO) { measure(context) }
            onReady(usage)
        }
    }

    /**
     * 清缓存：只动缓存目录里的**内容**，收藏 / 历史 / 数据库 / 已下载的文件都不碰。
     *
     * 顺序是先清 ExoPlayer 播放缓存（它可能被播放器占着句柄），再清 `cacheDir` 其余内容与
     * `externalCacheDir`；删不掉的条目会留下，所以清完重新统计一次体积，
     * 释放量按「清前实测 − 清后实测」上报，不按删了多少字节谎报。
     */
    fun clearCache(onCleared: (CacheClearResult) -> Unit) {
        if (cacheClearing) return
        cacheClearing = true
        val context = Dependencies.context
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val before = measure(context)
                deleteChildren(File(context.cacheDir, PLAYER_CACHE_DIR))
                deleteChildren(context.cacheDir)
                context.externalCacheDir?.let { deleteChildren(it) }
                CacheClearResult(before.totalBytes, measure(context).totalBytes)
            }
            cacheClearing = false
            onCleared(result)
        }
    }

    private fun measure(context: Context): CacheUsage {
        val cacheDir = context.cacheDir
        return CacheUsage(
            totalBytes = sizeOf(cacheDir) + sizeOf(context.externalCacheDir),
            playerBytes = sizeOf(File(cacheDir, PLAYER_CACHE_DIR))
        )
    }

    /** 目录总字节数；目录不存在、被系统保护读不了都按 0 算，不让统计把页面拖崩。 */
    private fun sizeOf(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        return runCatching {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }

    /** 删掉目录里的**内容**，保留目录本身；单个条目失败不影响其余条目。 */
    private fun deleteChildren(dir: File?) {
        val children = dir?.listFiles() ?: return
        children.forEach { child ->
            runCatching {
                if (child.isDirectory) child.deleteRecursively() else child.delete()
            }
        }
    }

    private companion object {
        /** ExoPlayer 播放缓存目录名，与 [com.tv.ball.presentation.player.ExoPlayerManager] 里建缓存那句一致。 */
        const val PLAYER_CACHE_DIR = "exoplayer_cache"
    }
}
