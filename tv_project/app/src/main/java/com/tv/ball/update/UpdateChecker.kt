package com.tv.ball.update

import com.tv.ball.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 检查更新。
 *
 * 优先读下载页镜像 `docs/latest.json`（国内可达、带 version_code 与 sha256），
 * 失败再退到 GitHub Releases API。
 */
object UpdateChecker {

    private const val MIRROR_JSON = "https://303562915.github.io/tv/latest.json"
    private const val MIRROR_BASE = "https://303562915.github.io/tv/"
    private const val GITHUB_API = "https://api.github.com/repos/303562915/tv/releases/latest"
    private const val RELEASES_PAGE = "https://github.com/303562915/tv/releases/latest"

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun check(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching { fetchMirror() ?: fetchGithub() }
    }

    /** 是否有比当前安装包更新的版本。 */
    fun isNewer(info: UpdateInfo): Boolean {
        if (info.versionCode > 0 && BuildConfig.VERSION_CODE > 0) {
            return info.versionCode > BuildConfig.VERSION_CODE
        }
        return compareVersion(info.versionName, BuildConfig.VERSION_NAME) > 0
    }

    private fun fetchMirror(): UpdateInfo? {
        val request = Request.Builder().url(MIRROR_JSON).header("Cache-Control", "no-cache").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string()?.trim().orEmpty()
            if (!body.startsWith("{")) return null
            val json = JSONObject(body)
            val file = json.optString("file")
            if (file.isBlank()) return null
            val url = if (file.startsWith("http")) file else MIRROR_BASE + file.removePrefix("/")
            return UpdateInfo(
                versionName = json.optString("version_name").ifBlank { "未知" },
                versionCode = json.optInt("version_code", -1),
                apkUrl = url,
                size = json.optLong("size", 0L),
                sha256 = json.optString("sha256"),
                notes = json.optString("notes"),
                pageUrl = RELEASES_PAGE,
                fileName = json.optString("file_name")
            )
        }
    }

    private fun fetchGithub(): UpdateInfo? {
        val request = Request.Builder()
            .url(GITHUB_API)
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val tag = json.optString("tag_name")
            val asset = json.optJSONArray("assets")?.let { assets ->
                (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk", true) }
            } ?: return null
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (url.isBlank()) return null
            val mirrored = if (name.endsWith(".apk", true)) MIRROR_BASE + name else ""
            return UpdateInfo(
                versionName = if (tag.startsWith("v")) tag.removePrefix("v") else "最新构建",
                versionCode = -1,
                apkUrl = url,
                size = asset.optLong("size", 0L),
                sha256 = "",
                notes = json.optString("body"),
                pageUrl = json.optString("html_url").ifBlank { RELEASES_PAGE },
                fileName = mirrored
            )
        }
    }

    /** 分段数字比较，"1.2.0-debug" 这类后缀按数字段处理。 */
    private fun compareVersion(latest: String, current: String): Int {
        val a = latest.split('.', '-', '_', '+').mapNotNull { it.toIntOrNull() }
        val b = current.split('.', '-', '_', '+').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}