package com.tv.ball.feature.f063

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一台被扫到的局域网主机。
 *
 * @param ip        主机地址
 * @param openPorts 这一轮真正连上的端口（按 [F063Engine.COMMON_PORTS] 的顺序，升序）
 * @param responded 是否收到过任何回包：端口开着算，端口关着但主机回了 RST 也算
 * @param rttMs     第一次收到回包的耗时；一个回包都没有时为 0
 *
 * [responded] 和 [openPorts] 分开记，是因为电视盒子上常常一个常见端口都不开，
 * 但它对 SYN 回 RST——只按"端口开没开"判断会把整台设备漏掉。
 */
data class HostResult(
    val ip: String,
    val openPorts: List<Int>,
    val responded: Boolean,
    val rttMs: Long
) {
    /** 主机是否在线。 */
    val alive: Boolean get() = responded || openPorts.isNotEmpty()
}

/**
 * 扫描进度快照。
 *
 * @param scanned 已经探完的主机数（含没探到东西的）
 * @param total   本轮总共要探多少台
 * @param found   这一台探到的结果；没探到东西时为 null
 *
 * 回调里带回 [found]，界面才能边扫边出结果，不必等 254 台全部收口才看到列表。
 */
data class ScanProgress(
    val scanned: Int,
    val total: Int,
    val found: HostResult?
)

/**
 * 局域网设备扫描引擎（纯 JDK socket，不引第三方库）。
 *
 * 要解决的问题：投屏 / 局域网播放前，用户不知道网段里到底有哪些设备、
 * 哪个 IP 的哪个端口通。没有 root 就不能发 ICMP，所以存活判定全部走 TCP 连接：
 *
 * 1. 用 [localSubnet] 从 [NetworkInterface] 里挑出本机的 IPv4 地址，按 /24 取网段；
 * 2. [scan] 对网段内 1..254 逐个 `Socket().connect` 常见端口，连上就记一条开放端口；
 *    端口关着但主机回 RST（[ConnectException]，消息里带 refused）同样证明"这台在"；
 * 3. [exportText] 把结果拼成可直接复制的纯文本。
 *
 * 并发模型：每台主机一个协程，[Semaphore] 把同时在跑的连接压到 [MAX_CONCURRENCY]，
 * 避免 254 × 7 个 socket 同时握手把电视的 Wi-Fi 打爆。
 * 单次连接超时 [DEFAULT_TIMEOUT_MS]（局域网里 400 ms 已经够宽松），
 * 最坏情况 254 × 7 × 400 ms / 48 ≈ 15 秒收口。
 *
 * 取消：协程被 cancel 后，每台主机探下一个端口之前都会查一次 [ensureActive]，
 * 所以取消后最多再等一个超时周期就停；已经连上的 socket 全部在 `use {}` 里关掉。
 *
 * 所有函数都是阻塞调用，调用方必须放在 Dispatchers.IO 上跑。
 */
object F063Engine {

    /** 常见端口表：HTTP / HTTPS / 文件共享 / 投屏 / UPnP / 备用 HTTP / 管理页。 */
    val COMMON_PORTS: List<Int> = listOf(80, 443, 5000, 8009, 1900, 8080, 9090)

    /** 端口含义，界面和导出文本里都用它把裸数字翻译成人话。 */
    val PORT_LABELS: Map<Int, String> = mapOf(
        80 to "HTTP",
        443 to "HTTPS",
        5000 to "文件共享",
        8009 to "投屏",
        1900 to "UPnP/SSDP",
        8080 to "HTTP 代理",
        9090 to "管理页"
    )

    /** 单个 TCP 连接的超时。局域网内 400 ms 足够，再长就只是为了把扫描拖慢。 */
    const val DEFAULT_TIMEOUT_MS: Int = 400

    /** 上限保护：太短的超时会漏设备，这里兜个底。 */
    private const val MIN_TIMEOUT_MS: Int = 100

    /** 同时进行的连接数上限。 */
    const val MAX_CONCURRENCY: Int = 48

    /** /24 网段：主机位从 1 到 254（.0 是网络号，.255 是广播）。 */
    private const val HOST_FIRST: Int = 1
    private const val HOST_LAST: Int = 254

    /** 只按 /24 切网段：再大的网段扫一轮要几分钟，对"投屏前看一眼"没有意义。 */
    private const val PREFIX_BITS: String = "/24"

    /** 网卡名的跳过名单：蜂窝、蓝牙、Wi-Fi Direct、VPN 都不是"局域网"那一层。 */
    private val IGNORED_NIC_PREFIXES = listOf("rmnet", "p2p", "tun", "tap", "dummy", "ipsec")

    // ===== 网段 =====

