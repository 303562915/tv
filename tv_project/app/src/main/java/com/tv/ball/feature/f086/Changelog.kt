package com.tv.ball.feature.f086

/**
 * 一条版本记录：[version] 是 versionName（对应 `app/build.gradle.kts` 里的真实发版号），
 * [note] 是那一版的一句话说明。
 */
data class ChangelogEntry(
    val version: String,
    val note: String
)

/**
 * 「更新记录」的数据源。
 *
 * 刻意写成**本地常量**：关于页的更新记录不该依赖网络——断网、GitHub 被墙、
 * 下载页镜像挂掉时，用户仍然要能看到「手上这版改了什么」。所以这里不发请求、
 * 不读缓存、不解析 JSON，只是最近 5 个版本的一句话清单。
 *
 * 发版时往列表头部补一条即可；顺序即展示顺序（新 → 旧）。
 */
object Changelog {

    /** 最近 5 个版本（新 → 旧）。 */
    val entries: List<ChangelogEntry> = listOf(
        ChangelogEntry("1.2.71", "安装通道扩到 root / Shizuku / Stellar / Dhizuku，关于页列出权限来源"),
        ChangelogEntry("1.2.59", "收藏状态同源、选集分批懒加载、设置页搜索"),
        ChangelogEntry("1.2.57", "补 drawable 修资源链接"),
        ChangelogEntry("1.2.56", "点击区 48dp、字号令牌收敛、对比度提升"),
        ChangelogEntry("1.2.46", "本地视频排序筛选与续播、下载并发可配")
    )

    /** 弹窗正文：每条一段「v版本号  说明」，段间空一行。 */
    fun dialogText(formatter: (version: String, note: String) -> String): String =
        entries.joinToString("\n\n") { formatter(it.version, it.note) }
}
