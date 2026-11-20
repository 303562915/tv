package com.tv.ball.feature.f055

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * 一次解析的结果。
 *
 * @param ms       本次解析耗时（毫秒）；走回退时是"DoH 失败 + 系统 DNS 成功"的合计耗时
 * @param addresses 解析到的地址（去重且保序）
 * @param error    DoH 通道失败的原因；成功为 null
 * @param fellBack 是否已回退到系统 DNS（[InetAddress.getAllByName]）
 */
data class DohResult(
    val ms: Long,
    val addresses: List<String>,
    val error: String?,
    val fellBack: Boolean
) {
    /** 拿到地址才算成功；回退成功也算成功，只是通道不同。 */
    val ok: Boolean get() = addresses.isNotEmpty()

    /** 结果来自哪条通道，界面直接显示这个。 */
    val source: String
        get() = when {
            fellBack && error == null -> "系统 DNS"
            fellBack -> "系统 DNS（已回退）"
            error != null -> "DoH 失败"
            else -> "DoH"
        }
}

/**
 * 自定义 DoH 解析引擎。
 *
 * 要解决的问题：运营商 DNS 被污染时，域名解析出来就是错的，播放器连不上源站。
 * DoH（DNS over HTTPS）把查询塞进普通 HTTPS 请求，中间人看不到也改不了，换个可信端点即可绕开污染。
 *
 * 协议按 RFC 8484 的 JSON 形式（阿里 / 腾讯 / Cloudflare / Google 都实现）：
 * ```
 * GET {dohUrl}?name=HOST&type=A
 * Accept: application/dns-json
 * → {"Status":0,"Answer":[{"name":"x.com","type":1,"TTL":60,"data":"1.2.3.4"}]}
 * ```
 * 先查 A，拿不到再补一发 AAAA（IPv6-only / 双栈站点走 AAAA 更稳）；两发都没有 data 就算 DoH 失败。
 *
 * 失败必须回退，这是验收硬要求：地址填错、被墙、超时一律自动走 [InetAddress.getAllByName]，
 * 并把失败原因原样带回去，界面显示"已回退系统 DNS + 原因"，不在解析链路里静默吞掉。
 *
 * 网络只发生在传入的 [client] 上，引擎本身无状态，可并发调用；全部是阻塞调用，
 * 调用方必须在 Dispatchers.IO 上跑。
 */
