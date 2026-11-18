package com.tv.ball.domain.parser.aggregate

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 抖音解析器
 */
class DouyinParser : PlatformParser {

    companion object {
        private const val TAG = "DouyinParser"
        private const val API_URL = "https://api.cenguigui.cn/api/douyin/video.php?url="
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun canParse(url: String): Boolean {
        return url.contains("douyin.com") || url.contains("iesdouyin.com")
    }

    override suspend fun parse(url: String): ParsedVideo? = withContext(Dispatchers.IO) {
        Log.d(TAG, "解析抖音视频: $url")

        try {
            // 提取短链接
            val shortUrl = extractShortUrl(url)
            Log.d(TAG, "提取的短链接: $shortUrl")

            // 调用解析 API
            val request = Request.Builder()
                .url(API_URL + shortUrl)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful || responseBody == null) {
                Log.e(TAG, "API 请求失败: ${response.code}")
                return@withContext null
            }

            Log.d(TAG, "API 响应: $responseBody")

            // 解析 JSON
            val json = JSONObject(responseBody)
            if (json.getInt("code") != 200) {
                Log.e(TAG, "解析失败: ${json.optString("msg")}")
                return@withContext null
            }

            val data = json.getJSONObject("data")
            
            // 判断是视频还是图集
            val isVideo = data.has("url") && !data.isNull("url")
            val images = if (data.has("images") && !data.isNull("images")) {
                val imagesArray = data.getJSONArray("images")
                List(imagesArray.length()) { imagesArray.getString(it) }
            } else null

            ParsedVideo(
                title = data.optString("title", "抖音视频"),
                author = data.optString("author", "未知作者"),
                coverUrl = data.optString("cover", ""),
                videoUrl = if (isVideo) data.optString("url") else null,
                platform = Platform.DOUYIN,
                description = data.optString("desc", ""),
                images = images,
                isVideo = isVideo
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析失败: ${e.message}", e)
            null
        }
    }

    private fun extractShortUrl(url: String): String {
        // 提取抖音短链接，格式如 https://v.douyin.com/xxxxxx/
        val regex = Regex("""(https://v\.douyin\.com/[a-zA-Z0-9_-]+/?)""")
        val match = regex.find(url)
        return match?.groupValues?.get(1) ?: url
    }
}

/**
 * 快手解析器
 */
class KuaishouParser : PlatformParser {

    companion object {
        private const val TAG = "KuaishouParser"
    }

    override fun canParse(url: String): Boolean {
        return url.contains("kuaishou.com") || url.contains("chenzhongtech.com")
    }

    override suspend fun parse(url: String): ParsedVideo? {
        Log.d(TAG, "解析快手视频: $url")

        return try {
            ParsedVideo(
                title = "快手视频标题",
                author = "快手作者",
                coverUrl = "https://picsum.photos/400/600",
                videoUrl = url,
                platform = Platform.KUAISHOU,
                description = "快手视频描述"
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析失败: ${e.message}", e)
            null
        }
    }
}

/**
 * B站解析器
 */
class BilibiliParser : PlatformParser {

    companion object {
        private const val TAG = "BilibiliParser"
    }

    override fun canParse(url: String): Boolean {
        return url.contains("bilibili.com") || url.contains("b23.tv")
    }

    override suspend fun parse(url: String): ParsedVideo? {
        Log.d(TAG, "解析B站视频: $url")

        return try {
            ParsedVideo(
                title = "B站视频标题",
                author = "B站UP主",
                coverUrl = "https://picsum.photos/400/600",
                videoUrl = extractBvid(url),
                platform = Platform.BILIBILI,
                description = "B站视频描述"
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析失败: ${e.message}", e)
            null
        }
    }

    private fun extractBvid(url: String): String {
        // 提取 BV 号
        val regex = Regex("""(BV[a-zA-Z0-9]+)""")
        val match = regex.find(url)
        return match?.groupValues?.get(1) ?: url
    }
}
