package com.tv.ball.update

import com.tv.ball.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * 检查更新，双渠道（对应 CI 生成的两份数据）：
 *
 * - 稳定版 `latest.json`：只由 `v*` 正式 Release 产生，比 `version_code`；
 * - 开发版 `development.json`：滚动 `dev` 预发布，比 `build_number`。
 *
 * 顺序：下载页镜像（国内可达、带 sha256）→ GitHub API → Release 页面（不占匿名配额）。
 */
object UpdateChecker {

    private const val SITE = "https://303562915.github.io/tv/"
    private const val MIRROR_STABLE_JSON = SITE + "latest.json"
    private const val MIRROR_DEV_JSON = SITE + "development.json"
    private const val STABLE_API = "https://api.github.com/repos/303562915/tv/releases/latest"
    private const val DEV_API = "https://api.github.com/repos/303562915/tv/releases/tags/dev"
    private const val STABLE_PAGE = "https://github.com/303562915/tv/releases/latest"
    private const val DEV_PAGE = "https://github.com/303562915/tv/releases/tag/dev"
    private const val CHECK_ATTEMPTS = 3
    private const val RETRY_DELAY_MILLIS = 800L
    private const val CHECK_TIMEOUT_SECONDS = 12L

    /** 检查失败的原因，界面按这个给人话。 */
    enum class Failure {
        /** 触发 GitHub 匿名配额（403 / 429）。 */
        RATE_LIMITED,

        /** 连接或读取超时。 */
        TIMEOUT,

        /** 没有网络 / DNS / 连接被拒。 */
        NETWORK,

        /** GitHub 回了 5xx。 */
        SERVER,

        /** 这个渠道还没有已发布的版本（404）。 */
        NO_RELEASE,

        /** 回的不是版本数据——可能是被劫持的响应或强制门户。 */
        MALFORMED,

        /** 其它。 */
        UNKNOWN
    }

    class CheckException(val failure: Failure) : Exception(failure.name)

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** 读取所选渠道的最新发布；拿不到就抛 [CheckException]。 */
    suspend fun check(channel: UpdateChannel): UpdateInfo = withContext(Dispatchers.IO) {
        var failure = Failure.UNKNOWN
        repeat(CHECK_ATTEMPTS) { attempt ->
            try {
                val mirrored = fetchMirror(channel)
                // 下载页镜像由 Pages 生成，偶发部署失败就会落后好几版；
                // 一落后应用就会一直判定「已是最新」。所以再问一次 GitHub API，
                // 两份取更新的那个（API 挂了就用镜像，不影响可用性）。
                val api = if (channel == UpdateChannel.DEV) {
                    runCatching { fetchGithub(channel) }.getOrNull()
                } else {
                    null
                }
                if (mirrored != null && api != null) {
                    return@withContext newestOf(mirrored, api)
                }
                return@withContext mirrored ?: api ?: fetchGithub(channel)
            } catch (e: CheckException) {
                failure = e.failure
            } catch (e: Exception) {
                failure = classify(e)
            }
            if (!failure.isTransient()) throw CheckException(failure)
            if (attempt < CHECK_ATTEMPTS - 1) delay(RETRY_DELAY_MILLIS)
        }
        throw CheckException(failure)
    }

    /** 失败原因给人话，界面上直接用。 */
    fun failureMessage(failure: Failure): String = when (failure) {
        Failure.RATE_LIMITED -> "GitHub 接口限流，请稍后再试"
        Failure.TIMEOUT -> "连接超时，检查网络后再试"
        Failure.NETWORK -> "网络不可用，检查网络后再试"
        Failure.SERVER -> "GitHub 服务异常，请稍后再试"
        Failure.NO_RELEASE -> "这个渠道还没有已发布的版本"
        Failure.MALFORMED -> "拿到的版本数据不完整，请稍后再试"
        Failure.UNKNOWN -> "检查更新失败，请稍后再试"
    }

