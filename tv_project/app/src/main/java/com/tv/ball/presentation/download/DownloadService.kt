package com.tv.ball.presentation.download

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tv.ball.R
import com.tv.ball.domain.downloader.VideoDownloader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

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

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d(TAG, "DownloadService onCreate")
        
        videoDownloader = VideoDownloader.getInstance(this)
        
        // 创建通知渠道
        createNotificationChannel()
        
        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification("下载服务运行中", 0, 0))
        
        // 监听下载任务变化，更新通知
        observeDownloads()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台下载服务通知"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, current: Int, total: Int): Notification {
        val intent = Intent(this, DownloadActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (total > 0) {
            builder.setProgress(total, current, false)
            builder.setContentText("$current / $total")
        }

        return builder.build()
    }

    private fun observeDownloads() {
        serviceScope.launch {
            videoDownloader.tasksFlow.collectLatest { tasks ->
                val downloadingTasks = tasks.filter { 
                    it.status == com.tv.ball.domain.downloader.DownloadStatus.DOWNLOADING ||
                    it.status == com.tv.ball.domain.downloader.DownloadStatus.MERGING
                }
                
                if (downloadingTasks.isEmpty()) {
                    // 没有下载任务，更新通知
                    val notification = createNotification("无下载任务", 0, 0)
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.notify(NOTIFICATION_ID, notification)
                } else {
                    // 有下载任务，显示进度
                    val firstTask = downloadingTasks.first()
                    val notification = createNotification(
                        "下载中: ${firstTask.title}",
                        firstTask.progress,
                        100
                    )
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.notify(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d(TAG, "DownloadService onStartCommand")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d(TAG, "DownloadService onDestroy")
        serviceScope.cancel()
    }
}
