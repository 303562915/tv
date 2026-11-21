package com.tv.ball.domain.repository

import com.tv.ball.data.local.LocalDataSource




class ConfigRepository constructor(
    private val localDataSource: LocalDataSource
) {
    
    companion object {
        private const val KEY_VIDEO_QUALITY = "video_quality"
        private const val KEY_AUTO_PLAY_NEXT = "auto_play_next"
        private const val KEY_DANMAKU_ENABLED = "danmaku_enabled"
        private const val KEY_GESTURE_CONTROL_ENABLED = "gesture_control_enabled"
        private const val KEY_PLAYBACK_SPEED = "playback_speed"
        private const val KEY_THEME_COLOR = "theme_color"
        private const val KEY_NIGHT_MODE = "night_mode"
        private const val KEY_SWIPE_DELETE_DIRECT = "swipe_delete_direct"

        const val QUALITY_HIGH = "high"
        const val QUALITY_MEDIUM = "medium"
        const val QUALITY_LOW = "low"

        const val DEFAULT_THEME_COLOR = 0xFF2196F3.toInt() // 默认蓝色
        
        const val NIGHT_MODE_FOLLOW_SYSTEM = 0
        const val NIGHT_MODE_OFF = 1
        const val NIGHT_MODE_ON = 2
    }
    
    fun getVideoQuality(): String {
        return localDataSource.getString(KEY_VIDEO_QUALITY, QUALITY_HIGH)
    }
    
    fun setVideoQuality(quality: String) {
        localDataSource.putString(KEY_VIDEO_QUALITY, quality)
    }
    
    fun isAutoPlayNextEnabled(): Boolean {
        return localDataSource.getBoolean(KEY_AUTO_PLAY_NEXT, true)
    }
    
    fun setAutoPlayNext(enabled: Boolean) {
        localDataSource.putBoolean(KEY_AUTO_PLAY_NEXT, enabled)
    }
    
    fun isDanmakuEnabled(): Boolean {
        return localDataSource.getBoolean(KEY_DANMAKU_ENABLED, true)
    }
    
    fun setDanmakuEnabled(enabled: Boolean) {
        localDataSource.putBoolean(KEY_DANMAKU_ENABLED, enabled)
    }
    
    fun isGestureControlEnabled(): Boolean {
        return localDataSource.getBoolean(KEY_GESTURE_CONTROL_ENABLED, true)
    }
    
    fun setGestureControlEnabled(enabled: Boolean) {
        localDataSource.putBoolean(KEY_GESTURE_CONTROL_ENABLED, enabled)
    }

    /**
     * 观看历史左滑行为：true = 滑动即删除，false = 滑动只露出删除按钮（默认）。
     */
    fun isSwipeDeleteDirect(): Boolean {
        return localDataSource.getBoolean(KEY_SWIPE_DELETE_DIRECT, false)
    }

    fun setSwipeDeleteDirect(enabled: Boolean) {
        localDataSource.putBoolean(KEY_SWIPE_DELETE_DIRECT, enabled)
    }
    
    fun getPlaybackSpeed(): Float {
        return localDataSource.getString("playback_speed", "1.0").toFloatOrNull() ?: 1.0f
    }
    
    fun setPlaybackSpeed(speed: Float) {
        localDataSource.putString("playback_speed", speed.toString())
    }

    fun getThemeColor(): Int {
        return localDataSource.getInt(KEY_THEME_COLOR, DEFAULT_THEME_COLOR)
    }

    fun setThemeColor(color: Int) {
        localDataSource.putInt(KEY_THEME_COLOR, color)
    }

    fun getNightMode(): Int {
        return localDataSource.getInt(KEY_NIGHT_MODE, NIGHT_MODE_FOLLOW_SYSTEM)
    }

    fun setNightMode(mode: Int) {
        localDataSource.putInt(KEY_NIGHT_MODE, mode)
    }
}
