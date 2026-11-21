package com.tv.ball.update

/** 更新渠道：稳定版（v* 正式 Release）/ 开发版（滚动 dev 预发布）。 */
enum class UpdateChannel {
    STABLE,
    DEV;

    companion object {
        /** 从 SharedPreferences 里存的字符串还原，认不出就当稳定版。 */
        fun from(value: String?): UpdateChannel = when (value?.lowercase()) {
            "dev", "development" -> DEV
            else -> STABLE
        }
    }
}
