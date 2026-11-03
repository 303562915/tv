package com.tv.ball.utils

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat

object ThemeUtils {

    enum class ThemeColor(val colorInt: Int, val colorName: String) {
        BLUE(0xFF2196F3.toInt(), "蓝色"),
        RED(0xFFF44336.toInt(), "红色"),
        GREEN(0xFF4CAF50.toInt(), "绿色"),
        PURPLE(0xFF9C27B0.toInt(), "紫色"),
        ORANGE(0xFFFF9800.toInt(), "橙色"),
        TEAL(0xFF009688.toInt(), "青色"),
        PINK(0xFFE91E63.toInt(), "粉色"),
        INDIGO(0xFF3F51B5.toInt(), "靛蓝"),
        CYAN(0xFF00BCD4.toInt(), "青蓝"),
        LIME(0xFFCDDC39.toInt(), "柠檬绿"),
        AMBER(0xFFFFC107.toInt(), "琥珀"),
        DEEP_PURPLE(0xFF673AB7.toInt(), "深紫");

        companion object {
            fun fromColorInt(colorInt: Int): ThemeColor {
                return values().find { it.colorInt == colorInt } ?: BLUE
            }
        }
    }

    /**
     * 应用夜间模式
     */
    fun applyNightMode(mode: Int) {
        val nightMode = when (mode) {
            0 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    /**
     * 应用主题色到 Activity
     */
    fun applyThemeColor(activity: Activity, color: Int) {
        // 设置状态栏颜色
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            activity.window.apply {
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                statusBarColor = color

                // 根据颜色亮度设置状态栏图标颜色
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val isLightColor = isColorLight(color)
                    decorView.systemUiVisibility = if (isLightColor) {
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    } else {
                        0
                    }
                }
            }
        }

        // 设置导航栏颜色（可选）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            activity.window.navigationBarColor = darkenColor(color, 0.8f)
        }
    }

    /**
     * 判断颜色是否为浅色
     */
    fun isColorLight(color: Int): Boolean {
        val darkness = 1 - (0.299 * Color.red(color) +
                           0.587 * Color.green(color) +
                           0.114 * Color.blue(color)) / 255
        return darkness < 0.5
    }

    /**
     * 加深颜色
     */
    fun darkenColor(color: Int, factor: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] *= factor
        return Color.HSVToColor(hsv)
    }

    /**
     * 变亮颜色
     */
    fun lightenColor(color: Int, factor: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = (hsv[2] + (1 - hsv[2]) * factor).coerceAtMost(1f)
        return Color.HSVToColor(hsv)
    }
}
