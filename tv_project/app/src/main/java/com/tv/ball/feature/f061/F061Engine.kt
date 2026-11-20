package com.tv.ball.feature.f061

import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale

/**
 * 「DLNA 设备发现」的 SSDP 实现（纯 JDK socket，不引第三方库）。
 *
 * 为什么是 SSDP 而不是别的方式：局域网里的电视 / 盒子只在 239.255.255.250:1900 上认这个协议，
 * 只要能让它们回一条 `HTTP/1.1 200 OK`，就等于拿到了后续投屏要用的 location（设备描述 XML 地址）。
 *
 * 流程固定三步：
 * 1. [discover] 建一个绑定在随机端口的 [MulticastSocket]，往组播地址发 `M-SEARCH`；
 * 2. 设备各自往我们的临时端口回单播响应，用 [parseResponse] 把 LOCATION / USN / SERVER 三个头抠出来；
 * 3. 同一个设备会为不同 `ST` 回多条（`...:MediaRenderer:1`、`upnp:rootdevice` 等），按 USN 里的 uuid 去重。
 *
 * 全程用 `soTimeout` + 截止时间轮询，不做无超时的阻塞 `receive()`：
 * 局域网里没有设备时也必须能在 timeout 内返回，否则界面会一直转圈。
 *
 * 名称补齐：M-SEARCH 的响应里没有 `friendlyName`，只有设备描述 XML 里有。
 * 所以拿到 LOCATION 之后再抓一次那个 XML，失败就退回 SERVER 头或地址——宁可名字糙一点，也不能让列表空着。
 */
object F061Engine {

    /** SSDP 规定的组播地址。 */
    const val MULTICAST_HOST: String = "239.255.255.250"

    /** SSDP 规定的组播端口。 */
    const val MULTICAST_PORT: Int = 1900

    /** 只找能投屏的渲染器；发现页不需要打印机、路由器这类别的 UPnP 设备。 */
    const val SEARCH_TARGET: String = "urn:schemas-upnp-org:device:MediaRenderer:1"

    /** 默认搜索窗口：够两个 M-SEARCH 往返，又不至于让用户以为卡死。 */
    const val DEFAULT_TIMEOUT_MS: Int = 3_000

    /** 低于这个时长的窗口基本收不到响应，兜一下底。 */
    private const val MIN_TIMEOUT_MS: Int = 500

    /** 单次 receive 的轮询粒度；比这个粗会让「提前结束」变迟钝。 */
    private const val RECEIVE_SLICE_MS: Int = 200

    /** 组播包最大 8KB 足够，SSDP 响应一般不到 1KB。 */
    private const val MAX_PACKET_BYTES: Int = 8 * 1024

    /** 抓设备描述 XML 的超时；设备就在局域网里，1.5 秒没回就放弃补名字。 */
    private const val NAME_TIMEOUT_MS: Int = 1_500

    /** 描述 XML 只读到 64KB，避免碰上异常设备把内存拖爆。 */
    private const val MAX_DESC_BYTES: Int = 64 * 1024

    /** 最多为多少台设备补名字；再多就只显示 SERVER / USN，搜索时长优先。 */
    private const val MAX_NAME_LOOKUPS: Int = 8

    /** 组播 TTL，1 就够（同一局域网），这里给 2 兼容个别多网段组网。 */
    private const val MULTICAST_TTL: Int = 2

