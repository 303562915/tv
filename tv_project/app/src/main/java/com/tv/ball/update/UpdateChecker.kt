package com.tv.ball.update

import com.tv.ball.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/*
 * 检查更新（对照 unicanta 的 UpdateChecker.kt）：
 *
 * 数据源按渠道分层，任何一步失败都会落到下一步，而不是整体报废：
 *   稳定版：下载页镜像 latest.json → GitHub API releases/latest → Release 页面 HTML
 *   开发版：下载页镜像 development.json → GitHub API releases/tags/dev → Release 页面 HTML
 *
 * - 镜像（github.io）国内可达但会被墙；API 不需要翻墙但匿名配额按出口 IP 计数；
 *   Release 页面没有配额 —— 三条路互为备份。
 * - 每次请求 12s 封顶（代理"活着但不通"的典型形态），瞬态失败重试一次。
 * - 失败要给出「为什么」：被墙 / 超时 / 配额烧完 / 上游 5xx / 该渠道还没有版本 /
 *   响应被劫持，用户看到一句话就知道该换节点还是等一等。
 */
object UpdateChecker {

    /** 下载页镜像（Pages 生成，国内可达）。 */
    private const val MIRROR_BASE = "https://303562915.github.io/tv/"
    private const val MIRROR_STABLE_JSON = "${MIRROR_BASE}latest.json"
    private const val MIRROR_DEV_JSON = "${MIRROR_BASE}development.json"

    private const val REPO_URL = "https://github.com/303562915/tv"
    private const val STABLE_API = "$REPO_URL/releases/latest"
    private const val DEV_API = "$REPO_URL/releases/tags/dev"

    /** Release 页面没有 API 配额：配额烧完（403/429）时从页面反解 tag。 */
    private const val STABLE_PAGE = "$REPO_URL/releases/latest"
    private const val DEV_PAGE = "$REPO_URL/releases/tag/dev"

    private const val RELEASES_HOME = "https://github.com/303562915/tv/releases"

    /** 一次尝试加一次重试：抖动的代理往往第二次就通了。 */
    private const val CHECK_ATTEMPTS = 2
    private const val RETRY_DELAY_MILLIS = 1_200L

    /** 超过这个时长视为代理坏了，而不是服务器慢。 */
    private const val CHECK_TIMEOUT_SECONDS = 12L

    /** 为什么一次检查两手空空。关于页把它翻成一句话，而不是一句笼统的「失败」。 */
    enum class Failure {
        /** GitHub 回了 403/429：这个出口 IP 的匿名配额没了。 */
        RATE_LIMITED,

        /** 超时：代理活着但不通，或网络太慢。 */
        TIMEOUT,

        /** 请求根本没到 GitHub：DNS、拒连、TLS，节点挂了。 */
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

