package com.tv.ball.domain.network

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 线路速度测试器
 * 并行测试多个线路的响应速度和下载速度
 */
class RouteSpeedTester(
    private val client: OkHttpClient
) {
    companion object {
        private const val TAG = "RouteSpeedTester"
        private const val TEST_TIMEOUT = 5000L // 5秒超时
        private const val TEST_BYTES = 1024 * 100 // 测试下载100KB
    }
    
    data class RouteTestResult(
        val url: String,
        val routeName: String,
        val responseTime: Long, // 响应时间（毫秒）
        val downloadSpeed: Long, // 下载速度（KB/s）
        val success: Boolean,
        val error: String? = null
    ) {
        val score: Long
            get() = if (success) {
                // 综合评分：响应时间权重30%，下载速度权重70%
                // 响应时间：越短越好（最多5秒，超过5秒得0分）
                val responseScore = (5000 - responseTime.coerceIn(0, 5000)) / 5
                // 下载速度：越快越好（最高10MB/s，超过得满分）
                val speedScore = downloadSpeed.coerceIn(0, 10000) / 10
                // 加权计算：响应时间30% + 下载速度70%
                (responseScore * 0.3 + speedScore * 0.7).toLong()
            } else {
                0
            }
    }
    
    /**
     * 并行测试多个线路
     */
    suspend fun testRoutes(
        routes: List<Pair<String, String>>, // Pair<URL, RouteName>
        onProgress: (String) -> Unit = {}
    ): List<RouteTestResult> = withContext(Dispatchers.IO) {
        Log.d(TAG, "========== 开始测试 ${routes.size} 个线路 ==========")
        Log.d(TAG, "检测标准：1) 先检查连通性(ms) - 无法访问直接排除")
        Log.d(TAG, "          2) 再测试实际请求响应速度 - 主要评判标准")
        Log.d(TAG, "          3) 综合评分：响应时间30% + 下载速度70%")
        
        val results = routes.map { (url, routeName) ->
            async {
                testSingleRoute(url, routeName, onProgress)
            }
        }.awaitAll()
        
        // 按评分排序
        val sortedResults = results.sortedByDescending { it.score }
        
        Log.d(TAG, "========== 测试完成 ==========")
        sortedResults.forEachIndexed { index, result ->
            if (result.success) {
                Log.i(TAG, "#${index + 1} ${result.routeName}: " +
                        "响应${result.responseTime}ms, " +
                        "速度${result.downloadSpeed}KB/s, " +
                        "评分${result.score}")
            } else {
                Log.w(TAG, "#${index + 1} ${result.routeName}: 失败 - ${result.error}")
            }
        }
        
        sortedResults
    }
    
    /**
     * 测试单个线路
     */
    private suspend fun testSingleRoute(
        url: String,
        routeName: String,
        onProgress: (String) -> Unit
    ): RouteTestResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "测试线路: $routeName")
        onProgress("测试 $routeName...")
        
        try {
            // 创建带超时的客户端
            val testClient = client.newBuilder()
                .connectTimeout(TEST_TIMEOUT, TimeUnit.MILLISECONDS)
                .readTimeout(TEST_TIMEOUT, TimeUnit.MILLISECONDS)
                .build()
            
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-${TEST_BYTES - 1}") // 只下载前100KB
                .build()
            
            // 第一步：测试连通性和响应时间（这是前提条件）
            val startTime = System.currentTimeMillis()
            val response = testClient.newCall(request).execute()
            val responseTime = System.currentTimeMillis() - startTime
            
            if (!response.isSuccessful) {
                Log.w(TAG, "$routeName: HTTP ${response.code} - 无法访问，直接排除")
                return@withContext RouteTestResult(
                    url, routeName, 0, 0, false,
                    "HTTP ${response.code}"
                )
            }
            
            // 第二步：测试实际下载速度（主要评判标准）
            val downloadStartTime = System.currentTimeMillis()
            val body = response.body
            val bytes = body?.bytes() ?: byteArrayOf()
            val downloadTime = System.currentTimeMillis() - downloadStartTime
            
            val downloadSpeed = if (downloadTime > 0) {
                (bytes.size / 1024) * 1000 / downloadTime // KB/s
            } else {
                0L
            }
            
            response.close()
            
            Log.d(TAG, "$routeName: 响应${responseTime}ms, 下载速度${downloadSpeed}KB/s")
            
            RouteTestResult(
                url, routeName, responseTime, downloadSpeed, true
            )
            
        } catch (e: Exception) {
            // 连接失败或超时，说明没有 ms 响应，直接排除
            Log.w(TAG, "$routeName: ${e.message} - 无法连接，直接排除")
            RouteTestResult(
                url, routeName, 0, 0, false,
                e.message ?: "未知错误"
            )
        }
    }
}
