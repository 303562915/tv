package com.tv.ball.feature.f086

import android.content.Context
import com.tv.ball.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一条第三方依赖在关于页上的全部信息。
 *
 * [version] 取的是 `gradle/libs.versions.toml` 里锁定的版本，[license] 是这一条自身的协议，
 * [url] 指向上游仓库。三项都要能单独取用：依赖行只拼版本与许可证，长文本区还要把地址带出来。
 */
data class Dep(
    val name: String,
    val version: String,
    val license: String,
    val url: String
)

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
 * F086「关于页与许可证」的能力层。
 *
 * 这一层只有一份静态数据 + 几个纯函数，不碰任何界面：
 *
 * - [DEPS]：与仓库 README「第三方依赖与协议」一节同源的清单。开源分发时"要保留哪些声明"
 *   是个事实问题，不能在两个地方各写一套——所以清单只有这一份，页面只负责画出来。
 *   顺序即 README 表格顺序，改版本时两边一起改（[version] 与 libs.versions.toml 对齐）。
 * - [versionText]：版本号取 `BuildConfig`（编译期就确定的字面量），安装时间取
 *   `PackageManager`（运行期的事实），两段来源不同，所以必须各取各的，不能互相替代。
 * - [licenseSummary]：**故意不接 Context**。功能中心列表绑定 module 时也读同一句摘要，
 *   那时候 Application 还没初始化完，取资源会在这个时序上炸；返回字面量最稳。
 * - [licenseText]：可滚动长文本区的内容，由 [DEPS] 直接生成，保证"列出来 11 条"与
 *   "统计里说 11 项"永远是同一个数字。
 */
object F086Engine {

    /** 仓库地址：README 里下载页与 Releases 同源，指向同一个 GitHub 仓库。 */
    const val REPO_URL = "https://github.com/303562915/tv"

    /** 下载页地址：CI 镜像 APK 与版本 JSON 的 Pages 站点。 */
    const val DOWNLOAD_PAGE_URL = "https://303562915.github.io/tv/"

    /** 许可证标识；用常量而不是散落的字面量，[licenseSummary] 的归组才靠得住。 */
    const val LICENSE_APACHE = "Apache-2.0"
    const val LICENSE_MIT = "MIT"

    /**
     * 打进 APK 的第三方依赖，顺序与 README 表格一致。
     *
     * 只收"真的被依赖、真的会被分发"的库：测试依赖与仅在编译期出现的注解处理器
     * （room-compiler、glide-compiler）不列——它们不进 APK，列出反而误导用户。
     */
    val DEPS: List<Dep> = listOf(
        Dep("ExoPlayer", "2.19.1", LICENSE_APACHE, "https://github.com/google/ExoPlayer"),
        Dep("OkHttp", "4.12", LICENSE_APACHE, "https://github.com/square/okhttp"),
        Dep("Glide", "4.16", LICENSE_APACHE, "https://github.com/bumptech/glide"),
        Dep(
            "Room",
            "2.6.1",
            LICENSE_APACHE,
            "https://developer.android.com/jetpack/androidx/releases/room"
        ),
        Dep("Gson", "2.10.1", LICENSE_APACHE, "https://github.com/google/gson"),
        Dep(
            "Kotlin Coroutines",
            "1.8.0",
            LICENSE_APACHE,
            "https://github.com/Kotlin/kotlinx.coroutines"
        ),
        Dep(
            "Material Components",
            "1.13.0",
            LICENSE_APACHE,
            "https://github.com/material-components/material-components-android"
        ),
        Dep(
            "AndroidX AppCompat",
            "1.7.1",
            LICENSE_APACHE,
            "https://developer.android.com/jetpack/androidx/releases/appcompat"
        ),
        Dep(
            "AndroidX ConstraintLayout",
            "2.2.0",
            LICENSE_APACHE,
            "https://developer.android.com/jetpack/androidx/releases/constraintlayout"
        ),
        Dep("NanoHTTPD", "2.3.1", LICENSE_APACHE, "https://github.com/NanoHttpd/nanohttpd"),
        Dep(
            "ViewPager2",
            "1.1.0",
            LICENSE_APACHE,
            "https://developer.android.com/jetpack/androidx/releases/viewpager2"
        )
    )

    /** 依赖清单（只读副本，页面按顺序画）。 */
    fun deps(): List<Dep> = DEPS

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

    /**
     * 一行许可证统计：`11 项依赖 · Apache-2.0 × 11`。
     *
     * 按 [Dep.license] 归组而不是直接说"全部 Apache-2.0"：哪天真的混进一个 MIT/Apache-2.0
     * 双协议的库，这里会自己变成两种颜色，不用改代码也不会说谎。
     * 不接 Context 的原因见类注释——功能中心列表绑定也会读它。
     */
    fun licenseSummary(): String {
        val grouped = DEPS.groupingBy { it.license }.eachCount()
        val body = grouped.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .joinToString(" / ") { (license, count) -> "$license × $count" }
        return "${DEPS.size} 项依赖 · $body"
    }

    /**
     * 可滚动长文本区的内容：先一句定位（本应用 MIT、依赖各自独立），再逐条列出依赖，
     * 最后附 Apache-2.0 的要点摘录。
     *
     * 只摘要点、不复制整篇 LICENSE：许可证全文有 200 行，塞进页面没有人会读；
     * 真正需要全文的场景是分发时的法务核对，那时候应该打开仓库里的 LICENSE 与各依赖的
     * NOTICE 文件。这里给的是"这份 APK 里到底有谁的代码、各自什么协议"。
     */
    fun licenseText(): String = buildString {
        appendLine("本应用以 MIT 协议发布；下列第三方组件各自保留其原始许可证与版权声明，")
        appendLine("分发本应用（含修改后分发）时需要一并保留这些声明。")
        appendLine()
        DEPS.forEachIndexed { index, dep ->
            appendLine("${index + 1}. ${dep.name}  ${dep.version}")
            appendLine("   许可证：${dep.license}")
            appendLine("   地址：${dep.url}")
        }
        appendLine()
        appendLine("Apache License 2.0 要点（全部 $LICENSE_APACHE 组件共用）：")
        appendLine("· 允许商用、修改与再分发，须保留版权声明、许可证副本与 NOTICE 文件；")
        appendLine("· 修改过的文件需要标注改动；")
        appendLine("· 不得使用贡献者的商标或名义为衍生产品背书；")
        appendLine("· 按「现状」提供，不含任何明示或暗示的担保。")
        appendLine()
        appendLine("本应用自身的 MIT 协议：可自由使用、修改、分发，须保留版权与许可声明；")
        appendLine("作者与贡献者不对使用本软件产生的任何后果负责。")
    }
}
