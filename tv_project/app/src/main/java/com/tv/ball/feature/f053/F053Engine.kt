package com.tv.ball.feature.f053

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * F053 网络诊断的真实能力层。
 *
 * 播放卡顿要能分清「DNS 慢」「目标不通」「都正常但带宽不够」三种情况，所以这里只做两件事：
 * 1. [resolve]：`InetAddress.getAllByName` 拿域名解析耗时与全部地址；
 * 2. [probeTcp]：`Socket.connect` 直连目标端口，量往返耗时。
 * 两个结果交给 [conclude] 出三档建议：DNS 拖后腿 / 连不上 / 都正常。
 *
 * 对外只暴露挂起接口，内部切 [Dispatchers.IO]；超时与失败都收敛成结果对象里的 error 字段，
 * 不往外抛异常，调用方拿到的永远是可渲染的数据。
 */
object F053Engine {

    /** 默认探测目标：Google 公共 DNS 的 443，国内也基本可达。 */
    const val DEFAULT_HOST: String = "8.8.8.8"

    /** 默认端口。 */
    const val DEFAULT_PORT: Int = 443

    /** 默认超时 3 秒：低于这个值分不清「慢」和「卡死」。 */
    const val DEFAULT_TIMEOUT_MS: Int = 3000

    /** DNS 耗时达到它就判「慢」，低于它算正常。 */
    const val DNS_SLOW_MS: Long = 800L

    /** TCP RTT 达到它就判「慢」，低于它算正常。 */
    const val TCP_SLOW_MS: Long = 800L

    /** 最近结果列表只留这么多条。 */
    const val HISTORY_LIMIT: Int = 5

    /** 建议档位，界面按它上色。 */
    enum class Level {
        /** 一切正常。 */
        HEALTHY,

        /** 解析或连通性一般，可能造成首帧慢。 */
        DEGRADED,

        /** 明确故障：解析失败或连不上。 */
        BROKEN
    }

    /** DNS 解析结果；[ms] 为负表示没测到（失败或未执行）。 */
    data class DnsResult(
        val host: String = "",
        val ms: Long = -1L,
        val addresses: List<String> = emptyList(),
        val error: String? = null
    ) {
        /** 是否解析成功。 */
        val ok: Boolean
            get() = error == null && addresses.isNotEmpty()

        /** 地址个数，界面直接显示。 */
        val addressCount: Int
            get() = addresses.size
    }

    /** TCP 连通性结果；[ms] 为负表示没连上。 */
    data class TcpResult(
        val host: String = "",
        val port: Int = 0,
        val ms: Long = -1L,
        val ok: Boolean = false,
        val error: String? = null
    )

    /** 一次完整诊断。 */
    data class Report(
        val dns: DnsResult,
        val tcp: TcpResult,
        val advice: Advice,
        /** 诊断完成时的墙上时间，列表按它排序展示。 */
        val atMillis: Long = 0L
    )

    /**
     * 三档建议。
     *
     * [code] 给界面选文案分支，文案本身由 Activity 从 string 资源取，
     * 引擎不认识 R 与 Context。
     */
    data class Advice(
        val level: Level,
        val code: Code
    )

    /** 建议分支，对应 strings 里的三条文案。 */
    enum class Code {
        /** DNS 慢或解析失败占主导。 */
        DNS_SLOW,

        /** 解析正常但 TCP 连不上。 */
        TCP_UNREACHABLE,

        /** 两端都正常：问题更可能在带宽或片源。 */
        NETWORK_OK,

        /** 都慢。 */
        ALL_SLOW
    }

    /**
     * DNS 解析：计时包住 `getAllByName`，失败把异常类名+消息塞进 [DnsResult.error]。
     *
     * `getAllByName` 本身没有超时参数，超时由系统解析器自己控制；
     * 这里只负责如实测量耗时，不做二次兜底，避免把「系统卡住」伪装成「解析失败」。
     */
    suspend fun resolve(host: String): DnsResult = withContext(Dispatchers.IO) {
        val target = host.trim()
        if (target.isEmpty()) {
            return@withContext DnsResult(host = target, error = "host 为空")
        }
        val startedAt = System.nanoTime()
        try {
            val all = InetAddress.getAllByName(target)
            val elapsed = (System.nanoTime() - startedAt) / 1_000_000L
            DnsResult(
                host = target,
                ms = elapsed,
                addresses = all.map { it.hostAddress ?: it.toString() }
            )
        } catch (t: Throwable) {
            val elapsed = (System.nanoTime() - startedAt) / 1_000_000L
            DnsResult(
                host = target,
                ms = elapsed,
                error = "${t.javaClass.simpleName}: ${t.message ?: "解析失败"}"
            )
        }
    }

