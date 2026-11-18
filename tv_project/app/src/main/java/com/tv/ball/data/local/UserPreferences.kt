package com.tv.ball.data.local

import android.content.Context
import android.content.SharedPreferences

/**
 * 播放器本地偏好（无需登录 / 无账号体系）。
 *
 * 说明：
 * - 原「用户登录信息管理」相关的能力（token / memberId / 会员 / 积分 / 余额 /
 *   邀请码 / 头像昵称 / 自动登录 / 验证码倒计时）随登录体系一起移除。
 * - 夜间模式、主题色、倍速、自动连播这类播放器设置由 [com.tv.ball.domain.repository.ConfigRepository]
 *   统一管理（存储于 video_player_prefs），这里不再重复存一份，避免出现两个数据源不一致。
 * - 此处只保存「本机播放行为」类的偏好，全部是本地数据，不涉及任何网络请求。
 */
class UserPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFS_NAME = "user_prefs"

        /** 是否记住播放进度（下次打开同一视频从上次位置继续） */
        private const val KEY_RESUME_PLAYBACK = "resume_playback"

        /** 上次使用的音量（0.0 ~ 1.0，负值表示未记录） */
        private const val KEY_LAST_VOLUME = "last_volume"

        /** 是否开启手势控制（音量/亮度/快进） */
        private const val KEY_GESTURE_CONTROL = "gesture_control"

        /** 是否默认全屏播放 */
        private const val KEY_FULLSCREEN = "fullscreen_playback"

        /** 是否默认静音启动 */
        private const val KEY_MUTE_ON_START = "mute_on_start"

        /** 字幕字号缩放比例 */
        private const val KEY_SUBTITLE_SCALE = "subtitle_scale"

        /** 上一次打开的本地视频目录（URI 字符串） */
        private const val KEY_LAST_LOCAL_FOLDER = "last_local_folder"

        const val DEFAULT_VOLUME = 1.0f
        const val DEFAULT_SUBTITLE_SCALE = 1.0f
    }

    // ── 播放进度 / 音量 ────────────────────────────────────────────────────────

    fun isResumePlaybackEnabled(): Boolean = prefs.getBoolean(KEY_RESUME_PLAYBACK, true)

    fun setResumePlaybackEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RESUME_PLAYBACK, enabled).apply()
    }

    fun getLastVolume(): Float = prefs.getFloat(KEY_LAST_VOLUME, DEFAULT_VOLUME)

    fun setLastVolume(volume: Float) {
        prefs.edit().putFloat(KEY_LAST_VOLUME, volume.coerceIn(0f, 1f)).apply()
    }

    // ── 播放行为 ─────────────────────────────────────────────────────────────

    fun isGestureControlEnabled(): Boolean = prefs.getBoolean(KEY_GESTURE_CONTROL, true)

    fun setGestureControlEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GESTURE_CONTROL, enabled).apply()
    }

    fun isFullscreenEnabled(): Boolean = prefs.getBoolean(KEY_FULLSCREEN, false)

    fun setFullscreenEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FULLSCREEN, enabled).apply()
    }

    fun isMuteOnStartEnabled(): Boolean = prefs.getBoolean(KEY_MUTE_ON_START, false)

    fun setMuteOnStartEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MUTE_ON_START, enabled).apply()
    }

    // ── 字幕 ────────────────────────────────────────────────────────────────

    fun getSubtitleScale(): Float = prefs.getFloat(KEY_SUBTITLE_SCALE, DEFAULT_SUBTITLE_SCALE)

    fun setSubtitleScale(scale: Float) {
        prefs.edit().putFloat(KEY_SUBTITLE_SCALE, scale.coerceIn(0.5f, 2.0f)).apply()
    }

    // ── 本地视频目录 ─────────────────────────────────────────────────────────

    fun getLastLocalFolder(): String? = prefs.getString(KEY_LAST_LOCAL_FOLDER, null)

    fun setLastLocalFolder(uri: String?) {
        prefs.edit().putString(KEY_LAST_LOCAL_FOLDER, uri).apply()
    }

    // ── 通用清理（设置页「恢复默认」用） ─────────────────────────────────────

    fun clearPlaybackPreferences() {
        prefs.edit().clear().apply()
    }
}
