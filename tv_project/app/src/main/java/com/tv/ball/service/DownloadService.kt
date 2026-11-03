package com.tv.ball.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tv.ball.R
import com.tv.ball.domain.downloader.VideoDownloader
import com.tv.ball.domain.downloader.DownloadStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * 后台下载服务
 * 使用 Foreground Service 确保下载在后台继续进行
 */
class DownloadService : Service() {

    companion object {
        private const val TAG = "DownloadService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "download_channel"
        private const val CHANNEL_NAME = "下载服务"
        
        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            context.stopService(intent)
        }
    }

    private lateinit var videoDownloader: VideoDownloader
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var notificationBuilder: NotificationCompat.Builder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "DownloadService onCreate")
        
        videoDownloader = VideoDownloader.getInstance(this)
        
        // 创建通知渠道
        createNotificationChannel()
        
        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification("准备下载...", 0, 0))
        
        // 监听下载任务变化
        observeDownloadTasks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "DownloadService onStartCommand")
        return START_STICKY // 服务被杀死后自动重启
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "DownloadService onDestroy")
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示下载进度"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, current: Int, total: Int): Notification {
        // 点击通知打开下载页面
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (total > 0) {
            notificationBuilder?.setProgress(100, (current * 100 / total), false)
            notificationBuilder?.setContentText("$current / $total")
        } else {
            notificationBuilder?.setContentText("准备中...")
        }

        return notificationBuilder!!.build()
    }

    private fun observeDownloadTasks() {
        serviceScope.launch {
            videoDownloader.tasksFlow.collectLatest { tasks ->
                // 统计下载中的任务
                val downloadingTasks = tasks.filter { 
                    it.status == DownloadStatus.DOWNLOADING || 
                    it.status == DownloadStatus.MERGING 
                }
                
                if (downloadingTasks.isEmpty()) {
                    // 没有下载任务，停止服务
                    Log.d(TAG, "没有下载任务，停止服务")
                    stopSelf()
                } else {
                    // 更新通知
                    val totalTasks = downloadingTasks.size
                    val totalProgress = downloadingTasks.sumOf { it.progress } / totalTasks
                    
                    val title = if (totalTasks == 1) {
                        "正在下载: ${downloadingTasks[0].title}"
                    } else {
                        "正在下载 $totalTasks 个任务"
                    }
                    
                    val notification = createNotification(title, totalProgress, 100)
                    val notificationManager = getSystemService(NotificationManager::class.java)
                    notificationManager.notify(NOTIFICATION_ID, notification)
                }
            }
        }
    }
}
