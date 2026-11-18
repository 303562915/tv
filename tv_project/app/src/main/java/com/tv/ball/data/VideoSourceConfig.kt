package com.tv.ball.data

enum class SourceType {
    API,      // JSON API
    API_XML,  // XML API
    WEB       // HTML 网页
}

data class VideoSourceConfig(
    val key: String,
    val name: String,
    val type: SourceType,
    val baseUrl: String
)

object VideoSourceConfigs {
    // 默认视频源配置（作为备用）
    private val defaultSources = listOf(
        VideoSourceConfig("iqiyi", "爱奇艺", SourceType.API, "https://iqiyizyapi.com/api.php/provide/vod/?ac=videolist&t={category}&pg={page}"),
        VideoSourceConfig("360zy", "360源", SourceType.API, "https://360zy5.com/api.php/provide/vod/?ac=videolist&t={category}&pg={page}&h=&ids=&wd="),
        VideoSourceConfig("ffzy5", "非凡通道", SourceType.API, "http://ffzy5.tv/api.php/provide/vod/?ac=videolist&t={category}&pg={page}"),
        VideoSourceConfig("dbzy", "豆瓣通道", SourceType.API, "https://caiji.dbzy5.com/api.php/provide/vod/from/dbm3u8/at/json/?ac=videolist&t={category}&pg={page}"),
        VideoSourceConfig("niuniuzy3", "牛牛通道", SourceType.API, "http://niuniuzy3.com/api.php/provide/vod/?ac=videolist&t={category}&pg={page}"),
        VideoSourceConfig("hongniu", "红牛资源", SourceType.API, "https://www.hongniuzy2.com/api.php/provide/vod/?ac=videolist&t={category}&pg={page}"),
        VideoSourceConfig("libvod", "LiB", SourceType.WEB, "https://www.libvio.cc/show/{category}-{area}-{year}--{lang}----{page}---{order}.html"),
        VideoSourceConfig("keke", "可可", SourceType.WEB, "https://103.194.185.51:51120/show/{category}-{type}-{area}-{year}-{lang}-{letter}-{order}-{page}-{extra}.html"),
        VideoSourceConfig("zxzj", "在线之家", SourceType.WEB, "https://www.zxzjhd.com/list/{category}-{page}.html"),
        VideoSourceConfig("dadaqu", "达达趣", SourceType.WEB, "https://www.dadaqu.me/show/{category}-{area}-{year}-{lang}-{letter}-{by}-{class}-{page}-{limit}-{order}-{extra}.html")
    )

    // 云端加载的视频源（优先使用）
    private var cloudSources: List<VideoSourceConfig>? = null

    val sources: List<VideoSourceConfig>
        get() = cloudSources ?: defaultSources

    // 从云端加载视频源配置
    suspend fun loadFromCloud() {
        try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val request = okhttp3.Request.Builder()
                .url("https://your-server-domain.com/app/api/video_sources_json.php")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val jsonString = response.body?.string()
                if (!jsonString.isNullOrEmpty()) {
                    val sources = parseSourcesJson(jsonString)
                    if (sources.isNotEmpty()) {
                        cloudSources = sources
                        android.util.Log.d("VideoSourceConfigs", "云端视频源加载成功: ${sources.size} 个")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("VideoSourceConfigs", "加载云端视频源失败: ${e.message}")
        }
    }

    private fun parseSourcesJson(jsonString: String): List<VideoSourceConfig> {
        return try {
            val items = mutableListOf<VideoSourceConfig>()
            val jsonArray = org.json.JSONArray(jsonString)

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val key = obj.getString("key")
                val name = obj.getString("name")
                val typeStr = obj.getString("type")
                val type = when (typeStr) {
                    "WEB" -> SourceType.WEB
                    "API_XML" -> SourceType.API_XML
                    else -> SourceType.API
                }
                val baseUrl = obj.getString("baseUrl")

                items.add(VideoSourceConfig(key, name, type, baseUrl))
            }
            items
        } catch (e: Exception) {
            android.util.Log.e("VideoSourceConfigs", "解析视频源JSON失败: ${e.message}")
            emptyList()
        }
    }

    fun getConfig(key: String): VideoSourceConfig? {
        return sources.find { it.key == key }
    }
}
