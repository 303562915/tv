package com.tv.ball.util

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.TextView
import com.tv.ball.di.Dependencies

/**
 * 主题色统一管理工具类
 */
object ThemeHelper {

    /**
     * 获取当前主题色
     */
    fun getThemeColor(): Int {
        return Dependencies.configRepository.getThemeColor()
    }

    /**
     * 为 TextView 设置主题色背景（圆角）
     */
    fun setThemeBackground(view: TextView, cornerRadius: Float = 8f) {
        val drawable = GradientDrawable().apply {
            setColor(getThemeColor())
            this.cornerRadius = cornerRadius * view.resources.displayMetrics.density
        }
        view.background = drawable
        view.setTextColor(Color.WHITE)
    }

    /**
     * 为 Button 设置主题色背景（圆角）
     */
    fun setThemeBackground(view: Button, cornerRadius: Float = 8f) {
        val drawable = GradientDrawable().apply {
            setColor(getThemeColor())
            this.cornerRadius = cornerRadius * view.resources.displayMetrics.density
        }
        view.background = drawable
        view.setTextColor(Color.WHITE)
    }

    /**
     * 为 View 设置主题色文字
     */
    fun setThemeTextColor(view: TextView) {
        view.setTextColor(getThemeColor())
    }

    /**
     * 创建主题色的 GradientDrawable
     */
    fun createThemeDrawable(cornerRadius: Float = 8f, context: Context): GradientDrawable {
        return GradientDrawable().apply {
            setColor(getThemeColor())
            this.cornerRadius = cornerRadius * context.resources.displayMetrics.density
        }
    }

    /**
     * 创建主题色的描边 GradientDrawable
     */
    fun createThemeStrokeDrawable(
        strokeWidth: Int = 2,
        cornerRadius: Float = 8f,
        context: Context
    ): GradientDrawable {
        return GradientDrawable().apply {
            setStroke(
                (strokeWidth * context.resources.displayMetrics.density).toInt(),
                getThemeColor()
            )
            this.cornerRadius = cornerRadius * context.resources.displayMetrics.density
            setColor(Color.TRANSPARENT)
        }
    }

    /**
     * 获取主题色的半透明版本
     */
    fun getThemeColorWithAlpha(alpha: Int): Int {
        val color = getThemeColor()
        return Color.argb(
            alpha,
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }
}
