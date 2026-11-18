package com.tv.ball.data

import android.content.Context
import android.content.SharedPreferences

object VideoSourceManager {
    private const val PREFS_NAME = "video_source_prefs"
    private const val KEY_CURRENT_SOURCE = "current_source"
    private const val DEFAULT_SOURCE = "iqiyi"
    
    private lateinit var prefs: SharedPreferences
    
    // 分类映射：通用分类ID -> 各源的实际分类ID
    // 通用分类：1=电影, 2=剧集, 3=综艺, 4=动漫, 6=国产剧, 8=日韩剧
    private val categoryMappings = mapOf(
        "iqiyi" to mapOf(
            1 to "1",    // 电影
            2 to "2",    // 剧集
            3 to "3",    // 综艺
            4 to "4",    // 动漫
            6 to "13",   // 国产剧
            8 to "14"    // 日韩剧
        ),
        "keke" to mapOf(
            1 to "1",    // 电影 -> /channel/1.html
            2 to "2",    // 剧集 -> /channel/2.html
            3 to "2",    // 综艺 -> /channel/2.html
            4 to "2",    // 动漫 -> /channel/2.html
            6 to "2",    // 国产剧 -> /channel/2.html
            8 to "2"     // 日韩剧 -> /channel/2.html
        ),
        "zxzj" to mapOf(
            1 to "1",    // 电影
            2 to "2",    // 剧集
            3 to "3",    // 综艺
            4 to "4",    // 动漫
            6 to "2",    // 国产剧 -> 剧集
            8 to "2"     // 日韩剧 -> 剧集
        ),
        "dadaqu" to mapOf(
            1 to "1",    // 电影
            2 to "2",    // 剧集
            3 to "3",    // 综艺
            4 to "4",    // 动漫
            6 to "2",    // 国产剧 -> 剧集
            8 to "2"     // 日韩剧 -> 剧集（可以通过area参数指定韩国）
        ),
        "hongniu" to mapOf(
            1 to "1",    // 电影
            2 to "2",    // 连续剧
            3 to "3",    // 综艺
            4 to "4",    // 动漫
            6 to "12",   // 国产剧
            8 to "18"    // 韩剧
        ),
        "360zy" to mapOf(
            1 to "1",    // 电影
            2 to "2",    // 剧集
            3 to "3",    // 综艺
            4 to "4",    // 动漫
            6 to "13",   // 国产剧
            8 to "14"    // 日韩剧
        ),
        "ffzy5" to mapOf(
            1 to "1",    // 电影
            2 to "2",    // 剧集
            3 to "3",    // 综艺
            4 to "4",    // 动漫
            6 to "13",   // 国产剧
            8 to "14"    // 日韩剧
        ),
        "dbzy" to mapOf(
            1 to "1",    // 电影
            2 to "2",    // 剧集
            3 to "3",    // 综艺
            4 to "4",    // 动漫
            6 to "13",   // 国产剧
            8 to "14"    // 日韩剧
        ),
        "niuniuzy3" to mapOf(
            1 to "1",    // 电影
            2 to "2",    // 剧集
            3 to "3",    // 综艺
            4 to "4",    // 动漫
            6 to "13",   // 国产剧
            8 to "14"    // 日韩剧
        ),
        "jisuzy" to mapOf(
            1 to "1",    // 电影
            2 to "2",    // 剧集
            3 to "3",    // 综艺
            4 to "4",    // 动漫
            6 to "13",   // 国产剧
            8 to "14"    // 日韩剧
        ),
        "taopian" to mapOf(
            1 to "1",    // 电影 - 淘片API不需要分类参数，这里只是占位
            2 to "2",    // 剧集
            3 to "3",    // 综艺
            4 to "4",    // 动漫
            6 to "13",   // 国产剧
            8 to "14"    // 日韩剧
        ),
        "shandian" to mapOf(
            1 to "1",    // 电影
            2 to "2",    // 电视剧
            3 to "3",    // 综艺
            4 to "4",    // 动漫
            6 to "13",   // 国产剧
            8 to "15"    // 韩剧
        ),
        "maotai" to mapOf(
            1 to "1",    // 电影
            2 to "2",    // 剧集
            3 to "3",    // 综艺
            4 to "4",    // 动漫
            6 to "13",   // 国产剧
            8 to "16"    // 日韩剧（韩剧）
        )
    )

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getCurrentSource(): String {
        return prefs.getString(KEY_CURRENT_SOURCE, DEFAULT_SOURCE) ?: DEFAULT_SOURCE
    }

