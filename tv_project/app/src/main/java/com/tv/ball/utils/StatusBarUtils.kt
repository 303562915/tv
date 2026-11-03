package com.tv.ball.utils

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.core.view.WindowCompat

object StatusBarUtils {

    /**
     * 设置透明沉浸式状态栏
     * @param activity Activity 实例
     * @param lightStatusBar 是否使用深色图标（适配浅色背景），默认为 true
     */
    fun setupTransparentStatusBar(activity: Activity, lightStatusBar: Boolean = true) {
        // 启用边到边显示
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)

        // 设置状态栏透明
        activity.window.statusBarColor = android.graphics.Color.TRANSPARENT

        // 设置导航栏透明
        activity.window.navigationBarColor = android.graphics.Color.TRANSPARENT

        // 设置状态栏图标颜色
        if (lightStatusBar) {
            setLightStatusBar(activity)
        } else {
            clearLightStatusBar(activity)
        }
    }

    /**
     * 设置半透明状态栏（带渐变遮罩）
     */
    fun setupGradientStatusBar(activity: Activity, lightStatusBar: Boolean = true) {
        // 启用边到边显示
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)

        // 设置半透明状态栏
        activity.window.statusBarColor = android.graphics.Color.parseColor("#40000000")

        // 设置导航栏透明
        activity.window.navigationBarColor = android.graphics.Color.TRANSPARENT

        // 设置状态栏图标颜色
        if (lightStatusBar) {
            setLightStatusBar(activity)
        } else {
            clearLightStatusBar(activity)
        }
    }

    /**
     * 设置状态栏图标为深色（适配浅色背景）
     */
    fun setLightStatusBar(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility =
                activity.window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    /**
     * 设置状态栏图标为浅色（适配深色背景）
     */
    fun clearLightStatusBar(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility =
                activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }
}
