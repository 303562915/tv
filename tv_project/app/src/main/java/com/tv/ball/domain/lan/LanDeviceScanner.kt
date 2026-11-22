package com.tv.ball.domain.lan

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * 局域网设备扫描器
 * 用于发现局域网内可以播放视频的电脑设备
 *
 * 三道闸（缺一个就会把陌生网络里的设备全列成"电脑"）：
 * 1. 网络门禁：[LanNetworkGate] 只认 wlan* / eth* 接口上的私网 IPv4。没连 WiFi 时直接返回
 *    [LanScanState.NOT_ON_LAN]，一个 IP 都不碰——移动数据的 10.x 地址扫出去是运营商的网段。
 * 2. 存活判定：用 TCP 端口探测，不用 [InetAddress.isReachable]（Android 上依赖 ICMP，
 *    行为不确定，某些路由器会让整段地址都"在线"）。
 * 3. 身份判定：命中 445/139/3389/5900/22 才算电脑；只命中 80/8080 这类泛 HTTP 端口时，
 *    必须再反解出真实主机名才认——路由器、摄像头、电视、透明劫持网关都在全段开 80。
 */
class LanDeviceScanner(private val context: Context) {

    companion object {
        private const val TAG = "LanDeviceScanner"

        /** 单个 TCP 连接的建立超时；局域网正常 RTT 远小于此值，端口关闭会立刻回 RST */
        private const val CONNECT_TIMEOUT = 300

        /** 同时在扫的 IP 数量，避免 254 个并发线程把 IO 线程池堵死 */
        private const val MAX_CONCURRENT = 48

        /** 最多列出多少台，防止异常网络里刷屏 */
        private const val MAX_RESULTS = 32

        /** 整轮扫描的总时长上限 */
        private const val SCAN_WINDOW_MS = 15_000L

        /** 反向 DNS 查询次数上限（透明劫持网络下 254 台都会"开着 80"，不能无限查 DNS） */
        private const val MAX_DNS_LOOKUPS = 24

        /** 只有真正的电脑 / 主机才会常开的端口：命中即判为电脑 */
        private val PC_ONLY_PORTS = intArrayOf(445, 139, 3389, 5900, 22)

        /** 泛 HTTP 端口：路由器、摄像头、电视、透明劫持网关都可能开着，单独命中不足以判定 */
        private val HTTP_PORTS = intArrayOf(80, 8080, 3000, 5000, 8888)

        private val IPV4_LITERAL = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    }

    data class LanDevice(
        val name: String,
        val ipAddress: String,
        val type: DeviceType
    )

    enum class DeviceType {
        COMPUTER,  // 电脑
        UNKNOWN    // 未知设备
    }

    /** 扫描结论：用来区分「没连 WiFi」和「连了但确实没有电脑」 */
    enum class LanScanState {
        NOT_ON_LAN,  // 不在 WiFi / 以太网上，没有扫
        SCANNED      // 已完成一轮扫描
    }

    /**
     * 扫描局域网内的设备。
     * 只在确认是电脑时回调，且每个 IP 只回调一次、总数不超过 [MAX_RESULTS]。
     */
    suspend fun scanDevices(onDeviceFound: (LanDevice) -> Unit): LanScanState = withContext(Dispatchers.IO) {
        val lan = LanNetworkGate.findLanAddress(context)
        if (lan == null) {
            Log.w(TAG, "当前不在 WiFi / 以太网上，跳过扫描")
            return@withContext LanScanState.NOT_ON_LAN
        }

        val localIp = lan.ip
        val ipPrefix = localIp.substringBeforeLast(".")
        val localLastSegment = localIp.substringAfterLast(".").toIntOrNull() ?: 0
        Log.d(TAG, "开始扫描 $ipPrefix.0/24（本机 $localIp/${lan.prefixLength}）")

        val semaphore = Semaphore(MAX_CONCURRENT)
        val mutex = Mutex()
        val results = mutableListOf<LanDevice>()
        val seenIps = HashSet<String>()
        val dnsBudget = AtomicInteger(MAX_DNS_LOOKUPS)

        withTimeoutOrNull(SCAN_WINDOW_MS) {
            coroutineScope {
                (1..254).map { i ->
                    async {
                        if (i == localLastSegment) return@async // 跳过本机
                        if (i == 1) return@async               // 跳过网关
                        semaphore.withPermit {
                            currentCoroutineContext().ensureActive()
                            val ip = "$ipPrefix.$i"
                            val device = classify(ip, dnsBudget) ?: return@withPermit
                            mutex.withLock {
                                if (!seenIps.add(ip)) return@withLock
                                if (results.size >= MAX_RESULTS) return@withLock
                                results.add(device)
                                Log.d(TAG, "发现局域网电脑: ${device.name} (${device.ipAddress})")
                                withContext(Dispatchers.Main) { onDeviceFound(device) }
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        Log.d(TAG, "扫描完成，共确认 ${results.size} 台电脑")
        LanScanState.SCANNED
    }

    /**
     * 判定单个 IP 是否为电脑；不是则返回 null（宁可不显示，也不要瞎列一堆）
     */
    private fun classify(ip: String, dnsBudget: AtomicInteger): LanDevice? {
        val hitPcPort = PC_ONLY_PORTS.any { isPortOpen(ip, it) }
        val hitHttpPort = if (hitPcPort) false else HTTP_PORTS.any { isPortOpen(ip, it) }
        if (!hitPcPort && !hitHttpPort) return null

        // 只开了泛 HTTP 端口的设备，必须能反解出真实主机名才认作电脑
        val hostName = if (dnsBudget.decrementAndGet() >= 0) lookupHostName(ip) else null
        if (!hitPcPort && hostName == null) {
            Log.d(TAG, "跳过 $ip：只开 HTTP 端口且无主机名（多为路由器 / 摄像头 / 透明劫持网关）")
            return null
        }

        return LanDevice(hostName ?: "电脑 ($ip)", ip, DeviceType.COMPUTER)
    }

    /**
     * TCP 端口连通性探测（存活判定的唯一依据）
     */
    private fun isPortOpen(ip: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 反向 DNS 查主机名；拿不到真实名字一律返回 null
     */
    private fun lookupHostName(ip: String): String? {
        return try {
            val address = InetAddress.getByName(ip)
            val name = address.canonicalHostName?.trimEnd('.')
            when {
                name.isNullOrBlank() -> null
                name == ip -> null
                name == address.hostAddress -> null
                name.contains(":") -> null              // 拿回来是 IPv6 字面量
                IPV4_LITERAL.matches(name) -> null      // 拿回来是 IP 形态
                else -> name
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 向设备发送打开浏览器的请求
     * 注意：这需要在目标电脑上运行一个接收服务
     */
    suspend fun sendOpenBrowserRequest(deviceIp: String, playUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // 尝试通过 HTTP 请求触发电脑打开浏览器
            // 这需要电脑上运行一个简单的接收服务
            val url = URL("http://$deviceIp:9999/open?url=${java.net.URLEncoder.encode(playUrl, "UTF-8")}")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 2000
            connection.readTimeout = 2000

            val responseCode = connection.responseCode
            connection.disconnect()

            responseCode == 200
        } catch (e: Exception) {
            Log.e(TAG, "发送打开浏览器请求失败: ${e.message}")
            false
        }
    }
}