    /**
     * TCP 连通性：`Socket().connect(InetSocketAddress, timeout)`，
     * 计时从 connect 开始到返回，[ms] 就是这一跳的 RTT。
     */
    suspend fun probeTcp(host: String, port: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): TcpResult =
        withContext(Dispatchers.IO) {
            val target = host.trim()
            if (target.isEmpty() || port !in 1..65535) {
                return@withContext TcpResult(
                    host = target,
                    port = port,
                    error = "参数非法：host 为空或端口越界"
                )
            }
            val budget = timeoutMs.coerceIn(200, 15_000)
            val startedAt = System.nanoTime()
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(target, port), budget)
                val elapsed = (System.nanoTime() - startedAt) / 1_000_000L
                TcpResult(host = target, port = port, ms = elapsed, ok = true)
            } catch (t: Throwable) {
                val elapsed = (System.nanoTime() - startedAt) / 1_000_000L
                TcpResult(
                    host = target,
                    port = port,
                    ms = elapsed,
                    ok = false,
                    error = "${t.javaClass.simpleName}: ${t.message ?: "连接失败"}"
                )
            } finally {
                runCatching { socket.close() }
            }
        }

    /**
     * 结论：先看 TCP，TCP 不通就是硬故障；TCP 通再看 DNS 与 RTT 的两档阈值。
     *
     * 判定顺序固定，保证同一种现象只有一条建议，不会出现「既说 DNS 慢又说连不上」。
     */
    fun conclude(dns: DnsResult, tcp: TcpResult): Advice = when {
        !tcp.ok -> Advice(Level.BROKEN, Code.TCP_UNREACHABLE)
        !dns.ok -> Advice(Level.BROKEN, Code.DNS_SLOW)
        dns.ms >= DNS_SLOW_MS && tcp.ms >= TCP_SLOW_MS -> Advice(Level.DEGRADED, Code.ALL_SLOW)
        dns.ms >= DNS_SLOW_MS -> Advice(Level.DEGRADED, Code.DNS_SLOW)
        tcp.ms >= TCP_SLOW_MS -> Advice(Level.DEGRADED, Code.ALL_SLOW)
        else -> Advice(Level.HEALTHY, Code.NETWORK_OK)
    }

    /** 一次跑完：解析 -> 连通 -> 结论。 */
    suspend fun diagnose(
        host: String,
        port: Int,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): Report {
        val dns = resolve(host)
        val tcp = probeTcp(host, port, timeoutMs)
        return Report(
            dns = dns,
            tcp = tcp,
            advice = conclude(dns, tcp),
            atMillis = System.currentTimeMillis()
        )
    }

    /** 新结果插到队首，超过 [HISTORY_LIMIT] 截断。 */
    fun pushHistory(history: List<Report>, report: Report): List<Report> =
        (listOf(report) + history).take(HISTORY_LIMIT)

    /** 端口输入解析，非法时回落到默认端口。 */
    fun parsePort(raw: String): Int {
        val value = raw.trim().toIntOrNull() ?: return DEFAULT_PORT
        return if (value in 1..65535) value else DEFAULT_PORT
    }

    /** 耗时展示：负数说明没测到。 */
    fun formatMs(ms: Long): String = if (ms < 0L) "—" else "${ms}ms"

    /** 阈值档位，给结果卡上的小标签用。 */
    fun dnsLevel(ms: Long): Level = when {
        ms < 0L -> Level.BROKEN
        ms >= DNS_SLOW_MS -> Level.DEGRADED
        else -> Level.HEALTHY
    }

    /** 同上，TCP 侧。 */
    fun tcpLevel(ok: Boolean, ms: Long): Level = when {
        !ok -> Level.BROKEN
        ms >= TCP_SLOW_MS -> Level.DEGRADED
        else -> Level.HEALTHY
    }

    /** 建议是否落在「正常」档。 */
    fun isHealthy(level: Level): Boolean = level == Level.HEALTHY
}