    fun setCurrentSource(sourceKey: String) {
        prefs.edit().putString(KEY_CURRENT_SOURCE, sourceKey).apply()
    }

    fun getCurrentConfig(): VideoSourceConfig {
        val currentKey = getCurrentSource()
        return VideoSourceConfigs.getConfig(currentKey) ?: VideoSourceConfigs.sources.first()
    }

    private fun mapCategory(sourceKey: String, category: Int): String {
        return categoryMappings[sourceKey]?.get(category) ?: category.toString()
    }

    fun getSourceApiUrl(
        sourceKey: String,
        category: Int,
        page: Int,
        type: String = "",
        area: String = "",
        year: String = "",
        lang: String = ""
    ): String {
        val config = VideoSourceConfigs.getConfig(sourceKey) ?: return ""

        // 淘片API特殊处理：不需要分类和分页参数
        if (sourceKey == "taopian") {
            return config.baseUrl
        }

        val mappedCategory = mapCategory(sourceKey, category)
        
        return when (config.type) {
            SourceType.API, SourceType.API_XML -> {
                config.baseUrl
                    .replace("{category}", mappedCategory)
                    .replace("{page}", page.toString())
            }
            SourceType.WEB -> {
                when (sourceKey) {
                    "keke" -> {
                        // 可可影视 URL格式: show/{category}-{type}-{area}-{year}-{lang}-{letter}-{order}-{page}-{extra}.html
                        // 示例: show/1-----3-2.html (基础分页)
                        // 示例: show/1-%E5%89%A7%E6%83%85----3-2.html (带类型)
                        // 示例: show/2--%E9%9F%A9%E5%9B%BD---3-2.html (带地区)
                        config.baseUrl
                            .replace("{category}", mappedCategory)
                            .replace("{type}", type)
                            .replace("{area}", area)
                            .replace("{year}", year)
                            .replace("{lang}", lang)
                            .replace("{letter}", "")
                            .replace("{order}", page.toString())
                            .replace("{page}", "2")
                            .replace("{extra}", "")
                    }
                    "zxzj" -> {
                        // 在线之家 URL格式: list/{category}-{page}.html
                        config.baseUrl
                            .replace("{category}", mappedCategory)
                            .replace("{page}", page.toString())
                    }
                    "dadaqu" -> {
                        // 达达趣 URL格式: show/{category}-{area}-{year}-{lang}-{letter}-{by}-{class}-{page}-{limit}-{order}-{extra}.html
                        // 示例: show/1--------2---.html (电影第2页)
                        // 示例: show/1-韩国----------.html (韩国电影)
                        // 示例: show/3-----------.html (综艺)
                        // 示例: show/2--------2---.html (剧集第2页)
                        config.baseUrl
                            .replace("{category}", mappedCategory)
                            .replace("{area}", area)
                            .replace("{year}", year)
                            .replace("{lang}", lang)
                            .replace("{letter}", "")
                            .replace("{by}", "")
                            .replace("{class}", "")
                            .replace("{page}", page.toString())
                            .replace("{limit}", "")
                            .replace("{order}", "")
                            .replace("{extra}", "")
                    }
                    else -> {
                        config.baseUrl
                            .replace("{category}", mappedCategory)
                            .replace("{area}", "")
                            .replace("{year}", "")
                            .replace("{lang}", "")
                            .replace("{page}", page.toString())
                            .replace("{order}", "")
                    }
                }
            }
        }
    }
    
    fun getSourceName(sourceKey: String): String {
        return VideoSourceConfigs.getConfig(sourceKey)?.name ?: "未知源"
    }
    
    fun isApiSource(sourceKey: String): Boolean {
        val type = VideoSourceConfigs.getConfig(sourceKey)?.type
        return type == SourceType.API || type == SourceType.API_XML
    }
    