    /**
     * 本机所在局域网的 /24 网段，形如 `192.168.1.0/24`；一个可用的 IPv4 都没有时返回 null。
     *
     * 取值顺序：先跳 loopback / 链路本地（169.254.x.x）/ 未启用的网卡和蜂窝类网卡，
     * 剩下多个候选时优先 `wlan*` / `eth*` / `en*`——电视盒子同时插网线和 Wi-Fi 时，
     * 用户心里想的是"这台电视连的那个网"，而 Android 列出的顺序并不保证这一点。
     */
    fun localSubnet(): String? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (e: Exception) {
            null
        } ?: return null

        val candidates = ArrayList<Pair<String, Inet4Address>>()
        for (nic in Collections.list(interfaces)) {
            val usable = try {
                nic.isUp && !nic.isLoopback && !nic.isVirtual
            } catch (e: Exception) {
                false
            }
            if (!usable) continue

            val name = nic.name.orEmpty()
            if (IGNORED_NIC_PREFIXES.any { name.startsWith(it, ignoreCase = true) }) continue

            for (address in Collections.list(nic.inetAddresses)) {
                val v4 = address as? Inet4Address ?: continue
                if (v4.isLoopbackAddress || v4.isLinkLocalAddress || v4.isAnyLocalAddress) continue
                val octets = v4.hostAddress?.split('.') ?: continue
                if (octets.size != 4) continue
                candidates += name to v4
            }
        }
        if (candidates.isEmpty()) return null