    /** 手上这个包是否比远端旧。 */
    fun isNewer(info: UpdateInfo): Boolean {
        if (info.buildNumber > 0) {
            return if (BuildConfig.DEV_BUILD_NUMBER > 0) {
                info.buildNumber > BuildConfig.DEV_BUILD_NUMBER
            } else {
                // 手上是正式/本地包，开发版渠道的构建一律算新
                true
            }
        }
        if (info.versionCode > 0) return info.versionCode > BuildConfig.VERSION_CODE
        return compareVersion(info.versionName, BuildConfig.VERSION_NAME) > 0
    }

    /** 两份数据里取更新的那份：开发版比 buildNumber，稳定版比 versionCode。 */
    private fun newestOf(a: UpdateInfo, b: UpdateInfo): UpdateInfo {
        val aKey = if (a.buildNumber > 0) a.buildNumber else a.versionCode
        val bKey = if (b.buildNumber > 0) b.buildNumber else b.versionCode
        return if (bKey > aKey) b else a
    }

    // ---------------------------------------------------------------- 镜像

    private fun fetchMirror(channel: UpdateChannel): UpdateInfo? {
        val url = if (channel == UpdateChannel.DEV) MIRROR_DEV_JSON else MIRROR_STABLE_JSON
        val body = requestText(url, "application/json") ?: return null
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            return null
        }
        val file = json.optString("file")
        if (file.isBlank()) return null
        val versionName = json.optString("version_name")
        if (versionName.isBlank()) return null
        return UpdateInfo(
            channel = channel,
            versionName = versionName,
            versionCode = json.optInt("version_code", -1),
            buildNumber = json.optInt("build_number", 0),
            apkUrl = if (file.startsWith("http")) file else SITE + file.removePrefix("/"),
            size = json.optLong("size", 0L),
            sha256 = json.optString("sha256"),
            notes = json.optString("notes"),
            pageUrl = if (channel == UpdateChannel.DEV) DEV_PAGE else STABLE_PAGE,
            fileName = json.optString("file_name")
        )
    }

    // ---------------------------------------------------------------- GitHub API

    private fun fetchGithub(channel: UpdateChannel): UpdateInfo {
        val api = if (channel == UpdateChannel.DEV) DEV_API else STABLE_API
        val body = try {
            requestText(api, "application/vnd.github+json") ?: throw CheckException(Failure.NETWORK)
        } catch (e: CheckException) {
            // 匿名配额按出口 IP 计数，403 / 429 时改读 Release 页面（不占配额）
            if (e.failure == Failure.RATE_LIMITED) return fetchFromPage(channel)
            throw e
        }
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw CheckException(Failure.MALFORMED)
        }

        if (channel == UpdateChannel.STABLE) {
            val tag = json.optString("tag_name").takeIf { it.isNotBlank() }
                ?: throw CheckException(Failure.MALFORMED)
            val asset = firstApk(json) ?: throw CheckException(Failure.NO_RELEASE)
            return UpdateInfo(
                channel = channel,
                versionName = tag.removePrefix("v"),
                versionCode = -1,
                buildNumber = 0,
                apkUrl = asset.first,
                size = asset.second,
                sha256 = "",
                notes = json.optString("body"),
                pageUrl = json.optString("html_url").ifBlank { STABLE_PAGE },
                fileName = asset.third
            )
        }

        val bodyText = json.optString("body")
        val build = field(bodyText, "development-build")?.toIntOrNull()
            ?: throw CheckException(Failure.MALFORMED)
        val version = field(bodyText, "version") ?: "1.1.0"
        val prefix = "TVPlayer-dev-$build"
        val asset = firstApk(json, prefix) ?: throw CheckException(Failure.NO_RELEASE)
        return UpdateInfo(
            channel = channel,
            versionName = version,
            versionCode = -1,
            buildNumber = build,
            apkUrl = asset.first,
            size = asset.second,
            sha256 = "",
            notes = bodyText,
            pageUrl = DEV_PAGE,
            fileName = asset.third
        )
    }

    /** releases 里的第一个（或指定前缀的）APK：url / size / name。 */
    private fun firstApk(json: JSONObject, prefix: String? = null): Triple<String, Long, String>? {
        val assets = json.optJSONArray("assets") ?: return null
        var fallback: Triple<String, Long, String>? = null
        for (i in 0 until assets.length()) {
            val item = assets.optJSONObject(i) ?: continue
            val name = item.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val url = item.optString("browser_download_url")
            if (url.isBlank()) continue
            val triple = Triple(url, item.optLong("size", 0L), name)
            if (prefix == null) return triple
            if (name.startsWith(prefix)) return triple
            if (fallback == null) fallback = triple
        }
        return fallback
    }

    // ---------------------------------------------------------------- 页面兜底

    private fun fetchFromPage(channel: UpdateChannel): UpdateInfo {
        val page = if (channel == UpdateChannel.DEV) DEV_PAGE else STABLE_PAGE
        val html = requestText(page, "text/html") ?: throw CheckException(Failure.NETWORK)

        val apkRegex = Regex("""/releases/download/([^"/]+)/([^"]+\.apk)""")
        val apkMatch = apkRegex.find(html) ?: throw CheckException(Failure.NO_RELEASE)
        val tag = apkMatch.groupValues[1]
        val fileName = apkMatch.groupValues[2]
        val url = "https://github.com/303562915/tv/releases/download/$tag/$fileName"

        return if (channel == UpdateChannel.DEV) {
            val build = Regex("""TVPlayer-dev-(\d+)\.apk""").find(fileName)?.groupValues?.get(1)?.toIntOrNull()
                ?: throw CheckException(Failure.MALFORMED)
            UpdateInfo(
                channel = channel,
                versionName = "1.1.0",
                versionCode = -1,
                buildNumber = build,
                apkUrl = url,
                size = 0L,
                sha256 = "",
                notes = "",
                pageUrl = DEV_PAGE,
                fileName = fileName
            )
        } else {
            UpdateInfo(
                channel = channel,
                versionName = tag.removePrefix("v"),
                versionCode = -1,
                buildNumber = 0,
                apkUrl = url,
                size = 0L,
                sha256 = "",
                notes = "",
                pageUrl = STABLE_PAGE,
                fileName = fileName
            )
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun requestText(url: String, accept: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("Cache-Control", "no-cache")
            .build()
        try {
            http.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> throw CheckException(Failure.NO_RELEASE)
                    response.code == 403 || response.code == 429 ->
                        throw CheckException(Failure.RATE_LIMITED)
                    response.code >= 500 -> throw CheckException(Failure.SERVER)
                    !response.isSuccessful -> throw CheckException(Failure.UNKNOWN)
                    else -> {
                        val body = response.body?.string().orEmpty()
                        return body.ifBlank { null }
                    }
                }
            }
        } catch (e: CheckException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw CheckException(Failure.TIMEOUT)
        } catch (e: IOException) {
            throw CheckException(Failure.NETWORK)
        }
    }

    private fun classify(e: Exception): Failure = when (e) {
        is SocketTimeoutException -> Failure.TIMEOUT
        is IOException -> Failure.NETWORK
        else -> Failure.UNKNOWN
    }

    private fun field(body: String, name: String): String? {
        body.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("$name:")) {
                return trimmed.removePrefix("$name:").trim().ifBlank { null }
            }
        }
        return null
    }

    private fun Failure.isTransient(): Boolean = when (this) {
        Failure.RATE_LIMITED, Failure.TIMEOUT, Failure.NETWORK, Failure.SERVER -> true
        Failure.NO_RELEASE, Failure.MALFORMED, Failure.UNKNOWN -> false
    }

    /** 分段数字比较，"1.2.0-dev3" 这类后缀按数字段处理。 */
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