    /** 检查无法回答时抛出；[failure] 说明是上面哪一种。 */
    class CheckException(val failure: Failure) : Exception(failure.name)

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 读取所选渠道的最新发布。
     * 镜像优先（带 version_code / sha256，还能拿镜像直链），API 兜底，页面最后。
     */
    suspend fun check(channel: UpdateChannel): UpdateInfo = withContext(Dispatchers.IO) {
        var failure = Failure.UNKNOWN
        repeat(CHECK_ATTEMPTS) { attempt ->
            try {
                return@withContext fetchMirror(channel) ?: fetchGithub(channel)
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

    private fun Failure.isTransient(): Boolean = when (this) {
        Failure.RATE_LIMITED, Failure.TIMEOUT, Failure.NETWORK, Failure.SERVER -> true
        Failure.NO_RELEASE, Failure.MALFORMED, Failure.UNKNOWN -> false
    }

    /** 镜像有数据就返回；404 / 解析失败 / 网络异常都返回 null，把机会让给 API。 */
    private fun fetchMirror(channel: UpdateChannel): UpdateInfo? = try {
        val url = if (channel == UpdateChannel.DEV) MIRROR_DEV_JSON else MIRROR_STABLE_JSON
        val body = requestText(url, "application/json") ?: return null
        val json = JSONObject(body)
        val file = json.optString("file")
        if (file.isBlank()) return null
        val versionName = json.optString("version_name").ifBlank { return null }
        val buildNumber = json.optInt("build_number", 0)
        UpdateInfo(
            channel = channel,
            versionName = versionName,
            versionCode = json.optInt("version_code", -1),
            buildNumber = buildNumber,
            apkUrl = if (file.startsWith("http")) file else MIRROR_BASE + file.removePrefix("/"),
            size = json.optLong("size", 0L),
            sha256 = json.optString("sha256"),
            notes = json.optString("notes"),
            pageUrl = MIRROR_BASE,
            fileName = json.optString("file_name")
        )
    } catch (e: Exception) {
        null
    }

    /** GitHub API：稳定版 releases/latest，开发版 releases/tags/dev + notes 字段解析。 */
    private fun fetchGithub(channel: UpdateChannel): UpdateInfo {
        val api = if (channel == UpdateChannel.DEV) DEV_API else STABLE_API
        val body = try {
            requestText(api, "application/vnd.github+json")
                ?: throw CheckException(Failure.NETWORK)
        } catch (e: CheckException) {
            // 匿名配额按出口 IP 计数，403/429 时改读 Release 页面（无配额）
            if (e.failure == Failure.RATE_LIMITED) return fetchFromPage(channel)
            throw e
        }
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw CheckException(Failure.MALFORMED)
        }
        val tag = json.optString("tag_name").takeIf { it.isNotBlank() }
            ?: throw CheckException(Failure.MALFORMED)
        if (channel == UpdateChannel.STABLE) {
            val asset = json.optJSONArray("assets")?.let { assets ->
                (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk", true) }
            } ?: return fetchFromPage(channel)
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url").ifBlank { return fetchFromPage(channel) }
            return UpdateInfo(
                channel = channel,
                versionName = tag.removePrefix("v").ifBlank { tag },
                versionCode = -1,
                buildNumber = 0,
                apkUrl = url,
                size = asset.optLong("size", 0L),
                sha256 = "",
                notes = json.optString("body"),
                pageUrl = json.optString("html_url").ifBlank { RELEASES_HOME },
                fileName = name
            )
        }
        // 开发版：必须真是 dev 预发布，版本与构建号写在 notes 里
        if (tag != "dev" || json.optBoolean("prerelease", false)) {
            throw CheckException(Failure.MALFORMED)
        }
        val notes = json.optString("body")
        fun field(name: String): String? = notes.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name:") }
            ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
        val version = field("version")
            ?.takeIf { Regex("[vV]?\\d+(\\.\\d+)+([+-][0-9A-Za-z.-]+)?").matches(it) }
            ?: throw CheckException(Failure.MALFORMED)
        val buildNumber = field("development-build")?.toIntOrNull()?.takeIf { it > 0 }
            ?: throw CheckException(Failure.MALFORMED)
        val asset = json.optJSONArray("assets")?.let { assets ->
            (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").startsWith("TVPlayer-dev-$buildNumber") &&
                    it.optString("name").endsWith(".apk", true) }
        } ?: return fetchFromPage(channel)
        if (asset == null) return fetchFromPage(channel)
        val name = asset.optString("name")
        val url = asset.optString("browser_download_url").ifBlank { return fetchFromPage(channel) }
        return UpdateInfo(
            channel = UpdateChannel.DEV,
            versionName = version.removePrefix("v"),
            versionCode = -1,
            buildNumber = buildNumber,
            apkUrl = url,
            size = asset.optLong("size", 0L),
            sha256 = "",
            notes = notes,
            pageUrl = DEV_PAGE,
            fileName = name
        )
    }

    /** 最后一条路：Release 页面本身。无配额，从 HTML 反解 tag / notes 字段。 */
    private fun fetchFromPage(channel: UpdateChannel): UpdateInfo {
        val page = requestText(
            if (channel == UpdateChannel.DEV) DEV_PAGE else STABLE_PAGE,
            "text/html"
        ) ?: throw CheckException(Failure.NETWORK)
        val tag = Regex("/releases/tag/([^\"'#?<>\\s]+)").find(page)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() && it != "latest" }
            ?: throw CheckException(Failure.MALFORMED)
        if (channel == UpdateChannel.STABLE) {
            return UpdateInfo(
                channel = channel,
                versionName = tag.removePrefix("v"),
                versionCode = -1,
                buildNumber = 0,
                apkUrl = "",
                size = 0,
                sha256 = "",
                notes = "",
                pageUrl = "$REPO_URL/releases/tag/$tag",
                fileName = ""
            )
        }
        val version = Regex("version:\\s*[vV]?(\\d+(?:\\.\\d+)+[0-9A-Za-z.+-]*)").find(page)
            ?.groupValues?.get(1)
            ?: throw CheckException(Failure.MALFORMED)
        val buildNumber = Regex("development-build:\\s*(\\d+)").find(page)
            ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
            ?: throw CheckException(Failure.MALFORMED)
        return UpdateInfo(
            channel = UpdateChannel.DEV,
            versionName = version,
            versionCode = -1,
            buildNumber = buildNumber,
            apkUrl = "",
            size = 0,
            sha256 = "",
            notes = "",
            pageUrl = DEV_PAGE,
            fileName = ""
        )
    }

    /** 一次有界的 GET；状态码决定报告哪种失败。失败返回 null（让位下一步），可分类的抛 CheckException。 */
    private fun requestText(url: String, accept: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", "TVPlayer-Android (+$REPO_URL)")
            .header("Cache-Control", "no-cache")
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                when {
                    response.code == 403 || response.code == 429 ->
                        throw CheckException(Failure.RATE_LIMITED)
                    response.code == 404 -> null
                    response.code >= 500 -> throw CheckException(Failure.SERVER)
                    !response.isSuccessful -> null
                    else -> response.body?.string()?.trim()?.takeIf { it.isNotEmpty() }
                }
            }
        } catch (e: CheckException) {
            throw e
        } catch (e: Exception) {
            throw CheckException(classify(e))
        }
    }

