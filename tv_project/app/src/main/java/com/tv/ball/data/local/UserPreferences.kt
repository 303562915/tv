package com.tv.ball.data.local

import android.content.Context
import android.content.SharedPreferences

/**
 * 用户登录信息管理
 */
class UserPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "user_prefs",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_TOKEN = "token"
        private const val KEY_MEMBER_ID = "member_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_EMAIL = "email"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_AVATAR = "avatar"
        private const val KEY_LEVEL = "level"
        private const val KEY_VIP_EXPIRE_TIME = "vip_expire_time"
        private const val KEY_POINTS = "points"
        private const val KEY_BALANCE = "balance"
        private const val KEY_INVITE_CODE = "invite_code"
        private const val KEY_AUTO_LOGIN = "auto_login"
        private const val KEY_CODE_COUNTDOWN = "code_countdown"

        // ===== 播放设置（设置页「播放设置」专用，播放侧直接读这些键）=====
        /** 自动连播下一集。默认开。 */
        private const val KEY_PLAY_AUTO_NEXT = "playback_auto_next"

        /** 记忆播放进度：下次从上次位置继续。默认开。 */
        private const val KEY_PLAY_REMEMBER_PROGRESS = "playback_remember_progress"

        /** 播放结束行为：0 停止、1 循环当前集、2 自动下一集。默认 2。 */
        private const val KEY_PLAY_ENDED_BEHAVIOR = "playback_ended_behavior"

        /** 快进 / 快退步长（秒）。默认 10。 */
        private const val KEY_PLAY_SEEK_STEP = "playback_seek_step_sec"

        /** 跳过片头广告开关。默认关（老用户不会被静默改变起播行为）。 */
        private const val KEY_PLAY_AD_SKIP = "playback_ad_skip_enabled"

        /** 片头广告时长（秒），开播自动快进到这里。默认 15。 */
        private const val KEY_PLAY_AD_SKIP_SEC = "playback_ad_skip_seconds"

        /** 预加载下一集。默认开。 */
        private const val KEY_PLAY_PRELOAD_NEXT = "playback_preload_next"

        /** 预加载提前量（秒）。默认 10。 */
        private const val KEY_PLAY_PRELOAD_LEAD = "playback_preload_lead_seconds"

        /** 加载失败自动重试次数。默认 2。 */
        private const val KEY_PLAY_RETRY = "playback_retry_count"

        /** 播放失败自动切换线路。默认开。 */
        private const val KEY_PLAY_AUTO_ROUTE = "playback_auto_switch_route"

        /** 允许移动网络（流量）播放。默认开。 */
        private const val KEY_PLAY_MOBILE_DATA = "playback_allow_mobile_data"

        /** 精确跳转：关掉后跳转只对齐关键帧，更快但落点粗。默认开。 */
        private const val KEY_PLAY_EXACT_SEEK = "playback_exact_seek"

        /** 播放结束行为取值：停止。 */
        const val ENDED_BEHAVIOR_STOP = 0

        /** 播放结束行为取值：循环当前集。 */
        const val ENDED_BEHAVIOR_LOOP = 1

        /** 播放结束行为取值：自动下一集。 */
        const val ENDED_BEHAVIOR_NEXT = 2
    }

    /**
     * 保存登录信息
     */
    fun saveLoginInfo(
        token: String?,
        memberId: Int?,
        username: String?,
        email: String?,
        nickname: String? = null,
        avatar: String? = null,
        level: String? = null,
        vipExpireTime: String? = null,
        points: String? = null,
        balance: String? = null,
        inviteCode: String? = null,
        autoLogin: Boolean = true
    ) {
        prefs.edit().apply {
            putBoolean(KEY_IS_LOGGED_IN, true)
            putString(KEY_TOKEN, token)
            putInt(KEY_MEMBER_ID, memberId ?: 0)
            putString(KEY_USERNAME, username)
            putString(KEY_EMAIL, email)
            putString(KEY_NICKNAME, nickname)
            putString(KEY_AVATAR, avatar)
            putString(KEY_LEVEL, level)
            putString(KEY_VIP_EXPIRE_TIME, vipExpireTime)
            putString(KEY_POINTS, points)
            putString(KEY_BALANCE, balance)
            putString(KEY_INVITE_CODE, inviteCode)
            putBoolean(KEY_AUTO_LOGIN, autoLogin)
            apply()
        }
    }

    /**
     * 更新用户信息（不改变登录状态）
     */
    fun updateUserInfo(
        nickname: String? = null,
        avatar: String? = null,
        level: String? = null,
        vipExpireTime: String? = null,
        points: String? = null,
        balance: String? = null
    ) {
        prefs.edit().apply {
            nickname?.let { putString(KEY_NICKNAME, it) }
            avatar?.let { putString(KEY_AVATAR, it) }
            level?.let { putString(KEY_LEVEL, it) }
            vipExpireTime?.let { putString(KEY_VIP_EXPIRE_TIME, it) }
            points?.let { putString(KEY_POINTS, it) }
            balance?.let { putString(KEY_BALANCE, it) }
            apply()
        }
    }

    /**
     * 清除登录信息（退出登录）
     */
    fun clearLoginInfo() {
        prefs.edit().clear().apply()
    }

    /**
     * 是否已登录
     */
    fun isLoggedIn(): Boolean {
        return prefs.getBoolean(KEY_IS_LOGGED_IN, false)
    }

    /**
     * 是否自动登录
     */
    fun isAutoLogin(): Boolean {
        return prefs.getBoolean(KEY_AUTO_LOGIN, true)
    }

    /**
     * 获取Token
     */
    fun getToken(): String? {
        return prefs.getString(KEY_TOKEN, null)
    }

    /**
     * 获取会员ID
     */
    fun getMemberId(): Int {
        return prefs.getInt(KEY_MEMBER_ID, 0)
    }

    /**
     * 获取用户名
     */
    fun getUsername(): String? {
        return prefs.getString(KEY_USERNAME, null)
    }

    /**
     * 获取邮箱
     */
    fun getEmail(): String? {
        return prefs.getString(KEY_EMAIL, null)
    }

    /**
     * 获取昵称
     */
    fun getNickname(): String? {
        return prefs.getString(KEY_NICKNAME, null)
    }

    /**
     * 获取头像
     */
    fun getAvatar(): String? {
        return prefs.getString(KEY_AVATAR, null)
    }

    /**
     * 获取等级
     */
    fun getLevel(): String? {
        return prefs.getString(KEY_LEVEL, null)
    }

    /**
     * 获取VIP过期时间
     */
    fun getVipExpireTime(): String? {
        return prefs.getString(KEY_VIP_EXPIRE_TIME, null)
    }

    /**
     * 获取积分
     */
    fun getPoints(): String? {
        return prefs.getString(KEY_POINTS, null)
    }

    /**
     * 获取余额
     */
    fun getBalance(): String? {
        return prefs.getString(KEY_BALANCE, null)
    }

    /**
     * 获取推荐码
     */
    fun getInviteCode(): String? {
        return prefs.getString(KEY_INVITE_CODE, null)
    }

    /**
     * 设置自动登录
     */
    fun setAutoLogin(autoLogin: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_LOGIN, autoLogin).apply()
    }

    /**
     * 获取验证码倒计时时间（秒）
     */
    fun getCodeCountdown(): Int {
        return prefs.getInt(KEY_CODE_COUNTDOWN, 60)
    }

    /**
     * 设置验证码倒计时时间（秒）
     */
    fun setCodeCountdown(seconds: Int) {
        prefs.edit().putInt(KEY_CODE_COUNTDOWN, seconds).apply()
    }

    // ============================================================
    // 播放设置
    //
    // 这里的每一个键都有对应的播放侧读取点（见设置页汇报表）：
    //   · 自动连播 / 结束行为 / 记忆进度 / 片头广告  → VideoPlayerActivity.onEnded、onReady、checkSkipIntro
    //   · 预加载下一集 / 提前量                      → VideoPlayerActivity.checkAndPreloadNextEpisode
    //   · 重试次数 / 精确跳转 / 快进步长             → ExoPlayerManager.initializePlayer
    //   · 失败自动换线                                → VideoPlayerActivity.onError
    //   · 移动网络播放                                → VideoPlayerActivity.handleNetworkChange
    // 写入一律 apply()：设置页不需要等磁盘回写，播放页下一次起播读到的就是内存里的新值。
    // ============================================================

    /** 是否自动连播下一集。 */
    fun isPlaybackAutoNext(): Boolean = prefs.getBoolean(KEY_PLAY_AUTO_NEXT, true)

    /** 设置自动连播下一集。 */
    fun setPlaybackAutoNext(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PLAY_AUTO_NEXT, enabled).apply()
    }

    /** 是否记忆播放进度（下次从上次位置继续）。 */
    fun isPlaybackRememberProgress(): Boolean = prefs.getBoolean(KEY_PLAY_REMEMBER_PROGRESS, true)

    /** 设置记忆播放进度。 */
    fun setPlaybackRememberProgress(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PLAY_REMEMBER_PROGRESS, enabled).apply()
    }

    /** 播放结束行为：[ENDED_BEHAVIOR_STOP] / [ENDED_BEHAVIOR_LOOP] / [ENDED_BEHAVIOR_NEXT]，坏值退回自动下一集。 */
    fun getPlaybackEndedBehavior(): Int {
        val value = prefs.getInt(KEY_PLAY_ENDED_BEHAVIOR, ENDED_BEHAVIOR_NEXT)
        return if (value in ENDED_BEHAVIOR_STOP..ENDED_BEHAVIOR_NEXT) value else ENDED_BEHAVIOR_NEXT
    }

    /** 设置播放结束行为。 */
    fun setPlaybackEndedBehavior(behavior: Int) {
        prefs.edit().putInt(KEY_PLAY_ENDED_BEHAVIOR, behavior).apply()
    }

    /** 快进 / 快退步长（秒），夹在 5~120，缺省 10。 */
    fun getPlaybackSeekStepSec(): Int = prefs.getInt(KEY_PLAY_SEEK_STEP, 10).coerceIn(5, 120)

    /** 设置快进 / 快退步长（秒）。 */
    fun setPlaybackSeekStepSec(seconds: Int) {
        prefs.edit().putInt(KEY_PLAY_SEEK_STEP, seconds.coerceIn(5, 120)).apply()
    }

    /** 是否跳过片头广告。 */
    fun isPlaybackAdSkipEnabled(): Boolean = prefs.getBoolean(KEY_PLAY_AD_SKIP, false)

    /** 设置是否跳过片头广告。 */
    fun setPlaybackAdSkipEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PLAY_AD_SKIP, enabled).apply()
    }

    /** 片头广告时长（秒），夹在 3~120，缺省 15。 */
    fun getPlaybackAdSkipSeconds(): Int = prefs.getInt(KEY_PLAY_AD_SKIP_SEC, 15).coerceIn(3, 120)

    /** 设置片头广告时长（秒）。 */
    fun setPlaybackAdSkipSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_PLAY_AD_SKIP_SEC, seconds.coerceIn(3, 120)).apply()
    }

    /** 是否预加载下一集。 */
    fun isPlaybackPreloadNext(): Boolean = prefs.getBoolean(KEY_PLAY_PRELOAD_NEXT, true)

    /** 设置是否预加载下一集。 */
    fun setPlaybackPreloadNext(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PLAY_PRELOAD_NEXT, enabled).apply()
    }

    /** 预加载提前量（秒），夹在 3~60，缺省 10。 */
    fun getPlaybackPreloadLeadSeconds(): Int = prefs.getInt(KEY_PLAY_PRELOAD_LEAD, 10).coerceIn(3, 60)

    /** 设置预加载提前量（秒）。 */
    fun setPlaybackPreloadLeadSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_PLAY_PRELOAD_LEAD, seconds.coerceIn(3, 60)).apply()
    }

    /** 加载失败自动重试次数，夹在 0~5，缺省 2（0 表示不重试）。 */
    fun getPlaybackRetryCount(): Int = prefs.getInt(KEY_PLAY_RETRY, 2).coerceIn(0, 5)

    /** 设置加载失败自动重试次数。 */
    fun setPlaybackRetryCount(count: Int) {
        prefs.edit().putInt(KEY_PLAY_RETRY, count.coerceIn(0, 5)).apply()
    }

    /** 播放失败是否自动切换线路。 */
    fun isPlaybackAutoSwitchRoute(): Boolean = prefs.getBoolean(KEY_PLAY_AUTO_ROUTE, true)

    /** 设置播放失败是否自动切换线路。 */
    fun setPlaybackAutoSwitchRoute(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PLAY_AUTO_ROUTE, enabled).apply()
    }

    /** 是否允许移动网络播放。 */
    fun isPlaybackAllowMobileData(): Boolean = prefs.getBoolean(KEY_PLAY_MOBILE_DATA, true)

    /** 设置是否允许移动网络播放。 */
    fun setPlaybackAllowMobileData(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PLAY_MOBILE_DATA, enabled).apply()
    }

    /** 是否精确跳转（关掉后按关键帧跳，更快但落点粗）。 */
    fun isPlaybackExactSeek(): Boolean = prefs.getBoolean(KEY_PLAY_EXACT_SEEK, true)

    /** 设置是否精确跳转。 */
    fun setPlaybackExactSeek(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PLAY_EXACT_SEEK, enabled).apply()
    }

    /**
     * 把上面这些播放设置全部写回出厂值。
     *
     * 只动播放设置这一段，不碰登录态、会员信息与验证码倒计时；
     * 设置页的「恢复默认播放设置」走这一个入口，避免二十几个键各写一份默认值。
     */
    fun resetPlaybackSettings() {
        prefs.edit()
            .putBoolean(KEY_PLAY_AUTO_NEXT, true)
            .putBoolean(KEY_PLAY_REMEMBER_PROGRESS, true)
            .putInt(KEY_PLAY_ENDED_BEHAVIOR, ENDED_BEHAVIOR_NEXT)
            .putInt(KEY_PLAY_SEEK_STEP, 10)
            .putBoolean(KEY_PLAY_AD_SKIP, false)
            .putInt(KEY_PLAY_AD_SKIP_SEC, 15)
            .putBoolean(KEY_PLAY_PRELOAD_NEXT, true)
            .putInt(KEY_PLAY_PRELOAD_LEAD, 10)
            .putInt(KEY_PLAY_RETRY, 2)
            .putBoolean(KEY_PLAY_AUTO_ROUTE, true)
            .putBoolean(KEY_PLAY_MOBILE_DATA, true)
            .putBoolean(KEY_PLAY_EXACT_SEEK, true)
            .apply()
    }
}
