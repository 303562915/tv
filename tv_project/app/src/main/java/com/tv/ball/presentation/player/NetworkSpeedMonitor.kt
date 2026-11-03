package com.tv.ball.presentation.player

import android.content.Context
import android.net.TrafficStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 网速监控器
 * 参考腾讯视频的做法：监控应用级别的网速，而不是设备级别
 */
class NetworkSpeedMonitor(private val context: Context) {
    
    private var lastAppRxBytes = 0L
    private var lastTimeStamp = 0L
    private val speedHistory = mutableListOf<Long>() // 保存最近10次的速度记录
    private val maxHistorySize = 10
    
    /**
     * 监控网速，返回Flow
     */
    fun monitorSpeed(): Flow<SpeedInfo> = flow {
        while (true) {
            val speedInfo = calculateSpeed()
            emit(speedInfo)
            delay(1000) // 每秒更新一次
        }
    }
    
    private fun calculateSpeed(): SpeedInfo {
        // 使用应用级别的流量统计，而不是设备级别
        val uid = android.os.Process.myUid()
        val currentAppRxBytes = TrafficStats.getUidRxBytes(uid)
        val currentTimeStamp = System.currentTimeMillis()
        
        if (lastAppRxBytes == 0L || currentAppRxBytes < 0) {
            // 首次运行或无法获取应用流量，使用设备总流量
            lastAppRxBytes = if (currentAppRxBytes < 0) {
                TrafficStats.getTotalRxBytes()
            } else {
                currentAppRxBytes
            }
            lastTimeStamp = currentTimeStamp
            return SpeedInfo(0L, "0 KB/s", SpeedQuality.UNKNOWN, 0L)
        }
        
        val timeDelta = currentTimeStamp - lastTimeStamp
        if (timeDelta <= 0) {
            return SpeedInfo(0L, "0 KB/s", SpeedQuality.UNKNOWN, 0L)
        }
        
        val bytesDelta = currentAppRxBytes - lastAppRxBytes
        val speedBytesPerSecond = (bytesDelta * 1000) / timeDelta
        
        lastAppRxBytes = currentAppRxBytes
        lastTimeStamp = currentTimeStamp
        
        // 记录速度历史，用于计算平均速度
        speedHistory.add(speedBytesPerSecond)
        if (speedHistory.size > maxHistorySize) {
            speedHistory.removeAt(0)
        }
        
        // 计算平均速度（最近10秒）
        val avgSpeed = if (speedHistory.isNotEmpty()) {
            speedHistory.average().toLong()
        } else {
            speedBytesPerSecond
        }
        
        val speedText = formatSpeed(speedBytesPerSecond)
        val quality = determineQuality(avgSpeed) // 使用平均速度判断质量
        
        return SpeedInfo(speedBytesPerSecond, speedText, quality, avgSpeed)
    }
    
    private fun formatSpeed(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond < 0 -> "0 B/s"
            bytesPerSecond < 1024 -> "$bytesPerSecond B/s"
            bytesPerSecond < 1024 * 1024 -> "${bytesPerSecond / 1024} KB/s"
            else -> String.format("%.2f MB/s", bytesPerSecond / (1024.0 * 1024.0))
        }
    }
    
    /**
     * 参考腾讯视频和Lua版本的阈值设置：
     * - 高速：> 2 MB/s (2048 KB/s)
     * - 还可以：500 KB/s - 2 MB/s
     * - 较慢：100 KB/s - 500 KB/s
     * - 过慢：< 100 KB/s
     */
    private fun determineQuality(bytesPerSecond: Long): SpeedQuality {
        val kbps = bytesPerSecond / 1024
        return when {
            kbps < 100 -> SpeedQuality.POOR       // < 100 KB/s - 过慢
            kbps < 500 -> SpeedQuality.FAIR       // 100-500 KB/s - 较慢
            kbps < 2048 -> SpeedQuality.GOOD      // 500 KB/s - 2 MB/s - 还可以
            else -> SpeedQuality.EXCELLENT        // > 2 MB/s - 高速
        }
    }
    
    data class SpeedInfo(
        val bytesPerSecond: Long,
        val speedText: String,
        val quality: SpeedQuality,
        val avgSpeed: Long // 平均速度
    )
    
    enum class SpeedQuality {
        UNKNOWN, POOR, FAIR, GOOD, EXCELLENT
    }
}