    /** 把网络异常按文案归桶：超时 / 连不上 / 其它。 */
    private fun classify(e: Throwable): Failure {
        val text = e.toString().lowercase()
        return when {
            "timeout" in text || "timed out" in text -> Failure.TIMEOUT
            "unresolvedaddress" in text || "unknownhost" in text || "connect" in text ||
                "ioexception" in text || "socket" in text || "ssl" in text ||
                "handshake" in text || "refused" in text || "offline" in text ||
                "network" in text -> Failure.NETWORK
            else -> Failure.UNKNOWN
        }
    }

    /** 是否有比当前安装包更新的版本。 */
    fun isNewer(info: UpdateInfo): Boolean {
        if (info.versionCode > 0 && BuildConfig.VERSION_CODE > 0) {
            return info.versionCode > BuildConfig.VERSION_CODE
        }
        val remote = info.versionName
        val local = BuildConfig.VERSION_NAME
        if (isNewerVersion(remote, local)) return true
        if (isNewerVersion(local, remote)) return false
        // 同名版本：开发版比构建号；本地是开发版而远端是同名稳定版时，本地已经更新。
        return if (info.channel == UpdateChannel.DEV) {
            info.buildNumber > BuildConfig.DEV_BUILD_NUMBER
        } else {
            BuildConfig.DEV_BUILD_NUMBER == 0
        }
    }

    /** 分段数字比较："1.2.0-debug" 这类后缀按数字段处理。 */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        fun parts(v: String): List<Int> = v.trim()
            .trimStart('v', 'V')
            .substringBefore('-')
            .substringBefore('+')
            .split('.')
            .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** 失败原因的一句话文案；关于页与弹窗共用。 */
    fun failureMessage(failure: Failure): String = when (failure) {
        Failure.RATE_LIMITED -> "GitHub 接口配额已用完（403/429），稍后再试"
        Failure.TIMEOUT -> "请求超时：网络或代理没有响应"
        Failure.NETWORK -> "网络不可达：连不上 GitHub（镜像与接口都不通）"
        Failure.SERVER -> "GitHub 服务异常（5xx），稍后再试"
        Failure.NO_RELEASE -> "该渠道还没有已发布的版本"
        Failure.MALFORMED -> "返回数据异常：响应可能被劫持或不是版本信息"
        Failure.UNKNOWN -> "检查更新失败，请稍后再试"
    }
}
