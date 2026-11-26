package com.tv.ball.domain.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.*
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * DLNA 设备模型
 *
 * [controlUrl] 是投屏真正要用到的东西：AVTransport 服务的控制地址。
 * 它必须从设备描述 XML 里解析出来，不能靠猜——各家固件命名完全不同。
 */
data class DLNADevice(
    val id: String,
    val name: String,
    val ipAddress: String,
    val port: Int = 8080,
    val location: String = "",
    val controlUrl: String = ""
)

/**
 * 投屏失败的原因，给界面拼可读提示用，不再统一说「请重试」。
 */
enum class DLNACastError {
    /** 设备描述里没有 AVTransport 服务，这台设备不支持被控制着播（多半是纯 MediaServer）。 */
    NO_AV_TRANSPORT_SERVICE,

    /** 控制地址解析不到（描述 XML 拉不下来或内容不合规）。 */
    DESCRIPTION_UNREACHABLE,

    /** SOAP 请求被设备拒绝：HTTP 非 2xx，或 UPnP 500 + UPnPError。 */
    DEVICE_REJECTED,

    /** 网络层直接失败：连不上、超时。 */
    NETWORK_ERROR,

    /** 其它异常。 */
    UNKNOWN
}

/**
 * 一次投屏的失败详情。
 *
 * [detail] 是原始报文 / 异常文本的截断，用来在 Toast 或日志里说清到底谁拒绝了。
 */
data class DLNACastFailure(
    val error: DLNACastError,
    val message: String,
    val detail: String = ""
)

/** 投屏结果：成功，或带原因的失败。 */
sealed class DLNACastResult {
    object Success : DLNACastResult()
    data class Failure(val reason: DLNACastFailure) : DLNACastResult()
}

/**
 * DLNA 管理器
 * 使用 SSDP 协议发现设备，使用 UPnP 协议控制播放
 *
 * 发现流程要点（和功能中心的 F061Engine 保持一致，两者此前实现割裂）：
 * 1. socket **绑定随机端口**——非 root 进程在 Android 上不许 bind `0.0.0.0:1900`，
 *    原来 `MulticastSocket(SSDP_PORT)` 直接抛异常，整条链路被静默吞掉，界面永远停在"正在搜索…"；
 * 2. `joinGroup` **指定 WiFi 网卡**——多网卡（WiFi + 蜂窝 + VPN）时组播会挂错接口；
 * 3. 搜索窗口过半**补发一次** M-SEARCH——UDP 丢包在 WiFi 上很常见。
 *
 * 投屏链路要点：`controlURL` 必须从设备描述 XML 里解析，原来靠 `location.replace(...)` 猜，
 * 各家固件命名不同，猜错就是 404/500，表现是「投屏失败，请重试」。
 */
class DLNAManager(private val context: Context) {

    companion object {
        private const val TAG = "DLNAManager"
        private const val SSDP_IP = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1"

        /** SSDP 只发一次太容易丢，窗口过半补发一次。 */
        private const val DISCOVERY_TIMEOUT = 5000L
        private const val RECEIVE_SLICE_MS = 200
        private const val MAX_PACKET_BYTES = 8 * 1024

        /** 组播 TTL：同一局域网 1 就够，给 2 兼容个别多网段组网。 */
        private const val MULTICAST_TTL = 2

        /** 抓设备描述 XML 的超时。设备就在局域网里，2 秒没回就别等了。 */
        private const val DESCRIPTION_TIMEOUT_MS = 2_000
        private const val MAX_DESC_BYTES = 64 * 1024

        /** 只有这些接口算局域网 */
        private val LAN_INTERFACE_PREFIXES = listOf("wlan", "eth")

        /** 移动数据 / VPN / 隧道，组播挂上去等于白挂 */
        private val BLOCKED_INTERFACE_PREFIXES = listOf(
            "rmnet", "ccmni", "pdp", "clat", "v4-", "tun", "ppp", "radio", "dummy", "sit", "ip6tnl"
        )

        /**
         * SetAVTransportURI 与 Play 之间的等待窗口。
         *
         * 设备侧收到 CurrentURI 之后要一点时间才准备好接收 Play，这段等待是协议兼容上的需要；
         * 但等待本身用 `delay` 实现（挂起，不占线程），不用 `Thread.sleep` 把调用线程按死。
         */
        private const val SETTLE_DELAY_MS = 800L
    }

