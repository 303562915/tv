package com.tv.ball.domain.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import java.io.IOException
import java.net.*
import java.nio.charset.StandardCharsets

/**
 * DLNA 设备模型
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
 * DLNA 管理器
 * 使用 SSDP 协议发现设备，使用 UPnP 协议控制播放
 */
class DLNAManager(private val context: Context) {

    companion object {
        private const val TAG = "DLNAManager"
        private const val SSDP_IP = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1"
        private const val DISCOVERY_TIMEOUT = 5000L

        /**
         * SetAVTransportURI 与 Play 之间的等待窗口。
         *
         * 设备侧收到 CurrentURI 之后要一点时间才准备好接收 Play，这段等待是协议兼容上的需要；
         * 但等待本身用 `delay` 实现（挂起，不占线程），不用 `Thread.sleep` 把调用线程按死。
         */
        private const val SETTLE_DELAY_MS = 500L
    }

    private val devices = mutableListOf<DLNADevice>()
    private var isDiscovering = false
    private var discoveryJob: Job? = null

    /**
     * 开始设备发现
     */
    fun startDiscovery(callback: (List<DLNADevice>) -> Unit) {
        if (isDiscovering) {
            Log.w(TAG, "设备发现已在进行中")
            return
        }

        Log.d(TAG, "开始发现 DLNA 设备...")
        isDiscovering = true
        devices.clear()

        discoveryJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                discoverDevices()
                withContext(Dispatchers.Main) {
                    callback(devices.toList())
                }
            } catch (e: Exception) {
                Log.e(TAG, "设备发现失败: ${e.message}", e)
            } finally {
                isDiscovering = false
            }
        }
    }

    /**
     * 使用 SSDP 协议发现设备
     */
    private suspend fun discoverDevices() {
        var socket: MulticastSocket? = null
        try {
            // 获取 WiFi 锁
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val multicastLock = wifiManager.createMulticastLock("dlna_discovery")
            multicastLock.acquire()

            // 创建组播 socket
            socket = MulticastSocket(SSDP_PORT)
            socket.soTimeout = DISCOVERY_TIMEOUT.toInt()
            
            val group = InetAddress.getByName(SSDP_IP)
            socket.joinGroup(group)

            // 发送 M-SEARCH 请求
            val searchMsg = buildSearchMessage()
            val searchPacket = DatagramPacket(
                searchMsg.toByteArray(),
                searchMsg.length,
                group,
                SSDP_PORT
            )
            socket.send(searchPacket)
            Log.d(TAG, "已发送 SSDP 搜索请求")

            // 接收响应
            val buffer = ByteArray(2048)
            val startTime = System.currentTimeMillis()
            
            while (System.currentTimeMillis() - startTime < DISCOVERY_TIMEOUT) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    
                    val response = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                    parseDevice(response, packet.address.hostAddress ?: "")
                } catch (e: SocketTimeoutException) {
                    break
                }
            }

            socket.leaveGroup(group)
            multicastLock.release()
            
            Log.d(TAG, "发现 ${devices.size} 个设备")
        } catch (e: Exception) {
            Log.e(TAG, "SSDP 发现失败: ${e.message}", e)
        } finally {
            socket?.close()
        }
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
                "\r\n"
    }

    /**
     * 解析设备信息
     */
    private fun parseDevice(response: String, ipAddress: String) {
        try {
            if (!response.contains("HTTP/1.1 200 OK")) {
                return
            }

            // 提取 LOCATION
            val locationRegex = "LOCATION:\\s*(.+)".toRegex(RegexOption.IGNORE_CASE)
            val locationMatch = locationRegex.find(response)
            val location = locationMatch?.groupValues?.get(1)?.trim() ?: return

            // 提取设备名称（从 SERVER 或 USN）
            val serverRegex = "SERVER:\\s*(.+)".toRegex(RegexOption.IGNORE_CASE)
            val serverMatch = serverRegex.find(response)
            val serverInfo = serverMatch?.groupValues?.get(1)?.trim() ?: "Unknown Device"

            // 生成设备 ID
            val deviceId = location.hashCode().toString()

            // 检查是否已存在
            if (devices.any { it.id == deviceId }) {
                return
            }

            val device = DLNADevice(
                id = deviceId,
                name = extractDeviceName(serverInfo),
                ipAddress = ipAddress,
                location = location,
                controlUrl = "" // 需要从设备描述 XML 中获取
            )

            devices.add(device)
            Log.d(TAG, "发现设备: ${device.name} @ $ipAddress")
        } catch (e: Exception) {
            Log.e(TAG, "解析设备失败: ${e.message}")
        }
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
     * 原来这里是「`Thread.sleep(500)` 之后发 Play」。既有调用方
     * （`VideoPlayerActivity` 在 `lifecycleScope.launch` 里同步取返回值）跑在主线程上，
     * 所以那 500 ms 是把消息循环按住不放的；同一条链上的 SOAP 请求也是阻塞 IO，
     * 在主线程上只会直接抛 `NetworkOnMainThreadException`。
     *
     * 现在等待换成挂起（[kotlinx.coroutines.delay] 而非 `Thread.sleep`），整段执行放到 IO 线程：
     * 等待窗口里不占 CPU，SOAP 请求也不会落在主线程上。签名保持不变（调用方不在本次改动范围内）；
     * 新代码要彻底不阻塞主线程，请用 [castVideoAsync]。
     */
    fun castVideo(device: DLNADevice, videoUrl: String, videoTitle: String = ""): Boolean =
        runBlocking(Dispatchers.IO) { performCast(device, videoUrl, videoTitle) }

    /**
     * 投屏视频（非阻塞版）。
     *
     * SetAVTransportURI → 等 [SETTLE_DELAY_MS] → Play 整段跑在 IO 线程，结果回调回主线程。
     * 主线程只负责发起这一次调用，不在等待窗口里被占住。
     */
    fun castVideoAsync(
        device: DLNADevice,
        videoUrl: String,
        videoTitle: String = "",
        onResult: (Boolean) -> Unit
    ) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val success = performCast(device, videoUrl, videoTitle)
            withContext(Dispatchers.Main) { onResult(success) }
        }
    }

    /**
     * 一次投屏的完整时序：SetAVTransportURI → 等待 → Play。
     *
     * 两段 SOAP 都是阻塞 IO，所以这里是个 `suspend` 函数，由调用方保证跑在 IO 调度器上；
     * 中间那段等待是设备侧的兼容需要，但用 `delay` 挂起，既不占线程也不把调用方主线程拖住。
     */
    private suspend fun performCast(
        device: DLNADevice,
        videoUrl: String,
        videoTitle: String
    ): Boolean {
        Log.d(TAG, "投屏到设备: ${device.name}, URL: $videoUrl")

        return try {
            val setUriSuccess = setAVTransportURI(device, videoUrl, videoTitle)
            if (!setUriSuccess) {
                Log.e(TAG, "设置播放 URI 失败")
                false
            } else {
                // 非阻塞等待：让设备把 CurrentURI 落地之后再发 Play
                delay(SETTLE_DELAY_MS)

                val playSuccess = play(device)
                if (playSuccess) {
                    Log.d(TAG, "投屏成功")
                } else {
                    Log.e(TAG, "播放失败")
                }
                playSuccess
            }
        } catch (e: CancellationException) {
            // 调用方取消（castVideoAsync 的 scope 被丢）不算投屏失败，按取消往上抛
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "投屏失败: ${e.message}", e)
            false
        }
    }

    /**
     * 设置播放 URI
     */
    private fun setAVTransportURI(device: DLNADevice, videoUrl: String, title: String): Boolean {
        val soap = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                        <CurrentURI>$videoUrl</CurrentURI>
                        <CurrentURIMetaData>&lt;DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"&gt;&lt;item id="0" parentID="-1" restricted="1"&gt;&lt;dc:title&gt;$title&lt;/dc:title&gt;&lt;res protocolInfo="http-get:*:video/*:*"&gt;$videoUrl&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>
                    </u:SetAVTransportURI>
                </s:Body>
            </s:Envelope>
        """.trimIndent()

        return sendSoapRequest(device, "SetAVTransportURI", soap)
    }

    /**
     * 播放
     */
    fun play(device: DLNADevice): Boolean {
        val soap = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                        <Speed>1</Speed>
                    </u:Play>
                </s:Body>
            </s:Envelope>
        """.trimIndent()

        return sendSoapRequest(device, "Play", soap)
    }

    /**
     * 暂停
     */
    fun pause(device: DLNADevice): Boolean {
        val soap = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:Pause xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                    </u:Pause>
                </s:Body>
            </s:Envelope>
        """.trimIndent()

        return sendSoapRequest(device, "Pause", soap)
    }

    /**
     * 停止
     */
    fun stop(device: DLNADevice): Boolean {
        val soap = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                    </u:Stop>
                </s:Body>
            </s:Envelope>
        """.trimIndent()

        return sendSoapRequest(device, "Stop", soap)
    }

    /**
     * 调整进度
     */
    fun seek(device: DLNADevice, position: Long): Boolean {
        val hours = position / 3600
        val minutes = (position % 3600) / 60
        val seconds = position % 60
        val timeString = String.format("%02d:%02d:%02d", hours, minutes, seconds)

        val soap = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:Seek xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                        <Unit>REL_TIME</Unit>
                        <Target>$timeString</Target>
                    </u:Seek>
                </s:Body>
            </s:Envelope>
        """.trimIndent()

        return sendSoapRequest(device, "Seek", soap)
    }

    /**
     * 发送 SOAP 请求
     */
    private fun sendSoapRequest(device: DLNADevice, action: String, soapBody: String): Boolean {
        return try {
            // 从 location 提取控制 URL
            val controlUrl = device.location.replace("/description.xml", "/AVTransport/control")
            
            val url = URL(controlUrl)
            val connection = url.openConnection() as HttpURLConnection
            
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            connection.setRequestProperty("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            // 发送请求
            connection.outputStream.use { it.write(soapBody.toByteArray(StandardCharsets.UTF_8)) }

            // 读取响应
            val responseCode = connection.responseCode
            Log.d(TAG, "SOAP 请求 $action 响应码: $responseCode")

            responseCode == 200
        } catch (e: IOException) {
            Log.e(TAG, "SOAP 请求失败: ${e.message}", e)
            false
        }
    }

    /**
     * 获取已发现的设备列表
     */
    fun getDevices(): List<DLNADevice> {
        return devices.toList()
    }
}
