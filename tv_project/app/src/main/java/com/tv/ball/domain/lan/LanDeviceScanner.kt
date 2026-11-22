package com.tv.ball.domain.lan

import android.content.Context
import android.net.wifi.WifiManager
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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * 局域网设备扫描器
 * 用于发现局域网内可以播放视频的电脑设备
 *
 * 判定原则（关键）：
 * 1. 存活判定不再用 [InetAddress.isReachable]——Android 上它依赖 ICMP，没有权限时行为不确定，
 *    某些机型 / 某些路由器会让它把整个网段的 IP 全判成"在线"，于是列表里冒出 200 多台"电脑"。
 * 2. 用 TCP 端口探测做存活判定。只有命中「主机专属端口」(445/139/3389/5900/22) 才算电脑；
 *    只命中 80/8080 这类泛 HTTP 端口时，必须再有真实的反向 DNS 主机名才认——
 *    路由器、摄像头、电视、以及运营商/公寓网的透明 HTTP 劫持都会在全部 IP 上开着 80 端口。
 * 3. 跳过本机与网关（.1），结果按 IP 去重并有上限，扫描有总时长窗口。
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
        private const val MAX_DNS_LOOKUPS = 48

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

    /**
     * 获取本机在局域网里的 IPv4 地址（优先 Wi-Fi，其次有线，兼容热点 / 多网卡）
     */
    private fun getLocalIpAddress(): String {
        val fromInterface = runCatching {
            val candidates = mutableListOf<Pair<String, String>>()
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (address in nif.inetAddresses) {
                    val ip = address.hostAddress ?: continue
                    if (address is Inet4Address && !address.isLoopbackAddress && isPrivateIpv4(ip)) {
                        candidates.add(nif.name to ip)
                    }
                }
            }
            (candidates.firstOrNull { it.first.startsWith("wlan") }
                ?: candidates.firstOrNull { it.first.startsWith("eth") }
                ?: candidates.firstOrNull())?.second ?: ""
        }.getOrDefault("")

        return fromInterface.ifEmpty { wifiIpv4() }
    }

    /**
     * 网卡枚举拿不到时的兜底：直接问 Wi-Fi 的 IPv4（热点 / 虚拟网卡场景）
     */
    @Suppress("DEPRECATION")
    private fun wifiIpv4(): String {
        return runCatching {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (ipInt == 0) {
                ""
            } else {
                String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            }
        }.getOrDefault("")
    }

    private fun isPrivateIpv4(ip: String): Boolean {
        if (!IPV4_LITERAL.matches(ip)) return false
        val parts = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return when {
            parts[0] == 10 -> true
            parts[0] == 172 -> parts[1] in 16..31
            parts[0] == 192 && parts[1] == 168 -> true
            else -> false
        }
    }

    /**
     * 扫描局域网内的设备。
     * 只在确认是电脑时回调，且每个 IP 只回调一次、总数不超过 [MAX_RESULTS]。
     */
    suspend fun scanDevices(onDeviceFound: (LanDevice) -> Unit) = withContext(Dispatchers.IO) {
        val localIp = getLocalIpAddress()
        if (localIp.isEmpty()) {
            Log.e(TAG, "无法获取本机局域网 IP，跳过扫描")
            return@withContext
        }

        val ipPrefix = localIp.substringBeforeLast(".")
        val localLastSegment = localIp.substringAfterLast(".").toIntOrNull() ?: 0
        Log.d(TAG, "开始扫描 $ipPrefix.0/24，本机 IP: $localIp")

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
     * TCP 端口连通性探测（本地存活判定的唯一依据）
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