    private val devices = mutableListOf<DLNADevice>()
    private var isDiscovering = false
    private var discoveryJob: Job? = null

    /** 上一次被发现的设备对应的控制地址缓存：location → controlURL。 */
    private val controlUrlCache = mutableMapOf<String, String>()

    /**
     * 开始设备发现
     */
    fun startDiscovery(
        callback: (List<DLNADevice>) -> Unit,
        onError: ((String) -> Unit)? = null
    ) {
        if (isDiscovering) {
            Log.w(TAG, "设备发现已在进行中")
            return
        }

        Log.d(TAG, "开始发现 DLNA 设备...")
        isDiscovering = true
        devices.clear()

        discoveryJob = CoroutineScope(Dispatchers.IO).launch {
            var error: String? = null
            try {
                error = discoverDevices()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "设备发现失败: ${e.message}", e)
                error = e.message ?: e.javaClass.simpleName
            } finally {
                isDiscovering = false
            }
            withContext(Dispatchers.Main) {
                if (error != null) onError?.invoke(error)
                callback(devices.toList())
            }
        }
    }

    /**
     * 使用 SSDP 协议发现设备。
     *
     * 返回非空字符串表示「网络 / 组播层面就失败了」，不是「家里没有设备」——
     * 界面要能区分这两种情况，否则用户永远只看到「未发现 DLNA 设备」。
     */
    private suspend fun discoverDevices(): String? {
        var created: MulticastSocket? = null
        var multicastLock: WifiManager.MulticastLock? = null
        try {
            // 组播锁：不少盒子在 WiFi 省电策略下会直接丢组播包，不拿锁可能一个包都收不到
            multicastLock = runCatching {
                val wifiManager = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiManager.createMulticastLock("dlna_discovery").apply { setReferenceCounted(false) }
            }.getOrNull()
            runCatching { multicastLock?.acquire() }

            // 关键修复①：随机端口。绑 1900 在非 root 的 Android 上必失败
            val socket = MulticastSocket()
            created = socket
            socket.soTimeout = RECEIVE_SLICE_MS
            socket.timeToLive = MULTICAST_TTL

            val group = InetAddress.getByName(SSDP_IP)

            // 关键修复②：组播挂到 WiFi 网卡上，不挂默认路由
            val lanInterface = findLanInterface()
            var joinedWithInterface = false
            if (lanInterface != null) {
                joinedWithInterface = runCatching { socket.joinGroup(group, lanInterface) }
                    .onFailure { Log.w(TAG, "指定网卡加入组播失败，回落默认: ${it.message}") }
                    .isSuccess
                if (!joinedWithInterface) {
                    runCatching { socket.joinGroup(group) }
                }
            } else {
                Log.w(TAG, "没找到局域网网卡，组播可能发不出去")
                runCatching { socket.joinGroup(group) }
            }

            val payload = buildSearchMessage().toByteArray(StandardCharsets.UTF_8)
            val started = System.currentTimeMillis()
            val deadline = started + DISCOVERY_TIMEOUT
            var resent = false
            val buffer = ByteArray(MAX_PACKET_BYTES)

            sendSearch(socket, group, payload)
            Log.d(TAG, "已发送 SSDP 搜索请求")

            while (true) {
                val now = System.currentTimeMillis()
                if (now >= deadline) break

                // 关键修复③：窗口过半补发，单发丢率太高
                if (!resent && now - started >= DISCOVERY_TIMEOUT / 2) {
                    resent = true
                    runCatching { sendSearch(socket, group, payload) }
                }

                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }

                val response = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                parseDevice(response, packet.address?.hostAddress ?: "")
            }

            if (joinedWithInterface && lanInterface != null) {
                runCatching { socket.leaveGroup(group, lanInterface) }
            } else {
                runCatching { socket.leaveGroup(group) }
            }
            Log.d(TAG, "发现 ${devices.size} 个设备")

            if (devices.isNotEmpty()) {
                // 补名字 + 解析真正的 controlURL；失败不影响设备被列出来
                enrichDevices()
            }
            return null
        } catch (e: Exception) {
            Log.e(TAG, "SSDP 发现失败: ${e.message}", e)
            return e.message ?: e.javaClass.simpleName
        } finally {
            runCatching { created?.close() }
            runCatching { multicastLock?.release() }
        }
    }

    /** 找局域网网卡（wlan* / eth*，排除蜂窝、VPN、隧道）。 */
    private fun findLanInterface(): NetworkInterface? {
        return runCatching {
            val candidates = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            var ethFallback: NetworkInterface? = null
            for (nif in candidates) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase(Locale.ROOT)
                if (BLOCKED_INTERFACE_PREFIXES.any { name.startsWith(it) }) continue
                if (LAN_INTERFACE_PREFIXES.none { name.startsWith(it) }) continue
                if (name.startsWith("wlan")) return@runCatching nif
                if (ethFallback == null) ethFallback = nif
            }
            ethFallback
        }.getOrNull()
    }

    /**
     * 构建 SSDP 搜索消息
     */
    private fun buildSearchMessage(): String {
        return "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $SSDP_IP:$SSDP_PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 3\r\n" +
                "ST: $SEARCH_TARGET\r\n" +
                "USER-AGENT: Android UPnP/1.1 TVPlayer/1.0\r\n" +
                "\r\n"
    }

    private fun sendSearch(socket: MulticastSocket, group: InetAddress, payload: ByteArray) {
        val packet = DatagramPacket(payload, payload.size, group, SSDP_PORT)
        socket.send(packet)
    }

    /**
     * 解析设备信息
     */
    private fun parseDevice(response: String, ipAddress: String) {
        try {
            if (!response.contains("HTTP/1.1 200 OK", ignoreCase = true)) return

            // 头名大小写不统一，按行解析而不是用贪心正则（原来 `(.+)` 会把 CRLF 也吃进去）
            val headers = parseHeaders(response)

            val location = headers["location"].orEmpty()
            if (location.isBlank()) return
            if (!location.startsWith("http://", ignoreCase = true) &&
                !location.startsWith("https://", ignoreCase = true)
            ) {
                return
            }

            val serverInfo = headers["server"].orEmpty()
            val usn = headers["usn"].orEmpty()

            // 去重键优先用 USN 的 uuid：一台设备会为不同 ST 回多条，location 也可能不同
            val uuid = usn.substringBefore("::").trim()
            val deviceId = uuid.ifEmpty { location }

            val alreadyHave = devices.any { it.id == deviceId }
            if (alreadyHave) return

            val device = DLNADevice(
                id = deviceId,
                name = extractDeviceName(serverInfo),
                ipAddress = ipAddress,
                location = location,
                controlUrl = controlUrlCache[location].orEmpty()
            )

            devices.add(device)
            Log.d(TAG, "发现设备: ${device.name} @ $ipAddress ($location)")
        } catch (e: Exception) {
            Log.e(TAG, "解析设备失败: ${e.message}")
        }
    }

    /** 把 SSDP 回包的头抠成 map（小写键）；首行 `HTTP/1.1 200 OK` 跳过。 */
    private fun parseHeaders(response: String): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        response.split('\n').forEachIndexed { index, raw ->
            val line = raw.trim().trimEnd('\r')
            if (index == 0 && line.startsWith("HTTP/", ignoreCase = true)) return@forEachIndexed
            if (line.isEmpty()) return@forEachIndexed
            val sep = line.indexOf(':')
            if (sep <= 0) return@forEachIndexed
            val key = line.substring(0, sep).trim().lowercase(Locale.ROOT)
            val value = line.substring(sep + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty() && key !in headers) {
                headers[key] = value
            }
        }
        return headers
    }

    /**
     * 提取设备名称
     */
    private fun extractDeviceName(serverInfo: String): String {
        return when {
            serverInfo.contains("Android", ignoreCase = true) -> "Android 设备"
            serverInfo.contains("TV", ignoreCase = true) -> "智能电视"
            serverInfo.contains("Roku", ignoreCase = true) -> "Roku"
            serverInfo.contains("Xbox", ignoreCase = true) -> "Xbox"
            serverInfo.contains("PlayStation", ignoreCase = true) -> "PlayStation"
            else -> "DLNA 设备"
        }
    }

    /**
     * 给已发现的设备补真名 + 解析真实的 AVTransport controlURL。
     *
     * 这一步原来完全缺失：`controlUrl` 一直是空串，投屏时靠 `location.replace(...)` 猜地址，
     * 猜错就 404/500，表现就是「投屏失败，请重试」。
     */
    private fun enrichDevices() {
        val snapshot = devices.toList()
        for (i in snapshot.indices) {
            val device = snapshot[i]
            val description = fetchDescription(device.location) ?: continue
            Log.d(TAG, "设备描述: ${description.friendlyName} (${description.modelName})")
            val newName = when {
                description.friendlyName.isNotBlank() -> description.friendlyName
                description.modelName.isNotBlank() -> description.modelName
                else -> device.name
            }
            val updated = device.copy(
                name = newName,
                controlUrl = description.avTransportControlUrl.ifBlank { device.controlUrl }
            )
            if (updated.controlUrl.isNotBlank()) {
                controlUrlCache[device.location] = updated.controlUrl
            }
            devices[i] = updated
        }
    }

    /** 拉设备描述 XML 并解析；拉不到返回 null（名字和 controlURL 都保持原样）。 */
    private fun fetchDescription(location: String): DLNAServiceParser.Description? {
        var connection: HttpURLConnection? = null
        return try {
            val opened = URL(location).openConnection() as? HttpURLConnection ?: return null
            connection = opened
            opened.connectTimeout = DESCRIPTION_TIMEOUT_MS
            opened.readTimeout = DESCRIPTION_TIMEOUT_MS
            opened.requestMethod = "GET"
            opened.instanceFollowRedirects = true
            if (opened.responseCode !in 200..299) return null
            val xml = opened.inputStream.use { readLimited(it, MAX_DESC_BYTES) }
            DLNAServiceParser.parse(xml, location)
        } catch (t: Throwable) {
            Log.w(TAG, "拉设备描述失败: ${t.message} ($location)")
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** 读固定上限字节；描述 XML 正常不到 10KB，不依赖 Content-Length。 */
    private fun readLimited(stream: java.io.InputStream, limit: Int): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4 * 1024)
        var total = 0
        while (total < limit) {
            val read = stream.read(buffer, 0, minOf(buffer.size, limit - total))
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toString("UTF-8")
    }

    /**
     * 停止设备发现
     */
    fun stopDiscovery() {
        if (!isDiscovering) {
            return
        }

        Log.d(TAG, "停止设备发现")
        discoveryJob?.cancel()
        isDiscovering = false
    }

    /**
     * 投屏视频（阻塞版，留给既有调用方）。
     *
     * 既有调用方（`VideoPlayerActivity` 在 `lifecycleScope.launch` 里同步取返回值）跑在主线程上，
     * SOAP 请求是阻塞 IO，落在主线程只会抛 `NetworkOnMainThreadException`。
     * 所以整段放到 IO 线程；等待用 `delay` 挂起，不占线程。
     * 新代码请用 [castVideoAsync]，它还能带回失败原因。
     */
    fun castVideo(device: DLNADevice, videoUrl: String, videoTitle: String = ""): Boolean =
        runBlocking(Dispatchers.IO) { performCast(device, videoUrl, videoTitle) is DLNACastResult.Success }

    /**
     * 投屏视频（非阻塞版），失败时带回具体原因。
     */
    fun castVideoAsync(
        device: DLNADevice,
        videoUrl: String,
        videoTitle: String = "",
        onResult: (DLNACastResult) -> Unit
    ) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val result = runCatching { performCast(device, videoUrl, videoTitle) }
                .getOrElse { t ->
                    if (t is CancellationException) throw t
                    DLNACastResult.Failure(
                        DLNACastFailure(
                            DLNACastError.UNKNOWN,
                            t.message ?: t.javaClass.simpleName
                        )
                    )
                }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    /**
     * 一次投屏的完整时序：解析 controlURL → SetAVTransportURI → 等待 → Play。
     *
     * 返回 [DLNACastResult]，不再是一个裸 Boolean——界面要能说清是「设备不支持」、
     * 「控制地址拿不到」还是「设备明确拒绝」。
     */
    private suspend fun performCast(
        device: DLNADevice,
        videoUrl: String,
        videoTitle: String
    ): DLNACastResult {
        Log.d(TAG, "投屏到设备: ${device.name}, URL: $videoUrl")

        // 关键修复：控制地址必须从设备描述解析，不能靠 replace 猜
        val controlUrl = resolveControlUrl(device)
        if (controlUrl.isBlank()) {
            val reason = if (device.location.isBlank()) {
                "设备没有提供描述地址"
            } else {
                "设备描述里找不到 AVTransport 服务（这台设备可能只支持被搜到、不支持被控制播放）"
            }
            Log.e(TAG, "解析 controlURL 失败: $reason")
            return DLNACastResult.Failure(
                DLNACastFailure(DLNACastError.NO_AV_TRANSPORT_SERVICE, reason, device.location)
            )
        }
        Log.d(TAG, "使用 controlURL: $controlUrl")

        return try {
            val setUri = setAVTransportURI(controlUrl, videoUrl, videoTitle)
            if (setUri is DLNACastResult.Failure) {
                Log.e(TAG, "设置播放 URI 失败: ${setUri.reason.message}")
                return setUri
            }

            // 非阻塞等待：让设备把 CurrentURI 落地之后再发 Play
            delay(SETTLE_DELAY_MS)

            val played = play(controlUrl)
            if (played is DLNACastResult.Success) {
                Log.d(TAG, "投屏成功")
            } else {
                Log.e(TAG, "播放失败: ${(played as DLNACastResult.Failure).reason.message}")
            }
            played
        } catch (e: CancellationException) {
            // 调用方取消（castVideoAsync 的 scope 被丢）不算投屏失败，按取消往上抛
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "投屏失败: ${e.message}", e)
            DLNACastResult.Failure(
                DLNACastFailure(DLNACastError.NETWORK_ERROR, e.message ?: e.javaClass.simpleName)
            )
        }
    }

    /** 拿设备的 AVTransport 控制地址：先用缓存，再拉描述解析。 */
    private fun resolveControlUrl(device: DLNADevice): String {
        if (device.controlUrl.isNotBlank()) return device.controlUrl
        val cached = controlUrlCache[device.location]
        if (!cached.isNullOrBlank()) return cached
        if (device.location.isBlank()) return ""
        val description = fetchDescription(device.location) ?: return ""
        if (description.avTransportControlUrl.isNotBlank()) {
            controlUrlCache[device.location] = description.avTransportControlUrl
        }
        return description.avTransportControlUrl
    }

    /**
     * 设置播放 URI
     */
    private fun setAVTransportURI(controlUrl: String, videoUrl: String, title: String): DLNACastResult {
        val escapedTitle = escapeXml(title)
        val escapedUrl = escapeXml(videoUrl)
        val soap = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
<CurrentURI>$escapedUrl</CurrentURI>
<CurrentURIMetaData>&lt;DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"&gt;&lt;item id="0" parentID="-1" restricted="1"&gt;&lt;dc:title&gt;$escapedTitle&lt;/dc:title&gt;&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;&lt;res protocolInfo="http-get:*:video/*:*"&gt;$escapedUrl&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>
</u:SetAVTransportURI>
</s:Body>
</s:Envelope>"""

        return sendSoapRequest(controlUrl, "SetAVTransportURI", soap)
    }

    /**
     * 播放
     */
    fun play(device: DLNADevice): Boolean {
        val controlUrl = resolveControlUrl(device)
        if (controlUrl.isBlank()) return false
        return play(controlUrl) is DLNACastResult.Success
    }

    private fun play(controlUrl: String): DLNACastResult {
        val soap = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
<Speed>1</Speed>
</u:Play>
</s:Body>
</s:Envelope>"""

        return sendSoapRequest(controlUrl, "Play", soap)
    }

    /**
     * 暂停
     */
    fun pause(device: DLNADevice): Boolean {
        val controlUrl = resolveControlUrl(device)
        if (controlUrl.isBlank()) return false
        val soap = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Pause xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
</u:Pause>
</s:Body>
</s:Envelope>"""

        return sendSoapRequest(controlUrl, "Pause", soap) is DLNACastResult.Success
    }

    /**
     * 停止
     */
    fun stop(device: DLNADevice): Boolean {
        val controlUrl = resolveControlUrl(device)
        if (controlUrl.isBlank()) return false
        val soap = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
</u:Stop>
</s:Body>
</s:Envelope>"""

        return sendSoapRequest(controlUrl, "Stop", soap) is DLNACastResult.Success
    }

    /**
     * 调整进度
     */
    fun seek(device: DLNADevice, position: Long): Boolean {
        val controlUrl = resolveControlUrl(device)
        if (controlUrl.isBlank()) return false
        val hours = position / 3600
        val minutes = (position % 3600) / 60
        val seconds = position % 60
        val timeString = String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)

        val soap = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Seek xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
<Unit>REL_TIME</Unit>
<Target>$timeString</Target>
</u:Seek>
</s:Body>
</s:Envelope>"""

        return sendSoapRequest(controlUrl, "Seek", soap) is DLNACastResult.Success
    }

    /**
     * 发送 SOAP 请求。
     *
     * 判定放宽到 2xx：UPnP 规范是 200 OK，但部分固件回 204。
     * 非 2xx 时把响应体里 `<errorCode>` / `<errorDescription>` 抠出来带回去，
     * 界面才能说清是「设备拒绝了这个 URI」而不是笼统的「请重试」。
     */
    private fun sendSoapRequest(controlUrl: String, action: String, soapBody: String): DLNACastResult {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(controlUrl)
            connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            connection.setRequestProperty("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
            connection.setRequestProperty("Connection", "close")
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            connection.outputStream.use { it.write(soapBody.toByteArray(StandardCharsets.UTF_8)) }

            val responseCode = connection.responseCode
            Log.d(TAG, "SOAP 请求 $action 响应码: $responseCode")

            if (responseCode in 200..299) {
                // 部分设备在 200 里也塞 UPnPError，顺手检查一下
                val body = runCatching {
                    connection.inputStream.use { readLimited(it, MAX_DESC_BYTES) }
                }.getOrNull().orEmpty()
                val upnpError = parseUpnpError(body)
                if (upnpError != null) {
                    DLNACastResult.Failure(
                        DLNACastFailure(DLNACastError.DEVICE_REJECTED, upnpError.second, upnpError.first)
                    )
                } else {
                    DLNACastResult.Success
                }
            } else {
                val body = runCatching {
                    connection.errorStream?.use { readLimited(it, MAX_DESC_BYTES) }
                }.getOrNull().orEmpty()
                val upnpError = parseUpnpError(body)
                val detail = upnpError?.first ?: body.take(200)
                val message = upnpError?.second ?: "设备返回 HTTP $responseCode"
                DLNACastResult.Failure(DLNACastFailure(DLNACastError.DEVICE_REJECTED, message, detail))
            }
        } catch (e: IOException) {
            Log.e(TAG, "SOAP 请求失败: ${e.message}", e)
            DLNACastResult.Failure(
                DLNACastFailure(DLNACastError.NETWORK_ERROR, e.message ?: e.javaClass.simpleName)
            )
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /**
     * 从 UPnP 错误响应里抠 `errorCode` → `errorDescription`。
     *
     * 只有出现 `<errorCode>` 时才认：正常响应体是空的，不能把它当成错误。
     */
    private fun parseUpnpError(body: String): Pair<String, String>? {
        if (body.isBlank()) return null
        val code = Regex("<errorCode>(\\d+)</errorCode>", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.getOrNull(1) ?: return null
        val desc = Regex("<errorDescription>(.*?)</errorDescription>", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val message = if (desc.isBlank()) "设备返回 UPnP 错误码 $code" else "设备返回 UPnP 错误码 $code（$desc）"
        return code to message
    }

    /** 拼进 SOAP XML 的文本必须转义，否则标题里一个 `&` 就让整条请求失效。 */
    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /**
     * 获取已发现的设备列表
     */
    fun getDevices(): List<DLNADevice> {
        return devices.toList()
    }
}