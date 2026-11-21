package com.tv.ball.update

/** 一次可用的更新信息。buildNumber 仅开发版通道有值（CI 的 run_number）。 */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val buildNumber: Int,
    val apkUrl: String,
    val size: Long,
    val sha256: String,
    val notes: String,
    val pageUrl: String,
    val fileName: String,
    val channel: String
) {
    /** 界面上显示的版本描述。 */
    val display: String
        get() = when {
            buildNumber > 0 -> "$versionName · dev $buildNumber"
            versionCode > 0 -> "$versionName ($versionCode)"
            else -> versionName
        }
}