        val chosen = candidates.firstOrNull { looksLikeLanNic(it.first) } ?: candidates.first()
        val octets = chosen.second.hostAddress.orEmpty().split('.')
        if (octets.size != 4) return null
        return octets[0] + "." + octets[1] + "." + octets[2] + ".0" + PREFIX_BITS
    }

    /**
     * 把 `192.168.1.0/24`、`192.168.1`、`192.168.1.` 都归一化成 `192.168.1`；不合法返回 null。
     *
     * 只认前三段：后面那段主机位（.0 或别的）对 /24 扫描没有意义，直接丢掉。
     */
    fun netPrefix(subnet: String): String? {
        val body = subnet.trim().substringBefore('/').trim().trimEnd('.')
        if (body.isEmpty()) return null
        val octets = body.split('.')
        if (octets.size < 3) return null
        val head = octets.take(3)
        val valid = head.all { part ->
            val value = part.toIntOrNull()
            value != null && value in 0..255
        }
        return if (valid) head.joinToString(".") else null
    }

    /** 网段里的 254 个待探地址。 */
    fun hostAddresses(prefix: String): List<String> = (HOST_FIRST..HOST_LAST).map { prefix + "." + it }

    // ===== 扫描 =====

    /**
     * 扫 [subnet] 这个 /24 网段的 [ports]。
     *
     * @param subnet     网段，接受 `192.168.1.0/24` / `192.168.1` / `192.168.1.` 三种写法
     * @param ports      要探的端口，默认就是 [COMMON_PORTS]
     * @param timeoutMs  单次连接超时
     * @param onProgress 每探完一台回调一次（在 IO 线程上回调，界面自己切主线程）
     * @return 有回包的主机，按 IP 末段升序
     *
     * 网段不合法时返回空列表，不抛异常：调用方（界面）此时该提示的是"没拿到网段"，
     * 而不是一个栈信息。
     */
    suspend fun scan(
        subnet: String,
        ports: List<Int> = COMMON_PORTS,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        onProgress: (ScanProgress) -> Unit = {}
    ): List<HostResult> {
        val prefix = netPrefix(subnet) ?: return emptyList()
        val probePorts = ports.ifEmpty { COMMON_PORTS }
        val timeout = timeoutMs.coerceAtLeast(MIN_TIMEOUT_MS)
        val hosts = hostAddresses(prefix)
        val total = hosts.size

        val gates = Semaphore(MAX_CONCURRENCY)
        val scanned = AtomicInteger(0)
        val found = Collections.synchronizedList(ArrayList<HostResult>(total))

        coroutineScope {
            hosts.map { ip ->
                async(Dispatchers.IO) {
                    gates.withPermit {
                        // 上一轮已经取消就别再浪费一个超时周期
                        currentCoroutineContext().ensureActive()
                        val hit = probeHost(ip, probePorts, timeout)
                        if (hit != null) found += hit
                        onProgress(ScanProgress(scanned.incrementAndGet(), total, hit))
                        hit
                    }
                }
            }.awaitAll()
        }

        // 完成顺序是乱序的，结果按 IP 末段排一下，界面和导出文本读起来才像一份清单
        return found.sortedWith(compareBy<HostResult>({ hostOrder(it.ip) }, { it.ip }))
    }

    /**
     * 探一台主机：把每个端口连一遍。
     *
     * 只要有一个端口连上，或者有端口回了 RST，这台就存在；全静默就是不在网里（或防火墙静默丢弃）。
     * 不因为"已经确认存活"就提前退出——界面要的是完整端口表，列一半没有价值。
     */
    private suspend fun probeHost(ip: String, ports: List<Int>, timeoutMs: Int): HostResult? {
        val open = ArrayList<Int>(ports.size)
        var responded = false
        var rttRecorded = false
        var rttMs = 0L
        val startedNs = System.nanoTime()

        for (port in ports) {
            currentCoroutineContext().ensureActive()
            when (probePort(ip, port, timeoutMs)) {
                PortState.OPEN -> {
                    open += port
                    responded = true
                }
                PortState.REFUSED -> responded = true
                PortState.SILENT -> Unit
            }
            // 只记第一次回包的耗时：后面几个端口的耗时受端口状态影响，不是这台设备的往返时间
            if (responded && !rttRecorded) {
                rttMs = (System.nanoTime() - startedNs) / 1_000_000L
                rttRecorded = true
            }
        }

        return if (responded) HostResult(ip, open, true, rttMs) else null
    }

    /**
     * 连一次 [ip]:[port]。
     *
     * [PortState.REFUSED] 与 [PortState.SILENT] 必须分开：前者是主机在、端口关（RST），
     * 后者是压根没人应答（超时 / 网络不可达），后者不能算这台设备存在。
     */
    private fun probePort(ip: String, port: Int, timeoutMs: Int): PortState = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
        }
        PortState.OPEN
    } catch (refused: ConnectException) {
        if (isRefused(refused)) PortState.REFUSED else PortState.SILENT
    } catch (timeout: SocketTimeoutException) {
        PortState.SILENT
    } catch (io: IOException) {
        PortState.SILENT
    }

    /** RST 的判定：不同 Android 版本上消息是 "Connection refused" 或 "ECONNREFUSED"。 */
    private fun isRefused(e: ConnectException): Boolean {
        val message = e.message.orEmpty()
        return message.contains("refused", ignoreCase = true) ||
            message.contains("econnrefused", ignoreCase = true)
    }

    // ===== 导出 =====

    /**
     * 把一轮结果拼成可直接复制出去的纯文本。
     *
     * 列宽固定 15 字符（IPv4 最长就是这个宽度），端口后面跟一行人话，
     * 粘到聊天窗口里也不会错位。
     */
    fun exportText(results: List<HostResult>): String {
        val builder = StringBuilder()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val withOpenPorts = results.count { it.openPorts.isNotEmpty() }

        builder.append("局域网设备扫描结果\n")
        builder.append("时间：").append(stamp).append('\n')
        builder.append("存活主机：").append(results.size).append(" 台（其中 ")
            .append(withOpenPorts).append(" 台有开放端口）\n")
        builder.append("探测端口：").append(COMMON_PORTS.joinToString(" / ")).append('\n')
        builder.append("--------------------------------------------------------------\n")

        if (results.isEmpty()) {
            builder.append("（本轮没有发现存活主机）\n")
            return builder.toString()
        }

        for (result in results) {
            builder.append(result.ip.padEnd(15))
            builder.append("  ")
            if (result.openPorts.isEmpty()) {
                builder.append("—  （端口有回包，无开放端口）")
            } else {
                builder.append(result.openPorts.joinToString(", "))
                val labels = result.openPorts.mapNotNull { PORT_LABELS[it] }
                if (labels.isNotEmpty()) {
                    builder.append("  (").append(labels.joinToString(", ")).append(')')
                }
            }
            if (result.rttMs > 0L) builder.append("  ").append(result.rttMs).append(" ms")
            builder.append('\n')
        }
        return builder.toString()
    }

    /** 端口 + 含义，界面上的一行端口文本（例：`80 HTTP · 443 HTTPS`）。 */
    fun describePorts(result: HostResult): String =
        result.openPorts.joinToString(" · ") { port ->
            PORT_LABELS[port]?.let { label -> port.toString() + " " + label } ?: port.toString()
        }

    /** 排序列：IP 末段的数值。 */
    private fun hostOrder(ip: String): Int = ip.substringAfterLast('.').toIntOrNull() ?: Int.MAX_VALUE

    /** 有线的名字优先于无线，无线优先于其它（`wlan` / `eth` / `en` 三类最像"家里的网"）。 */
    private fun looksLikeLanNic(name: String): Boolean =
        name.startsWith("wlan", ignoreCase = true) ||
            name.startsWith("eth", ignoreCase = true) ||
            name.startsWith("en", ignoreCase = true)

    /** 单端口探测的三种结局。 */
    private enum class PortState {
        /** 连上了：端口开放。 */
        OPEN,

        /** 回 RST：主机在、端口关。 */
        REFUSED,

        /** 没回包：超时 / 不可达。 */
        SILENT
    }
}
