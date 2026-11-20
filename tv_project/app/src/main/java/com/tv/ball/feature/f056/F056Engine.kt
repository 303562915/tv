package com.tv.ball.feature.f056

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * F056「代理配置」的真实能力层。
 *
 * 有些网络环境（公司网、机房出口、跨境专线）必须经 HTTP 或 SOCKS5 代理才能出去，
 * 这里的职责就三件：
 * 1. [ProxyConfig] 存类型 / 主机 / 端口 / 开关，并提供 [validate] 的字段级校验；
 * 2. [buildOkHttp] 把配置翻译成 `java.net.Proxy` 挂到 OkHttpClient 上，业务侧直接用这个客户端即可；
 * 3. [testConnection] 用真实请求打 `https://www.gstatic.com/generate_204`，回报状态码与耗时。
 *
 * 网络动作全部切 [Dispatchers.IO]；失败不抛异常，统一收敛到 [TestResult.error]，
 * 界面拿到的永远是可渲染的数据。引擎不认识 `R` 与文案，文案由 Activity 取 string 资源。
 */
object F056Engine {

    /** HTTP 代理默认端口：8080 是内网代理最普遍的约定。 */
    const val DEFAULT_PORT_HTTP: Int = 8080

    /** SOCKS 代理默认端口：1080 是 SOCKS5 的约定俗成。 */
    const val DEFAULT_PORT_SOCKS: Int = 1080

    /** 测试连接用的探测地址：204 无响应体，最省流量也最不容易被缓存干扰。 */
    const val TEST_URL: String = "https://www.gstatic.com/generate_204"

    /** 204 是这条探测地址的正常结果，其余状态码按「代理通了但目标另有说法」处理。 */
    const val EXPECTED_CODE: Int = 204

    /** 测试连接的超时（连接、读、写各算一次），按需求固定 5 秒。 */
    const val TEST_TIMEOUT_MS: Int = 5_000

    /** 界面状态机的三档，只影响颜色，不影响逻辑。 */
    enum class Level {
        /** 还没测 / 没配。 */
        IDLE,

        /** 通了。 */
        OK,

        /** 明确失败。 */
        BAD
    }

    /** 代理协议：OkHttp 只区分 HTTP 与 SOCKS，SOCKS 侧按 SOCKS5 处理。 */
    enum class ProxyType {
        /** HTTP CONNECT 代理，明文 HTTP 与 HTTPS 都能过。 */
        HTTP,

        /** SOCKS 代理（OkHttp 走 SOCKS5，不支持用户名口令认证）。 */
        SOCKS
    }

    /**
     * 一份代理配置。
     *
     * [host] 保留用户原始输入（可能带空格），真正建连接前一律过 [normalized]：
     * 存进去的永远是 trim 过、端口落在合法区间的值。
     */
    data class ProxyConfig(
        val type: ProxyType = ProxyType.HTTP,
        val host: String = "",
        val port: Int = DEFAULT_PORT_HTTP,
        val enabled: Boolean = false
    ) {
        /** 字段校验是否通过。 */
        val valid: Boolean
            get() = problems().isEmpty()

        /** 校验不通过的原因，空列表代表合法。 */
        fun problems(): List<Problem> = buildList {
            if (host.isBlank()) add(Problem.HOST_EMPTY)
            if (port !in 1..65535) add(Problem.PORT_RANGE)
        }

        /** 规整化：主机去空格，端口越界时回落到该协议的默认端口。 */
        fun normalized(): ProxyConfig = copy(
            host = host.trim(),
            port = if (port in 1..65535) port else defaultPortFor(type)
        )

        /** 一句话描述，日志与界面摘要共用。 */
        fun describe(): String = "${type.name} ${host.trim()}:$port"
    }

    /** 校验失败项；具体文案在 Activity 侧按 string 资源取。 */
    enum class Problem {
        /** 主机为空。 */
        HOST_EMPTY,

        /** 端口不在 1~65535。 */
        PORT_RANGE
    }

    /** 校验结果。 */
    data class Validation(
        val ok: Boolean = true,
        val problems: List<Problem> = emptyList()
    )

    /** 测试连接结果；[ms] 为负表示没测到（参数非法或提前失败）。 */
    data class TestResult(
        val ok: Boolean = false,
        val code: Int = -1,
        val ms: Long = -1L,
        val error: String? = null
    )

    /** 校验入口：把 [ProxyConfig.problems] 包一层，方便调用方判 [Validation.ok]。 */
    fun validate(config: ProxyConfig): Validation {
        val problems = config.problems()
        return Validation(ok = problems.isEmpty(), problems = problems)
    }

    /** 协议对应的默认端口。 */
    fun defaultPortFor(type: ProxyType): Int = when (type) {
        ProxyType.HTTP -> DEFAULT_PORT_HTTP
        ProxyType.SOCKS -> DEFAULT_PORT_SOCKS
    }

    /**
     * 把配置翻成 `java.net.Proxy`。
     *
     * 这是整个功能与系统网络栈的唯一接缝：OkHttp、`URL.openConnection`、`Socket` 的代理参数
     * 都吃这个对象，所以类型映射只写这一处。
     */
    fun proxyOf(config: ProxyConfig): Proxy {
        val normalized = config.normalized()
        val type = when (normalized.type) {
            ProxyType.HTTP -> Proxy.Type.HTTP
            ProxyType.SOCKS -> Proxy.Type.SOCKS
        }
        return Proxy(type, InetSocketAddress(normalized.host, normalized.port))
    }

