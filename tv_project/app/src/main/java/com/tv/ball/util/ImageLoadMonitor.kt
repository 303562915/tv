package com.tv.ball.util

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 图片加载性能监控工具
 * 用于统计和分析图片加载性能
 */
object ImageLoadMonitor {
    
    private const val TAG = "ImageLoadMonitor"
    
    // 统计数据
    private val loadStartTimes = ConcurrentHashMap<String, Long>()
    private val successCount = AtomicInteger(0)
    private val failureCount = AtomicInteger(0)
    private val totalLoadTime = AtomicInteger(0)
    private val preloadCount = AtomicInteger(0)

    // 失败URL记录（最多保留100条）
    private val failedUrls = ConcurrentHashMap<String, FailureInfo>()
    private const val MAX_FAILED_URLS = 100

    data class FailureInfo(
        val url: String,
        val error: String?,
        val timestamp: Long,
        var count: Int = 1
    )
    
    /**
     * 记录加载开始
     */
    fun onLoadStart(url: String) {
        loadStartTimes[url] = System.currentTimeMillis()
    }
    
    /**
     * 记录加载成功
     */
    fun onLoadSuccess(url: String) {
        val startTime = loadStartTimes.remove(url)
        if (startTime != null) {
            val duration = (System.currentTimeMillis() - startTime).toInt()
            totalLoadTime.addAndGet(duration)
            successCount.incrementAndGet()
            
            if (duration > 3000) {
                Log.w(TAG, "慢速加载: $url 耗时 ${duration}ms")
            }
        }
    }
    
    /**
     * 记录加载失败
     */
    fun onLoadFailure(url: String, error: String?) {
        loadStartTimes.remove(url)
        failureCount.incrementAndGet()

        // 记录失败的URL
        val existing = failedUrls[url]
        if (existing != null) {
            existing.count++
        } else {
            if (failedUrls.size >= MAX_FAILED_URLS) {
                // 移除最旧的记录
                val oldest = failedUrls.values.minByOrNull { it.timestamp }
                oldest?.let { failedUrls.remove(it.url) }
            }
            failedUrls[url] = FailureInfo(url, error, System.currentTimeMillis())
        }

        Log.e(TAG, "加载失败: $url, 原因: $error")
    }
    
    /**
     * 记录预加载
     */
    fun onPreload(count: Int) {
        preloadCount.addAndGet(count)
        Log.d(TAG, "预加载 $count 张图片")
    }
    
    /**
     * 获取失败的URL列表
     */
    fun getFailedUrls(): List<FailureInfo> {
        return failedUrls.values.sortedByDescending { it.count }
    }

    /**
     * 获取统计报告
     */
    fun getReport(): String {
        val success = successCount.get()
        val failure = failureCount.get()
        val total = success + failure
        val avgTime = if (success > 0) totalLoadTime.get() / success else 0
        val successRate = if (total > 0) (success * 100.0 / total) else 0.0

        val report = StringBuilder()
        report.append("""
            |图片加载统计报告
            |==================
            |总加载次数: $total
            |成功: $success (${String.format("%.1f", successRate)}%)
            |失败: $failure
            |平均加载时间: ${avgTime}ms
            |预加载次数: ${preloadCount.get()}
            |==================
        """.trimMargin())

        // 添加失败URL详情（最多显示前10个）
        if (failedUrls.isNotEmpty()) {
            report.append("\n\n失败URL详情（按失败次数排序）:\n")
            getFailedUrls().take(10).forEach { info ->
                report.append("- [${info.count}次] ${info.url}\n")
                report.append("  错误: ${info.error ?: "未知"}\n")
            }
            if (failedUrls.size > 10) {
                report.append("... 还有 ${failedUrls.size - 10} 个失败URL\n")
            }
        }

        return report.toString()
    }
    
    /**
     * 打印统计报告
     */
    fun printReport() {
        Log.i(TAG, getReport())
    }
    
    /**
     * 重置统计数据
     */
    fun reset() {
        loadStartTimes.clear()
        successCount.set(0)
        failureCount.set(0)
        totalLoadTime.set(0)
        preloadCount.set(0)
        failedUrls.clear()
        Log.d(TAG, "统计数据已重置")
    }
    
    /**
     * 检查是否有性能问题
     */
    fun checkPerformance(): PerformanceStatus {
        val success = successCount.get()
        val failure = failureCount.get()
        val total = success + failure
        val avgTime = if (success > 0) totalLoadTime.get() / success else 0
        val successRate = if (total > 0) (success * 100.0 / total) else 100.0
        
        return when {
            successRate < 80 -> PerformanceStatus.POOR
            avgTime > 2000 -> PerformanceStatus.SLOW
            avgTime > 1000 -> PerformanceStatus.MODERATE
            else -> PerformanceStatus.GOOD
        }
    }
    
    enum class PerformanceStatus {
        GOOD,       // 性能良好
        MODERATE,   // 性能一般
        SLOW,       // 加载较慢
        POOR        // 性能较差
    }
}
