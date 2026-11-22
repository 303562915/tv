package com.tv.ball.presentation.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tv.ball.domain.downloader.DownloadStatus
import com.tv.ball.domain.downloader.DownloadTask

/**
 * 下载完成通知的统一出口。
 *
 * - 渠道复用 [DownloadService] 已经建好的 [CHANNEL_ID]；没建过（例如页面单独启动）就按同样
 *   id 补建一次，同 id 重复创建不会顶掉用户已经改过的设置；
 * - 通知不可用（系统通知被关 / 没授予 POST_NOTIFICATIONS）时直接跳过，不影响下载本身；
 * - 按任务 id 在进程内去重：服务与下载页两条观察路径同时收到回调也只发一条。
 */
object DownloadCompletionNotifier {

    /** 与 DownloadService 里的下载渠道同一个 id，直接复用，不再新建渠道。 */
    const val CHANNEL_ID = "download_channel"

    private const val CHANNEL_NAME = "下载服务"
    private const val CHANNEL_DESC = "显示下载进度"

    /** 完成通知 id 基数，按任务 id 偏移，避免多个任务互相覆盖。 */
    private const val NOTIFICATION_ID_BASE = 2000

    private const val REQUEST_CODE = 2000

    /** 已经发过完成通知的任务 id。 */
    private val notifiedTaskIds = HashSet<Long>()

    /** 进程内第一次收到任务列表时只登记基线，避免把历史已完成任务全弹一遍。 */
    private var baselineSeeded = false

    /**
     * 任务列表回调入口：只对「本次新变成 COMPLETED」的任务发通知。
     * 服务与页面都可以调用，内部做去重。
     */
    fun onTasks(context: Context, tasks: List<DownloadTask>) {
        val completed = tasks.filter { it.status == DownloadStatus.COMPLETED }
        if (!baselineSeeded) {
            baselineSeeded = true
            completed.forEach { notifiedTaskIds.add(it.id) }
            return
        }
        completed.forEach { task ->
            if (notifiedTaskIds.add(task.id)) {
                notifyCompleted(context, task)
            }
        }
    }

    /** 下载完成通知：标题「下载完成：文件名」，点击回到下载页。 */
    private fun notifyCompleted(context: Context, task: DownloadTask) {
        if (!canPostNotifications(context)) {
            android.util.Log.d(
                "DownloadNotifier",
                "通知不可用（已关闭或无权限），跳过完成通知: ${task.fileName}"
            )
            return
        }

        ensureChannel(context)

        // 点击通知直接打开下载页，而不是首页
        val intent = Intent(context, DownloadActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("下载完成：${task.fileName}")
            .setContentText(task.savePath)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID_BASE + (task.id % 1000L).toInt(), notification)
        } catch (e: SecurityException) {
            android.util.Log.w("DownloadNotifier", "缺少通知权限，跳过完成通知", e)
        }
    }

    /** 通知总开关 / 运行时权限都可用才发，避免无权限直接抛异常。 */
    private fun canPostNotifications(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    /** 渠道只补建一次，已存在则不动。 */
    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = CHANNEL_DESC
                setShowBadge(false)
            }
        )
    }
}