package com.tv.ball.domain.parser.aggregate

/**
 * 支持的视频平台
 */
enum class Platform(val platformName: String, val domains: List<String>) {
    DOUYIN("抖音", listOf("douyin.com", "iesdouyin.com")),
    KUAISHOU("快手", listOf("kuaishou.com", "chenzhongtech.com")),
    BILIBILI("哔哩哔哩", listOf("bilibili.com", "b23.tv")),
    WEIBO("微博", listOf("weibo.com", "weibo.cn")),
    XIAOHONGSHU("小红书", listOf("xiaohongshu.com", "xhslink.com"));

    companion object {
        /**
         * 根据 URL 识别平台
         */
        fun detectPlatform(url: String): Platform? {
            val lowerUrl = url.lowercase()
            return values().find { platform ->
                platform.domains.any { domain -> lowerUrl.contains(domain) }
            }
        }
    }
}

/**
 * 解析结果
 */
data class ParsedVideo(
    val title: String,
    val author: String,
    val coverUrl: String,
    val videoUrl: String?,
    val platform: Platform,
    val description: String = "",
    val images: List<String>? = null,  // 图集链接
    val isVideo: Boolean = true  // 是否为视频
)

/**
 * 平台解析器接口
 */
interface PlatformParser {
    /**
     * 是否支持该 URL
     */
    fun canParse(url: String): Boolean

    /**
     * 解析视频信息
     */
    suspend fun parse(url: String): ParsedVideo?
}
