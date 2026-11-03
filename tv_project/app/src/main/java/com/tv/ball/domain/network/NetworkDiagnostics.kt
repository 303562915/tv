package com.tv.ball.domain.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URL

/**
 * 网络诊断工具
 * 用于诊断 DNS 解析和网络连接问题
 */
object NetworkDiagnostics {
    
    private const val TAG = "NetworkDiagnostics"
    
    /**
     * 诊断结果
     */
    data class DiagnosticResult(
        val url: String,
        val hostname: String,
        val systemDnsSuccess: Boolean,
        val systemDnsIp: String?,
        val customDnsSuccess: Boolean,
        val customDnsIp: String?,
        val connectionSuccess: Boolean,
        val responseCode: Int?,
        val errorMessage: String?,
        val networkType: String,
        val suggestions: List<String>
    )
    
    /**
     * 完整诊断 URL
     */
    suspend fun diagnose(context: Context, urlString: String): DiagnosticResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== 开始诊断 URL ===")
        Log.d(TAG, "URL: $urlString")
        
        val url = URL(urlString)
        val hostname = url.host
        val suggestions = mutableListOf<String>()
        
        // 1. 检查网络类型
        val networkType = getNetworkType(context)
        Log.d(TAG, "网络类型: $networkType")
        
        // 2. 测试系统 DNS 解析
        var systemDnsSuccess = false
        var systemDnsIp: String? = null
        try {
            val addresses = InetAddress.getAllByName(hostname)
            if (addresses.isNotEmpty()) {
                systemDnsIp = addresses[0].hostAddress
                systemDnsSuccess = true
                Log.d(TAG, "✓ 系统 DNS 解析成功: $hostname -> $systemDnsIp")
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ 系统 DNS 解析失败: ${e.message}")
            suggestions.add("系统 DNS 无法解析该域名，建议使用自定义 DNS")
        }
        
        // 3. 测试自定义 DNS 解析
        var customDnsSuccess = false
        var customDnsIp: String? = null
        try {
            customDnsIp = CustomDnsResolver.resolve(hostname)
            customDnsSuccess = customDnsIp != null
            if (customDnsSuccess) {
                Log.d(TAG, "✓ 自定义 DNS 解析成功: $hostname -> $customDnsIp")
            } else {
                Log.e(TAG, "✗ 自定义 DNS 解析失败")
                suggestions.add("所有 DNS 服务器都无法解析该域名，可能域名不存在或被屏蔽")
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ 自定义 DNS 解析异常: ${e.message}")
        }
        
        // 4. 测试连接
        var connectionSuccess = false
        var responseCode: Int? = null
        var errorMessage: String? = null
        
        try {
            val connection = EnhancedUrlConnection.openConnection(urlString, useDnsResolver = true)
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.connect()
            
            responseCode = connection.responseCode
            connectionSuccess = responseCode in 200..399
            connection.disconnect()
            
            if (connectionSuccess) {
                Log.d(TAG, "✓ 连接成功: HTTP $responseCode")
            } else {
                Log.e(TAG, "✗ 连接失败: HTTP $responseCode")
                errorMessage = "HTTP $responseCode"
                suggestions.add("服务器返回错误状态码 $responseCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ 连接异常: ${e.message}")
            errorMessage = e.message
            suggestions.add("连接失败: ${e.message}")
        }
        
        // 5. 生成建议
        if (!systemDnsSuccess && customDnsSuccess) {
            suggestions.add("建议：启用应用内的自定义 DNS 解析功能")
        }
        
        if (!customDnsSuccess) {
            suggestions.add("建议：检查网络连接，或尝试切换 WiFi/移动数据")
        }
        
        if (networkType == "无网络") {
            suggestions.add("建议：请检查设备的网络连接")
        }
        
        DiagnosticResult(
            url = urlString,
            hostname = hostname,
            systemDnsSuccess = systemDnsSuccess,
            systemDnsIp = systemDnsIp,
            customDnsSuccess = customDnsSuccess,
            customDnsIp = customDnsIp,
            connectionSuccess = connectionSuccess,
            responseCode = responseCode,
            errorMessage = errorMessage,
            networkType = networkType,
            suggestions = suggestions
        )
    }
    
    /**
     * 获取当前网络类型
     */
    private fun getNetworkType(context: Context): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return "无网络"
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "未知"
        
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            else -> "其他"
        }
    }
    
    /**
     * 打印诊断报告
     */
    fun printReport(result: DiagnosticResult) {
        Log.d(TAG, "")
        Log.d(TAG, "========== 诊断报告 ==========")
        Log.d(TAG, "URL: ${result.url}")
        Log.d(TAG, "域名: ${result.hostname}")
        Log.d(TAG, "网络类型: ${result.networkType}")
        Log.d(TAG, "")
        Log.d(TAG, "--- DNS 解析 ---")
        Log.d(TAG, "系统 DNS: ${if (result.systemDnsSuccess) "✓ 成功 (${result.systemDnsIp})" else "✗ 失败"}")
        Log.d(TAG, "自定义 DNS: ${if (result.customDnsSuccess) "✓ 成功 (${result.customDnsIp})" else "✗ 失败"}")
        Log.d(TAG, "")
        Log.d(TAG, "--- 连接测试 ---")
        Log.d(TAG, "连接状态: ${if (result.connectionSuccess) "✓ 成功" else "✗ 失败"}")
        if (result.responseCode != null) {
            Log.d(TAG, "HTTP 状态码: ${result.responseCode}")
        }
        if (result.errorMessage != null) {
            Log.d(TAG, "错误信息: ${result.errorMessage}")
        }
        Log.d(TAG, "")
        if (result.suggestions.isNotEmpty()) {
            Log.d(TAG, "--- 建议 ---")
            result.suggestions.forEach { suggestion ->
                Log.d(TAG, "• $suggestion")
            }
        }
        Log.d(TAG, "==============================")
        Log.d(TAG, "")
    }
}
