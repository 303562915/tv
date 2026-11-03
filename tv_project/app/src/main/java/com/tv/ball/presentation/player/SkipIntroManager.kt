package com.tv.ball.presentation.player

import android.content.Context
import android.content.SharedPreferences

/**
 * 片头片尾跳过管理器
 */
class SkipIntroManager(context: Context) {
    
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
     */
    fun shouldSkipOutro(currentPosition: Long, duration: Long): Boolean {
        if (!isSkipOutroEnabled()) return false
        if (duration <= 0) return false
        
        val outroDuration = getOutroDuration()
        val outroStartPosition = duration - outroDuration
        
        return currentPosition >= outroStartPosition
    }
    
    /**
     * 获取跳过片头后的位置
     */
    fun getSkipIntroPosition(): Long {
        return getIntroDuration()
    }
}
