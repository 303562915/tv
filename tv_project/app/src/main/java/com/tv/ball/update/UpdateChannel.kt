package com.tv.ball.update

import android.content.Context

/** 更新渠道：稳定版（v* 正式 Release）/ 开发版（滚动 dev 预发布）。 */
enum class UpdateChannel {
    STABLE,
    DEV;

    /** 存进 SharedPreferences 的字符串。 */
    val key: String get() = if (this == DEV) "dev" else "stable"

    companion object {
        /** 渠道选择与关于页共用同一份 SharedPreferences。 */
        const val PREFS = "f086_about"
        const val KEY_CHANNEL = "update_channel"

        /** 默认渠道：开发版。 */
        val DEFAULT = DEV

        /** 从 SharedPreferences 里存的字符串还原，没存过或认不出就按默认渠道（开发版）。 */
        fun from(value: String?): UpdateChannel = when (value?.lowercase()) {
            "stable", "release" -> STABLE
            "dev", "development" -> DEV
            else -> DEFAULT
        }

        /** 读当前选择的渠道。 */
        fun read(context: Context): UpdateChannel = from(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CHANNEL, null)
        )

        /** 记住用户切换的渠道。 */
        fun save(context: Context, channel: UpdateChannel) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CHANNEL, channel.key)
                .apply()
        }
    }
}