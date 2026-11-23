package com.tv.ball.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.google.android.exoplayer2.Player
import com.tv.ball.di.Dependencies
import com.tv.ball.presentation.player.VideoPlayerActivity

/**
 * 后台播放前台服务（可选功能）。
 *
 * 只在播放页「允许后台播放」开关打开（`user_prefs / playback_background_enabled`，默认关）时，
 * 由 VideoPlayerActivity 的 onPause 路径启动：切到后台不暂停，交给本服务保活，
 * 并通过通知上的播放/暂停与关闭按钮控制播放。默认关闭时本服务完全不会被拉起，
 * 播放页行为与旧版完全一致。
 *
 * 通知复用「正在播放」样式（低优先级、点击回播放页），渠道复用现有渠道
 * （优先 download_channel），不新建渠道、不加新权限。
 *
 * MediaSession 用 androidx 的 MediaSessionCompat（`androidx.media:media`）：
 * 通知走 MediaStyle 并挂上 sessionToken，Android 13+ 才会把它识别成「媒体通知」，
 * 在通知栏/锁屏/媒体输出切换器里显示播放控件；锁屏与蓝牙耳机的播放暂停键也走同一会话。
 */
class PlaybackService : Service() {

    companion object {
        private const val TAG = "PlaybackService"

        /** 前台通知 id：与播放页「正在播放」提示（NOW_PLAYING_NOTIFICATION_ID = 4301）错开，互不覆盖。 */
        private const val NOTIFICATION_ID = 4302

        /** 通知播放/暂停按钮的 action。 */
        private const val ACTION_PLAY_PAUSE = "com.tv.ball.playback.ACTION_PLAY_PAUSE"

        /** 通知「关闭」按钮的 action：暂停播放 + 停服务 + 释放 MediaSession。 */
        private const val ACTION_CLOSE = "com.tv.ball.playback.ACTION_CLOSE"

        /** 播放页传过来的标题（通知文案用，可选）。 */
        const val EXTRA_TITLE = "com.tv.ball.playback.EXTRA_TITLE"

        private const val REQUEST_OPEN = 4311
        private const val REQUEST_PLAY_PAUSE = 4312
        private const val REQUEST_CLOSE = 4313

        /**
         * 播放页起前台服务用。
         * O+ 走 startForegroundService；调用方（播放页）自带 try/catch 兜底，
         * ForegroundServiceStartNotAllowedException 等拒绝路径不崩、退回普通通知。
         */
        fun start(context: Context, title: String) {
            val intent = Intent(context, PlaybackService::class.java)
                .putExtra(EXTRA_TITLE, title)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        /** 用户真正退出播放页（onDestroy）时停掉服务：撤前台通知、释放 MediaSession。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }

    /** 通知 / 锁屏文案用的标题：每次播放页起服务时刷新。 */
    private var currentTitle: String = "未命名视频"

    private var mediaSession: MediaSessionCompat? = null

    /** 当前挂着监听的那个 player：换集 / 释放后 player 实例会变，变了就重挂。 */
    private var attachedPlayer: Player? = null

    /** 播放状态变化 → 刷新通知按钮文案 + MediaSession playbackState。 */
    private val playerStateListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshForegroundUi()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            refreshForegroundUi()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PlaybackService onCreate")
        attachStateListener()
        ensureMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val extraTitle = intent?.getStringExtra(EXTRA_TITLE)
        if (!extraTitle.isNullOrBlank()) currentTitle = extraTitle

        attachStateListener()
        ensureMediaSession()

        when (intent?.action) {
            ACTION_PLAY_PAUSE -> {
                // 通知按钮 / 锁屏按键：切播放 ↔ 暂停
                // （onStartCommand 与 ExoPlayer 都在主线程，直接调安全）
                runCatching { Dependencies.exoPlayerManager.togglePlayPause() }
                Log.d(TAG, "通知切播放/暂停")
            }
            ACTION_CLOSE -> {
                Log.d(TAG, "通知点「关闭」：暂停 + 停服务")
                // 关闭语义：暂停播放（播放器释放仍由播放页现有逻辑负责）、撤前台、随 onDestroy 释放 MediaSession
                runCatching { Dependencies.exoPlayerManager.pause() }
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // startForeground 失败（Android 14+ 缺 FOREGROUND_SERVICE_MEDIA_PLAYBACK 权限 /
        // ForegroundServiceStartNotAllowedException / 渠道缺失等）：退回普通通知，服务自杀 —— 不崩
        if (!ensureForeground()) stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 任务被划走：撤掉前台标记；服务本身由播放页 onDestroy 的 stopService 收尾
        runCatching { stopForegroundCompat() }
    }

    override fun onDestroy() {
        attachedPlayer?.let { runCatching { it.removeListener(playerStateListener) } }
        attachedPlayer = null
        runCatching { stopForegroundCompat() }
        runCatching {
            mediaSession?.isActive = false
            mediaSession?.release()
        }
        mediaSession = null
        Log.d(TAG, "PlaybackService onDestroy：前台已撤、MediaSession 已释放")
        super.onDestroy()
    }

    // ==================== 前台通知 ====================

    /**
     * 起前台 + 发通知。复用「正在播放」样式（低优先级、点回播放页），
     * 额外加播放/暂停（切态）与关闭两个动作按钮。
     */
    private fun ensureForeground(): Boolean {
        val notification = buildNotification() ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 14+ 必须显式带 foregroundServiceType，否则抛 MissingForegroundServiceTypeException
                androidx.core.app.ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            // 覆盖 ForegroundServiceStartNotAllowedException / SecurityException 等一切拒绝
            // （Android 14+ 的 mediaPlayback 类型已在 Manifest 声明 FOREGROUND_SERVICE_MEDIA_PLAYBACK，
            // 但被系统策略拒绝、后台启动受限时仍可能抛）：退回普通通知（播放页还有一条低优先级
            // 「正在播放」提示），服务自杀，绝不崩
            Log.w(TAG, "startForeground 失败，退回普通通知: ${e.message}")
            runCatching {
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
            }
            false
        }
    }

    /** 播放状态变化时重发同一条通知（同 id 只更新不叠加），并同步 MediaSession。 */
    private fun refreshForegroundUi() {
        refreshMediaSessionPlaybackState()
        val notification = buildNotification() ?: return
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification? {
        val channelId = findExistingNotificationChannelId() ?: run {
            Log.w(TAG, "没有可复用的通知渠道，通知放弃")
            return null
        }
        val manager = Dependencies.exoPlayerManager
        val playing = manager.isPlaying()

        val openPlayerIntent = Intent(this, VideoPlayerActivity::class.java).apply {
            // 播放页还在栈里就复用同一个实例：CLEAR_TOP + SINGLE_TOP 不会重建页面，播放不中断
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            openPlayerIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val playPauseIntent = PendingIntent.getService(
            this,
            REQUEST_PLAY_PAUSE,
            Intent(this, PlaybackService::class.java).setAction(ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val closeIntent = PendingIntent.getService(
            this,
            REQUEST_CLOSE,
            Intent(this, PlaybackService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(if (playing) "正在播放：$currentTitle" else "已暂停：$currentTitle")
            .setContentIntent(contentIntent)
            .setSmallIcon(if (playing) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // 播放/暂停按钮（没有上一集动作：服务拿不到播放页的集数据，可选功能就不放）
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "暂停" else "播放",
                playPauseIntent
            )
            // 关闭：暂停 + 停服务
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "关闭",
                closeIntent
            )
            // MediaStyle + sessionToken：Android 13+ 媒体控件（系统媒体卡片）靠这个识别
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .build()
    }

    /**
     * 在**已存在**的渠道里挑一条用：优先下载渠道（download_channel），没有就取重要性最低的那条；
     * 一条都没有返回 null —— 绝不新建渠道（与播放页 postNowPlayingNotification 同一口径）。
     */
    private fun findExistingNotificationChannelId(): String? {
        val manager = getSystemService(NotificationManager::class.java) ?: return null
        val preferred = com.tv.ball.presentation.download.DownloadCompletionNotifier.CHANNEL_ID
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return preferred
        val channels = manager.notificationChannels ?: return null
        if (channels.any { it.id == preferred }) return preferred
        return channels.minByOrNull { it.importance }?.id
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        stopForeground(true)
    }

    // ==================== MediaSession（框架自带，不加依赖） ====================

    /**
     * 最小化 MediaSession：只为了让锁屏 / 蓝牙耳机的播放/暂停键能控制播放。
     * 会话由本服务持有、随服务销毁释放；ExoPlayerManager 是单例（Dependencies），
     * 播放器实例跨页面存活，服务在这里调 pause/play 即可。
     */
    private fun ensureMediaSession() {
        if (mediaSession != null) return
        mediaSession = runCatching {
            MediaSessionCompat(this, "TVPlayerPlayback").apply {
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        runCatching { Dependencies.exoPlayerManager.play() }
                    }

                    override fun onPause() {
                        runCatching { Dependencies.exoPlayerManager.pause() }
                    }

                    override fun onStop() {
                        runCatching { Dependencies.exoPlayerManager.pause() }
                    }

                    // 媒体按键：MediaSessionCompat 已把 KeyEvent 解成 onPlay/onPause，
                    // 这里只兜住播放/暂停「切换」语义（单击耳机线控）
                    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                        @Suppress("DEPRECATION")
                        val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                        if (event?.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                            event?.keyCode == KeyEvent.KEYCODE_HEADSETHOOK
                        ) {
                            if (event?.action == KeyEvent.ACTION_DOWN) {
                                runCatching { Dependencies.exoPlayerManager.togglePlayPause() }
                            }
                            return true
                        }
                        return super.onMediaButtonEvent(mediaButtonIntent)
                    }
                })
                isActive = true
                setPlaybackState(buildPlaybackState())
            }
        }.getOrNull()
    }

    /** 把当前播放状态写进 MediaSession（锁屏按钮态 / 媒体卡片进度）。 */
    private fun refreshMediaSessionPlaybackState() {
        mediaSession?.setPlaybackState(buildPlaybackState())
    }

    private fun buildPlaybackState(): PlaybackStateCompat {
        val manager = Dependencies.exoPlayerManager
        val player = manager.getPlayer()
        val state = when {
            player?.playbackState == Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            player?.playbackState == Player.STATE_ENDED -> PlaybackStateCompat.STATE_STOPPED
            manager.isPlaying() -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_STOP
        return PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(
                state,
                manager.getCurrentPosition().coerceAtLeast(0L),
                if (state == PlaybackStateCompat.STATE_PLAYING) manager.getPlaybackSpeed() else 0f
            )
            .build()
    }

    /** 给当前 ExoPlayer 挂状态监听；player 实例变了（换集 / 释放重建）就重挂。 */
    private fun attachStateListener() {
        val player = Dependencies.exoPlayerManager.getPlayer()
        if (player === attachedPlayer) return
        attachedPlayer?.let { runCatching { it.removeListener(playerStateListener) } }
        attachedPlayer = player
        player?.addListener(playerStateListener)
    }
}
