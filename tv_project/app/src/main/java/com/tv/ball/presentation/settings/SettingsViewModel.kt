package com.tv.ball.presentation.settings

import androidx.lifecycle.ViewModel
import com.tv.ball.di.Dependencies
import com.tv.ball.feature.f057.F057Engine
import com.tv.ball.feature.f081.F081Engine
import com.tv.ball.presentation.player.AspectRatioManager
import com.tv.ball.presentation.player.SkipIntroManager

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
}
