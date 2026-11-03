package com.tv.ball.presentation.player

import android.content.Context
import android.content.SharedPreferences
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout

/**
 * 画面比例管理器
 */
class AspectRatioManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("aspect_ratio", Context.MODE_PRIVATE)
    
    companion object {
        private const val KEY_ASPECT_RATIO_MODE = "aspect_ratio_mode"
    }
    
    enum class AspectRatioMode(val displayName: String, val resizeMode: Int) {
        FIT("自适应", AspectRatioFrameLayout.RESIZE_MODE_FIT),
        FIXED_WIDTH("16:9", AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH),
        FIXED_HEIGHT("4:3", AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT),
        FILL("铺满屏幕", AspectRatioFrameLayout.RESIZE_MODE_FILL),
        ZOOM("放大裁剪", AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
    }
    
    /**
     * 获取当前画面比例模式
     */
    fun getCurrentMode(): AspectRatioMode {
        val modeOrdinal = prefs.getInt(KEY_ASPECT_RATIO_MODE, AspectRatioMode.FIT.ordinal)
        return AspectRatioMode.values().getOrNull(modeOrdinal) ?: AspectRatioMode.FIT
    }
    
    /**
     * 设置画面比例模式
     */
    fun setMode(mode: AspectRatioMode) {
        prefs.edit().putInt(KEY_ASPECT_RATIO_MODE, mode.ordinal).apply()
    }
    
    /**
     * 获取所有模式
     */
    fun getAllModes(): List<AspectRatioMode> {
        return AspectRatioMode.values().toList()
    }
    
    /**
     * 切换到下一个模式
     */
    fun nextMode(): AspectRatioMode {
        val currentMode = getCurrentMode()
        val modes = AspectRatioMode.values()
        val nextIndex = (currentMode.ordinal + 1) % modes.size
        val nextMode = modes[nextIndex]
        setMode(nextMode)
        return nextMode
    }
}
