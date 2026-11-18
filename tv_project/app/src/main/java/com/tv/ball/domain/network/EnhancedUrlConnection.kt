package com.tv.ball.domain.network

import android.util.Log
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * 增强的 URL 连接工具
 * 支持自定义 DNS 解析，解决某些域名无法访问的问题
 */
object EnhancedUrlConnection {
    
    private const val TAG = "EnhancedUrlConnection"
    
    /**
     * 创建 HTTP 连接，支持自定义 DNS 解析
     * @param urlString 原始 URL
     * @param useDnsResolver 是否使用自定义 DNS 解析器
     */
    suspend fun openConnection(urlString: String, useDnsResolver: Boolean = true): HttpURLConnection {
        Log.d(TAG, "打开连接: $urlString")
        
        return if (useDnsResolver) {
            openConnectionWithDns(urlString)
        } else {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            
            // 如果是HTTPS，禁用SSL验证
            if (connection is HttpsURLConnection) {
                disableSslVerification(connection)
            }
            
            connection
        }
    }
    
    /**
     * 禁用SSL证书验证
     */
    private fun disableSslVerification(connection: HttpsURLConnection) {
        try {
            val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
                object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                }
            )
            
            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            connection.sslSocketFactory = sslContext.socketFactory
            connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            
            Log.d(TAG, "✓ 已禁用SSL验证")
        } catch (e: Exception) {
            Log.e(TAG, "禁用SSL验证失败: ${e.message}")
        }
    }
    
    /**
     * 使用自定义 DNS 解析打开连接
     */
    private suspend fun openConnectionWithDns(urlString: String): HttpURLConnection {
        try {
            val url = URL(urlString)
            val hostname = url.host
            val port = if (url.port != -1) url.port else url.defaultPort
            val protocol = url.protocol
            
            Log.d(TAG, "解析域名: $hostname")
            
            // 尝试自定义 DNS 解析
            val resolvedIp = CustomDnsResolver.resolve(hostname)
            
            if (resolvedIp != null) {
                Log.d(TAG, "✓ DNS 解析成功: $hostname -> $resolvedIp")
                
                // 构建新的 URL，使用 IP 地址
                val newUrlString = urlString.replace(hostname, resolvedIp)
                val newUrl = URL(newUrlString)
                val connection = newUrl.openConnection() as HttpURLConnection
                
                // 设置 Host 头，确保服务器知道原始域名
                connection.setRequestProperty("Host", hostname)
                
                // 如果是 HTTPS，需要禁用SSL验证
                if (connection is HttpsURLConnection) {
                    // 信任所有证书
                    val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
                        object : javax.net.ssl.X509TrustManager {
                            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                        }
                    )
                    
                    val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
                    sslContext.init(null, trustAllCerts, java.security.SecureRandom())
                    connection.sslSocketFactory = sslContext.socketFactory
                    
                    // 完全禁用hostname验证
                    connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                    
                    Log.d(TAG, "✓ 已禁用SSL验证")
                }
                
                return connection
            } else {
                Log.w(TAG, "✗ DNS 解析失败，使用系统默认")
                // DNS 解析失败，回退到系统默认
                return url.openConnection() as HttpURLConnection
            }
        } catch (e: Exception) {
            Log.e(TAG, "打开连接失败: ${e.message}", e)
            // 出错时回退到系统默认
            val url = URL(urlString)
            return url.openConnection() as HttpURLConnection
        }
    }
    
    /**
     * 测试 URL 是否可访问
     */
    suspend fun testConnection(urlString: String): Boolean {
        return try {
            val connection = openConnection(urlString)
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.connect()
            
            val responseCode = connection.responseCode
            connection.disconnect()
            
            val success = responseCode in 200..399
            Log.d(TAG, "测试连接: $urlString -> $responseCode (${if (success) "成功" else "失败"})")
            success
        } catch (e: Exception) {
            Log.e(TAG, "测试连接失败: $urlString", e)
            false
        }
    }
}
