package com.tv.ball.util

import android.content.Context
import android.util.Log
import com.tv.ball.domain.network.NetworkDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 网络测试辅助工具
 * 用于快速测试和诊断网络问题
 */
object NetworkTestHelper {
    
    private const val TAG = "NetworkTestHelper"
    
    /**
     * 测试指定的 URL 是否可访问
     * 
     * @param context 上下文
     * @param url 要测试的 URL
     * @return 测试结果消息
     */
    suspend fun testUrl(context: Context, url: String): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "测试 URL: $url")
        
        val result = NetworkDiagnostics.diagnose(context, url)
        NetworkDiagnostics.printReport(result)
        
        buildString {
            appendLine("=== 网络诊断结果 ===")
            appendLine()
            appendLine("URL: ${result.url}")
            appendLine("域名: ${result.hostname}")
            appendLine("网络类型: ${result.networkType}")
            appendLine()
            appendLine("DNS 解析:")
            appendLine("  系统 DNS: ${if (result.systemDnsSuccess) "✓ 成功 (${result.systemDnsIp})" else "✗ 失败"}")
            appendLine("  自定义 DNS: ${if (result.customDnsSuccess) "✓ 成功 (${result.customDnsIp})" else "✗ 失败"}")
            appendLine()
            appendLine("连接测试:")
            appendLine("  状态: ${if (result.connectionSuccess) "✓ 成功" else "✗ 失败"}")
            if (result.responseCode != null) {
                appendLine("  HTTP 状态码: ${result.responseCode}")
            }
            if (result.errorMessage != null) {
                appendLine("  错误: ${result.errorMessage}")
            }
            
            if (result.suggestions.isNotEmpty()) {
                appendLine()
                appendLine("建议:")
                result.suggestions.forEach { suggestion ->
                    appendLine("  • $suggestion")
                }
            }
        }
    }
    
    /**
     * 快速测试常见的视频 URL
     */
    suspend fun quickTest(context: Context): String = withContext(Dispatchers.IO) {
        val testUrls = listOf(
            "https://www.baidu.com",
            "https://play.ly166.com:65/test.m3u8"
        )
        
        buildString {
            appendLine("=== 快速网络测试 ===")
            appendLine()
            
            testUrls.forEach { url ->
                appendLine("测试: $url")
                try {
                    val result = NetworkDiagnostics.diagnose(context, url)
                    appendLine("  DNS: ${if (result.customDnsSuccess) "✓" else "✗"}")
                    appendLine("  连接: ${if (result.connectionSuccess) "✓" else "✗"}")
                } catch (e: Exception) {
                    appendLine("  错误: ${e.message}")
                }
                appendLine()
            }
        }
    }
}
