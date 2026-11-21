package com.tv.ball.presentation.player

import android.content.Context
import android.content.SharedPreferences

/**
 * 片头片尾跳过管理器
 *
 * 「我的 → 播放设置」里三个开关的持久化与判定都在这里：
 * 片头跳过提示、片尾自动跳过、跳过片头广告。
 */
class SkipIntroManager(context: Context) {

    /** 播放设置（「我的 → 播放设置」写入的那一份）也放在一起判定。 */
    private val appContext: Context = context.applicationContext

    private val settings by lazy {
        runCatching { com.tv.ball.data.local.UserPreferences(appContext) }.getOrNull()
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences("skip_intro", Context.MODE_PRIVATE)
    
    companion object {
        private const val KEY_SKIP_INTRO_ENABLED = "skip_intro_enabled"
        private const val KEY_SKIP_OUTRO_ENABLED = "skip_outro_enabled"
        private const val KEY_INTRO_DURATION = "intro_duration"
        private const val KEY_OUTRO_DURATION = "outro_duration"
        
        // 默认片头片尾时长（毫秒）
        private const val DEFAULT_INTRO_DURATION = 90000L  // 90秒
        private const val DEFAULT_OUTRO_DURATION = 60000L  // 60秒
    }
    
    /**
     * 是否启用片头跳过
     */
    fun isSkipIntroEnabled(): Boolean {
        return prefs.getBoolean(KEY_SKIP_INTRO_ENABLED, true)
    }
    
    /**
     * 设置片头跳过
     */
    fun setSkipIntroEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SKIP_INTRO_ENABLED, enabled).apply()
    }
    
    /**
     * 是否启用片尾跳过
     */
    fun isSkipOutroEnabled(): Boolean {
        return prefs.getBoolean(KEY_SKIP_OUTRO_ENABLED, false)
    }
    
    /**
     * 设置片尾跳过
     */
    fun setSkipOutroEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SKIP_OUTRO_ENABLED, enabled).apply()
    }
    
    /**
     * 获取片头时长
     */
    fun getIntroDuration(): Long {
        return prefs.getLong(KEY_INTRO_DURATION, DEFAULT_INTRO_DURATION)
    }
    
    /**
     * 设置片头时长
     */
    fun setIntroDuration(duration: Long) {
        prefs.edit().putLong(KEY_INTRO_DURATION, duration).apply()
    }
    
    /**
     * 获取片尾时长
     */
    fun getOutroDuration(): Long {
        return prefs.getLong(KEY_OUTRO_DURATION, DEFAULT_OUTRO_DURATION)
    }
    
    /**
     * 设置片尾时长
     */
    fun setOutroDuration(duration: Long) {
        prefs.edit().putLong(KEY_OUTRO_DURATION, duration).apply()
    }
    
    /**
     * 检查是否应该跳过片头
     * @param currentPosition 当前播放位置
     * @param hasWatched 是否已经观看过（用于判断是否首次播放）
     */
    fun shouldSkipIntro(currentPosition: Long, hasWatched: Boolean): Boolean {
        if (!isSkipIntroEnabled()) return false
        if (hasWatched) return false // 已经看过，不再跳过
        
        val introDuration = getIntroDuration()
        return currentPosition < introDuration
    }
    
    /**
     * 检查是否应该跳过片尾
     * @param currentPosition 当前播放位置
     * @param duration 总时长
     *
     * 除了「自动跳过片尾」这个开关，还要求「播放设置 → 自动连播下一集」打开、
     * 且播放结束行为是「自动下一集」：片尾跳过的动作就是直接进下一集，
     * 结束行为选了停止 / 循环的时候不该被它偷偷推着往下播。
     */
    fun shouldSkipOutro(currentPosition: Long, duration: Long): Boolean {
        if (!isSkipOutroEnabled()) return false
        if (duration <= 0) return false
        if (!canAdvanceToNextEpisode()) return false
        
        val outroDuration = getOutroDuration()
        val outroStartPosition = duration - outroDuration
        
        return currentPosition >= outroStartPosition
    }

    /**
     * 是否允许自动进入下一集。
     *
     * 读不到设置（老数据 / 单元测试）按允许处理，保持原来的播放行为。
     */
    private fun canAdvanceToNextEpisode(): Boolean {
        val s = settings ?: return true
        return s.isPlaybackAutoNext() &&
            s.getPlaybackEndedBehavior() ==
            com.tv.ball.data.local.UserPreferences.ENDED_BEHAVIOR_NEXT
    }
    
    /**
     * 获取跳过片头后的位置
     */
    fun getSkipIntroPosition(): Long {
        return getIntroDuration()
    }

    // ===== 跳过片头广告（「播放设置」里独立的一组参数，和片头时长分开）=====

    /** 是否开启跳过片头广告。 */
    fun isAdSkipEnabled(): Boolean = settings?.isPlaybackAdSkipEnabled() ?: false

    /** 片头广告时长（秒）。 */
    fun getAdSkipSeconds(): Int = settings?.getPlaybackAdSkipSeconds() ?: 15

    /** 开播后要快进到的位置（毫秒）。 */
    fun getAdSkipPositionMs(): Long = getAdSkipSeconds() * 1000L

    /**
     * 检查开播这一刻是不是该跳过片头广告。
     *
     * 只在「还没跳过一次」且当前仍在广告区间时才返回 true，
     * 调用方跳完把 hasSkippedAd 置 true 即可，不会出现反复 seek 抖动。
     */
    fun shouldSkipAd(currentPosition: Long, hasSkippedAd: Boolean): Boolean {
        if (hasSkippedAd) return false
        if (!isAdSkipEnabled()) return false
        val adPosition = getAdSkipPositionMs()
        if (adPosition <= 0) return false
        return currentPosition < adPosition
    }
}