    /**
     * 按配置造一个带代理的 OkHttpClient。
     *
     * 超时按 5 秒对齐测试口径；`followRedirects` 保持默认（204 不会跳转），
     * 关闭自动重试，否则代理没开时会白等两轮 5 秒才报错。
     */
    fun buildOkHttp(config: ProxyConfig): OkHttpClient {
        val timeout = TEST_TIMEOUT_MS.toLong()
        return OkHttpClient.Builder()
            .proxy(proxyOf(config))
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .callTimeout(timeout + 1_000L, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * 测试连接：GET [TEST_URL]，拿状态码 + 耗时。
     *
     * 判定口径：
     * - 拿到 2xx/3xx → 代理链路可用（[TestResult.ok] 为真，[TestResult.code] 给出真实状态码）；
     * - 拿到 4xx/5xx → 代理是通的，但出口那边有话要说，按失败展示但仍回报状态码；
     * - 抛异常（连不上代理、SOCKS 握手失败、超时）→ [TestResult.error] 带异常类名与消息。
     */
    suspend fun testConnection(
        config: ProxyConfig,
        url: String = TEST_URL
    ): TestResult = withContext(Dispatchers.IO) {
        val normalized = config.normalized()
        val problems = normalized.problems()
        if (problems.isNotEmpty()) {
            return@withContext TestResult(
                ok = false,
                error = problems.joinToString(separator = ",") { it.name }
            )
        }

        val client = buildOkHttp(normalized)
        val request = Request.Builder().url(url).get().build()
        val startedAt = System.nanoTime()
        try {
            client.newCall(request).execute().use { response ->
                val elapsed = (System.nanoTime() - startedAt) / 1_000_000L
                TestResult(
                    ok = response.code in 200..399,
                    code = response.code,
                    ms = elapsed
                )
            }
        } catch (t: Throwable) {
            val elapsed = (System.nanoTime() - startedAt) / 1_000_000L
            TestResult(
                ok = false,
                ms = elapsed,
                error = "${t.javaClass.simpleName}: ${t.message ?: "请求失败"}"
            )
        }
    }

    /** 端口输入解析：非法时回落到 [fallback]。 */
    fun parsePort(raw: String, fallback: Int): Int {
        val value = raw.trim().toIntOrNull() ?: return fallback
        return if (value in 1..65535) value else fallback
    }

    /** 耗时展示：负数说明没测到。 */
    fun formatMs(ms: Long): String = if (ms < 0L) "—" else "${ms}ms"

    /** 结果档位，界面按它上色。 */
    fun levelOf(result: TestResult): Level = when {
        result.ok -> Level.OK
        result.error != null || result.code > 0 -> Level.BAD
        else -> Level.IDLE
    }
}

/**
 * 代理配置持久化。
 *
 * 独立成类而不是塞进 Activity：换源、播放、图片加载这些链路要读同一份配置决定是否挂代理，
 * 不该依赖界面是否还活着。读写都走 `applicationContext`，进程内随时可用。
 */
class F056ProxyStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读一份完整配置；存过脏数据（端口 0、类型写错）时逐字段回退，保证返回的一定能渲染。 */
    fun load(): F056ProxyConfig {
        val type = when (prefs.getString(KEY_TYPE, TYPE_HTTP)) {
            TYPE_SOCKS -> F056Engine.ProxyType.SOCKS
            else -> F056Engine.ProxyType.HTTP
        }
        val storedPort = prefs.getInt(KEY_PORT, F056Engine.defaultPortFor(type))
        val port = if (storedPort in 1..65535) storedPort else F056Engine.defaultPortFor(type)
        return F056ProxyConfig(
            type = type,
            host = prefs.getString(KEY_HOST, "") ?: "",
            port = port,
            enabled = prefs.getBoolean(KEY_ENABLED, false)
        )
    }

    /** 落盘；只在 [F056Engine.validate] 通过后调用，非法配置不写。 */
    fun save(config: F056ProxyConfig) {
        val normalized = config.normalized()
        if (!normalized.valid) return
        prefs.edit()
            .putString(KEY_TYPE, if (normalized.type == F056Engine.ProxyType.SOCKS) TYPE_SOCKS else TYPE_HTTP)
            .putString(KEY_HOST, normalized.host)
            .putInt(KEY_PORT, normalized.port)
            .putBoolean(KEY_ENABLED, normalized.enabled)
            .apply()
    }

    /** 只切开关，其余字段保持原样：界面上的开关是"即时生效"，不该等保存按钮。 */
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "f056_proxy_prefs"
        private const val KEY_TYPE = "f056_proxy_type"
        private const val KEY_HOST = "f056_proxy_host"
        private const val KEY_PORT = "f056_proxy_port"
        private const val KEY_ENABLED = "f056_proxy_enabled"
        private const val TYPE_HTTP = "http"
        private const val TYPE_SOCKS = "socks"
    }
}

/** 引擎侧配置的短别名，省得每个方法签名都带一长串限定名。 */
typealias F056ProxyConfig = F056Engine.ProxyConfig
