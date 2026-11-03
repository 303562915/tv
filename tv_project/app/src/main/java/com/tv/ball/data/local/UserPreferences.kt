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
}