    /** 设备描述里 `<friendlyName>` 的抠取规则。 */
    private val FRIENDLY_NAME_RE: Regex = Regex(
        "<friendlyName>(.*?)</friendlyName>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** 上次 [discover] 为什么一台都没搜到，界面据此给不同的说明。 */
    enum class Failure {
        /** 网络 / 网卡不支持组播，或者被系统拦了（部分电视盒子、模拟器就是这样）。 */
        MULTICAST_UNSUPPORTED,

        /** 连不上组播路由：Wi-Fi 没连上、热点网段隔离之类。 */
        NO_NETWORK,

        /** 其它异常，原样把 message 抛给界面。 */
        UNKNOWN
    }

    /**
     * 一台被发现的 DLNA 渲染设备。
     *
     * [friendlyName] 可能为空：SSDP 响应本身不带名字，补名字要额外一次 HTTP，
     * 那一跳失败时这个字段就是空串，显示层用 [displayName] 兜底。
     */
    data class DlnaDevice(
        /** 唯一服务名，形如 `uuid:xxxx::urn:schemas-upnp-org:device:MediaRenderer:1`。 */
        val usn: String,
        /** 设备描述 XML 地址，后续要投屏就靠它拿控制地址。 */
        val location: String,
        /** 设备自报的 SERVER 头，例如 `Linux/3.0 UPnP/1.0 Android/9`。 */
        val server: String,
        /** 描述 XML 里的 `friendlyName`，没抓到就是空串。 */
        val friendlyName: String,
        /** 回包那台机器的点分十进制地址；拿不到时为空串。 */
        val peerAddress: String
    ) {
        /** 列表里显示的地址：优先 LOCATION 的 host，回落到回包源地址。 */
        val host: String
            get() = hostOf(location) ?: peerAddress

        /** 列表里显示的名字，三级兜底，保证永不为空。 */
        val displayName: String
            get() = when {
                friendlyName.isNotBlank() -> friendlyName
                server.isNotBlank() -> server
                usn.isNotBlank() -> usn
                else -> host
            }

        /** 去重键：同 uuid 的多条 ST 响应算同一台设备。 */
        val dedupeKey: String
            get() {
                val uuid = usn.substringBefore("::").trim().lowercase(Locale.ROOT)
                return when {
                    uuid.isNotEmpty() -> uuid
                    location.isNotBlank() -> location.trim().lowercase(Locale.ROOT)
                    else -> host.lowercase(Locale.ROOT)
                }
            }
    }

    /** 上一次 [discover] 的失败类型；成功（哪怕一台都没搜到）时为 null。 */
    @Volatile
    var lastFailure: Failure? = null
        private set

    /** 上一次 [discover] 的异常原文，给界面拼说明用。 */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * 搜索局域网内的 DLNA 渲染设备。
     *
     * 阻塞调用，必须在 IO 线程执行；内部所有等待都有上限，[timeoutMs] 到点即返回已收到的部分结果。
     * 部分设备回包慢的情况很常见，所以结果按收到顺序返回，不在窗口结束前做排序。
     *
     * 搜不到时先看 [lastFailure]：非空说明是网络/组播层面的问题（不是「家里没电视」），
     * 界面要区分这两种情况，别把「网卡不支持组播」说成「没有设备」。
     */
    fun discover(timeoutMs: Int = DEFAULT_TIMEOUT_MS): List<DlnaDevice> {
        lastFailure = null
        lastError = null

        val budget = timeoutMs.coerceAtLeast(MIN_TIMEOUT_MS)
        val payload = searchRequest(budget).toByteArray(Charsets.UTF_8)
        val found = LinkedHashMap<String, DlnaDevice>()
        var opened: MulticastSocket? = null

        try {
            // 绑定在随机端口：我们要的是设备回的单播响应，不需要自己占着 1900
            val socket = MulticastSocket()
            opened = socket
            socket.soTimeout = RECEIVE_SLICE_MS
            socket.timeToLive = MULTICAST_TTL

            val group = InetAddress.getByName(MULTICAST_HOST)
            val started = System.currentTimeMillis()
            val deadline = started + budget
            var resent = false
            val buffer = ByteArray(MAX_PACKET_BYTES)

            sendSearch(socket, group, payload)

            while (true) {
                val now = System.currentTimeMillis()
                if (now >= deadline) break

                // UDP 丢包不罕见，窗口过半时补发一次，命中率明显好过只发一次
                if (!resent && now - started >= budget / 2) {
                    resent = true
                    sendSearch(socket, group, payload)
                }

                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (timeout: SocketTimeoutException) {
                    // 正常情况：这一片窗口里没人回话，继续等下一片
                    continue
                }

                val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                val device = parseResponse(text, packet.address?.hostAddress) ?: continue
                if (device.dedupeKey !in found) {
                    found[device.dedupeKey] = device
                }
            }
        } catch (t: Throwable) {
            lastFailure = classify(t)
            lastError = t.message ?: t.javaClass.simpleName
        } finally {
            runCatching { opened?.close() }
        }

        return enrichNames(found.values.toList())
    }

    /**
     * 解析一条 SSDP 响应文本。
     *
     * 只认头，不认 body（SSDP 响应没有 body）。LOCATION 与 USN 都没有的报文直接判 null 丢掉——
     * 那多半是别的协议碰巧进了同一个 socket。协议里的头名大小写不统一，这里一律按小写比对。
     *
     * [peerAddress] 是回包来源地址，LOCATION 缺失或不是合法 URL 时用它兜住地址列。
     */
    fun parseResponse(text: String, peerAddress: String? = null): DlnaDevice? {
        if (text.isBlank()) return null

        val headers = LinkedHashMap<String, String>()
        text.split('\n').forEachIndexed { index, raw ->
            val line = raw.trim().trimEnd('\r')
            // 第一行是 `HTTP/1.1 200 OK`，不是头
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

        val location = headers["location"].orEmpty()
        val usn = headers["usn"].orEmpty()
        val server = headers["server"].orEmpty()
        if (location.isBlank() && usn.isBlank()) return null

        val peer = peerAddress.orEmpty()
        val host = hostOf(location) ?: peer
        if (host.isBlank()) return null

        return DlnaDevice(
            usn = usn.ifBlank { "uuid:$host" },
            location = location,
            server = server,
            friendlyName = "",
            peerAddress = if (peer.isNotBlank()) peer else host
        )
    }

    /** 按 [DlnaDevice.dedupeKey] 去重，保留第一次出现的顺序。 */
    fun dedupe(devices: List<DlnaDevice>): List<DlnaDevice> {
        val seen = LinkedHashMap<String, DlnaDevice>()
        devices.forEach { device ->
            if (device.dedupeKey !in seen) seen[device.dedupeKey] = device
        }
        return seen.values.toList()
    }

    /** 从 URL 里取 host；地址不合法时返回 null，由调用方兜底。 */
    fun hostOf(location: String): String? {
        if (location.isBlank()) return null
        return try {
            URL(location).host?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 抓设备描述 XML 里的 `friendlyName`。
     *
     * 设备描述就在局域网里，用最朴素的 [HttpURLConnection] 就够，不引 OkHttp；
     * 任何异常都吞成 null——名字是锦上添花，不能因此让整次搜索失败。
     */
    fun fetchFriendlyName(location: String, timeoutMs: Int = NAME_TIMEOUT_MS): String? {
        if (location.isBlank()) return null
        var connection: HttpURLConnection? = null
        return try {
            val opened = URL(location).openConnection() as? HttpURLConnection ?: return null
            connection = opened
            opened.connectTimeout = timeoutMs
            opened.readTimeout = timeoutMs
            opened.requestMethod = "GET"
            opened.instanceFollowRedirects = true
            if (opened.responseCode !in 200..299) return null
            val body = opened.inputStream.use { readLimited(it, MAX_DESC_BYTES) }
            parseFriendlyName(body)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** 从设备描述 XML 里抠 `friendlyName`，抠不到返回 null。 */
    fun parseFriendlyName(xml: String): String? {
        val raw = FRIENDLY_NAME_RE.find(xml)?.groupValues?.getOrNull(1) ?: return null
        val name = unescapeXml(raw).trim()
        return name.ifBlank { null }
    }

    // ===== 内部实现 =====

    /** 拼一条 M-SEARCH 请求；MX 按搜索窗口换算成秒，设备回包才会落在窗口内。 */
    private fun searchRequest(budgetMs: Int): String {
        val mx = ((budgetMs + 999) / 1000).coerceIn(1, 5)
        return buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: ").append(MULTICAST_HOST).append(':').append(MULTICAST_PORT).append("\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: ").append(mx).append("\r\n")
            append("ST: ").append(SEARCH_TARGET).append("\r\n")
            append("USER-AGENT: Android UPnP/1.1 TVPlayer/1.0\r\n")
            append("\r\n")
        }
    }

    private fun sendSearch(socket: MulticastSocket, group: InetAddress, payload: ByteArray) {
        val packet = DatagramPacket(payload, payload.size, group, MULTICAST_PORT)
        socket.send(packet)
    }

    /** 给缺名字的设备补 friendlyName；数量有上限，失败就保持原样。 */
    private fun enrichNames(devices: List<DlnaDevice>): List<DlnaDevice> {
        if (devices.isEmpty()) return devices
        var lookups = 0
        return devices.map { device ->
            if (device.friendlyName.isNotBlank() || device.location.isBlank()) return@map device
            if (lookups >= MAX_NAME_LOOKUPS) return@map device
            lookups++
            val name = fetchFriendlyName(device.location).orEmpty()
            if (name.isBlank()) device else device.copy(friendlyName = name)
        }
    }

    /** 把底层异常归类成界面能解释的三种情况。 */
    private fun classify(t: Throwable): Failure {
        val message = (t.message ?: "").lowercase(Locale.ROOT)
        return when {
            // 网卡/系统层面压根不放行组播：模拟器、部分盒子、被省电策略掐掉的 Wi-Fi 都是这条路
            message.contains("multicast") || message.contains("permission denied") ||
                message.contains("eacces") -> Failure.MULTICAST_UNSUPPORTED

            // 有 socket 但没路由：Wi-Fi 没连上、热点网段不在同一广播域
            t is UnknownHostException ||
                (t is SocketException &&
                    (message.contains("network is unreachable") || message.contains("enetunreach") ||
                        message.contains("enotconn"))) -> Failure.NO_NETWORK

            else -> Failure.UNKNOWN
        }
    }

    /** 读固定上限字节；描述 XML 正常不到 10KB，不按长度读是为了不依赖 Content-Length。 */
    private fun readLimited(stream: java.io.InputStream, limit: Int): String {
        val out = java.io.ByteArrayOutputStream()
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

    /** 设备描述里的 XML 实体还原；`&amp;` 必须最后替换，否则 `&amp;lt;` 会被解两次。 */
    private fun unescapeXml(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