class F055Engine(
    private val config: F055Config,
    private val client: OkHttpClient = defaultClient(),
    private val gson: Gson = Gson()
) {

    /** 按持久化配置解析 [host]：开关关闭或没配地址时直接走系统 DNS。 */
    fun resolve(host: String): DohResult {
        val snapshot = config.snapshot()
        return resolve(host, snapshot.dohUrl, snapshot.enabled)
    }

    /**
     * 用显式配置解析 [host]（界面还没保存输入框内容时用这个，避免为了测试先写盘）。
     *
     * @param dohUrl  DoH 端点，例如 `https://dns.alidns.com/resolve`
     * @param enabled 开关；false 时整条链路直接走系统 DNS
     */
    fun resolve(host: String, dohUrl: String?, enabled: Boolean): DohResult {
        val name = normalizeHost(host)
        if (name.isEmpty()) return DohResult(0L, emptyList(), "域名为空", false)

        if (!enabled) {
            val started = System.currentTimeMillis()
            return systemResolve(name).copy(
                ms = System.currentTimeMillis() - started,
                error = "DoH 未启用",
                fellBack = true
            )
        }

        val started = System.currentTimeMillis()
        return when (val outcome = queryChain(name, dohUrl?.trim().orEmpty())) {
            is Outcome.Success -> DohResult(
                ms = System.currentTimeMillis() - started,
                addresses = outcome.addresses,
                error = null,
                fellBack = false
            )
            is Outcome.Failure -> systemResolve(name).copy(
                ms = System.currentTimeMillis() - started,
                error = outcome.reason,
                fellBack = true
            )
        }
    }

    /**
     * 只走 DoH、不回退：界面上的"仅测 DoH"用。
     * 失败返回空地址 + 原因，不抛异常。
     */
    fun resolveDohOnly(host: String, dohUrl: String?): DohResult {
        val name = normalizeHost(host)
        if (name.isEmpty()) return DohResult(0L, emptyList(), "域名为空", false)
        val started = System.currentTimeMillis()
        return when (val outcome = queryChain(name, dohUrl?.trim().orEmpty())) {
            is Outcome.Success -> DohResult(
                System.currentTimeMillis() - started, outcome.addresses, null, false
            )
            is Outcome.Failure -> DohResult(
                System.currentTimeMillis() - started, emptyList(), outcome.reason, false
            )
        }
    }

    /** 只看系统 DNS（[InetAddress.getAllByName]），失败返回空列表而不抛异常。 */
    fun fallback(host: String): DohResult {
        val started = System.currentTimeMillis()
        return systemResolve(normalizeHost(host)).copy(ms = System.currentTimeMillis() - started)
    }

    /**
     * 抓一次 DoH JSON 原始响应，给"端点连通性测试"和排障用；失败返回 (null, 原因)。
     * 不解析 DNS 语义，只看 HTTP 通不通、响应体长什么样。
     */
    fun probe(dohUrl: String, probeHost: String = PROBE_HOST): Pair<String?, String?> {
        val url = buildUrl(dohUrl.trim(), probeHost, TYPE_A) ?: return null to "DoH 地址不合法"
        return try {
            client.newCall(buildRequest(url)).execute().use { response ->
                if (!response.isSuccessful) {
                    null to "HTTP ${response.code}"
                } else {
                    val body = response.body?.string()
                    if (body.isNullOrBlank()) null to "响应为空" else body to null
                }
            }
        } catch (e: Exception) {
            null to describe(e)
        }
    }

    // ===== DoH =====

    private sealed interface Outcome {
        data class Success(val addresses: List<String>) : Outcome
        data class Failure(val reason: String) : Outcome
    }

    /** A → AAAA：拿到地址就停；两发都失败时把两边原因合并（A 的放前面，更贴近配错的典型场景）。 */
    private fun queryChain(host: String, dohUrl: String): Outcome {
        if (dohUrl.isEmpty()) return Outcome.Failure("DoH 地址为空")
        if (buildUrl(dohUrl, host, TYPE_A) == null) {
            return Outcome.Failure("DoH 地址不合法：$dohUrl")
        }

        val a = query(host, dohUrl, TYPE_A)
        if (a is Outcome.Success) return a

        val aaaa = query(host, dohUrl, TYPE_AAAA)
        if (aaaa is Outcome.Success) return aaaa

        val aReason = (a as Outcome.Failure).reason
        val aaaaReason = (aaaa as Outcome.Failure).reason
        return Outcome.Failure(if (aReason == aaaaReason) aReason else "$aReason；AAAA：$aaaaReason")
    }

    private fun query(host: String, dohUrl: String, type: String): Outcome {
        val url = buildUrl(dohUrl, host, type) ?: return Outcome.Failure("DoH 地址不合法")
        return try {
            client.newCall(buildRequest(url)).execute().use { response ->
                if (!response.isSuccessful) {
                    // 404 / 403 多半是端点写错（把网页地址当成了 resolve 接口）
                    Outcome.Failure("HTTP ${response.code}")
                } else {
                    val body = response.body?.string()
                    if (body.isNullOrBlank()) {
                        Outcome.Failure("响应为空")
                    } else {
                        parseResponse(body, host)
                    }
                }
            }
        } catch (e: Exception) {
            Outcome.Failure(describe(e))
        }
    }

    /**
     * 解析 RFC 8484 JSON 响应。
     *
     * 只取 `Answer[].data` 并按 `type` 过滤：CNAME 链里的 data 是域名不是 IP，混进来会让界面
     * 显示"地址 0 个却有内容"。`Status` 非 0（3 = NXDOMAIN）单独给一句人话。
     */
    private fun parseResponse(body: String, host: String): Outcome {
        val payload = try {
            gson.fromJson(body, DohResponse::class.java)
        } catch (e: Exception) {
            return Outcome.Failure("响应不是合法 JSON")
        } ?: return Outcome.Failure("响应不是合法 JSON")

        val addresses = payload.answers.orEmpty()
            .filter { it.type == null || it.type == TYPE_A_NUM || it.type == TYPE_AAAA_NUM }
            .mapNotNull { it.data?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        if (addresses.isNotEmpty()) return Outcome.Success(addresses)

        return when (val status = payload.status ?: 0) {
            0 -> Outcome.Failure("DoH 未返回地址（$host 无 A / AAAA 记录）")
            3 -> Outcome.Failure("域名不存在（NXDOMAIN）")
            else -> Outcome.Failure("DNS 状态码 $status")
        }
    }

    /** `name` / `type` 交给 HttpUrl 编码，域名里的非 ASCII 与特殊字符不会破坏查询串。 */
    private fun buildUrl(dohUrl: String, host: String, type: String): HttpUrl? =
        dohUrl.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("name", host)
            ?.addQueryParameter("type", type)
            ?.build()

    private fun buildRequest(url: HttpUrl): Request = Request.Builder()
        .url(url)
        // RFC 8484 的 JSON 形式：服务端据此回 application/dns-json 而不是二进制 wire 格式
        .header("Accept", "application/dns-json")
        .get()
        .build()

    // ===== 系统 DNS =====

    private fun systemResolve(host: String): DohResult {
        if (host.isEmpty()) return DohResult(0L, emptyList(), "域名为空", true)
        return try {
            val addresses = InetAddress.getAllByName(host)
                // 带 %wlan0 / %eth0 的 scoped IPv6 去掉 scope 才有可比性，也方便直接给播放器用
                .map { it.hostAddress?.substringBefore('%').orEmpty() }
                .filter { it.isNotEmpty() }
                .distinct()
            DohResult(0L, addresses, if (addresses.isEmpty()) "系统 DNS 无结果" else null, true)
        } catch (e: Exception) {
            DohResult(0L, emptyList(), "系统 DNS 也失败：${describe(e)}", true)
        }
    }

    // ===== 工具 =====

    /** 用户可能把整条 URL 粘进来，剥掉 scheme / 路径 / 端口，只留主机名。 */
    private fun normalizeHost(raw: String): String = raw.trim()
        .removePrefix("http://")
        .removePrefix("https://")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore(':')
        .trim()

    private fun describe(e: Throwable): String {
        val message = e.message?.takeIf { it.isNotBlank() }
        return if (message != null) "${e.javaClass.simpleName}: $message" else e.javaClass.simpleName
    }

    /** JSON 响应体：字段名保持服务端原样，只映射用得到的那几个。 */
    private data class DohResponse(
        @SerializedName("Status") val status: Int? = null,
        @SerializedName("Answer") val answers: List<AnswerRecord>? = null
    )

    private data class AnswerRecord(
        @SerializedName("type") val type: Int? = null,
        @SerializedName("data") val data: String? = null,
        @SerializedName("TTL") val ttl: Long? = null
    )

    companion object {
        const val TYPE_A = "A"
        const val TYPE_AAAA = "AAAA"

        /** RFC 1035：A = 1、AAAA = 28。 */
        private const val TYPE_A_NUM = 1
        private const val TYPE_AAAA_NUM = 28

        /** 默认 DoH 端点：阿里公共 DNS，国内可达性好，与界面默认值一致。 */
        const val DEFAULT_DOH_URL = "https://dns.alidns.com/resolve"

        /** 连通性测试用的域名，选一个各家都能解的。 */
        const val PROBE_HOST = "www.baidu.com"

        /** 常见端点，界面上一键填入。 */
        val PRESETS: List<Pair<String, String>> = listOf(
            "阿里公共 DNS" to DEFAULT_DOH_URL,
            "腾讯公共 DNS" to "https://doh.pub/dns-query",
            "Cloudflare" to "https://cloudflare-dns.com/dns-query"
        )

        /**
         * DoH 专用客户端。
         *
         * 不直接复用 [com.tv.ball.di.Dependencies.okHttpClient]：那个客户端的 callTimeout 只有 2 秒
         * （为换源优化的），DoH 打境外端点常被它掐断，误判成"被污染"。这里放宽到 5 秒 / 总 8 秒。
         * 端点域名本身仍要靠系统 DNS 解析一次，这一点绕不开，所以失败回退必须无条件存在。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/**
 * DoH 配置持久化：端点地址 + 开关（外加最近一次测试的域名，纯界面回填用）。
 *
 * 独立成类而不是塞进 Activity，是为了让解析链路（数据源 / 播放器）也能读到同一份配置，
 * 不必依赖界面存在。
 */
class F055Config(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** DoH 端点地址；没配过或存了空串时给默认值。 */
    var dohUrl: String
        get() = prefs.getString(KEY_URL, F055Engine.DEFAULT_DOH_URL)
            ?.takeIf { it.isNotBlank() }
            ?: F055Engine.DEFAULT_DOH_URL
        set(value) {
            prefs.edit().putString(KEY_URL, value.trim()).apply()
        }

    /** 开关：关闭后解析一律走系统 DNS。 */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /** 最近一次测试的域名。 */
    var lastHost: String
        get() = prefs.getString(KEY_LAST_HOST, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_LAST_HOST, value.trim()).apply()
        }

    /** 一次性快照：解析链路按快照走，不必每次查询都碰 SharedPreferences。 */
    fun snapshot(): Snapshot = Snapshot(dohUrl, enabled)

    data class Snapshot(val dohUrl: String, val enabled: Boolean)

    companion object {
        private const val PREFS_NAME = "f055_doh_prefs"
        private const val KEY_URL = "f055_doh_url"
        private const val KEY_ENABLED = "f055_doh_enabled"
        private const val KEY_LAST_HOST = "f055_last_host"
    }
}