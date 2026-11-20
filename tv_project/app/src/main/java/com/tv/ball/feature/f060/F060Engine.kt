package com.tv.ball.feature.f060

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * 一次预检的结果。
 *
 * @param ok         地址是否可达（拿到 2xx~3xx 之外的 4xx 也算"服务器活着"，见 [isReachableCode]）
 * @param statusCode 拿到的 HTTP 状态码；连接层就失败（超时 / DNS / TLS）时为 null
 * @param ms         本方法内部测得的总耗时（HEAD 失败再发 GET 时是两发之和）
 * @param error      失败原因的人话描述；成功为 null
 * @param cachedAtMs 结果被写入缓存的时刻；直接返回未缓存的实时结果时等于发起时刻
 */
data class PreflightResult(
    val ok: Boolean,
    val statusCode: Int?,
    val ms: Long,
    val error: String?,
    val cachedAtMs: Long
) {
    /** 界面用：状态码没有就说"—"（连接层失败），不显示 0。 */
    val statusText: String get() = statusCode?.toString() ?: "—"

    companion object {
        /** 4xx 说明服务器活着、只是不接受这个请求（防盗链 / 需要 Referer），不应判成"不可达"。 */
        fun isReachableCode(code: Int): Boolean = code in 200..499

        /** 输入本身不合法，没发请求。 */
        fun invalid(reason: String, nowMs: Long): PreflightResult =
            PreflightResult(false, null, 0L, reason, nowMs)
    }
}

/**
 * 播放地址连通性预检引擎。
 *
 * 要解决的问题：换源列表里混着大量失效地址，点进去要等播放器自己超时才知道播不了，
 * 一集片子可能连试五六个源。预检在点播之前先问一句"这个地址通不通"，不通直接提示。
 *
 * 探测方式按代价从低到高：
 * 1. `HEAD`：只要响应头，不下载任何字节，最省流量；
 * 2. `HEAD` 失败（很多电视盒子源站 / 部分 CDN 直接 405 / 501 回绝 HEAD）时回落
 *    `GET` + `Range: bytes=0-0`：只取第 1 个字节，服务端按 206 返回，同样不传整片。
 *
 * 超时 5 秒（连接 / 读 / 总调用都是 5 秒）：验收要求"超时 5 秒判定不可达"，所以整条链路
 * 包括回落的 GET 也要能在这个量级收口——两发各自 5 秒上限，`callTimeout` 再兜一层。
 *
 * 结果缓存 5 分钟：同一个地址反复点播（重试、切集再切回）不应该每次都发网络请求。
 * 缓存键是归一化后的 URL 字符串，值是 [PreflightResult] + 写入时刻；
 * 过期判断一律交给 [fresh]，界面和播放链路用同一份判断，不会出现"界面说新鲜、播放器说过期"。
 *
 * 全部是阻塞调用，调用方必须在 Dispatchers.IO 上跑；引擎自身线程安全（缓存是 ConcurrentHashMap）。
 */