    fun getSearchApiUrl(sourceKey: String, keyword: String): String {
        val config = VideoSourceConfigs.getConfig(sourceKey) ?: return ""

        return when (sourceKey) {
            "iqiyi" -> "https://iqiyizyapi.com/api.php/provide/vod/?ac=videolist&wd=$keyword"
            "360zy" -> "https://360zy5.com/api.php/provide/vod/?ac=videolist&wd=$keyword"
            "ffzy5" -> "http://ffzy5.tv/api.php/provide/vod/?ac=videolist&wd=$keyword"
            "dbzy" -> "https://caiji.dbzy5.com/api.php/provide/vod/from/dbm3u8/at/json/?ac=videolist&wd=$keyword"
            "niuniuzy3" -> "http://niuniuzy3.com/api.php/provide/vod/?ac=videolist&wd=$keyword"
            "hongniu" -> "https://www.hongniuzy2.com/api.php/provide/vod/?ac=videolist&wd=$keyword"
            "libvod" -> "https://www.libvio.cc/api.php/provide/vod/?ac=videolist&wd=$keyword"
            "shandian" -> "https://xsd.sdzyapi.com/api.php/provide/vod/from/sdm3u8/at/xml?ac=videolist&wd=$keyword"
            "maotai" -> "https://caiji.maotaizy.cc/api.php/provide/vod/from/mtm3u8/at/json?ac=videolist&wd=$keyword"
            else -> config.baseUrl.replace("{keyword}", keyword)
        }
    }
    
    fun getSearchWebUrl(sourceKey: String, keyword: String): String {
        return when (sourceKey) {
            "keke" -> {
                // 可可影视搜索使用IP地址
                "https://103.194.185.51:51120/search?t=bGEt7qN3fnzhrkO989OwEA==&k=$keyword"
            }
            "zxzj" -> {
                // 在线之家搜索
                "https://www.zxzjhd.com/search.html?wd=$keyword"
            }
            "dadaqu" -> {
                // 达达趣搜索
                "https://www.dadaqu.me/search.html?wd=$keyword"
            }
            else -> "https://www.freeok.mobi/so1so/-------------.html?wd=$keyword"
        }
    }
    
    fun getDetailUrl(sourceKey: String, videoId: String): String {
        val config = VideoSourceConfigs.getConfig(sourceKey) ?: return ""

        val detailUrl = when (config.type) {
            SourceType.API, SourceType.API_XML -> {
                // API类型使用JSON/XML接口获取详情
                when (sourceKey) {
                    "iqiyi" -> "https://iqiyizyapi.com/api.php/provide/vod/?ac=videolist&ids=$videoId"
                    "360zy" -> "https://360zy5.com/api.php/provide/vod/?ac=videolist&ids=$videoId"
                    "ffzy5" -> "http://ffzy5.tv/api.php/provide/vod/?ac=videolist&ids=$videoId"
                    "dbzy" -> "https://caiji.dbzy5.com/api.php/provide/vod/from/dbm3u8/at/json/?ac=videolist&ids=$videoId"
                    "niuniuzy3" -> "http://niuniuzy3.com/api.php/provide/vod/?ac=videolist&ids=$videoId"
                    "hongniu" -> "https://www.hongniuzy2.com/api.php/provide/vod/?ac=videolist&ids=$videoId"
                    "jisuzy" -> "http://jisuziyuan999.com/api.php/provide/vod/?ac=videolist&ids=$videoId"
                    "shandian" -> "https://xsd.sdzyapi.com/api.php/provide/vod/from/sdm3u8/at/xml?ac=videolist&ids=$videoId"
                    "maotai" -> "https://caiji.maotaizy.cc/api.php/provide/vod/from/mtm3u8/at/json?ac=videolist&ids=$videoId"
                    else -> ""
                }
            }
            SourceType.WEB -> {
                // 网页类型直接返回URL（已经是完整URL）
                videoId
            }
        }
        
        android.util.Log.d("VideoSourceManager", "========== 构建详情URL ==========")
        android.util.Log.d("VideoSourceManager", "视频源: $sourceKey")
        android.util.Log.d("VideoSourceManager", "视频ID: $videoId")
        android.util.Log.d("VideoSourceManager", "详情URL: $detailUrl")
        android.util.Log.d("VideoSourceManager", "========================================")
        
        return detailUrl
    }
}
