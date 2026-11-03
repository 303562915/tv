package com.tv.ball.domain.lan

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * 局域网设备扫描器
 * 用于发现局域网内可以播放视频的电脑设备
 */
class LanDeviceScanner(private val context: Context) {

    companion object {
        private const val TAG = "LanDeviceScanner"
        private const val SCAN_TIMEOUT = 500 // 每个IP的扫描超时时间（毫秒）
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
     * 获取本机 IP 地址
     */
    private fun getLocalIpAddress(): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val ipInt = wifiInfo.ipAddress

        return if (ipInt != 0) {
            String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        } else {
            ""
        }
    }

    /**
     * 扫描局域网内的设备
     */
    suspend fun scanDevices(onDeviceFound: (LanDevice) -> Unit) = withContext(Dispatchers.IO) {
        val localIp = getLocalIpAddress()
        if (localIp.isEmpty()) {
            Log.e(TAG, "无法获取本机IP地址")
            return@withContext
        }

        Log.d(TAG, "开始扫描局域网，本机IP: $localIp")

        // 获取网段前缀 (例如: 192.168.1)
        val ipPrefix = localIp.substringBeforeLast(".")
        val localLastSegment = localIp.substringAfterLast(".").toIntOrNull() ?: 0

        // 扫描整个网段 (1-254)
        val jobs = mutableListOf<Job>()
        
        for (i in 1..254) {
            if (i == localLastSegment) continue // 跳过本机

            val job = launch(Dispatchers.IO) {
                val ip = "$ipPrefix.$i"
                try {
                    // 先 ping 检测设备是否在线
                    val address = InetAddress.getByName(ip)
                    if (address.isReachable(SCAN_TIMEOUT)) {
                        // 设备在线，尝试获取设备信息
                        val device = identifyDevice(ip, address)
                        if (device != null) {
                            Log.d(TAG, "发现设备: ${device.name} (${device.ipAddress})")
                            withContext(Dispatchers.Main) {
                                onDeviceFound(device)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 忽略扫描错误
                }
            }
            jobs.add(job)
        }

        // 等待所有扫描任务完成
        jobs.forEach { it.join() }
        Log.d(TAG, "扫描完成")
    }

    /**
     * 识别设备类型
     */
    private fun identifyDevice(ip: String, address: InetAddress): LanDevice? {
        try {
            val hostname = address.canonicalHostName ?: address.hostName ?: ip
            
            // 根据主机名判断设备类型
            val type = when {
                hostname.contains("PC", ignoreCase = true) ||
                hostname.contains("DESKTOP", ignoreCase = true) ||
                hostname.contains("LAPTOP", ignoreCase = true) ||
                hostname.contains("Windows", ignoreCase = true) ||
                hostname.contains("Mac", ignoreCase = true) ||
                hostname.contains("Linux", ignoreCase = true) -> DeviceType.COMPUTER
                else -> {
                    // 尝试检测常见的 HTTP 端口来判断是否是电脑
                    if (isHttpPortOpen(ip)) DeviceType.COMPUTER else DeviceType.UNKNOWN
                }
            }

            // 只返回电脑设备
            return if (type == DeviceType.COMPUTER) {
                val deviceName = if (hostname != ip) hostname else "电脑 ($ip)"
                LanDevice(deviceName, ip, type)
            } else {
                null
            }
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 检测 HTTP 端口是否开放
     */
    private fun isHttpPortOpen(ip: String): Boolean {
        val ports = listOf(80, 8080, 3000, 5000) // 常见的 HTTP 端口
        
        for (port in ports) {
            try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress(ip, port), 200)
                socket.close()
                return true
            } catch (e: Exception) {
                // 端口未开放，继续尝试下一个
            }
        }
        return false
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
