package com.tv.ball.feature.f086

import android.content.Context
import com.tv.ball.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用自身信息的快照。
 *
 * [updatedAtMillis] 是 `PackageManager` 给的 lastUpdateTime，也就是**这个 APK 装进设备的时刻**。
 * 名字里不写 "buildTime"：Gradle 的构建时刻不参与打包，装出来读不到它；
 * 真正能反映"手上这一份是什么时候的"只有安装时间，关于页要诚实，不编一个假构建时间。
 */
data class AppInfo(
    val versionName: String,
    val versionCode: Long,
    val packageName: String,
    val updatedAtMillis: Long
)

/**
 * F086「关于页」的能力层。
 *
 * 这一层只有几个纯函数，不碰任何界面：
 *
 * - [versionText]：版本号取 `BuildConfig`（编译期就确定的字面量），安装时间取
 *   `PackageManager`（运行期的事实），两段来源不同，所以必须各取各的，不能互相替代。
 */
object F086Engine {

    /** 仓库地址：README 里下载页与 Releases 同源，指向同一个 GitHub 仓库。 */
    const val REPO_URL = "https://github.com/303562915/tv"

    /** 下载页地址：CI 镜像 APK 与版本 JSON 的 Pages 站点。 */
    const val DOWNLOAD_PAGE_URL = "https://303562915.github.io/tv/"

    /** 仓库地址；页面里"打开仓库"按钮与文案共用同一份。 */
    fun repoUrl(): String = REPO_URL

    /**
     * 读应用自身信息。
     *
     * 版本号与版本码来自 [com.tv.ball.BuildConfig]：那是编译期常量，装的包即使被改过名字也读得到。
     * 安装时间来自 `PackageManager`，读失败（极少数被裁过的 ROM 会抛 NameNotFoundException）时
     * 退回 0，由调用方显示「未知」——关于页不能因为读不到一个时间就崩掉。
     */
    fun appInfo(context: Context): AppInfo {
        val app = context.applicationContext ?: context
        val packageName = app.packageName
        val installedAt = runCatching {
            app.packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        return AppInfo(
            versionName = com.tv.ball.BuildConfig.VERSION_NAME,
            versionCode = com.tv.ball.BuildConfig.VERSION_CODE.toLong(),
            packageName = packageName,
            updatedAtMillis = installedAt
        )
    }

    /**
     * 版本行（对照 unicanta AppHero）：`v1.2.0 (3) · 安装于 2026-11-20 22:10`；
     * CI 构建的开发版显示 `v1.2.0 (dev 42) · 安装于 …`——同名版本下，dev 构建号是
     * 屏幕上唯一能把两个渠道区分开的东西。
     *
     * 安装时间读不到时换成「未知」，不留一个 1970 年的假时间。
     */
    fun versionText(context: Context): String {
        val info = appInfo(context)
        val stamp = if (info.updatedAtMillis > 0L) {
            formatDate(info.updatedAtMillis)
        } else {
            context.getString(R.string.f086_value_unknown)
        }
        val dev = com.tv.ball.BuildConfig.DEV_BUILD_NUMBER
        return if (dev > 0) {
            context.getString(R.string.f086_version_line_dev, info.versionName, dev, stamp)
        } else {
            context.getString(R.string.f086_version_line, info.versionName, info.versionCode, stamp)
        }
    }

    /** 绝对时间（本机时区，精确到分）。每次现建 SimpleDateFormat：它不是线程安全的。 */
    fun formatDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}