class F060Engine(
    private val client: OkHttpClient = defaultClient(),
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** URL → 结果快照（带写入时刻）。 */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, PreflightResult>()

    /**
     * 预检 [url]。
     *
     * @param force 强制刷新：跳过缓存直接发请求，并把新结果写回缓存（界面上"重新预检"用这个）
     */
    fun preflight(url: String, force: Boolean = false): PreflightResult {
        val now = clock()
        val key = normalize(url)
        if (key.isEmpty()) return PreflightResult.invalid(EMPTY_INPUT, now)

        val httpUrl = key.toHttpUrlOrNull()
            ?: return PreflightResult.invalid(BAD_SCHEME, now)

        if (!force) {
            val hit = cache[key]
            if (hit != null && fresh(hit, now)) {
                // 命中缓存时保留原始 cachedAtMs：界面靠它显示"几分钟前的结果"
                return hit
            }
        }

        val started = clock()
        val outcome = probe(httpUrl.toString())
        val result = outcome.copy(
            ms = clock() - started,
            cachedAtMs = clock()
        )
        // 失败的也缓存：一个死地址 5 分钟内点十次也没必要试十次
        cache[key] = result
        return result
    }

    /** 只读缓存，不发网络请求；播放链路起步时用它判断"刚才预检过没有"。 */
    fun cached(url: String): PreflightResult? {
        val key = normalize(url)
        if (key.isEmpty()) return null
        val hit = cache[key] ?: return null
        return hit.takeIf { fresh(it, clock()) }
    }

    /**
     * 判断一条缓存结果还有没有效（验收：5 分钟）。
     *
     * 抽成静态方法是为了让播放链路不必持有引擎实例也能用同一套判断；
     * 未来时刻（时钟回拨）一律当作有效，不回绕成"每次都要重测"。
     */
    fun fresh(result: PreflightResult, nowMs: Long): Boolean {
        val age = nowMs - result.cachedAtMs
        return age in 0..TTL_MS
    }

    /** 清空缓存：换源列表整体刷新时用。 */
    fun clearCache() {
        cache.clear()
    }

    /**
     * 一行摘要，给提示 Toast / 日志用："可达 · HTTP 206 · 132 ms"，失败时带上原因。
     *
     * 是否命中缓存由调用方拼（它才知道这次是不是跳过了请求），摘要本身只陈述结果事实，
     * 免得同一份文案在界面和日志里出现"缓存（缓存）"这种重复。
     */
    fun formatSummary(result: PreflightResult): String {
        val head = if (result.ok) {
            "可达 · HTTP ${result.statusText}"
        } else {
            "不可达${result.statusCode?.let { " · HTTP $it" } ?: ""}"
        }
        val elapsed = if (result.ms > 0L) " · ${result.ms} ms" else ""
        val tail = result.error?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
        return "$head$elapsed$tail"
    }

    // ===== 探测 =====

    /**
     * 真正的网络动作：先 HEAD，不理想再 GET + Range。
     *
     * 什么时候算"HEAD 不够、要回落 GET"：
     * - HEAD 抛异常（超时 / 连接被拒 / DNS / TLS）；
     * - HEAD 返回 405 / 501（方法不被允许）、403（部分源站对 HEAD 一律拒，GET 却放行）。
     * 其余状态码直接采信：2xx/3xx/4xx 已经足够判断"服务器活着且地址被受理"。
     */
    private fun probe(url: String): PreflightResult {
        val headFailure = runProbe(url, useHead = true)
        if (headFailure.ok || headFailure.statusCode != null && !needGetFallback(headFailure.statusCode)) {
            return headFailure
        }

        val getResult = runProbe(url, useHead = false)
        return when {
            getResult.ok -> getResult
            // 两边都挂：GET 的错误更贴近播放器实际行为（播放器发的是 GET），优先报它
            getResult.error != null -> getResult.copy(
                ms = headFailure.ms + getResult.ms,
                error = "${getResult.error}（HEAD：${headFailure.error ?: "HTTP ${headFailure.statusText}"}）"
            )
            else -> getResult.copy(ms = headFailure.ms + getResult.ms)
        }
    }

    private fun needGetFallback(code: Int): Boolean = code == 405 || code == 501 || code == 403

    /**
     * 发一次请求。[useHead] 为 false 时带 `Range: bytes=0-0`。
     *
     * 用 `range(0, 0)` 而不是手写 header，OkHttp 会正确拼成 `bytes=0-0`；
     * 服务端支持就回 206 + 1 字节，不支持就回 200 + 完整响应头（我们随即 close，不读 body）。
     */
    private fun runProbe(url: String, useHead: Boolean): PreflightResult {
        val builder = Request.Builder().url(url)
            // 伪装成常规播放器 UA：少数源站对无 UA 请求直接 403
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")

        val request = if (useHead) {
            builder.head().build()
        } else {
            builder.header("Range", "bytes=0-0").get().build()
        }

        val started = clock()
        return try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                val ms = clock() - started
                val reachable = PreflightResult.isReachableCode(code)
                PreflightResult(
                    ok = reachable,
                    statusCode = code,
                    ms = ms,
                    error = if (reachable) null else "HTTP $code",
                    cachedAtMs = clock()
                )
            }
        } catch (e: SocketTimeoutException) {
            PreflightResult(false, null, clock() - started, "超时（${TIMEOUT_SECONDS} 秒）", clock())
        } catch (e: UnknownHostException) {
            PreflightResult(false, null, clock() - started, "域名解析失败", clock())
        } catch (e: SSLException) {
            PreflightResult(false, null, clock() - started, "TLS 握手失败：${brief(e)}", clock())
        } catch (e: Exception) {
            PreflightResult(false, null, clock() - started, brief(e), clock())
        }
    }

    // ===== 工具 =====

    /**
     * 归一化：去空白、补 scheme、去 fragment。
     *
     * 用户从别处粘过来的手机号片源常常是 `http://x.com/a.m3u8#t=1`，fragment 不影响请求，
     * 但会让缓存键分裂成两份，所以先剥掉。缺 scheme 的按 http 补（播放链路也是这么猜的）。
     */
    private fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "http://$trimmed"
        }
        return withScheme.substringBefore('#')
    }

    private fun brief(e: Throwable): String {
        val message = e.message?.takeIf { it.isNotBlank() }
        return if (message != null) "${e.javaClass.simpleName}: $message" else e.javaClass.simpleName
    }

    companion object {
        /** 缓存有效期：验收硬要求 5 分钟。 */
        const val TTL_MS = 5 * 60 * 1000L

        /** 单次请求超时上限：验收硬要求 5 秒。 */
        const val TIMEOUT_SECONDS = 5L

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        private const val EMPTY_INPUT = "地址为空"
        private const val BAD_SCHEME = "地址不合法（只支持 http / https）"

        /** 示例地址：界面上一键填入，覆盖"直连 / 带 Range 的 CDN / 需要 Referer"几种典型。 */
        val SAMPLES: List<Pair<String, String>> = listOf(
            "示例：测试视频" to "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            "示例：国内 CDN" to "https://vd3.bdstatic.com/mda-pk5c3i2/cae_h264_nowatermark.mp4",
            "示例：失效地址" to "https://invalid.example.com/not-exist.m3u8"
        )

        /**
         * 预检专用客户端。
         *
         * 独立于 [com.tv.ball.di.Dependencies.okHttpClient]（那个 callTimeout 为换源优化得很短），
         * 也不跟随重定向之外做任何额外重试：重定向跟随是必要的（很多源站 302 到真实 CDN），
         * 但 `retryOnConnectionFailure` 关掉，避免一次预检在后台悄悄发好几发请求把 5 秒拖成 15 秒。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
