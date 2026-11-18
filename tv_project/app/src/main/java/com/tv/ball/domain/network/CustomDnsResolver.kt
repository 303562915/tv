package com.tv.ball.domain.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * 自定义 DNS 解析器
 * 支持多个 DNS 服务器，解决某些域名无法解析的问题
 */
object CustomDnsResolver {
    
    private const val TAG = "CustomDnsResolver"
    
    // 公共 DNS 服务器列表
    private val DNS_SERVERS = listOf(
        "8.8.8.8",          // Google DNS
        "8.8.4.4",          // Google DNS 备用
        "1.1.1.1",          // Cloudflare DNS
        "1.0.0.1",          // Cloudflare DNS 备用
        "114.114.114.114",  // 114 DNS (中国)
        "223.5.5.5",        // 阿里 DNS (中国)
        "223.6.6.6"         // 阿里 DNS 备用 (中国)
    )
    
    /**
     * 解析域名为 IP 地址
     * 尝试多个 DNS 服务器，直到成功
     */
    suspend fun resolve(hostname: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "开始解析域名: $hostname")
        
        // 首先尝试系统默认 DNS
        try {
            val addresses = InetAddress.getAllByName(hostname)
            if (addresses.isNotEmpty()) {
                val ip = addresses[0].hostAddress
                Log.d(TAG, "✓ 系统 DNS 解析成功: $hostname -> $ip")
                return@withContext ip
            }
        } catch (e: UnknownHostException) {
            Log.w(TAG, "✗ 系统 DNS 解析失败: $hostname")
        } catch (e: Exception) {
            Log.e(TAG, "系统 DNS 解析异常: ${e.message}", e)
        }
        
        // 系统 DNS 失败，尝试自定义 DNS 服务器
        for (dnsServer in DNS_SERVERS) {
            try {
                val ip = resolveWithDnsServer(hostname, dnsServer)
                if (ip != null) {
                    Log.d(TAG, "✓ 使用 DNS $dnsServer 解析成功: $hostname -> $ip")
                    return@withContext ip
                }
            } catch (e: Exception) {
                Log.w(TAG, "✗ DNS $dnsServer 解析失败: ${e.message}")
            }
        }
        
        Log.e(TAG, "所有 DNS 服务器都无法解析: $hostname")
        null
    }
    
    /**
     * 使用指定的 DNS 服务器解析域名
     */
    private fun resolveWithDnsServer(hostname: String, dnsServer: String): String? {
        return try {
            // 使用 dnsjava 库或原生方法
            // 这里使用简单的方法：通过 InetAddress 解析
            // 注意：Android 不直接支持指定 DNS 服务器，需要使用第三方库
            
            // 临时方案：使用 DoH (DNS over HTTPS)
            resolveWithDoH(hostname, dnsServer)
        } catch (e: Exception) {
            Log.e(TAG, "DNS 解析异常: $dnsServer", e)
            null
        }
    }
    
    /**
     * 使用 DNS over HTTPS 解析
     */
    private fun resolveWithDoH(hostname: String, dnsServer: String): String? {
        return try {
            // 根据 DNS 服务器选择 DoH 端点
            val dohUrl = when (dnsServer) {
                "8.8.8.8", "8.8.4.4" -> "https://dns.google/resolve?name=$hostname&type=A"
                "1.1.1.1", "1.0.0.1" -> "https://cloudflare-dns.com/dns-query?name=$hostname&type=A"
                "223.5.5.5", "223.6.6.6" -> "https://dns.alidns.com/resolve?name=$hostname&type=A"
                else -> null
            }
            
            if (dohUrl == null) {
                return null
            }
            
            // 发送 HTTP 请求
            val url = java.net.URL(dohUrl)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/dns-json")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                
                // 解析 JSON 响应
                parseDoHResponse(response)
            } else {
                connection.disconnect()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "DoH 解析失败", e)
            null
        }
    }
    
    /**
     * 解析 DoH JSON 响应
     */
    private fun parseDoHResponse(json: String): String? {
        return try {
            // 简单的 JSON 解析（不使用第三方库）
            val answerMatch = Regex(""""Answer":\s*\[(.*?)\]""").find(json)
            if (answerMatch != null) {
                val answerSection = answerMatch.groupValues[1]
                val dataMatch = Regex(""""data":\s*"([^"]+)"""").find(answerSection)
                dataMatch?.groupValues?.get(1)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析 DoH 响应失败", e)
            null
        }
    }
    
    /**
     * 批量解析多个域名
     */
    suspend fun resolveMultiple(hostnames: List<String>): Map<String, String?> = withContext(Dispatchers.IO) {
        hostnames.associateWith { hostname ->
            resolve(hostname)
        }
    }
}
