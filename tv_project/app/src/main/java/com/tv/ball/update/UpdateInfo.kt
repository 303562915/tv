package com.tv.ball.update

/** 一次可用的更新信息。versionCode 为 -1 表示远端没有给出可信版本号（例如只有滚动 latest 构建）。 */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val size: Long,
    val sha256: String,
    val notes: String,
    val pageUrl: String,
    val fileName: String
)