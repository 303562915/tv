package com.tv.ball.presentation.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player
import com.tv.ball.R
import com.tv.ball.VideoPlayerApplication
import com.tv.ball.databinding.ActivityVideoPlayerBinding
import com.tv.ball.di.Dependencies
import com.tv.ball.domain.model.Episode
import com.tv.ball.util.VideoFlowLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private var portraitDetailView: View? = null
    private var scrollView: androidx.core.widget.NestedScrollView? = null
    private var containerLayout: LinearLayout? = null
    private var portraitEpisodeAdapter: EpisodeSelectorAdapter? = null
    private var dialogEpisodeAdapter: EpisodeSelectorAdapter? = null
    private var hasPlayedRevealAnimation = false // 标记是否已播放过揭露动画
    private val skipIntroHandler = Handler(Looper.getMainLooper())
    private val viewModel: VideoPlayerViewModel by lazy {
        VideoPlayerViewModel(context = applicationContext)
    }

    private val exoPlayerManager: ExoPlayerManager by lazy {
        Dependencies.exoPlayerManager
    }

    private val playlistManager = PlaylistManager()
    private val systemInfoMonitor by lazy { SystemInfoMonitor(this) }
    private val networkSpeedMonitor by lazy { NetworkSpeedMonitor(this) }
    private val networkStatusMonitor by lazy { 
        NetworkStatusMonitor(this) { networkType, message ->
            runOnUiThread {
                handleNetworkChange(networkType, message)
            }
        }
    }
    private val skipIntroManager by lazy { SkipIntroManager(this) }
    private val aspectRatioManager by lazy { AspectRatioManager(this) }

    // 开源本地播放器：只按 Intent 传入的地址播放，不再有视频源/嗅探概念
    private val videoSource = "local"
    private var currentPlayingUrl: String = ""
    
    private lateinit var gestureDetector: GestureDetector
    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }
    private val hideGestureHintHandler = Handler(Looper.getMainLooper())
    private val hideGestureHintRunnable = Runnable { hideGestureHint() }
    private val longPressHandler = Handler(Looper.getMainLooper())  // 专门用于长按检测的Handler
    private var isControlsVisible = true
    private var isLocked = false
    private var isSeeking = false
    private var hasSkippedIntro = false
    
    // 长按倍速相关
    private var isLongPressActive = false
    private var isSpeedLocked = false  // 倍速是否已锁定
    private var normalSpeed = 1.0f  // 记录正常速度
    private var longPressStartY = 0f  // 长按开始的Y坐标
    private var longPressCheckRunnable: Runnable? = null  // 长按检测任务
    
    // 获取长按加速倍数（从SharedPreferences读取）
    private fun getLongPressSpeed(): Float {
        val prefs = getSharedPreferences("player_settings", MODE_PRIVATE)
        return prefs.getFloat("long_press_speed", 3.0f)
    }
    
    // 滑动灵敏度（默认1.25倍）
    private var seekSensitivity = 1.25f
    
    // 检查长按上滑锁定功能是否启用
    private fun isLongPressLockEnabled(): Boolean {
        val prefs = getSharedPreferences("player_settings", MODE_PRIVATE)
        return prefs.getBoolean("long_press_lock_enabled", true)
    }
    
    private var videoTitle = ""
    private var videoId = 0L
    private var thumbnailUrl: String? = null
    private var detailUrl: String? = null
    private var episodesJson: String? = null
    
    // 下一集提示阈值（剩余秒数）
    private val nextEpisodeHintThresholdSeconds = 5
    
    // 下一集倒计时相关
    private var nextEpisodeCountdown = 5  // 倒计时秒数
    private var isShowingNextEpisodeHint = false  // 是否正在显示提示
    private val countdownHandler = Handler(Looper.getMainLooper())
    private var countdownRunnable: Runnable? = null
    
    // 手势相关
    private var gestureDownX = 0f
    private var gestureDownY = 0f
    private var gestureType = GestureType.NONE
    
    // 防止快速暂停/恢复循环
    private var lastPauseTime = 0L
    private var lastResumeTime = 0L
    private val LIFECYCLE_THROTTLE_MS = 500L  // 500ms内不重复执行
    
    // 播放进度恢复标志
    private var hasRestoredPlaybackPosition = false
    
    enum class GestureType {
        NONE, PROGRESS, BRIGHTNESS, VOLUME
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        android.util.Log.d("VideoPlayer", "=== onCreate 开始 ===")

        // 加载播放器布局
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)

        // 创建 ScrollView 作为根容器
        scrollView = androidx.core.widget.NestedScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
            id = View.generateViewId()
            setBackgroundColor(android.graphics.Color.WHITE) // 设置白色背景（默认普通模式）
        }

        // 创建垂直 LinearLayout
        containerLayout = LinearLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.TRANSPARENT) // 设置透明背景
        }

        // 将播放器添加到容器
        containerLayout?.addView(binding.root)

        // 将容器添加到 ScrollView
        scrollView?.addView(containerLayout)

        // 设置 ScrollView 为内容视图
        setContentView(scrollView)

        // 设置初始屏幕方向为竖屏
        android.util.Log.d("VideoPlayer", "设置屏幕方向为竖屏: SCREEN_ORIENTATION_PORTRAIT")
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // 设置全屏和保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        android.util.Log.d("VideoPlayer", "当前屏幕方向: ${resources.configuration.orientation}")

        // 确保手势提示初始是隐藏的
        hideGestureHint()

        setupGestureDetector()
        setupPlayer()
        setupControls()
        observeViewModel()
        loadVideoFromIntent()
        startTimeUpdate()
        startNetworkMonitoring()

        // 初始化画面比例
        applyAspectRatio(aspectRatioManager.getCurrentMode())

        // 初始化时根据当前方向调整布局
        updateOrientationUI()
    }
    
    private fun setupGestureDetector() {
        android.util.Log.d("VideoPlayer", "=== setupGestureDetector 初始化手势检测器 ===")
        var isScrolling = false
        var longPressDetected = false  // 标记是否已检测到长按

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                android.util.Log.d("VideoPlayer", "手势 onDown: x=${e.x}, y=${e.y}, isSpeedLocked=$isSpeedLocked, normalSpeed=$normalSpeed")
                // 重置所有标记
                isScrolling = false
                longPressDetected = false
                tempProgress = exoPlayerManager.getCurrentPosition()
                gestureType = GestureType.NONE
                longPressStartY = e.y  // 记录按下位置
                
                // 取消之前的长按检测
                longPressCheckRunnable?.let { longPressHandler.removeCallbacks(it) }
                
                // 启动长按检测（500ms后）
                longPressCheckRunnable = Runnable {
                    android.util.Log.d("VideoPlayer", "=== 长按检测Runnable执行 ===")
                    android.util.Log.d("VideoPlayer", "长按检测Runnable执行: isScrolling=$isScrolling, isLocked=$isLocked, isSpeedLocked=$isSpeedLocked, isPlaying=${exoPlayerManager.isPlaying()}")
                    
                    if (!isScrolling && !isLocked && exoPlayerManager.isPlaying()) {
                        // 如果已经锁定倍速，再次长按取消锁定并恢复速度
                        if (isSpeedLocked) {
                            isSpeedLocked = false
                            android.util.Log.d("VideoPlayer", "!!! 再次长按，取消锁定: normalSpeed=$normalSpeed")
                            exoPlayerManager.setPlaybackSpeed(normalSpeed)
                            hideLongPressSpeedHint()
                            android.util.Log.d("VideoPlayer", "!!! 再次长按，已恢复速度: ${exoPlayerManager.getPlaybackSpeed()}x")
                        } else {
                            // 未锁定时，激活长按加速（但不显示提示，等待滑动）
                            longPressDetected = true
                            isLongPressActive = true
                            val currentSpeed = exoPlayerManager.getPlaybackSpeed()
                            val longPressSpeed = getLongPressSpeed()
                            android.util.Log.d("VideoPlayer", "!!! 长按激活前: currentSpeed=$currentSpeed, normalSpeed=$normalSpeed, longPressSpeed=$longPressSpeed")
                            // 只有当前速度不是长按速度时，才保存normalSpeed
                            if (currentSpeed != longPressSpeed) {
                                normalSpeed = currentSpeed
                                android.util.Log.d("VideoPlayer", "!!! 长按激活: 保存normalSpeed=$normalSpeed")
                            } else {
                                android.util.Log.d("VideoPlayer", "!!! 长按激活: 当前已是长按速度，不保存normalSpeed（保持=$normalSpeed）")
                            }
                            android.util.Log.d("VideoPlayer", "!!! 准备设置播放速度: $longPressSpeed")
                            exoPlayerManager.setPlaybackSpeed(longPressSpeed)
                            android.util.Log.d("VideoPlayer", "!!! 已设置播放速度，当前速度: ${exoPlayerManager.getPlaybackSpeed()}x")
                            // 立即显示提示（不等待滑动）
                            showLongPressSpeedHint(longPressSpeed, false)
                            android.util.Log.d("VideoPlayer", "长按检测成功: ${normalSpeed}x -> ${longPressSpeed}x, isLongPressActive=$isLongPressActive, 已显示加速提示")
                        }
                    } else {
                        android.util.Log.d("VideoPlayer", "!!! 长按检测失败: isScrolling=$isScrolling, isLocked=$isLocked, isPlaying=${exoPlayerManager.isPlaying()}")
                    }
                }
                android.util.Log.d("VideoPlayer", "启动长按检测任务，500ms后执行")
                longPressHandler.postDelayed(longPressCheckRunnable!!, 500)
                
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 隐藏手势提示
                hideGestureHint()
                toggleControlsVisibility()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isLocked) {
                    togglePlayPause()
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                android.util.Log.d("VideoPlayer", "!!! 手势 onScroll 被调用: distanceX=$distanceX, distanceY=$distanceY, isLocked=$isLocked, isLongPressActive=$isLongPressActive")

                if (isLocked || e1 == null) {
                    android.util.Log.d("VideoPlayer", "手势被锁定或e1为null，返回false")
                    return false
                }

                // 检查是否是长按后滑动（优先处理）
                if (isLongPressActive && !isSpeedLocked && isLongPressLockEnabled()) {
                    val swipeDistance = e1.y - e2.y  // 上滑为正值，下滑为负值
                    android.util.Log.d("VideoPlayer", "!!! 长按中滑动: swipeDistance=$swipeDistance, e1.y=${e1.y}, e2.y=${e2.y}")
                    
                    // 如果上滑超过150像素，锁定倍速
                    if (swipeDistance > 150f) {
                        isSpeedLocked = true
                        isLongPressActive = false
                        longPressDetected = false
                        val longPressSpeed = getLongPressSpeed()
                        showLongPressSpeedHint(longPressSpeed, true)
                        android.util.Log.d("VideoPlayer", "!!! 倍速已锁定: ${longPressSpeed}x")
                        
                        // 重置滑动标记，避免触发其他手势
                        isScrolling = false
                        gestureType = GestureType.NONE
                        return true
                    }
                    // 如果下滑超过80像素，显示"松手恢复速度"提示
                    else if (swipeDistance < -80f) {
                        showSpeedRestoreHint()
                        android.util.Log.d("VideoPlayer", "!!! 下滑提示：松手恢复速度")
                        return true
                    }
                    // 如果上滑超过30像素，显示加速提示（但不锁定）
                    else if (swipeDistance > 30f) {
                        val longPressSpeed = getLongPressSpeed()
                        showLongPressSpeedHint(longPressSpeed, false)
                        android.util.Log.d("VideoPlayer", "!!! 显示加速提示: ${longPressSpeed}x")
                        return true
                    }
                    // 长按状态下的滑动不触发其他手势
                    android.util.Log.d("VideoPlayer", "长按状态下滑动，不触发其他手势")
                    return true
                }

                // 标记正在滑动（会取消长按检测）
                if (!isScrolling) {
                    isScrolling = true
                    longPressCheckRunnable?.let { longPressHandler.removeCallbacks(it) }
                    android.util.Log.d("VideoPlayer", "开始滑动，取消长按检测")
                }

                // 取消之前的隐藏任务
                hideGestureHintHandler.removeCallbacks(hideGestureHintRunnable)

                // 计算总滑动距离
                val totalDeltaX = e2.x - e1.x
                val totalDeltaY = e2.y - e1.y

                android.util.Log.d("VideoPlayer", "总滑动距离: deltaX=$totalDeltaX, deltaY=$totalDeltaY")

                // 判断手势类型（只在第一次判断，并且滑动距离超过阈值）
                if (gestureType == GestureType.NONE) {
                    // 设置最小滑动阈值，避免误触
                    val minSwipeDistance = 50f
                    val absDeltaX = abs(totalDeltaX)
                    val absDeltaY = abs(totalDeltaY)

                    android.util.Log.d("VideoPlayer", "绝对滑动距离: X=$absDeltaX, Y=$absDeltaY, 起始X=${e1.x}, 屏幕宽度=$screenWidth")

                    if (absDeltaX > minSwipeDistance || absDeltaY > minSwipeDistance) {
                        // 判断是横向还是纵向滑动，需要有明显的方向差异（1.5倍）
                        if (absDeltaX > absDeltaY * 1.5) {
                            // 左右滑动 - 调整进度
                            gestureType = GestureType.PROGRESS
                            tempProgress = exoPlayerManager.getCurrentPosition()
                            android.util.Log.d("VideoPlayer", "手势类型: 进度调整 (横向滑动)")
                        } else if (absDeltaY > absDeltaX * 1.5) {
                            // 上下滑动 - 判断左侧还是右侧
                            if (e1.x < screenWidth / 2) {
                                // 左侧 - 调整亮度
                                gestureType = GestureType.BRIGHTNESS
                                initialBrightness = window.attributes.screenBrightness
                                if (initialBrightness < 0) {
                                    initialBrightness = 0.5f
                                }
                                android.util.Log.d("VideoPlayer", "手势类型: 亮度调整 (左侧纵向), 初始亮度=$initialBrightness")
                            } else {
                                // 右侧 - 调整音量
                                gestureType = GestureType.VOLUME
                                val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                                val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                                val currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                                initialVolume = currentVolume.toFloat() / maxVolume
                                android.util.Log.d("VideoPlayer", "手势类型: 音量调整 (右侧纵向), 初始音量=$initialVolume")
                            }
                        } else {
                            android.util.Log.d("VideoPlayer", "滑动方向不明确，等待更多滑动数据 (X=$absDeltaX, Y=$absDeltaY)")
                        }
                    } else {
                        android.util.Log.d("VideoPlayer", "滑动距离未达到阈值: X=$absDeltaX, Y=$absDeltaY")
                    }
                }

                // 只有确定了手势类型后才处理
                when (gestureType) {
                    GestureType.PROGRESS -> {
                        android.util.Log.d("VideoPlayer", "处理进度手势")
                        handleProgressGesture(-distanceX)
                    }
                    GestureType.BRIGHTNESS -> {
                        android.util.Log.d("VideoPlayer", "处理亮度手势")
                        handleBrightnessGesture(-distanceY)
                    }
                    GestureType.VOLUME -> {
                        android.util.Log.d("VideoPlayer", "处理音量手势")
                        handleVolumeGesture(-distanceY)
                    }
                    else -> {
                        android.util.Log.d("VideoPlayer", "手势类型未确定")
                    }
                }

                return true
            }
        })

        // 在播放器视图上设置触摸监听器
        android.util.Log.d("VideoPlayer", "设置 playerView 触摸监听器")
        var downX = 0f
        var downY = 0f
        
        binding.playerView.setOnTouchListener { view, event ->
            android.util.Log.d("VideoPlayer", "playerView 触摸事件: action=${event.action}, x=${event.x}, y=${event.y}, isLongPressActive=$isLongPressActive")
            
            // 先传递给GestureDetector
            val gestureHandled = gestureDetector.onTouchEvent(event)
            
            // 拦截触摸事件，防止 ScrollView 消费
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    // 禁用 ScrollView 的滚动
                    scrollView?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    // 手动检测长按后的滑动
                    if (isLongPressActive && !isSpeedLocked && isLongPressLockEnabled()) {
                        val swipeDistance = downY - event.y  // 上滑为正值，下滑为负值
                        android.util.Log.d("VideoPlayer", "!!! ACTION_MOVE 检测滑动: swipeDistance=$swipeDistance, downY=$downY, currentY=${event.y}, normalSpeed=$normalSpeed")
                        
                        // 如果上滑超过150像素，锁定倍速
                        if (swipeDistance > 150f) {
                            isSpeedLocked = true
                            isLongPressActive = false
                            val longPressSpeed = getLongPressSpeed()
                            android.util.Log.d("VideoPlayer", "!!! 锁定前: normalSpeed=$normalSpeed, currentSpeed=${exoPlayerManager.getPlaybackSpeed()}")
                            // 注意：不要修改normalSpeed，它应该保持为长按前的速度
                            showLongPressSpeedHint(longPressSpeed, true)
                            android.util.Log.d("VideoPlayer", "!!! 倍速已锁定: ${longPressSpeed}x, normalSpeed保持为=$normalSpeed (应该是1.0)")
                        }
                        // 如果下滑超过80像素，显示"松手恢复速度"提示
                        else if (swipeDistance < -80f) {
                            showSpeedRestoreHint()
                            android.util.Log.d("VideoPlayer", "!!! 下滑提示：松手恢复速度")
                        }
                        // 如果上滑超过30像素，显示加速提示（但不锁定）
                        else if (swipeDistance > 30f) {
                            val longPressSpeed = getLongPressSpeed()
                            showLongPressSpeedHint(longPressSpeed, false)
                            android.util.Log.d("VideoPlayer", "!!! 显示加速提示: ${longPressSpeed}x")
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    android.util.Log.d("VideoPlayer", "!!! 触摸事件 UP/CANCEL, isLongPressActive=$isLongPressActive, isSpeedLocked=$isSpeedLocked, normalSpeed=$normalSpeed, currentSpeed=${exoPlayerManager.getPlaybackSpeed()}")
                    
                    // 取消长按检测任务
                    longPressCheckRunnable?.let { 
                        longPressHandler.removeCallbacks(it)
                        android.util.Log.d("VideoPlayer", "取消长按检测任务")
                    }
                    
                    // 处理长按加速（只有未锁定时才恢复速度）
                    if (isLongPressActive && !isSpeedLocked) {
                        isLongPressActive = false
                        // 恢复到之前的速度
                        exoPlayerManager.setPlaybackSpeed(normalSpeed)
                        hideLongPressSpeedHint()
                        android.util.Log.d("VideoPlayer", "!!! 取消长按加速，恢复速度: ${normalSpeed}x")
                    }

                    // 重置滑动标记
                    isScrolling = false

                    // 处理进度调整
                    if (gestureType == GestureType.PROGRESS && tempProgress > 0) {
                        exoPlayerManager.seekTo(tempProgress)
                    }

                    // 延迟隐藏提示
                    hideGestureHintHandler.postDelayed(hideGestureHintRunnable, 150)

                    // 重置手势类型
                    gestureType = GestureType.NONE
                    
                    // 恢复 ScrollView 的滚动
                    scrollView?.requestDisallowInterceptTouchEvent(false)
                }
            }

            true // 返回 true 表示消费了触摸事件
        }
    }

    private var tempProgress = 0L
    private var initialBrightness = 0.5f
    private var initialVolume = 0.5f
    private val screenWidth by lazy { resources.displayMetrics.widthPixels }
    private val screenHeight by lazy { resources.displayMetrics.heightPixels }
    
    // 手势提示相关
    private var gestureStartTime = 0L // 手势开始时间
    
    // 进度条预览相关
    private var previewJob: kotlinx.coroutines.Job? = null // 预览任务
    private val previewCache = mutableMapOf<Long, android.graphics.Bitmap>() // 帧缓存（时间戳 -> Bitmap）
    private var lastPreviewTime = 0L // 上次预览的时间点
    
    // 缩略图精灵图相关（用于进度预览）
    private var thumbnailSprite: android.graphics.Bitmap? = null
    private val thumbnailColumns = 5  // 精灵图列数
    private val thumbnailRows = 4     // 精灵图行数  
    private val totalThumbnails = thumbnailColumns * thumbnailRows  // 总缩略图数 = 20
    private var thumbnailWidth = 0
    private var thumbnailHeight = 0
    private var isThumbnailGenerating = false  // 是否正在生成缩略图
    private val fineAdjustDelayMs = 1000L // 1秒后切换到细调模式
    
    private fun handleProgressGesture(deltaX: Float) {
        val duration = exoPlayerManager.getDuration()
        if (duration <= 0) return

        // 根据滑动距离计算进度变化（每滑动屏幕宽度的1/3改变总时长的1/10，乘以灵敏度系数）
        val change = (deltaX / screenWidth * duration * 0.3f * seekSensitivity).toLong()
        tempProgress = (tempProgress + change).coerceIn(0, duration)

        showProgressSeekHint(tempProgress, duration)
    }
    
    private fun handleBrightnessGesture(deltaY: Float) {
        val change = -deltaY / screenHeight * 2 // 向上滑动增加亮度
        val newBrightness = (initialBrightness + change).coerceIn(0f, 1f)
        initialBrightness = newBrightness

        val layoutParams = window.attributes
        layoutParams.screenBrightness = newBrightness
        window.attributes = layoutParams

        android.util.Log.d("VideoPlayer", "亮度调整: deltaY=$deltaY, change=$change, newBrightness=$newBrightness")
        
        // 显示亮度进度条（使用亮度图标）
        showVolumeOrBrightnessProgress(
            iconRes = R.drawable.ic_brightness,
            percent = (newBrightness * 100).toInt()
        )
    }

    private fun handleVolumeGesture(deltaY: Float) {
        val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)

        val change = -deltaY / screenHeight * 2 // 向上滑动增加音量
        val newVolume = (initialVolume + change).coerceIn(0f, 1f)
        initialVolume = newVolume

        val volumeLevel = (newVolume * maxVolume).toInt()
        audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, volumeLevel, 0)

        android.util.Log.d("VideoPlayer", "音量调整: deltaY=$deltaY, change=$change, newVolume=$newVolume, volumeLevel=$volumeLevel")
        
        // 显示音量进度条（使用音量图标）
        val iconRes = if (newVolume == 0f) {
            R.drawable.dkplayer_ic_action_volume_off
        } else {
            R.drawable.dkplayer_ic_action_volume_up
        }
        showVolumeOrBrightnessProgress(iconRes, (newVolume * 100).toInt())
    }
    
    /**
     * 显示音量/亮度进度条（顶部，图标+进度条）
     */
    private fun showVolumeOrBrightnessProgress(iconRes: Int, percent: Int) {
        // 设置图标
        binding.volumeBrightnessIcon.setImageResource(iconRes)
        
        // 检查是否需要切换到细调模式（1秒后进度条变细，隐藏图标）
        val currentTime = System.currentTimeMillis()
        if (gestureStartTime == 0L) {
            gestureStartTime = currentTime
            // 粗调模式：显示图标 + 粗进度条（176dp宽，29dp高）
            binding.volumeBrightnessIcon.visibility = View.VISIBLE
            binding.volumeBrightnessIcon.alpha = 1f
            
            // 设置外层容器高度
            val containerParams = binding.volumeBrightnessLayout.layoutParams as FrameLayout.LayoutParams
            containerParams.height = (29 * resources.displayMetrics.density).toInt()
            binding.volumeBrightnessLayout.layoutParams = containerParams
            
            val progressParams = binding.volumeBrightnessProgressBar.layoutParams as FrameLayout.LayoutParams
            progressParams.width = (176 * resources.displayMetrics.density).toInt()
            progressParams.height = (29 * resources.displayMetrics.density).toInt()
            progressParams.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            binding.volumeBrightnessProgressBar.layoutParams = progressParams
            
            // 重置图标位置（顶部对齐，垂直居中）
            val iconParams = binding.volumeBrightnessIcon.layoutParams as FrameLayout.LayoutParams
            iconParams.width = (22 * resources.displayMetrics.density).toInt()
            iconParams.height = (22 * resources.displayMetrics.density).toInt()
            iconParams.marginStart = (7 * resources.displayMetrics.density).toInt()
            iconParams.topMargin = (3.5f * resources.displayMetrics.density).toInt()
            iconParams.gravity = android.view.Gravity.START or android.view.Gravity.TOP
            binding.volumeBrightnessIcon.layoutParams = iconParams
        } else if ((currentTime - gestureStartTime) >= fineAdjustDelayMs) {
            // 细调模式：先隐藏图标，然后进度条变细（宽度不变176dp，高度变为8dp）
            
            // 第一步：图标淡出动画（200ms）
            if (binding.volumeBrightnessIcon.visibility == View.VISIBLE) {
                binding.volumeBrightnessIcon.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .withEndAction {
                        binding.volumeBrightnessIcon.visibility = View.GONE
                        
                        // 第二步：图标消失后，开始进度条缩小动画（300ms）
                        val containerParams = binding.volumeBrightnessLayout.layoutParams as FrameLayout.LayoutParams
                        val progressParams = binding.volumeBrightnessProgressBar.layoutParams as FrameLayout.LayoutParams
                        
                        val targetHeight = (8 * resources.displayMetrics.density).toInt()
                        
                        // 只动画容器高度和进度条高度，宽度不变
                        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                            duration = 300
                            interpolator = android.view.animation.DecelerateInterpolator()
                            
                            val startContainerHeight = containerParams.height
                            val startHeight = progressParams.height
                            
                            addUpdateListener { animator ->
                                val fraction = animator.animatedValue as Float
                                
                                // 调整容器高度，保持顶部对齐
                                containerParams.height = (startContainerHeight + (targetHeight - startContainerHeight) * fraction).toInt()
                                binding.volumeBrightnessLayout.layoutParams = containerParams
                                
                                // 只调整进度条高度，宽度不变
                                progressParams.height = (startHeight + (targetHeight - startHeight) * fraction).toInt()
                                progressParams.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                                binding.volumeBrightnessProgressBar.layoutParams = progressParams
                            }
                            start()
                        }
                    }
                    .start()
            }
        }
        
        // 设置进度
        binding.volumeBrightnessProgressBar.progress = percent
        binding.volumeBrightnessLayout.visibility = View.VISIBLE
    }
    
    /**
     * 显示进度调整提示（中间大显示）
     */
    private fun showProgressSeekHint(currentPos: Long, duration: Long) {
        android.util.Log.d("VideoPlayer", "显示进度调整提示: $currentPos / $duration")
        
        // 不显示遮罩层，保持界面清爽
        // binding.progressOverlay.visibility = View.VISIBLE
        
        // 显示进度提示
        binding.progressSeekLayout.visibility = View.VISIBLE
        
        // 更新时间显示（统一格式）
        binding.progressSeekTimeDisplay.text = "${formatTime(currentPos)} / ${formatTime(duration)}"
        
        // 更新中间的进度条
        binding.gestureProgressBar.max = 100
        val progress = if (duration > 0) {
            ((currentPos.toFloat() / duration) * 100).toInt()
        } else {
            0
        }
        binding.gestureProgressBar.progress = progress
        
        // 同时更新底部的进度条（实时跟随，丝滑）
        binding.progressSeekBar.max = duration.toInt()
        binding.progressSeekBar.progress = currentPos.toInt()
        binding.currentPositionText.text = formatTime(currentPos)
        
        // 使用 FFmpeg 提取指定时间点的视频帧预览
        showProgressPreview(currentPos)
    }
    
    /**
     * 显示进度条预览图（拖动时显示指定时间点的视频帧）
     * 在线视频：需要服务器端支持
     * 离线视频：使用缩略图精灵图方案
     */
    private fun showProgressPreview(positionMs: Long) {
        // 检查是否是本地文件
        val currentMediaItem = exoPlayerManager.getPlayer()?.currentMediaItem
        val videoUri = currentMediaItem?.localConfiguration?.uri
        
        if (videoUri == null) {
            android.util.Log.d("VideoPlayer", "无法获取视频URI")
            return
        }
        
        // 如果是在线视频（http/https），禁用预览
        val scheme = videoUri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            android.util.Log.d("VideoPlayer", "在线视频不支持预览: $videoUri")
            return
        }
        
        android.util.Log.d("VideoPlayer", "!!! showProgressPreview 被调用: positionMs=$positionMs, URI=$videoUri, scheme=$scheme")
        
        // 防抖：如果时间点变化小于300毫秒，不重新提取
        val timeDiff = kotlin.math.abs(positionMs - lastPreviewTime)
        if (timeDiff < 300) {
            return
        }
        
        lastPreviewTime = positionMs
        
        // 如果精灵图还没生成，先生成
        if (thumbnailSprite == null && !isThumbnailGenerating) {
            android.util.Log.d("VideoPlayer", "精灵图未生成，开始生成...")
            generateThumbnailSprite()
            return
        }
        
        // 如果精灵图还在生成中，显示加载提示
        if (thumbnailSprite == null) {
            android.util.Log.d("VideoPlayer", "缩略图精灵图生成中...")
            return
        }
        
        // 从精灵图中提取对应位置的缩略图
        val duration = exoPlayerManager.getDuration()
        if (duration <= 0) {
            android.util.Log.d("VideoPlayer", "视频时长无效: $duration")
            return
        }
        
        // 计算应该显示第几张缩略图（0-19）
        val thumbnailIndex = ((positionMs.toFloat() / duration) * totalThumbnails).toInt()
            .coerceIn(0, totalThumbnails - 1)
        
        android.util.Log.d("VideoPlayer", "计算缩略图索引: positionMs=$positionMs, duration=$duration, index=$thumbnailIndex")
        
        // 检查缓存
        val cacheKey = thumbnailIndex.toLong()
        if (previewCache.containsKey(cacheKey)) {
            binding.progressPreviewImage.setImageBitmap(previewCache[cacheKey])
            binding.progressPreviewImage.visibility = View.VISIBLE
            android.util.Log.d("VideoPlayer", "✓ 使用缓存预览: index=$thumbnailIndex, 图片已设置并显示")
            return
        }
        
        android.util.Log.d("VideoPlayer", "缓存未命中，开始从精灵图裁剪...")
        
        // 从精灵图中裁剪出对应的缩略图
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sprite = thumbnailSprite ?: return@launch
                
                android.util.Log.d("VideoPlayer", "精灵图尺寸: ${sprite.width}x${sprite.height}, 单个缩略图: ${thumbnailWidth}x${thumbnailHeight}")
                
                // 计算在精灵图中的位置（行列）
                val row = thumbnailIndex / thumbnailColumns
                val col = thumbnailIndex % thumbnailColumns
                
                // 裁剪出对应的缩略图
                val x = col * thumbnailWidth
                val y = row * thumbnailHeight
                
                android.util.Log.d("VideoPlayer", "裁剪位置: row=$row, col=$col, x=$x, y=$y")
                
                val thumbnail = android.graphics.Bitmap.createBitmap(
                    sprite,
                    x, y,
                    thumbnailWidth, thumbnailHeight
                )
                
                withContext(Dispatchers.Main) {
                    binding.progressPreviewImage.setImageBitmap(thumbnail)
                    binding.progressPreviewImage.visibility = View.VISIBLE
                    
                    android.util.Log.d("VideoPlayer", "✓ 预览图已设置并显示: index=$thumbnailIndex, visibility=${binding.progressPreviewImage.visibility}")
                    
                    // 缓存
                    if (previewCache.size >= 20) {
                        previewCache.keys.minOrNull()?.let { oldestKey ->
                            previewCache[oldestKey]?.recycle()
                            previewCache.remove(oldestKey)
                        }
                    }
                    previewCache[cacheKey] = thumbnail
                    
                    android.util.Log.d("VideoPlayer", "预览显示成功: index=$thumbnailIndex, position=${positionMs}ms")
                }
            } catch (e: Exception) {
                android.util.Log.e("VideoPlayer", "预览失败", e)
            }
        }
    }
    
    /**
     * 生成缩略图精灵图（在后台线程）
     * 从本地视频中提取20帧，拼接成5x4的精灵图
     * 注意：只支持本地文件，不支持网络视频
     */
    private fun generateThumbnailSprite() {
        if (isThumbnailGenerating) return
        
        isThumbnailGenerating = true
        android.util.Log.d("VideoPlayer", "开始生成缩略图精灵图...")
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val duration = exoPlayerManager.getDuration()
                if (duration <= 0) {
                    android.util.Log.e("VideoPlayer", "视频时长无效，无法生成缩略图")
                    isThumbnailGenerating = false
                    return@launch
                }
                
                // 获取当前播放的视频URI
                val currentMediaItem = exoPlayerManager.getPlayer()?.currentMediaItem
                val videoUri = currentMediaItem?.localConfiguration?.uri
                
                if (videoUri == null) {
                    android.util.Log.e("VideoPlayer", "无法获取视频URI")
                    isThumbnailGenerating = false
                    return@launch
                }
                
                // 只支持本地文件
                val scheme = videoUri.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") {
                    android.util.Log.d("VideoPlayer", "在线视频不支持预览，跳过生成")
                    isThumbnailGenerating = false
                    return@launch
                }
                
                android.util.Log.d("VideoPlayer", "本地视频URI: $videoUri, scheme: $scheme")
                
                // 创建一个临时的MediaMetadataRetriever来提取帧
                val retriever = android.media.MediaMetadataRetriever()
                
                try {
                    // 设置数据源（根据URI类型选择合适的方法）
                    when (scheme) {
                        "file" -> {
                            // file:// URI，提取路径
                            val path = videoUri.path
                            if (path != null) {
                                android.util.Log.d("VideoPlayer", "使用文件路径: $path")
                                retriever.setDataSource(path)
                            } else {
                                android.util.Log.e("VideoPlayer", "无法从URI提取文件路径")
                                isThumbnailGenerating = false
                                return@launch
                            }
                        }
                        "content" -> {
                            // content:// URI，使用Context
                            android.util.Log.d("VideoPlayer", "使用Content URI")
                            retriever.setDataSource(this@VideoPlayerActivity, videoUri)
                        }
                        else -> {
                            // 其他情况，尝试直接使用URI字符串
                            android.util.Log.d("VideoPlayer", "使用URI字符串: ${videoUri.toString()}")
                            retriever.setDataSource(videoUri.toString())
                        }
                    }
                    
                    // 提取第一帧来确定缩略图尺寸
                    val firstFrame = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (firstFrame == null) {
                        android.util.Log.e("VideoPlayer", "无法提取视频帧")
                        isThumbnailGenerating = false
                        return@launch
                    }
                    
                    // 计算缩略图尺寸（保持16:9比例，宽度160dp）
                    val targetWidth = (160 * resources.displayMetrics.density).toInt()
                    val targetHeight = (targetWidth * 9 / 16)
                    thumbnailWidth = targetWidth
                    thumbnailHeight = targetHeight
                    
                    // 创建精灵图画布（5列 x 4行）
                    val spriteWidth = thumbnailWidth * thumbnailColumns
                    val spriteHeight = thumbnailHeight * thumbnailRows
                    val sprite = android.graphics.Bitmap.createBitmap(
                        spriteWidth,
                        spriteHeight,
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    val canvas = android.graphics.Canvas(sprite)
                    
                    // 提取20帧并绘制到精灵图上
                    for (i in 0 until totalThumbnails) {
                        // 计算时间点（均匀分布）
                        val timeUs = (duration * 1000 * i / totalThumbnails)
                        
                        // 提取帧
                        val frame = retriever.getFrameAtTime(
                            timeUs,
                            android.media.MediaMetadataRetriever.OPTION_CLOSEST
                        )
                        
                        if (frame != null) {
                            // 缩放到目标尺寸
                            val scaledFrame = android.graphics.Bitmap.createScaledBitmap(
                                frame,
                                thumbnailWidth,
                                thumbnailHeight,
                                true
                            )
                            
                            // 计算在精灵图中的位置
                            val row = i / thumbnailColumns
                            val col = i % thumbnailColumns
                            val x = col * thumbnailWidth.toFloat()
                            val y = row * thumbnailHeight.toFloat()
                            
                            // 绘制到精灵图上
                            canvas.drawBitmap(scaledFrame, x, y, null)
                            
                            // 释放原始帧
                            frame.recycle()
                            scaledFrame.recycle()
                            
                            android.util.Log.d("VideoPlayer", "已提取第 ${i + 1}/$totalThumbnails 帧")
                        }
                    }
                    
                    thumbnailSprite = sprite
                    isThumbnailGenerating = false
                    
                    android.util.Log.d("VideoPlayer", "缩略图精灵图生成完成！尺寸: ${spriteWidth}x${spriteHeight}")
                    
                } catch (e: Exception) {
                    android.util.Log.e("VideoPlayer", "生成缩略图精灵图失败", e)
                    isThumbnailGenerating = false
                } finally {
                    retriever.release()
                }
                
            } catch (e: Exception) {
                android.util.Log.e("VideoPlayer", "生成缩略图精灵图失败", e)
                isThumbnailGenerating = false
            }
        }
    }
    
    private fun showGestureHint(text: String) {
        android.util.Log.d("VideoPlayer", "显示手势提示: $text")
        
        // 显示文字提示（用于音量/亮度调整）
        binding.gestureHintText.text = text
        binding.gestureHintLayout.visibility = View.VISIBLE
    }
    
    private fun hideGestureHint() {
        // 隐藏进度调整提示和遮罩
        binding.progressOverlay.visibility = View.GONE
        binding.progressSeekLayout.visibility = View.GONE
        
        // 隐藏音量/亮度提示
        binding.gestureHintLayout.visibility = View.GONE
        binding.volumeBrightnessLayout.visibility = View.GONE
        
        // 隐藏长按加速提示
        hideLongPressSpeedHint()
        
        // 重置手势状态
        gestureStartTime = 0L
        
        // 恢复图标显示和进度条默认尺寸
        binding.volumeBrightnessIcon.visibility = View.VISIBLE
        binding.volumeBrightnessIcon.alpha = 1f
        val params = binding.volumeBrightnessProgressBar.layoutParams
        params.width = (150 * resources.displayMetrics.density).toInt()
        params.height = (16 * resources.displayMetrics.density).toInt()
        binding.volumeBrightnessProgressBar.layoutParams = params
    }
    
    /**
     * 显示长按加速提示（顶部中间）
     * @param speed 当前速度
     * @param isLocked 是否已锁定
     */
    private fun showLongPressSpeedHint(speed: Float, isLocked: Boolean) {
        binding.longPressSpeedLayout.visibility = View.VISIBLE
        
        if (isLocked) {
            // 锁定状态：隐藏三角形动画
            binding.longPressSpeedText.text = String.format("%.1fX 已锁定", speed)
            stopSpeedArrowAnimation()
            binding.speedArrowsLayout.visibility = View.GONE  // 隐藏整个动画容器
        } else {
            // 未锁定状态：显示动画（箭头向右）
            binding.longPressSpeedText.text = String.format("%.1fX", speed)
            binding.speedArrowsLayout.visibility = View.VISIBLE  // 显示动画容器
            
            // 重置箭头方向为向右（0度）
            binding.speedArrow1.rotation = 0f
            binding.speedArrow2.rotation = 0f
            binding.speedArrow3.rotation = 0f
            
            startSpeedArrowAnimation()
        }
    }
    
    /**
     * 隐藏长按加速提示
     */
    private fun hideLongPressSpeedHint() {
        binding.longPressSpeedLayout.visibility = View.GONE
        stopSpeedArrowAnimation()
        // 注意：不要在这里重置isSpeedLocked，因为用户可能已经锁定了倍速
        // isSpeedLocked只应该在明确取消锁定时重置（单击或再次长按）
    }
    
    /**
     * 显示恢复倍速提示（下滑时显示）
     */
    private fun showSpeedRestoreHint() {
        binding.longPressSpeedLayout.visibility = View.VISIBLE
        // 显示向下箭头和提示文字
        binding.longPressSpeedText.text = "松手恢复速度"
        binding.speedArrowsLayout.visibility = View.VISIBLE
        
        // 旋转箭头向下（180度）
        binding.speedArrow1.rotation = 90f
        binding.speedArrow2.rotation = 90f
        binding.speedArrow3.rotation = 90f
        
        stopSpeedArrowAnimation()
    }
    
    /**
     * 震动反馈
     * @param duration 震动时长（毫秒）
     */
    private fun performHapticFeedback(duration: Long) {
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    android.os.VibrationEffect.createOneShot(
                        duration,
                        android.os.VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
            android.util.Log.d("VideoPlayer", "震动反馈: ${duration}ms")
        } catch (e: Exception) {
            android.util.Log.e("VideoPlayer", "震动失败: ${e.message}")
        }
    }
    
    private var arrowAnimator: android.animation.ValueAnimator? = null
    
    /**
     * 启动三角形循环动画
     */
    private fun startSpeedArrowAnimation() {
        // 停止之前的动画
        stopSpeedArrowAnimation()
        
        // 创建循环动画
        arrowAnimator = android.animation.ValueAnimator.ofInt(0, 3).apply {
            duration = 600 // 每个循环600ms
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.RESTART
            
            addUpdateListener { animator ->
                val phase = animator.animatedValue as Int
                
                // 根据phase显示/隐藏三角形，创建波浪效果
                binding.speedArrow1.alpha = when (phase) {
                    0 -> 1.0f
                    1 -> 0.6f
                    else -> 0.3f
                }
                
                binding.speedArrow2.alpha = when (phase) {
                    0 -> 0.3f
                    1 -> 1.0f
                    2 -> 0.6f
                    else -> 0.3f
                }
                
                binding.speedArrow3.alpha = when (phase) {
                    0 -> 0.3f
                    1 -> 0.3f
                    2 -> 1.0f
                    else -> 0.6f
                }
            }
            
            start()
        }
    }
    
    /**
     * 停止三角形动画
     */
    private fun stopSpeedArrowAnimation() {
        arrowAnimator?.cancel()
        arrowAnimator = null
        
        // 重置透明度
        binding.speedArrow1.alpha = 1.0f
        binding.speedArrow2.alpha = 1.0f
        binding.speedArrow3.alpha = 1.0f
    }
    
    private fun setupPlayer() {
        val player = exoPlayerManager.initializePlayer()
        binding.playerView.player = player

        exoPlayerManager.setPlayerListener(object : ExoPlayerManager.PlayerListener {
            override fun onReady() {
                binding.loadingView.visibility = View.GONE
                viewModel.updateDuration(exoPlayerManager.getDuration())
                updatePlayPauseButton()
                
                // 恢复播放进度（如果有保存的进度且还没恢复过）
                if (!hasRestoredPlaybackPosition) {
                    val savedPosition = viewModel.getSavedPosition()
                    if (savedPosition > 0) {
                        android.util.Log.d("VideoPlayer", "恢复播放进度: ${savedPosition}ms")
                        exoPlayerManager.seekTo(savedPosition)
                        hasRestoredPlaybackPosition = true
                    } else {
                        // 没有保存的进度，标记为已恢复
                        hasRestoredPlaybackPosition = true
                    }
                }
                
                // 自动开始播放
                exoPlayerManager.play()
                android.util.Log.d("VideoPlayer", "播放器就绪，开始播放")
                
                // 重置倍速状态（确保每次播放新视频时状态正确）
                isSpeedLocked = false
                isLongPressActive = false
                normalSpeed = 1.0f
                android.util.Log.d("VideoPlayer", "播放器就绪，重置倍速状态: normalSpeed=$normalSpeed")

                // 根据视频时长改变播放器下方区域的背景颜色
                updateDetailSectionBackground()

                // 检查视频尺寸，自动判断是否需要竖屏全屏
                val videoWidth = exoPlayerManager.getVideoWidth()
                val videoHeight = exoPlayerManager.getVideoHeight()
                
                android.util.Log.d("VideoPlayer", "视频尺寸: ${videoWidth}x${videoHeight}")
                
                if (videoWidth > 0 && videoHeight > 0) {
                    // 如果视频高度大于宽度，说明是竖屏视频，自动切换到竖屏全屏
                    if (videoHeight > videoWidth) {
                        android.util.Log.d("VideoPlayer", "检测到竖屏视频，切换到竖屏全屏模式")
                        setPortraitFullscreen()
                    } else {
                        // 横屏视频，根据视频尺寸自动切换横竖屏
                        autoAdjustOrientation()
                    }
                }
            }
            
            override fun onBuffering() {
                // 显示缓冲提示（无论是否正在播放）
                runOnUiThread {
                    binding.loadingView.visibility = View.VISIBLE
                    updateStatusText("正在缓冲...")
                }
            }
            
            override fun onEnded() {
                android.util.Log.d("VideoPlayer", "播放结束 onEnded 被调用")
                viewModel.savePlaybackProgress()

                // 隐藏下一集提示
                hideNextEpisodeHint()

                // 播放列表自动播放下一集
                if (playlistManager.hasNext()) {
                    android.util.Log.d("VideoPlayer", "准备自动播放下一集")
                    playNextEpisode()
                } else {
                    android.util.Log.d("VideoPlayer", "没有下一集可播放")
                }
            }
            
            override fun onError(message: String) {
                viewModel.onPlaybackError(message)
                runOnUiThread {
                    updateStatusText("播放失败: $message")
                    binding.loadingView.visibility = View.GONE
                }
            }
            
            override fun onPlayingChanged(isPlaying: Boolean) {
                android.util.Log.d("VideoPlayer", "========================================")
                android.util.Log.d("VideoPlayer", "onPlayingChanged 被调用")
                android.util.Log.d("VideoPlayer", "  isPlaying: $isPlaying")
                android.util.Log.d("VideoPlayer", "  当前位置: ${exoPlayerManager.getCurrentPosition()}ms")
                android.util.Log.d("VideoPlayer", "  缓冲位置: ${exoPlayerManager.getBufferedPosition()}ms")
                android.util.Log.d("VideoPlayer", "  isBuffering: ${exoPlayerManager.isBuffering()}")
                android.util.Log.d("VideoPlayer", "  isPlayerReady: ${exoPlayerManager.isPlayerReady()}")
                android.util.Log.d("VideoPlayer", "  → 不调用 ViewModel，避免循环")
                android.util.Log.d("VideoPlayer", "========================================")
                
                // 不调用 ViewModel 的 play/pause，避免状态循环
                // 只更新 UI
                if (isPlaying) {
                    binding.loadingView.visibility = View.GONE
                }

                updatePlayPauseButton()
            }
        })
    }
    
    private fun setupControls() {
        // 返回按钮
        binding.backButton.setOnClickListener {
            finish()
        }
        
        // 锁定按钮
        binding.lockButton.setOnClickListener {
            isLocked = !isLocked
            updateLockState()
        }
        
        // 播放/暂停按钮
        binding.playPauseButton.setOnClickListener {
            togglePlayPause()
        }
        
        // 全屏按钮
        binding.fullscreenButton.setOnClickListener {
            toggleOrientation()
        }

        // 旋转按钮
        binding.rotateButton.setOnClickListener {
            toggleOrientation()
        }
        
        // 倍速按钮 - 打开倍速设置对话框
        binding.speedButton.setOnClickListener {
            showSpeedSettingsDialog()
        }
        
        // 选集按钮
        binding.episodeButton.setOnClickListener {
            showEpisodeDialog()
        }
        
        // 下一集按钮
        binding.nextButton.setOnClickListener {
            playNextEpisode()
        }
        
        // 菜单按钮
        binding.menuButton.setOnClickListener {
            showPlayerMenu()
        }
        
        // 分享按钮
        binding.shareButton.setOnClickListener {
            shareVideo()
        }
        
        // 截屏按钮
        binding.screenshotButton.setOnClickListener {
            captureScreenshot()
        }
        
        // 清晰度按钮 - 显示视频信息
        try {
            binding.qualityButton?.setOnClickListener {
                showVideoInfo()
            }
        } catch (e: Exception) {
            // 如果布局中没有qualityButton，忽略错误
        }
        
        // 进度条
        binding.progressSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.currentPositionText.text = formatTime(progress.toLong())
                    // 显示该时间点的预览图
                    showProgressPreview(progress.toLong())
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
                hideControlsHandler.removeCallbacks(hideControlsRunnable)
            }
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                // 隐藏预览图
                binding.progressPreviewImage.visibility = View.GONE
                seekBar?.let {
                    exoPlayerManager.seekTo(it.progress.toLong())
                }
                resetHideControlsTimer()
            }
        })
    }
    
    private fun togglePlayPause() {
        exoPlayerManager.togglePlayPause()
        updatePlayPauseButton()
        resetHideControlsTimer()
    }
    
    private fun updatePlayPauseButton() {
        val isPlaying = exoPlayerManager.isPlaying()
        binding.playPauseButton.setImageResource(
            if (isPlaying) com.tv.ball.R.drawable.ic_pause_black_24dp
            else com.tv.ball.R.drawable.ic_play_arrow_black_24dp
        )
    }
    
    /**
     * 根据视频时长更新播放器下方区域的背景颜色和文字颜色
     * - 时长 ≥ 60分钟（电影）：深蓝色背景 + 金黄色文字
     * - 时长 < 60分钟（普通影视）：白色背景 + 黑色文字
     */
    private fun updateDetailSectionBackground() {
        val durationMs = exoPlayerManager.getDuration()
        val durationMinutes = (durationMs / 1000 / 60).toInt()
        
        android.util.Log.d("VideoPlayer", "视频时长: ${durationMinutes}分钟 (${durationMs}ms)")
        
        // 判断是否为电影（≥60分钟）
        val isMovie = durationMinutes >= 60
        
        setDetailSectionStyle(isMovie)
    }
    
    /**
     * 设置详情区域的样式（背景色和文字颜色）
     * @param isMovie true=电影模式（深蓝+金黄），false=普通模式（白色+黑色）
     */
    private fun setDetailSectionStyle(isMovie: Boolean) {
        // 查找播放器下方的详情区域
        val detailSection = portraitDetailView?.findViewById<View>(R.id.portraitDetailSection)

        if (detailSection != null) {
            val backgroundColor = if (isMovie) {
                ContextCompat.getColor(this, R.color.episode_movie_bg)
            } else {
                ContextCompat.getColor(this, R.color.episode_normal_bg)
            }

            // 使用揭露动画设置背景色
            animateBackgroundChange(detailSection, backgroundColor, isMovie)

            // 同时设置ScrollView的背景色，避免底部黑色区域
            scrollView?.setBackgroundColor(backgroundColor)

            // 更新文字颜色
            if (isMovie) {
                updateDetailSectionTextColors(detailSection, R.color.episode_movie_text)
                android.util.Log.d("VideoPlayer", "设置电影样式（深蓝色背景 + 金黄色文字）")
            } else {
                updateDetailSectionTextColors(detailSection, R.color.episode_normal_text)
                android.util.Log.d("VideoPlayer", "设置普通影视样式（白色背景 + 黑色文字）")
            }
        } else {
            android.util.Log.w("VideoPlayer", "未找到 portraitDetailSection，无法更新背景色")
        }
    }

    /**
     * 使用揭露动画改变背景色
     */
    private fun animateBackgroundChange(view: View, targetColor: Int, isMovie: Boolean) {
        // 只在第一次播放动画
        if (hasPlayedRevealAnimation) {
            view.setBackgroundColor(targetColor)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // 等待视图布局完成
            view.post {
                // 从底部中心开始揭露
                val cx = view.width / 2
                val cy = view.height
                val finalRadius = Math.hypot(cx.toDouble(), cy.toDouble()).toFloat()

                // 创建覆盖揭露动画
                val anim = android.view.ViewAnimationUtils.createCircularReveal(
                    view, cx, cy, 0f, finalRadius
                )
                anim.duration = 2000 // 2000ms (2秒)，慢速揭露

                // 设置插值器，让动画更自然平滑
                anim.interpolator = android.view.animation.DecelerateInterpolator(3f)

                // 先设置目标颜色，然后播放揭露动画
                view.setBackgroundColor(targetColor)
                anim.start()

                // 标记已播放过动画
                hasPlayedRevealAnimation = true

                android.util.Log.d("VideoPlayer", "播放背景揭露动画: ${if (isMovie) "深蓝色" else "白色"} (2秒)")
            }
        } else {
            // Android 5.0 以下直接设置颜色
            view.setBackgroundColor(targetColor)
            hasPlayedRevealAnimation = true
        }
    }
    
    /**
     * 更新详情区域的所有文字颜色
     */
    private fun updateDetailSectionTextColors(detailSection: View, textColorRes: Int) {
        val textColor = ContextCompat.getColor(this, textColorRes)
        val secondaryTextColor = if (textColorRes == R.color.episode_movie_text) {
            // 电影模式：次要文字使用稍暗的金黄色
            ContextCompat.getColor(this, R.color.episode_movie_text).let { color ->
                android.graphics.Color.argb(
                    180, // 70% 透明度
                    android.graphics.Color.red(color),
                    android.graphics.Color.green(color),
                    android.graphics.Color.blue(color)
                )
            }
        } else {
            // 普通模式：次要文字使用灰色
            0xFFAAAAAA.toInt()
        }
        
        // 更新标题
        detailSection.findViewById<TextView>(R.id.videoTitleDetail)?.setTextColor(textColor)
        
        // 更新视频信息
        detailSection.findViewById<TextView>(R.id.videoInfoText)?.setTextColor(secondaryTextColor)
        
        // 更新"选集"标题
        detailSection.findViewById<ViewGroup>(R.id.portraitDetailSection)?.let { parent ->
            parent.findViewWithTag<TextView>("选集")?.setTextColor(textColor)
        }
        
        // 更新集数统计文字
        detailSection.findViewById<TextView>(R.id.episodeCountText)?.setTextColor(secondaryTextColor)
        
        // 更新功能按钮下方的文字（收藏、分享）
        val buttonTextViews = listOf(
            detailSection.findViewById<LinearLayout>(R.id.favoriteButton),
            detailSection.findViewById<LinearLayout>(R.id.shareButton)
        )
        
        buttonTextViews.forEach { buttonLayout ->
            buttonLayout?.let { layout ->
                // 查找LinearLayout中的TextView
                for (i in 0 until layout.childCount) {
                    val child = layout.getChildAt(i)
                    if (child is TextView) {
                        child.setTextColor(secondaryTextColor)
                    }
                }
            }
        }
        
        // 更新图标颜色
        val iconViews = listOf(
            detailSection.findViewById<LinearLayout>(R.id.favoriteButton),
            detailSection.findViewById<LinearLayout>(R.id.shareButton)
        )
        
        iconViews.forEach { buttonLayout ->
            buttonLayout?.let { layout ->
                // 查找LinearLayout中的ImageView
                for (i in 0 until layout.childCount) {
                    val child = layout.getChildAt(i)
                    if (child is android.widget.ImageView) {
                        child.setColorFilter(secondaryTextColor)
                    }
                }
            }
        }
    }
    
    private fun toggleOrientation() {
        requestedOrientation = if (resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            // 从横屏切换到竖屏，锁定竖屏
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            // 从竖屏切换到横屏，锁定横屏
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun autoAdjustOrientation() {
        // 不再自动调整屏幕方向，保持用户当前选择的方向
        // 用户可以通过旋转按钮手动切换横竖屏
        android.util.Log.d("VideoPlayer", "autoAdjustOrientation: 保持当前屏幕方向")
    }
    
    /**
     * 判断当前播放的是否是本地视频
     */
    private fun isLocalVideo(): Boolean {
        // 从当前播放的URL判断
        if (currentPlayingUrl.isNotEmpty()) {
            return currentPlayingUrl.startsWith("/") || currentPlayingUrl.startsWith("file://") || currentPlayingUrl.startsWith("content://")
        }
        
        // 从播放列表获取当前剧集的URL
        val currentEpisode = playlistManager.getCurrentEpisode()
        if (currentEpisode != null) {
            return currentEpisode.url.startsWith("/") || currentEpisode.url.startsWith("file://") || currentEpisode.url.startsWith("content://")
        }
        
        // 从Intent获取视频URL
        val videoUrl = intent.getStringExtra("VIDEO_URL") ?: ""
        return videoUrl.startsWith("/") || videoUrl.startsWith("file://") || videoUrl.startsWith("content://")
    }
    
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        android.util.Log.d("VideoPlayer", "=== onConfigurationChanged: orientation=${newConfig.orientation} ===")
        android.util.Log.d("VideoPlayer", "ORIENTATION_PORTRAIT=1, ORIENTATION_LANDSCAPE=2, 当前=${newConfig.orientation}")
        updateOrientationUI()
    }
    
    private fun updateOrientationUI() {
        val isLandscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE

        if (isLandscape) {
            // 横屏：全屏显示，隐藏系统UI
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
            binding.fullscreenButton.setImageResource(R.drawable.ic_fullscreen_exit_black_24dp)

            // 横屏：禁用滚动，播放器全屏
            scrollView?.isNestedScrollingEnabled = false
            val playerParams = binding.root.layoutParams
            playerParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            binding.root.layoutParams = playerParams

            // 判断是否是本地视频
            val isLocalVideo = isLocalVideo()

            // 横屏：显示所有控制按钮
            try {
                binding.qualityButton?.visibility = View.VISIBLE
            } catch (e: Exception) {
                // 忽略
            }
            binding.episodeButton.visibility = View.VISIBLE

            binding.speedButton.visibility = View.VISIBLE

            // 移除竖屏详情视图
            removePortraitDetailView()
        } else {
            // 竖屏：显示系统UI
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            binding.fullscreenButton.setImageResource(R.drawable.ic_fullscreen_black_24dp)

            // 竖屏：启用滚动，设置播放器高度
            scrollView?.isNestedScrollingEnabled = true
            val screenHeight = resources.displayMetrics.heightPixels
            val playerHeight = (screenHeight * 0.35).toInt()
            val playerParams = binding.root.layoutParams
            playerParams.height = playerHeight
            binding.root.layoutParams = playerParams

            // 竖屏：隐藏高清、选集、倍速按钮
            try {
                binding.qualityButton?.visibility = View.GONE
            } catch (e: Exception) {
                // 忽略
            }
            binding.episodeButton.visibility = View.GONE
            binding.speedButton.visibility = View.GONE

            // 添加竖屏详情视图
            addPortraitDetailView()
        }

        // 更新画面尺寸
        binding.playerView.post {
            applyAspectRatio(aspectRatioManager.getCurrentMode())
        }
    }

    private fun addPortraitDetailView() {
        if (portraitDetailView != null) return

        // 加载详情视图
        portraitDetailView = LayoutInflater.from(this).inflate(
            R.layout.portrait_detail_section,
            containerLayout,
            false
        )

        // 设置LayoutParams，让详情视图填满剩余空间
        portraitDetailView?.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,  // 高度设为0
            1f  // weight设为1，填满剩余空间
        )

        // 添加到容器布局
        containerLayout?.addView(portraitDetailView)

        // 设置视频标题
        portraitDetailView?.findViewById<TextView>(R.id.videoTitleDetail)?.text = videoTitle

        // 立即设置默认背景色（白色，普通影视模式）
        // 等视频加载完成后会根据实际时长更新
        setDetailSectionStyle(isMovie = false)

        // 设置功能按钮点击事件
        setupPortraitButtons()

        // 设置选集列表
        setupPortraitEpisodeList()
    }

    private fun setupPortraitButtons() {
        // 收藏按钮
        portraitDetailView?.findViewById<LinearLayout>(R.id.favoriteButton)?.setOnClickListener {
            toggleFavorite()
        }

        // 分享按钮
        portraitDetailView?.findViewById<LinearLayout>(R.id.shareButton)?.setOnClickListener {
            shareVideo()
        }

        // 全25集按钮
        portraitDetailView?.findViewById<LinearLayout>(R.id.moreEpisodesButton)?.setOnClickListener {
            showAllEpisodesBottomSheet()
        }

        // 更新收藏状态
        updateFavoriteIcon()
    }
    
     * 更新状态文字（显示在进度条下方）
     */
    private fun updateStatusText(message: String) {
        runOnUiThread {
            binding.statusText?.apply {
                text = message
                visibility = View.VISIBLE
                
                // 移除之前的延迟任务
                removeCallbacks(hideStatusTextRunnable)
                
                // 根据消息类型设置不同的显示时长
                val hideDelay = when {
                    message.contains("测试") || message.contains("正在") -> 0L // 测试中不自动隐藏
                    message.contains("播放链接") || message.contains("播放器就绪") -> 3000L
                    message.contains("更快线路") || message.contains("最优") -> 2000L
                    else -> 3000L
                }
                
                if (hideDelay > 0) {
                    postDelayed(hideStatusTextRunnable, hideDelay)
                }
            }
        }
    }
    
    private val hideStatusTextRunnable = Runnable {
        binding.statusText?.visibility = View.GONE
    }

    private fun toggleFavorite() {
        lifecycleScope.launch {
            val favoriteDao = (application as VideoPlayerApplication).database.favoriteDao()
            val isFavorite = favoriteDao.isFavorite(videoId)

            if (isFavorite) {
                // 取消收藏
                favoriteDao.deleteFavoriteByVideoId(videoId)
                Toast.makeText(this@VideoPlayerActivity, "已取消收藏", Toast.LENGTH_SHORT).show()
            } else {
                // 添加收藏，保存解析后的URL和请求头
                val favorite = com.tv.ball.data.model.FavoriteEntity(
                    videoId = videoId,
                    title = videoTitle,
                    thumbnailUrl = intent.getStringExtra("THUMBNAIL_URL"),
                    detailUrl = intent.getStringExtra("DETAIL_URL"),
                    episodesJson = intent.getStringExtra("EPISODES_JSON"),
                    parsedVideoUrl = currentPlayingUrl.takeIf { it.isNotEmpty() },
                    videoHeaders = viewModel.getVideoHeaders()?.let { headers ->
                        headers.entries.joinToString(",", "{", "}") { (key, value) ->
                            "\"$key\":\"${value.replace("\"", "\\\"")}\""
                        }
                    }
                )
                favoriteDao.insertFavorite(favorite)
                Toast.makeText(this@VideoPlayerActivity, "已添加到收藏", Toast.LENGTH_SHORT).show()
            }

            // 更新图标
            updateFavoriteIcon()
        }
    }

    private fun updateFavoriteIcon() {
        lifecycleScope.launch {
            val favoriteDao = (application as VideoPlayerApplication).database.favoriteDao()
            val isFavorite = favoriteDao.isFavorite(videoId)

            portraitDetailView?.findViewById<android.widget.ImageView>(R.id.favoriteIcon)?.apply {
                if (isFavorite) {
                    setImageResource(android.R.drawable.btn_star_big_on)
                    setColorFilter(ContextCompat.getColor(context, R.color.primary))
                } else {
                    setImageResource(R.drawable.ic_favorite_border_black_24dp)
                    clearColorFilter()
                }
            }
        }
    }

    private fun removePortraitDetailView() {
        portraitDetailView?.let { view ->
            containerLayout?.removeView(view)
            portraitDetailView = null
        }
    }

    private fun setupPortraitEpisodeList() {
        val episodes = playlistManager.getPlaylist()
        android.util.Log.d("VideoPlayer", "setupPortraitEpisodeList: episodes.size=${episodes.size}")
        
        if (episodes.isEmpty()) {
            android.util.Log.w("VideoPlayer", "setupPortraitEpisodeList: 播放列表为空，无法设置选集列表")
            return
        }

        val recyclerView = portraitDetailView?.findViewById<RecyclerView>(R.id.episodeRecyclerViewDetail)
        val tabLayout = portraitDetailView?.findViewById<com.google.android.material.tabs.TabLayout>(R.id.tabLayoutPortrait)
        val moreButton = portraitDetailView?.findViewById<LinearLayout>(R.id.moreEpisodesButton)
        val episodeCountText = portraitDetailView?.findViewById<TextView>(R.id.episodeCountText)
        
        if (recyclerView == null || tabLayout == null || moreButton == null || episodeCountText == null) {
            android.util.Log.e("VideoPlayer", "setupPortraitEpisodeList: 找不到必要的View元素")
            android.util.Log.e("VideoPlayer", "recyclerView=$recyclerView, tabLayout=$tabLayout, moreButton=$moreButton, episodeCountText=$episodeCountText")
            return
        }

        // 按线路分组
        val grouped = episodes.groupBy { episode ->
            // 从 "[线路1] 第1集" 中提取 "线路1"
            val match = """\[([^\]]+)\]""".toRegex().find(episode.name)
            match?.groupValues?.get(1) ?: "默认线路"
        }.map { (sourceName, episodeList) ->
            // 移除剧集名称中的线路前缀，保持Episode类型
            val cleanedEpisodes = episodeList.map { episode ->
                val cleanName = episode.name.replace("""\[[^\]]+\]\s*""".toRegex(), "")
                episode.copy(name = cleanName)
            }
            sourceName to cleanedEpisodes
        }.sortedByDescending { (sourceName, episodeList) ->
            // 线路排序：m3u8 线路优先
            var score = 0
            val lowerName = sourceName.lowercase()
            val firstUrl = episodeList.firstOrNull()?.url?.lowercase() ?: ""
            
            // 最高优先级：线路名或URL包含 m3u8
            if (lowerName.contains("m3u8") || firstUrl.contains(".m3u8")) {
                score += 1000
                android.util.Log.d("VideoPlayer", "✓✓✓ M3U8线路优先: $sourceName")
            }
            
            // 次优先级：线路名包含 HD、高清、1080p 等
            if (lowerName.contains("hd") || lowerName.contains("高清") || 
                lowerName.contains("1080") || lowerName.contains("720")) {
                score += 500
            }
            
            // MP4 线路
            if (lowerName.contains("mp4") || firstUrl.contains(".mp4")) {
                score += 300
            }
            
            score
        }

        // 设置Tab
        tabLayout.removeAllTabs()
        grouped.forEach { (sourceName, _) ->
            tabLayout.addTab(tabLayout.newTab().setText(sourceName))
        }

        val currentIndex = playlistManager.getCurrentIndex()
        val currentEpisode = episodes.getOrNull(currentIndex)
        
        // 找到当前播放的剧集所在的线路
        var selectedTabIndex = 0
        var indexInSelectedLine = -1
        
        if (currentEpisode != null) {
            grouped.forEachIndexed { tabIndex, (_, episodeList) ->
                val indexInLine = episodeList.indexOfFirst { it.url == currentEpisode.url }
                if (indexInLine != -1) {
                    selectedTabIndex = tabIndex
                    indexInSelectedLine = indexInLine
                    return@forEachIndexed
                }
            }
        }
        
        // 创建Adapter，使用当前线路中的索引
        portraitEpisodeAdapter = EpisodeSelectorAdapter(indexInSelectedLine) { index, episode ->
            android.util.Log.d("VideoPlayer", "点击集数: index=$index, name=${episode.name}, url=${episode.url}")
            
            // 需要找到原始索引（在完整列表中的位置）
            // 优先通过 URL 匹配（更可靠）
            val originalIndex = episodes.indexOfFirst { it.url == episode.url }
            
            if (originalIndex != -1) {
                android.util.Log.d("VideoPlayer", "找到原始索引: $originalIndex, 开始播放")
                playEpisode(originalIndex)
                
                // 更新当前线路中的索引
                portraitEpisodeAdapter?.updateCurrentIndex(index)
                dialogEpisodeAdapter?.updateCurrentIndex(originalIndex)
            } else {
                android.util.Log.e("VideoPlayer", "未找到原始索引！episode.url=${episode.url}")
                Toast.makeText(this, "无法播放该集数", Toast.LENGTH_SHORT).show()
            }
        }

        // 使用横向LinearLayoutManager，只显示一行
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
            this,
            androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL,
            false
        )
        recyclerView.adapter = portraitEpisodeAdapter

        // 更新集数文本
        if (grouped.isNotEmpty()) {
            val totalEpisodes = grouped[selectedTabIndex].second.size
            episodeCountText.text = "全${totalEpisodes}集"
        }

        // 点击"更多"按钮，弹出完整选集对话框
        moreButton.setOnClickListener {
            showAllEpisodesBottomSheet()
        }

        // 显示选中的线路的剧集
        if (grouped.isNotEmpty()) {
            portraitEpisodeAdapter?.submitList(grouped[selectedTabIndex].second) {
                // 数据更新后，滚动到当前播放的集数
                if (indexInSelectedLine >= 0) {
                    recyclerView.post {
                        recyclerView.scrollToPosition(indexInSelectedLine)
                    }
                }
            }
        }

        // Tab切换监听
        tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                tab?.position?.let { position ->
                    if (position < grouped.size) {
                        val selectedLineEpisodes = grouped[position].second
                        portraitEpisodeAdapter?.submitList(selectedLineEpisodes)
                        
                        // 更新集数文本
                        episodeCountText.text = "全${selectedLineEpisodes.size}集"
                        
                        // 找到当前播放的剧集在这个线路中的索引
                        val currentEpisode = episodes.getOrNull(playlistManager.getCurrentIndex())
                        if (currentEpisode != null) {
                            val indexInLine = selectedLineEpisodes.indexOfFirst { 
                                it.url == currentEpisode.url 
                            }
                            if (indexInLine != -1) {
                                // 更新适配器的当前索引
                                portraitEpisodeAdapter?.updateCurrentIndex(indexInLine)
                            } else {
                                // 当前播放的集数不在这个线路中，设置为-1（不高亮任何集数）
                                portraitEpisodeAdapter?.updateCurrentIndex(-1)
                            }
                        }
                    }
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })

        // 找到当前播放的剧集所在的线路并切换到该Tab
        if (currentEpisode != null) {
            tabLayout.getTabAt(selectedTabIndex)?.select()
        }
    }
    
    private fun updateLockState() {
        if (isLocked) {
            // 锁定状态：隐藏顶部和底部控制栏，只显示锁定按钮
            hideControlsWithAnimation()
            binding.leftControlBar.visibility = View.VISIBLE
            binding.lockButton.setImageResource(com.tv.ball.R.drawable.closure_lock_btn)
            Toast.makeText(this, "已锁定屏幕", Toast.LENGTH_SHORT).show()
        } else {
            // 解锁状态：显示所有控制栏
            showControlsWithAnimation()
            binding.lockButton.setImageResource(com.tv.ball.R.drawable.closure_unlock_btn)
            Toast.makeText(this, "已解锁屏幕", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showAllEpisodesBottomSheet() {
        val episodes = playlistManager.getPlaylist()
        if (episodes.isEmpty()) {
            Toast.makeText(this, "没有播放列表", Toast.LENGTH_SHORT).show()
            return
        }

        // 创建底部弹窗
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.FullWidthBottomSheetDialog)
        val dialogView = layoutInflater.inflate(R.layout.bottom_sheet_all_episodes, null)
        bottomSheet.setContentView(dialogView)

        val tabLayout = dialogView.findViewById<com.google.android.material.tabs.TabLayout>(R.id.tabLayout)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.episodesRecyclerView)
        val closeButton = dialogView.findViewById<android.widget.ImageButton>(R.id.closeButton)

        // 按线路分组
        val grouped = episodes.groupBy { episode ->
            val match = """\[([^\]]+)\]""".toRegex().find(episode.name)
            match?.groupValues?.get(1) ?: "默认线路"
        }.map { (sourceName, episodeList) ->
            val cleanedEpisodes = episodeList.map { episode ->
                val cleanName = episode.name.replace("""\[[^\]]+\]\s*""".toRegex(), "")
                episode.copy(name = cleanName)
            }
            sourceName to cleanedEpisodes
        }.sortedByDescending { (sourceName, episodeList) ->
            // 线路排序：m3u8 线路优先
            var score = 0
            val lowerName = sourceName.lowercase()
            val firstUrl = episodeList.firstOrNull()?.url?.lowercase() ?: ""
            
            // 最高优先级：线路名或URL包含 m3u8
            if (lowerName.contains("m3u8") || firstUrl.contains(".m3u8")) {
                score += 1000
                android.util.Log.d("VideoPlayer", "✓✓✓ M3U8线路优先（底部弹窗）: $sourceName")
            }
            
            // 次优先级：线路名包含 HD、高清、1080p 等
            if (lowerName.contains("hd") || lowerName.contains("高清") || 
                lowerName.contains("1080") || lowerName.contains("720")) {
                score += 500
            }
            
            // MP4 线路
            if (lowerName.contains("mp4") || firstUrl.contains(".mp4")) {
                score += 300
            }
            
            score
        }

        // 设置Tab
        tabLayout.removeAllTabs()
        grouped.forEach { (sourceName, _) ->
            tabLayout.addTab(tabLayout.newTab().setText(sourceName))
        }

        val currentIndex = playlistManager.getCurrentIndex()
        val currentEpisode = episodes.getOrNull(currentIndex)
        
        // 找到当前播放的剧集所在的线路
        var selectedTabIndex = 0
        var indexInSelectedLine = -1
        
        if (currentEpisode != null) {
            grouped.forEachIndexed { tabIndex, (_, episodeList) ->
                val indexInLine = episodeList.indexOfFirst { it.url == currentEpisode.url }
                if (indexInLine != -1) {
                    selectedTabIndex = tabIndex
                    indexInSelectedLine = indexInLine
                    return@forEachIndexed
                }
            }
        }

        // 创建Adapter
        var bottomSheetAdapter: EpisodeSelectorAdapter? = null
        bottomSheetAdapter = EpisodeSelectorAdapter(indexInSelectedLine) { index, episode ->
            val originalIndex = episodes.indexOfFirst { it.name == episode.name && it.url == episode.url }
            if (originalIndex != -1) {
                playEpisode(originalIndex)
                bottomSheetAdapter?.updateCurrentIndex(index)
                portraitEpisodeAdapter?.updateCurrentIndex(index)
                bottomSheet.dismiss()
            }
        }

        // 使用网格布局
        recyclerView.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 6)
        recyclerView.adapter = bottomSheetAdapter

        // 显示选中的线路的剧集
        if (grouped.isNotEmpty()) {
            bottomSheetAdapter.submitList(grouped[selectedTabIndex].second)
        }

        // Tab切换监听
        tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                tab?.position?.let { position ->
                    if (position < grouped.size) {
                        val selectedLineEpisodes = grouped[position].second
                        bottomSheetAdapter.submitList(selectedLineEpisodes)
                        
                        // 找到当前播放的剧集在这个线路中的索引
                        if (currentEpisode != null) {
                            val indexInLine = selectedLineEpisodes.indexOfFirst { 
                                it.url == currentEpisode.url 
                            }
                            bottomSheetAdapter.updateCurrentIndex(if (indexInLine != -1) indexInLine else -1)
                        }
                    }
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })

        // 关闭按钮
        closeButton.setOnClickListener {
            bottomSheet.dismiss()
        }

        // 选中当前tab
        if (currentEpisode != null) {
            tabLayout.getTabAt(selectedTabIndex)?.select()
        }

        bottomSheet.show()
        
        // 设置底部弹窗宽度为全屏
        bottomSheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        bottomSheet.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        
        // 强制设置对话框容器的宽度为全屏
        val bottomSheetInternal = bottomSheet.findViewById<android.widget.FrameLayout>(
            com.google.android.material.R.id.design_bottom_sheet
        )
        bottomSheetInternal?.layoutParams?.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
    }
    
    private val speedOptions = arrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var currentSpeedIndex = 2 // 默认1.0x
    
    private fun cyclePlaybackSpeed() {
        // 循环切换到下一个倍速
        currentSpeedIndex = (currentSpeedIndex + 1) % speedOptions.size
        val newSpeed = speedOptions[currentSpeedIndex]
        
        exoPlayerManager.setPlaybackSpeed(newSpeed)
        binding.speedButton.text = String.format("%.2fx", newSpeed)
        
        // 显示提示
        Toast.makeText(this, "播放速度: ${String.format("%.2fx", newSpeed)}", Toast.LENGTH_SHORT).show()
    }
    
    private fun showSpeedDialog() {
        val speeds = arrayOf("0.5×", "0.75×", "1.0×", "1.25×", "1.5×", "2.0×")
        val speedValues = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        
        AlertDialog.Builder(this)
            .setTitle("播放速度")
            .setItems(speeds) { _, which ->
                exoPlayerManager.setPlaybackSpeed(speedValues[which])
                binding.speedButton.text = speeds[which]
            }
            .show()
    }
    
    private fun showEpisodeDialog() {
        try {
            android.util.Log.d("VideoPlayer", "showEpisodeDialog called")
            android.util.Log.d("VideoPlayer", "Playlist size: ${playlistManager.getPlaylistSize()}")

            if (playlistManager.getPlaylistSize() == 0) {
                Toast.makeText(this, "没有播放列表", Toast.LENGTH_SHORT).show()
                return
            }

            val playlist = playlistManager.getPlaylist()
            android.util.Log.d("VideoPlayer", "Playlist: $playlist")

            if (playlist.isEmpty()) {
                Toast.makeText(this, "播放列表为空", Toast.LENGTH_SHORT).show()
                return
            }

            // 分组剧集：按线路分组
            val episodeGroups = groupEpisodesBySource(playlist)
            android.util.Log.d("VideoPlayer", "Episode groups: ${episodeGroups.size}")

            // 根据线路数量选择不同的布局
            if (episodeGroups.size == 1) {
                // 只有一个线路：使用全屏布局
                showFullscreenEpisodeDialog(playlist, episodeGroups[0].episodes)
            } else {
                // 多个线路：使用带Tab的布局
                showTabsEpisodeDialog(playlist, episodeGroups)
            }
            
            android.util.Log.d("VideoPlayer", "Dialog shown successfully")
        } catch (e: Exception) {
            android.util.Log.e("VideoPlayer", "Error in showEpisodeDialog", e)
            Toast.makeText(this, "打开选集失败: ${e.message}", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    /**
     * 按线路分组剧集
     */
    private fun groupEpisodesBySource(episodes: List<Episode>): List<EpisodeGroup> {
        val groups = mutableMapOf<String, MutableList<Episode>>()
        
        episodes.forEach { episode ->
            val sourceName = extractSourceName(episode.name)
            if (!groups.containsKey(sourceName)) {
                groups[sourceName] = mutableListOf()
            }
            groups[sourceName]?.add(episode)
        }
        
        return groups.map { (sourceName, episodeList) ->
            EpisodeGroup(sourceName, episodeList)
        }
    }

    /**
     * 提取线路名称
     * 例如: "[线路1] 第1集" -> "线路1"
     * 如果没有线路前缀，返回"默认线路"
     */
    private fun extractSourceName(episodeName: String): String {
        val match = """\[([^\]]+)\]""".toRegex().find(episodeName)
        return match?.groupValues?.get(1) ?: "默认线路"
    }

    /**
     * 显示全屏选集对话框（单线路）
     */
    private fun showFullscreenEpisodeDialog(allEpisodes: List<Episode>, displayEpisodes: List<Episode>) {
        val dialogView = layoutInflater.inflate(com.tv.ball.R.layout.dialog_episode_list_fullscreen, null)
        val recyclerView = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(
            com.tv.ball.R.id.episodeRecyclerView
        )

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        // 使用网格布局，5列，间距更紧凑
        val gridLayoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 5)
        recyclerView.layoutManager = gridLayoutManager
        
        // 添加ItemDecoration来控制间距
        recyclerView.addItemDecoration(object : androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: android.graphics.Rect,
                view: android.view.View,
                parent: androidx.recyclerview.widget.RecyclerView,
                state: androidx.recyclerview.widget.RecyclerView.State
            ) {
                outRect.left = 4
                outRect.right = 4
                outRect.top = 4
                outRect.bottom = 4
            }
        })

        dialogEpisodeAdapter = EpisodeSelectorAdapter(playlistManager.getCurrentIndex()) { index, episode ->
            android.util.Log.d("VideoPlayer", "Episode clicked: $index - ${episode.name}")
            // 在原始列表中查找索引
            val originalIndex = allEpisodes.indexOfFirst { it.url == episode.url }
            if (originalIndex >= 0) {
                playEpisode(originalIndex)
                dialogEpisodeAdapter?.updateCurrentIndex(originalIndex)
                portraitEpisodeAdapter?.updateCurrentIndex(originalIndex)
            }
            dialog.dismiss()
        }

        dialogEpisodeAdapter?.submitList(displayEpisodes)
        recyclerView.adapter = dialogEpisodeAdapter
        
        // 添加关闭按钮监听
        val closeButton = dialogView.findViewById<android.widget.ImageButton>(com.tv.ball.R.id.closeButton)
        closeButton?.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
        
        // 全屏显示
        dialog.window?.let { window ->
            val params = window.attributes
            params.width = android.view.WindowManager.LayoutParams.MATCH_PARENT
            params.height = android.view.WindowManager.LayoutParams.MATCH_PARENT
            window.attributes = params
            window.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    /**
     * 显示带Tab的选集对话框（多线路）
     */
    private fun showTabsEpisodeDialog(allEpisodes: List<Episode>, episodeGroups: List<EpisodeGroup>) {
        val dialogView = layoutInflater.inflate(com.tv.ball.R.layout.dialog_episode_list_with_tabs, null)
        val tabLayout = dialogView.findViewById<com.google.android.material.tabs.TabLayout>(
            com.tv.ball.R.id.sourceTabLayout
        )
        val recyclerView = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(
            com.tv.ball.R.id.episodeRecyclerView
        )

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        // 使用网格布局，5列，间距更紧凑
        val gridLayoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 5)
        recyclerView.layoutManager = gridLayoutManager
        
        // 添加ItemDecoration来控制间距
        recyclerView.addItemDecoration(object : androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: android.graphics.Rect,
                view: android.view.View,
                parent: androidx.recyclerview.widget.RecyclerView,
                state: androidx.recyclerview.widget.RecyclerView.State
            ) {
                outRect.left = 4
                outRect.right = 4
                outRect.top = 4
                outRect.bottom = 4
            }
        })

        var currentGroupIndex = 0
        
        // 找到当前播放集数所在的线路
        val currentEpisode = allEpisodes.getOrNull(playlistManager.getCurrentIndex())
        if (currentEpisode != null) {
            val currentSource = extractSourceName(currentEpisode.name)
            currentGroupIndex = episodeGroups.indexOfFirst { it.sourceName == currentSource }.coerceAtLeast(0)
        }

        dialogEpisodeAdapter = EpisodeSelectorAdapter(playlistManager.getCurrentIndex()) { index, episode ->
            android.util.Log.d("VideoPlayer", "Episode clicked: $index - ${episode.name}")
            // 在原始列表中查找索引
            val originalIndex = allEpisodes.indexOfFirst { it.url == episode.url }
            if (originalIndex >= 0) {
                playEpisode(originalIndex)
                dialogEpisodeAdapter?.updateCurrentIndex(originalIndex)
                portraitEpisodeAdapter?.updateCurrentIndex(originalIndex)
            }
            dialog.dismiss()
        }

        // 添加Tab
        episodeGroups.forEach { group ->
            tabLayout.addTab(tabLayout.newTab().setText(group.sourceName))
        }

        // 显示当前线路的剧集
        fun showEpisodesForGroup(groupIndex: Int) {
            if (groupIndex >= 0 && groupIndex < episodeGroups.size) {
                val group = episodeGroups[groupIndex]
                dialogEpisodeAdapter?.submitList(group.episodes)
            }
        }

        showEpisodesForGroup(currentGroupIndex)
        tabLayout.selectTab(tabLayout.getTabAt(currentGroupIndex))
        recyclerView.adapter = dialogEpisodeAdapter

        // Tab切换监听
        tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                tab?.position?.let { position ->
                    showEpisodesForGroup(position)
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
        
        // 添加关闭按钮监听
        val closeButton = dialogView.findViewById<android.widget.ImageButton>(com.tv.ball.R.id.closeButton)
        closeButton?.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
        
        // 全屏显示，背景透明度30%
        dialog.window?.let { window ->
            val params = window.attributes
            params.width = android.view.WindowManager.LayoutParams.MATCH_PARENT
            params.height = android.view.WindowManager.LayoutParams.MATCH_PARENT
            window.attributes = params
            window.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    /**
     * 剧集分组数据类
     */
    data class EpisodeGroup(
        val sourceName: String,
        val episodes: List<Episode>
    )
    
    private fun playEpisode(index: Int) {
        android.util.Log.d("VideoPlayer", "=== playEpisode 开始 ===")
        android.util.Log.d("VideoPlayer", "索引: $index")
        
        val episode = playlistManager.selectEpisode(index)
        if (episode != null) {
            android.util.Log.d("VideoPlayer", "集数名称: ${episode.name}")
            android.util.Log.d("VideoPlayer", "集数URL: ${episode.url}")
            
            binding.titleText.text = "$videoTitle - ${episode.name}"
            hasSkippedIntro = false
            hasStartedRouteTest = false  // 重置线路测试标志
            
            // 清空之前的嗅探链接，避免干扰
            sniffedVideoLinks.clear()
            currentLinkIndex = 0
            hasStartedPlaying = false
            
            android.util.Log.d("VideoPlayer", "调用 viewModel.loadVideo")
            viewModel.loadVideo(
                videoId = videoId,
                videoTitle = videoTitle,
                videoUrl = episode.url,
                thumbnailUrl = thumbnailUrl,
                detailUrl = detailUrl,
                episodesJson = episodesJson,
                episodeName = episode.name,
                videoSource = null,
                allEpisodesList = playlistManager.getPlaylist(),  // 传递完整剧集列表
                episodeIndex = index  // 传递当前索引
            )
            updateNextButtonVisibility()
        } else {
            android.util.Log.e("VideoPlayer", "playEpisode: episode 为 null!")
        }
    }
    
    private fun showSpeedSettingsDialog() {
        val currentSpeed = exoPlayerManager.getPlaybackSpeed()
        
        val dialog = SpeedSettingsDialog(
            this, 
            currentSpeed, 
            seekSensitivity,
            onSpeedSelected = { speed ->
                exoPlayerManager.setPlaybackSpeed(speed)
                updateSpeedButtonText(speed)
            },
            onSensitivitySelected = { sensitivity ->
                seekSensitivity = sensitivity
                Toast.makeText(this, "滑动灵敏度已设置为 ${String.format("%.2f", sensitivity)}X", Toast.LENGTH_SHORT).show()
            }
        )
        dialog.show()
    }
    
    private fun updateSpeedButtonText(speed: Float) {
        binding.speedButton.text = String.format("%.2fx", speed)
    }
    
    private fun playNextEpisode() {
        val nextEpisode = playlistManager.next()
        if (nextEpisode != null) {
            binding.titleText.text = "$videoTitle - ${nextEpisode.name}"
            viewModel.loadVideo(
                videoId = videoId,
                videoTitle = videoTitle,
                videoUrl = nextEpisode.url,
                thumbnailUrl = thumbnailUrl,
                detailUrl = detailUrl,
                episodesJson = episodesJson,
                episodeName = nextEpisode.name,
                videoSource = null,
                allEpisodesList = playlistManager.getPlaylist(),  // 传递完整剧集列表
                episodeIndex = playlistManager.getCurrentIndex()  // 传递当前索引
            )
            updateNextButtonVisibility()
            Toast.makeText(this, "播放 ${nextEpisode.name}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "已经是最后一集了", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun updateNextButtonVisibility() {
        binding.nextButton.visibility = if (playlistManager.hasNext()) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
    }
    
    private fun showMenuDialog() {
        val items = arrayOf("视频信息", "画面比例", "重复播放", "使用系统播放器")
        AlertDialog.Builder(this)
            .setTitle("菜单")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showVideoInfo()
                    1 -> showAspectRatioDialog()
                    2 -> toggleRepeatMode()
                    3 -> openWithSystemPlayer()
                }
            }
            .show()
    }
    
    private fun shareVideo() {
        val currentEpisode = playlistManager.getCurrentEpisode()
        val videoUrl = currentEpisode?.url ?: intent.getStringExtra("VIDEO_URL") ?: ""
        
        if (videoUrl.isEmpty()) {
            Toast.makeText(this, "无法获取视频地址", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND)
            shareIntent.type = "text/plain"
            shareIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, videoTitle)
            shareIntent.putExtra(android.content.Intent.EXTRA_TEXT, "$videoTitle\n$videoUrl")
            startActivity(android.content.Intent.createChooser(shareIntent, "分享视频"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun captureScreenshot() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                // 获取播放器视图的bitmap
                val playerView = binding.playerView
                val bitmap = android.graphics.Bitmap.createBitmap(
                    playerView.width,
                    playerView.height,
                    android.graphics.Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(bitmap)
                playerView.draw(canvas)
                
                // 保存到相册
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "screenshot_${System.currentTimeMillis()}.jpg")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES)
                }
                
                val uri = contentResolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                )
                
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { outputStream ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, outputStream)
                        Toast.makeText(this, "截屏已保存到相册", Toast.LENGTH_SHORT).show()
                    }
                } ?: run {
                    Toast.makeText(this, "截屏失败", Toast.LENGTH_SHORT).show()
                }
                
                bitmap.recycle()
            } catch (e: Exception) {
                Toast.makeText(this, "截屏失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "系统版本过低，不支持截屏功能", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun openWithSystemPlayer() {
        val currentEpisode = playlistManager.getCurrentEpisode()
        val videoUrl = currentEpisode?.url ?: intent.getStringExtra("VIDEO_URL") ?: ""

        if (videoUrl.isEmpty()) {
            Toast.makeText(this, "无法获取视频地址", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            intent.setDataAndType(android.net.Uri.parse(videoUrl), "video/*")
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开系统播放器: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openInBrowser(url: String) {
        if (url.isEmpty()) {
            Toast.makeText(this, "无法获取视频地址", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开链接: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showVideoInfo() {
        try {
            val currentEpisode = playlistManager.getCurrentEpisode()
            val videoUrl = currentEpisode?.url ?: intent.getStringExtra("VIDEO_URL") ?: ""
            
            val videoInfo = VideoInfoDialog.VideoInfo(
                title = videoTitle,
                url = videoUrl,
                width = exoPlayerManager.getVideoWidth(),
                height = exoPlayerManager.getVideoHeight(),
                duration = exoPlayerManager.getDuration(),
                currentPosition = exoPlayerManager.getCurrentPosition(),
                bitrate = 0, // ExoPlayer可以获取，但需要额外实现
                fps = 0f,
                codec = "H.264", // 可以从ExoPlayer获取
                audioCodec = "AAC",
                audioChannels = 2,
                audioSampleRate = 48000,
                fileSize = 0
            )
            
            val dialog = VideoInfoDialog.newInstance(videoInfo)
            dialog.show(supportFragmentManager, VideoInfoDialog.TAG)
        } catch (e: Exception) {
            android.util.Log.e("VideoPlayer", "显示视频信息失败", e)
            Toast.makeText(this, "无法显示视频信息", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showAspectRatioDialog() {
        val items = arrayOf("自适应缩放", "适应放大裁剪", "视频铺满屏幕", "按照原始尺寸")
        val currentMode = aspectRatioManager.getCurrentMode()
        val currentIndex = when (currentMode) {
            AspectRatioManager.AspectRatioMode.FIT -> 0
            AspectRatioManager.AspectRatioMode.FILL -> 1
            AspectRatioManager.AspectRatioMode.ZOOM -> 2
            AspectRatioManager.AspectRatioMode.FIXED_WIDTH -> 3
            else -> 0
        }
        
        AlertDialog.Builder(this)
            .setTitle("画面比例")
            .setSingleChoiceItems(items, currentIndex) { dialog, which ->
                val newMode = when (which) {
                    0 -> AspectRatioManager.AspectRatioMode.FIT
                    1 -> AspectRatioManager.AspectRatioMode.FILL
                    2 -> AspectRatioManager.AspectRatioMode.ZOOM
                    3 -> AspectRatioManager.AspectRatioMode.FIXED_WIDTH
                    else -> AspectRatioManager.AspectRatioMode.FIT
                }
                aspectRatioManager.setMode(newMode)
                applyAspectRatio(newMode)
                Toast.makeText(this, "已选择 ${items[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun toggleRepeatMode() {
        val currentMode = exoPlayerManager.getRepeatMode()
        val newMode = when (currentMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        
        exoPlayerManager.setRepeatMode(newMode)
        
        val modeText = when (newMode) {
            Player.REPEAT_MODE_OFF -> "关闭重复"
            Player.REPEAT_MODE_ONE -> "单集循环"
            Player.REPEAT_MODE_ALL -> "列表循环"
            else -> "未知模式"
        }
        
        Toast.makeText(this, "重复播放: $modeText", Toast.LENGTH_SHORT).show()
    }
    
    private fun observeViewModel() {
        var pendingSeekPosition: Long = 0
        
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.videoUrl.collect { url ->
                        url?.let {
                            try {
                                // 重置播放进度恢复标志（新视频开始播放）
                                hasRestoredPlaybackPosition = false
                                
                                // 更新当前播放URL，用于下载等功能
                                currentPlayingUrl = it
                                android.util.Log.d("VideoPlayerActivity", "更新currentPlayingUrl: $it")
                                
                                val headers = viewModel.getVideoHeaders()
                                exoPlayerManager.prepareMedia(it, headers)
                            } catch (e: Exception) {
                                viewModel.onPlaybackError(e.message ?: "播放器初始化失败")
                            }
                        }
                    }
                }
                
                launch {
                    viewModel.playerState.collect { state ->
                        android.util.Log.d("VideoPlayerActivity", "播放器状态变化: $state, pendingSeekPosition=$pendingSeekPosition, hasRestoredPlaybackPosition=$hasRestoredPlaybackPosition")
                        when (state) {
                            is PlayerState.Loading -> {
                                binding.loadingView.visibility = View.VISIBLE
                                binding.errorText.visibility = View.GONE
                                updateStatusText("加载中...")
                            }
                            is PlayerState.LoadingWithMessage -> {
                                // 检查是否需要嗅探
                                if (state.message.contains("检测到网页") || state.message.contains("正在解析")) {
                                    // Activity接管嗅探过程
                                    binding.loadingView.visibility = View.VISIBLE
                                    binding.errorText.visibility = View.GONE
                                    updateStatusText(state.message)
                                    
                                    // 开始嗅探
                                    if (!isSniffing) {
                                        startSniffing()
                                    }
                                } else {
                                    binding.loadingView.visibility = View.VISIBLE
                                    binding.errorText.visibility = View.GONE
                                    updateStatusText(state.message)
                                }
                            }
                            is PlayerState.Ready, is PlayerState.Playing -> {
                                binding.loadingView.visibility = View.GONE
                                binding.errorText.visibility = View.GONE
                                
                                // 显示播放器就绪提示
                                if (state is PlayerState.Ready) {
                                    updateStatusText("播放器就绪")
                                }
                                
                                // 播放器准备好或开始播放后，如果有待恢复的位置且还没恢复过，则恢复播放位置
                                if (!hasRestoredPlaybackPosition && pendingSeekPosition > 0) {
                                    android.util.Log.d("VideoPlayerActivity", "播放器准备好，从历史记录恢复播放位置: $pendingSeekPosition ms")
                                    // 延迟一点再 seek，确保播放器完全准备好
                                    lifecycleScope.launch {
                                        kotlinx.coroutines.delay(300)
                                        exoPlayerManager.seekTo(pendingSeekPosition)
                                        hasRestoredPlaybackPosition = true
                                        android.util.Log.d("VideoPlayerActivity", "已恢复播放位置")
                                    }
                                }
                            }
                            is PlayerState.Error -> {
                                binding.loadingView.visibility = View.GONE
                                binding.errorText.visibility = View.VISIBLE
                                binding.errorText.text = state.message
                                updateStatusText("播放失败: ${state.message}")
                            }
                            else -> {}
                        }
                    }
                }
                
                launch {
                    viewModel.currentPosition.collect { position ->
                        android.util.Log.d("VideoPlayerActivity", "currentPosition 更新: $position ms, hasRestoredPlaybackPosition=$hasRestoredPlaybackPosition")
                        // 只在还没恢复过位置时，保存待恢复的位置
                        if (!hasRestoredPlaybackPosition && position > 0) {
                            if (exoPlayerManager.isPlayerReady()) {
                                android.util.Log.d("VideoPlayerActivity", "播放器已准备好，立即恢复播放位置: $position ms")
                                exoPlayerManager.seekTo(position)
                                hasRestoredPlaybackPosition = true
                            } else {
                                android.util.Log.d("VideoPlayerActivity", "播放器未准备好，保存待恢复的播放位置: $position ms")
                                pendingSeekPosition = position
                            }
                        }
                    }
                }
                
                // 监听线路切换事件
                launch {
                    viewModel.routeSwitchEvent.collect { episode ->
                        episode?.let {
                            android.util.Log.d("VideoPlayerActivity", "收到线路切换事件: ${it.name}")
                            
                            // 更新标题
                            binding.titleText.text = "$videoTitle - ${it.name}"
                            
                            // 更新选集列表的选中状态
                            val newIndex = playlistManager.getPlaylist().indexOfFirst { ep -> 
                                ep.url == it.url 
                            }
                            if (newIndex >= 0) {
                                android.util.Log.d("VideoPlayerActivity", "更新选集UI，新索引: $newIndex")
                                playlistManager.selectEpisode(newIndex)
                                
                                // 更新所有选集适配器
                                dialogEpisodeAdapter?.updateCurrentIndex(newIndex)
                                portraitEpisodeAdapter?.updateCurrentIndex(newIndex)
                            }
                        }
                    }
                }
                
                // 监听线路测试状态
                launch {
                    viewModel.routeTestStatus.collect { status ->
                        status?.let {
                            android.util.Log.d("VideoPlayerActivity", "线路测试状态: $it")
                            updateStatusText(it)
                        }
                    }
                }

                // 监听发现更快路线
                launch {
                    viewModel.fasterRouteFound.collect { fasterRoute ->
                        if (fasterRoute != null) {
                            showFasterRouteHint(fasterRoute)
                        } else {
                            hideFasterRouteHint()
                        }
                    }
                }
            }
        }
    }
    
    private fun loadVideoFromIntent() {
        // 重置播放进度恢复标志
        hasRestoredPlaybackPosition = false
        
        videoId = intent.getLongExtra("VIDEO_ID", 0)
        videoTitle = intent.getStringExtra("VIDEO_TITLE") ?: ""
        val videoUrl = intent.getStringExtra("VIDEO_URL") ?: ""
        thumbnailUrl = intent.getStringExtra("THUMBNAIL_URL") ?: intent.getStringExtra("COVER_URL")
        detailUrl = intent.getStringExtra("DETAIL_URL")
        
        // ========== 日志：开始新会话 ==========
        VideoFlowLogger.startSession(videoTitle, videoUrl)
        VideoFlowLogger.info(
            VideoFlowLogger.FlowStage.INIT,
            "初始化播放器",
            "视频ID: $videoId\n视频标题: $videoTitle\n视频URL: $videoUrl"
        )
        // ====================================
        
        // 获取缓存的解析URL和请求头（来自历史记录）
        val cachedParsedVideoUrl = intent.getStringExtra("PARSED_VIDEO_URL")
        val cachedVideoHeaders = intent.getStringExtra("VIDEO_HEADERS")
        val cachedEpisodeName = intent.getStringExtra("CACHED_EPISODE_NAME")  // 缓存对应的集数名称
        
        // 检查是否是短剧
        val isShortDrama = intent.getBooleanExtra("IS_SHORT_DRAMA", false)
        android.util.Log.d("VideoPlayer", "loadVideoFromIntent: isShortDrama=$isShortDrama")
        
        // 检查是否传入了播放列表
        episodesJson = intent.getStringExtra("EPISODES_JSON")
        val startIndex = intent.getIntExtra("START_INDEX", 0)
        
        android.util.Log.d("VideoPlayer", "episodesJson=$episodesJson")
        android.util.Log.d("VideoPlayer", "startIndex=$startIndex")
        
        // 初始化短剧管理器
        shortDramaManager.initShortDramaMode(isShortDrama, episodesJson, startIndex)
        
        val currentEpisodesJson = episodesJson
        if (!currentEpisodesJson.isNullOrEmpty()) {
            // 解析播放列表
            try {
                val episodes = parseEpisodesFromJson(currentEpisodesJson)
                playlistManager.setPlaylist(episodes, startIndex)
                
                // 显示选集和下一集按钮
                binding.episodeButton.visibility = View.VISIBLE
                binding.nextButton.visibility = if (playlistManager.hasNext()) View.VISIBLE else View.GONE
                
                // 播放当前集
                playlistManager.getCurrentEpisode()?.let { episode ->
                    binding.titleText.text = "$videoTitle - ${episode.name}"
                    
                    // 只有当缓存的集数与当前集数匹配时，才使用缓存的解析URL
                    val useCachedUrl = cachedEpisodeName == episode.name
                    
                    viewModel.loadVideo(
                        videoId = videoId,
                        videoTitle = videoTitle,
                        videoUrl = episode.url,
                        thumbnailUrl = thumbnailUrl,
                        detailUrl = detailUrl,
                        episodesJson = currentEpisodesJson,
                        episodeName = episode.name,
                        parsedVideoUrl = if (useCachedUrl) cachedParsedVideoUrl else null,
                        videoHeaders = if (useCachedUrl) cachedVideoHeaders else null,
                        videoSource = null,
                        allEpisodesList = episodes,  // 传入完整剧集列表
                        episodeIndex = startIndex  // 传入当前集数索引
                    )
                }
            } catch (e: Exception) {
                // 解析失败，使用单个视频
                binding.episodeButton.visibility = View.GONE
                binding.nextButton.visibility = View.GONE
                binding.titleText.text = videoTitle
                if (videoUrl.isNotEmpty()) {
                    viewModel.loadVideo(videoId, videoTitle, videoUrl, thumbnailUrl, 
                        parsedVideoUrl = cachedParsedVideoUrl, videoHeaders = cachedVideoHeaders,
                        videoSource = null)
                }
            }
        } else {
            // 单个视频 - 隐藏选集和下一集按钮
            binding.episodeButton.visibility = View.GONE
            binding.nextButton.visibility = View.GONE
            binding.titleText.text = videoTitle
            if (videoUrl.isNotEmpty()) {
                viewModel.loadVideo(videoId, videoTitle, videoUrl, thumbnailUrl,
                    parsedVideoUrl = cachedParsedVideoUrl, videoHeaders = cachedVideoHeaders,
                    videoSource = null)
            }
        }
    }
    
    private fun parseEpisodesFromJson(json: String): List<com.tv.ball.domain.model.Episode> {
        // 简单的JSON解析，格式: [{"name":"第1集","url":"..."},...]
        val episodes = mutableListOf<com.tv.ball.domain.model.Episode>()
        try {
            // 移除首尾的方括号
            val content = json.trim().removePrefix("[").removeSuffix("]")
            
            // 分割每个对象
            val items = content.split("},")
            
            android.util.Log.d("VideoPlayer", "开始解析剧集JSON，共 ${items.size} 个条目")
            
            for ((index, item) in items.withIndex()) {
                val cleanItem = item.trim().removePrefix("{").removeSuffix("}")
                
                // 提取name、url和isCloudStorage
                val nameMatch = """"name"\s*:\s*"([^"]+)"""".toRegex().find(cleanItem)
                val urlMatch = """"url"\s*:\s*"([^"]+)"""".toRegex().find(cleanItem)
                val isCloudMatch = """"isCloudStorage"\s*:\s*(true|false)""".toRegex().find(cleanItem)
                
                if (nameMatch != null && urlMatch != null) {
                    val name = nameMatch.groupValues[1]
                    val url = urlMatch.groupValues[1]
                    val isCloud = isCloudMatch?.groupValues?.get(1) == "true"
                    
                    android.util.Log.d("VideoPlayer", "解析第${index+1}集: name=$name, isCloud=$isCloud, url=${url.take(50)}...")
                    
                    // 不再过滤网盘资源，全部添加到列表中
                    // 网盘资源在播放时会有特殊处理
                    episodes.add(
                        com.tv.ball.domain.model.Episode(
                            name = name,
                            url = url,
                            isCloudStorage = isCloud
                        )
                    )
                } else {
                    android.util.Log.w("VideoPlayer", "第${index+1}个条目解析失败: $cleanItem")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("VideoPlayer", "解析剧集JSON失败", e)
            e.printStackTrace()
        }
        
        android.util.Log.d("VideoPlayer", "解析剧集完成: 总共 ${episodes.size} 集")
        return episodes
    }
    
    private fun startTimeUpdate() {
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(200)
                if (!isSeeking) {
                    updateProgress()
                    checkSkipIntro()
                }
                updateSystemInfo()
            }
        }
    }
    
    private fun startNetworkMonitoring() {
        // 启动网络状态监控（智能切换WiFi/移动数据）
        networkStatusMonitor.startMonitoring()
        
        // 启动网速监控
        lifecycleScope.launch {
            networkSpeedMonitor.monitorSpeed().collect { speedInfo ->
                updateNetworkSpeed(speedInfo)
            }
        }
    }
    
    /**
     * 处理网络切换
     */
    private fun handleNetworkChange(networkType: NetworkStatusMonitor.NetworkType, message: String) {
        android.util.Log.d("VideoPlayer", "网络切换: $networkType - $message")
        
        when (networkType) {
            NetworkStatusMonitor.NetworkType.MOBILE -> {
                // 使用移动数据时提示用户
                showNetworkSwitchToast("⚡ $message\n正在使用移动数据加速播放")
            }
            NetworkStatusMonitor.NetworkType.WIFI -> {
                showNetworkSwitchToast("✓ $message")
            }
            NetworkStatusMonitor.NetworkType.NONE -> {
                showNetworkSwitchToast("✗ $message")
            }
        }
    }
    
    /**
     * 显示网络切换提示
     */
    private fun showNetworkSwitchToast(message: String) {
        runOnUiThread {
            android.widget.Toast.makeText(
                this,
                message,
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    private var poorSpeedCount = 0 // 记录连续网速较慢的次数
    private var hasShownHighSpeedHint = false  // 标记是否已显示过"线路高速"提示
    private var bufferingCount = 0 // 记录缓冲次数
    private var lastBufferingTime = 0L // 上次缓冲时间
    
    private fun updateNetworkSpeed(speedInfo: NetworkSpeedMonitor.SpeedInfo) {
        binding.networkSpeedText.text = speedInfo.speedText
        
        // 根据网速质量显示不同颜色（参考Lua版本）
        val color = when (speedInfo.quality) {
            NetworkSpeedMonitor.SpeedQuality.POOR -> {
                android.graphics.Color.RED
            }
            NetworkSpeedMonitor.SpeedQuality.FAIR -> {
                android.graphics.Color.parseColor("#FFBB33")
            }
            NetworkSpeedMonitor.SpeedQuality.GOOD -> {
                android.graphics.Color.parseColor("#99CC00")
            }
            NetworkSpeedMonitor.SpeedQuality.EXCELLENT -> {
                android.graphics.Color.parseColor("#99CC00")
            }
            else -> android.graphics.Color.GRAY
        }
        binding.networkSpeedText.setTextColor(color)
        
        // 参考腾讯视频的逻辑：
        // 1. 只在播放时监控
        // 2. 只在正在缓冲时才显示网速慢提示
        // 3. 连续3次网速过慢（< 100 KB/s）才提示
        // 4. 如果网速恢复，立即隐藏提示
        if (exoPlayerManager.isPlaying()) {
            val isBuffering = binding.loadingView.visibility == View.VISIBLE
            
            // 只有在缓冲时才考虑显示网速提示
            if (isBuffering) {
                if (speedInfo.quality == NetworkSpeedMonitor.SpeedQuality.POOR) {
                    poorSpeedCount++
                    // 连续3次网速过慢才显示提示
                    if (poorSpeedCount >= 3) {
                        binding.qualityHintText.visibility = View.VISIBLE
                        binding.qualityHintText.text = "⚠ 当前线路过慢，建议切换源"
                        binding.qualityHintText.setTextColor(android.graphics.Color.parseColor("#FF4444"))
                    }
                } else if (speedInfo.quality == NetworkSpeedMonitor.SpeedQuality.FAIR) {
                    // 网速较慢但还能播放，显示温和提示
                    poorSpeedCount++
                    if (poorSpeedCount >= 5) {
                        binding.qualityHintText.visibility = View.VISIBLE
                        binding.qualityHintText.text = "线路还可以"
                        binding.qualityHintText.setTextColor(android.graphics.Color.parseColor("#FFBB33"))
                    }
                } else {
                    // 网速恢复，重置计数并隐藏提示
                    poorSpeedCount = 0
                    binding.qualityHintText.visibility = View.GONE
                }
            } else {
                // 不在缓冲状态，显示正常的质量提示
                if (speedInfo.quality == NetworkSpeedMonitor.SpeedQuality.EXCELLENT && !hasShownHighSpeedHint) {
                    // 只显示一次"线路高速"提示
                    hasShownHighSpeedHint = true
                    binding.qualityHintText.visibility = View.VISIBLE
                    binding.qualityHintText.text = "线路高速"
                    binding.qualityHintText.setTextColor(android.graphics.Color.parseColor("#99CC00"))
                    // 2秒后自动隐藏
                    binding.qualityHintText.postDelayed({
                        binding.qualityHintText.visibility = View.GONE
                    }, 2000)
                } else {
                    // 播放正常，隐藏提示
                    poorSpeedCount = 0
                    // 不隐藏"线路高速"提示，让它自动消失
                }
            }
        } else {
            // 暂停或停止时，重置计数但不隐藏"线路高速"提示（让它自动消失）
            poorSpeedCount = 0
        }
    }
    
    private fun checkSkipIntro() {
        if (hasSkippedIntro) return
        
        val currentPosition = exoPlayerManager.getCurrentPosition()
        val duration = exoPlayerManager.getDuration()

        if (!hasSkippedIntro && skipIntroManager.shouldSkipIntro(currentPosition, false)) {
            // 显示跳过片头按钮，5秒后自动消失
            if (binding.skipIntroButton.visibility != View.VISIBLE) {
                binding.skipIntroButton.visibility = View.VISIBLE
                binding.skipIntroButton.text = "跳过片头"
                
                // 5秒后自动隐藏按钮
                skipIntroHandler.postDelayed({
                    if (!hasSkippedIntro) {
                        binding.skipIntroButton.visibility = View.GONE
                        hasSkippedIntro = true
                    }
                }, 5000)
            }

            binding.skipIntroButton.setOnClickListener {
                exoPlayerManager.seekTo(skipIntroManager.getSkipIntroPosition())
                hasSkippedIntro = true
                binding.skipIntroButton.visibility = View.GONE
                skipIntroHandler.removeCallbacksAndMessages(null)
            }
        } else if (currentPosition > skipIntroManager.getIntroDuration()) {
            // 已经过了片头时间，自动隐藏按钮
            binding.skipIntroButton.visibility = View.GONE
            hasSkippedIntro = true
            skipIntroHandler.removeCallbacksAndMessages(null)
        }

        // 检查片尾跳过
        if (skipIntroManager.shouldSkipOutro(currentPosition, duration)) {
            // 自动播放下一集
            if (playlistManager.hasNext()) {
                playNextEpisode()
            }
        }
    }
    
    private fun showPlayerMenu() {
        val menuSheet = PlayerMenuBottomSheet.newInstance()
        
        // 设置当前状态
        menuSheet.setCurrentSpeed(exoPlayerManager.getPlaybackSpeed())
        menuSheet.setCurrentAspectRatio(aspectRatioManager.getCurrentMode())
        menuSheet.setSkipIntroEnabled(skipIntroManager.isSkipIntroEnabled())
        menuSheet.setSkipOutroEnabled(skipIntroManager.isSkipOutroEnabled())
        menuSheet.setCurrentRepeatMode(exoPlayerManager.getRepeatMode())
        
        // 设置监听器
        menuSheet.setOnAspectRatioSelectedListener { mode ->
            aspectRatioManager.setMode(mode)
            applyAspectRatio(mode)
            Toast.makeText(this, "画面比例: ${mode.displayName}", Toast.LENGTH_SHORT).show()
        }
        
        menuSheet.setOnSpeedChangedListener { speed ->
            exoPlayerManager.setPlaybackSpeed(speed)
            Toast.makeText(this, "播放速度: ${String.format("%.2fx", speed)}", Toast.LENGTH_SHORT).show()
        }
        
        menuSheet.setOnSkipIntroToggledListener { enabled ->
            skipIntroManager.setSkipIntroEnabled(enabled)
        }
        
        menuSheet.setOnSkipOutroToggledListener { enabled ->
            skipIntroManager.setSkipOutroEnabled(enabled)
        }
        
        menuSheet.setOnRepeatModeChangedListener { mode ->
            exoPlayerManager.setRepeatMode(mode)
            val modeText = when (mode) {
                Player.REPEAT_MODE_OFF -> "关闭重复"
                Player.REPEAT_MODE_ONE -> "单集循环"
                Player.REPEAT_MODE_ALL -> "列表循环"
                else -> "未知模式"
            }
            Toast.makeText(this, "重复播放: $modeText", Toast.LENGTH_SHORT).show()
        }
        
        menuSheet.show(supportFragmentManager, PlayerMenuBottomSheet.TAG)
    }
    
    private fun applyAspectRatio(mode: AspectRatioManager.AspectRatioMode) {
        binding.playerView.resizeMode = mode.resizeMode
    }
    
    // 播放进度保存相关
    private var lastSaveTime = 0L
    private val saveInterval = 10000L // 每10秒保存一次
    
    private fun updateProgress() {
        val position = exoPlayerManager.getCurrentPosition()
        val duration = exoPlayerManager.getDuration()

        // 只在有效时更新UI
        if (position >= 0) {
            binding.currentPositionText.text = formatTime(position)
        }

        if (duration > 0) {
            binding.durationText.text = formatTime(duration)
            binding.progressSeekBar.max = duration.toInt()
            binding.progressSeekBar.progress = position.toInt()

            // 更新缓存进度（使用 secondaryProgress）
            val bufferedPosition = exoPlayerManager.getBufferedPosition()
            binding.progressSeekBar.secondaryProgress = bufferedPosition.toInt()
            
            // 定期保存播放进度（每10秒保存一次）
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastSaveTime >= saveInterval && position > 0) {
                lastSaveTime = currentTime
                viewModel.updatePosition(position)
                viewModel.updateDuration(duration)
                viewModel.savePlaybackProgress()
            }
            
            // 预加载下一集：在视频快结束前提前获取下一集播放地址
            checkAndPreloadNextEpisode(position, duration)
        } else {
            binding.durationText.text = "--:--"
        }

        // 更新清晰度显示（只在视频尺寸改变时更新）
        updateVideoQuality()
    }
    
    private fun updateVideoQuality() {
        val videoWidth = exoPlayerManager.getVideoWidth()
        val videoHeight = exoPlayerManager.getVideoHeight()
        
        if (videoWidth > 0 && videoHeight > 0) {
            val minDimension = minOf(videoWidth, videoHeight)
            val quality = when {
                minDimension >= 2160 -> "4K"
                minDimension >= 1440 -> "2K"
                minDimension >= 1080 -> "1080p"
                minDimension >= 720 -> "高清"
                minDimension >= 480 -> "标清"
                minDimension >= 360 -> "流畅"
                else -> "低画质"
            }
            
            try {
                binding.qualityButton?.text = quality
            } catch (e: Exception) {
                // 如果布局中没有qualityButton，忽略错误
            }
        }
    }
    
    private fun updateSystemInfo() {
        // 更新时间
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        binding.currentTimeText.text = timeFormat.format(Date())
        
        // 更新网络状态
        binding.networkStatusText.text = systemInfoMonitor.getNetworkType()
        
        // 更新电池图标
        binding.batteryIcon.setImageResource(systemInfoMonitor.getBatteryIconResource())
    }
    
    /**
     * 显示加载日志(带淡入淡出效果)
     */
    private fun showLoadingLog(message: String) {
        runOnUiThread {
            binding.loadingLogText.apply {
                text = message
                visibility = View.VISIBLE
                
                // 淡入
                animate()
                    .alpha(1f)
                    .setDuration(300)
                    .withEndAction {
                        // 3秒后淡出
                        Handler(Looper.getMainLooper()).postDelayed({
                            animate()
                                .alpha(0f)
                                .setDuration(500)
                                .withEndAction {
                                    visibility = View.GONE
                                }
                                .start()
                        }, 3000)
                    }
                    .start()
            }
        }
    }
    
    /**
     * 隐藏加载日志
     */
    private fun hideLoadingLog() {
        runOnUiThread {
            binding.loadingLogText.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    binding.loadingLogText.visibility = View.GONE
                }
                .start()
        }
    }
    
    private fun toggleControlsVisibility() {
        if (isControlsVisible) {
            hideControlsWithAnimation()
        } else {
            showControlsWithAnimation()
        }
    }
    
    private fun showControls() {
        isControlsVisible = true
        binding.topToolbar.visibility = if (isLocked) View.GONE else View.VISIBLE
        binding.bottomControlBar.visibility = if (isLocked) View.GONE else View.VISIBLE
        binding.leftControlBar.visibility = View.VISIBLE
        resetHideControlsTimer()
    }
    
    private fun showControlsWithAnimation() {
        isControlsVisible = true
        
        // 淡入动画
        if (!isLocked) {
            binding.topToolbar.apply {
                visibility = View.VISIBLE
                alpha = 0f
                animate().alpha(1f).setDuration(300).start()
            }
            binding.bottomControlBar.apply {
                visibility = View.VISIBLE
                alpha = 0f
                animate().alpha(1f).setDuration(300).start()
            }
        }
        
        binding.leftControlBar.apply {
            visibility = View.VISIBLE
            alpha = 0f
            animate().alpha(1f).setDuration(300).start()
        }
        
        resetHideControlsTimer()
    }
    
    private fun hideControls() {
        isControlsVisible = false
        binding.topToolbar.visibility = View.GONE
        binding.bottomControlBar.visibility = View.GONE
        binding.leftControlBar.visibility = if (isLocked) View.VISIBLE else View.GONE
    }
    
    private fun hideControlsWithAnimation() {
        isControlsVisible = false
        
        // 淡出动画
        binding.topToolbar.animate().alpha(0f).setDuration(300).withEndAction {
            binding.topToolbar.visibility = View.GONE
            binding.topToolbar.alpha = 1f
        }.start()
        
        binding.bottomControlBar.animate().alpha(0f).setDuration(300).withEndAction {
            binding.bottomControlBar.visibility = View.GONE
            binding.bottomControlBar.alpha = 1f
        }.start()
        
        if (!isLocked) {
            binding.leftControlBar.animate().alpha(0f).setDuration(300).withEndAction {
                binding.leftControlBar.visibility = View.GONE
                binding.leftControlBar.alpha = 1f
            }.start()
        }
    }
    
    private fun resetHideControlsTimer() {
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        hideControlsHandler.postDelayed(hideControlsRunnable, 5000)
    }
    
    private fun formatTime(ms: Long): String {
        // 处理无效时间
        if (ms < 0 || ms == Long.MAX_VALUE) {
            return "00:00"
        }
        
        val seconds = (ms / 1000).toInt()
        val minutes = seconds / 60
        val hours = minutes / 60
        
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60)
        } else {
            String.format("%02d:%02d", minutes, seconds % 60)
        }
    }
    
    override fun onPause() {
        super.onPause()
        
        // 防止快速循环调用
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPauseTime < LIFECYCLE_THROTTLE_MS) {
            android.util.Log.w("VideoPlayer", "⚠️ [生命周期] onPause - 忽略快速重复调用 (${currentTime - lastPauseTime}ms)")
            android.util.Log.w("VideoPlayer", "⚠️ [生命周期] 调用堆栈: ${Thread.currentThread().stackTrace.take(10).joinToString("\n")}")
            return
        }
        lastPauseTime = currentTime
        
        android.util.Log.d("VideoPlayer", "⏸️ [生命周期] onPause - 正常调用")
        
        // 检查是否进入画中画模式
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && isInPictureInPictureMode) {
            // 画中画模式下继续播放
            android.util.Log.d("VideoPlayer", "⏸️ [生命周期] onPause - 画中画模式，继续播放")
        } else {
            // 非画中画模式，暂停播放
            exoPlayerManager.pause()
            android.util.Log.d("VideoPlayer", "⏸️ [生命周期] onPause - 暂停播放")
        }
        
        // 保存播放进度
        viewModel.updatePosition(exoPlayerManager.getCurrentPosition())
        viewModel.savePlaybackProgress()
        
        // 保持后台播放器继续缓冲（不释放）
        android.util.Log.d("VideoPlayer", "⏸️ [生命周期] onPause - 保持后台播放器缓冲")
    }
    
    override fun onResume() {
        super.onResume()
        
        // 防止快速循环调用
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastResumeTime < LIFECYCLE_THROTTLE_MS) {
            android.util.Log.w("VideoPlayer", "⚠️ [生命周期] onResume - 忽略快速重复调用 (${currentTime - lastResumeTime}ms)")
            return
        }
        lastResumeTime = currentTime
        
        android.util.Log.d("VideoPlayer", "▶️ [生命周期] onResume - 恢复播放")
        
        // 如果不是画中画模式，恢复播放
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N || !isInPictureInPictureMode) {
            // 恢复播放进度
            val savedPosition = viewModel.getSavedPosition()
            if (savedPosition > 0 && exoPlayerManager.getCurrentPosition() == 0L) {
                android.util.Log.d("VideoPlayer", "▶️ [生命周期] 恢复播放进度: ${savedPosition}ms")
                exoPlayerManager.seekTo(savedPosition)
            }
            
            // 恢复播放
            if (exoPlayerManager.isPlayerReady()) {
                exoPlayerManager.play()
                android.util.Log.d("VideoPlayer", "▶️ [生命周期] 恢复播放")
            }
        }
        
        // 检查后台播放器状态
        backgroundPlayer?.let {
            val bufferedSeconds = it.bufferedPosition / 1000
            android.util.Log.d("VideoPlayer", "📊 [后台播放器] 已缓冲: ${bufferedSeconds}秒")
        }
    }
    
    override fun onStop() {
        super.onStop()
        android.util.Log.d("VideoPlayer", "⏹️ [生命周期] onStop")
        
        // 检查是否支持画中画且不在画中画模式
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && 
            !isInPictureInPictureMode && 
            exoPlayerManager.isPlaying()) {
            // 尝试进入画中画模式
            tryEnterPictureInPictureMode()
        }
        
        // 应用切到后台，暂停后台播放器的缓冲以节省流量
        backgroundPlayer?.let {
            if (it.playbackState == com.google.android.exoplayer2.Player.STATE_BUFFERING) {
                android.util.Log.d("VideoPlayer", "⏸️ [后台播放器] 暂停缓冲以节省流量")
                // 不释放，只是暂停
            }
        }
    }
    
    override fun onStart() {
        super.onStart()
        android.util.Log.d("VideoPlayer", "▶️ [生命周期] onStart")
        
        // 应用回到前台，恢复后台播放器的缓冲
        backgroundPlayer?.let {
            android.util.Log.d("VideoPlayer", "▶️ [后台播放器] 恢复缓冲")
        }
    }
    
    /**
     * 检查是否需要显示下一集提示（播放列表存在下一集时）
     */
    private fun checkNextEpisodeHint(currentPosition: Long, duration: Long) {
        if (duration <= 0 || isShowingNextEpisodeHint) return
        if (!playlistManager.hasNext()) return

        val remainingSeconds = (duration - currentPosition) / 1000
        if (remainingSeconds in 1..nextEpisodeHintThresholdSeconds.toLong()) {
            showNextEpisodeHint()
        }
    }

    /**
     * 显示下一集倒计时提示（只依赖 EPISODES_JSON 播放列表）
     */
    private fun showNextEpisodeHint() {
        val nextEpisode = playlistManager.getPlaylist()
            .getOrNull(playlistManager.getCurrentIndex() + 1) ?: return

        isShowingNextEpisodeHint = true
        nextEpisodeCountdown = 5

        binding.nextEpisodeHintButton.visibility = View.VISIBLE
        binding.nextEpisodeHintButton.text = "${nextEpisodeCountdown}秒后播放 ${nextEpisode.name}"

        binding.nextEpisodeHintButton.setOnClickListener {
            android.util.Log.d("VideoPlayer", "用户点击立即播放下一集")
            hideNextEpisodeHint()
            playNextEpisode()
        }

        startNextEpisodeCountdown()
    }

    /**
     * 开始倒计时
     */
    private fun startNextEpisodeCountdown() {
        countdownRunnable = object : Runnable {
            override fun run() {
                if (nextEpisodeCountdown > 0) {
                    binding.nextEpisodeHintButton.text = "${nextEpisodeCountdown}秒后播放下一集"
                    nextEpisodeCountdown--
                    countdownHandler.postDelayed(this, 1000)
                } else {
                    // 倒计时结束：交给 onEnded 自动切换下一集
                    hideNextEpisodeHint()
                }
            }
        }
        countdownRunnable?.let { countdownHandler.post(it) }
    }

    /**
     * 取消倒计时
     */
    private fun cancelNextEpisodeCountdown() {
        countdownRunnable?.let {
            countdownHandler.removeCallbacks(it)
        }
        countdownRunnable = null
    }

    /**
     * 隐藏下一集提示
     */
    private fun hideNextEpisodeHint() {
        binding.nextEpisodeHintButton.visibility = View.GONE
        isShowingNextEpisodeHint = false
        cancelNextEpisodeCountdown()
    }
    
    /**
     * 设置竖屏全屏模式
     */
    private fun setPortraitFullscreen() {
        android.util.Log.d("VideoPlayer", "切换到竖屏全屏模式")

        // 锁定竖屏方向
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // 设置透明状态栏和导航栏
        com.tv.ball.utils.StatusBarUtils.setupTransparentStatusBar(this)

        // 隐藏系统UI，全屏显示
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        // 设置播放器为全屏高度
        binding.root.post {
            val params = binding.root.layoutParams
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            binding.root.layoutParams = params

            // 短剧模式：保持滚动，显示下方的集数选择
            // 普通视频：禁用滚动
            val isShortDrama = intent.getBooleanExtra("IS_SHORT_DRAMA", false)
            if (!isShortDrama) {
                scrollView?.isNestedScrollingEnabled = false
            } else {
                // 短剧模式：添加竖屏详情视图（包含集数选择）
                addPortraitDetailView()
            }
        }
    }
    
    /**
     * 尝试进入画中画模式
     */
    private fun tryEnterPictureInPictureMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // 检查是否支持画中画
                if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                    android.util.Log.w("VideoPlayer", "设备不支持画中画功能")
                    return
                }
                
                // 获取视频宽高比
                val videoWidth = exoPlayerManager.getVideoWidth()
                val videoHeight = exoPlayerManager.getVideoHeight()
                
                val rational = if (videoWidth > 0 && videoHeight > 0) {
                    Rational(videoWidth, videoHeight)
                } else {
                    Rational(16, 9) // 默认16:9
                }
                
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(rational)
                    .build()
                
                val result = enterPictureInPictureMode(params)
                android.util.Log.d("VideoPlayer", "进入画中画模式: ${if (result) "成功" else "失败"}")
            } catch (e: Exception) {
                android.util.Log.e("VideoPlayer", "进入画中画模式失败: ${e.message}", e)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Android 7.0-7.1 使用旧API
            try {
                @Suppress("DEPRECATION")
                enterPictureInPictureMode()
                android.util.Log.d("VideoPlayer", "进入画中画模式（旧API）")
            } catch (e: Exception) {
                android.util.Log.e("VideoPlayer", "进入画中画模式失败: ${e.message}", e)
            }
        }
    }
    
    /**
     * 画中画模式变化回调
     */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        
        if (isInPictureInPictureMode) {
            // 进入画中画模式 - 隐藏控制UI
            android.util.Log.d("VideoPlayer", "📺 进入画中画模式")
            hideControls()
            binding.lockButton.visibility = View.GONE
            
            // 确保继续播放
            if (!exoPlayerManager.isPlaying()) {
                exoPlayerManager.play()
            }
        } else {
            // 退出画中画模式 - 恢复控制UI
            android.util.Log.d("VideoPlayer", "📺 退出画中画模式")
            binding.lockButton.visibility = View.VISIBLE
            showControls()
        }
    }
    
    /**
     * 用户离开Activity时的回调
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        
        // 当用户按Home键或切换应用时，自动进入画中画模式
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && 
            exoPlayerManager.isPlaying() && 
            !isInPictureInPictureMode) {
            android.util.Log.d("VideoPlayer", "用户离开，自动进入画中画模式")
            tryEnterPictureInPictureMode()
        }
    }
    
    override fun onDestroy() {
        // ========== 日志：结束会话 ==========
        VideoFlowLogger.info(
            VideoFlowLogger.FlowStage.OTHER,
            "播放器销毁",
            "播放时长: ${exoPlayerManager.getCurrentPosition()}ms"
        )
        VideoFlowLogger.endSession()
        // ====================================

        super.onDestroy()
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        hideGestureHintHandler.removeCallbacks(hideGestureHintRunnable)
        cancelNextEpisodeCountdown()  // 清理倒计时
        cancelFasterRouteCountdown()  // 清理更快路线倒计时
        networkStatusMonitor.stopMonitoring()
        releaseBackgroundPlayer()  // 释放后台播放器

        // 清理预览缓存
        previewJob?.cancel()
        previewCache.values.forEach { it.recycle() }
        previewCache.clear()

        // 释放缩略图精灵图
        thumbnailSprite?.recycle()
        thumbnailSprite = null

        exoPlayerManager.releasePlayer()
    }

    // ==================== 更快路线提示 ====================

    private val fasterRouteCountdownHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var fasterRouteCountdownRunnable: Runnable? = null
    private var fasterRouteCountdownSeconds = 3

    private fun showFasterRouteHint(fasterRoute: VideoPlayerViewModel.FasterRouteInfo) {
        binding.fasterRouteHint.visibility = View.VISIBLE
        binding.fasterRouteInfo.text = "${fasterRoute.routeName} (${fasterRoute.downloadSpeed}KB/s)"

        // 设置点击事件
        binding.fasterRouteHint.setOnClickListener {
            viewModel.switchToFasterRoute()
            cancelFasterRouteCountdown()
        }

        // 开始3秒倒计时
        fasterRouteCountdownSeconds = 3
        startFasterRouteCountdown()

        // 底部控制栏渐变动画
        animateBottomControlBar()
    }

    private fun startFasterRouteCountdown() {
        fasterRouteCountdownRunnable = object : Runnable {
            override fun run() {
                if (fasterRouteCountdownSeconds > 0) {
                    binding.fasterRouteCountdown.text = "${fasterRouteCountdownSeconds}秒后自动消失"
                    fasterRouteCountdownSeconds--
                    fasterRouteCountdownHandler.postDelayed(this, 1000)
                } else {
                    // 倒计时结束，隐藏提示
                    viewModel.dismissFasterRoute()
                }
            }
        }
        fasterRouteCountdownRunnable?.let { fasterRouteCountdownHandler.post(it) }
    }

    private fun cancelFasterRouteCountdown() {
        fasterRouteCountdownRunnable?.let {
            fasterRouteCountdownHandler.removeCallbacks(it)
        }
        fasterRouteCountdownRunnable = null
    }

    private fun hideFasterRouteHint() {
        binding.fasterRouteHint.visibility = View.GONE
        cancelFasterRouteCountdown()
    }

    private fun animateBottomControlBar() {
        val bottomBar = binding.bottomControlBar

        // 创建覆盖揭露动画（从中心向外扩散）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val cx = bottomBar.width / 2
            val cy = bottomBar.height / 2
            val finalRadius = Math.hypot(cx.toDouble(), cy.toDouble()).toFloat()

            val anim = android.view.ViewAnimationUtils.createCircularReveal(
                bottomBar, cx, cy, 0f, finalRadius
            )
            anim.duration = 600

            // 同时进行颜色渐变动画
            val colorFrom = android.graphics.Color.parseColor("#80000000") // 半透明黑色
            val colorTo = android.graphics.Color.parseColor("#E01E88E5") // 深蓝色

            val colorAnim = android.animation.ValueAnimator.ofObject(
                android.animation.ArgbEvaluator(), colorFrom, colorTo
            )
            colorAnim.duration = 600
            colorAnim.addUpdateListener { animator ->
                bottomBar.setBackgroundColor(animator.animatedValue as Int)
            }

            // 同时播放两个动画
            anim.start()
            colorAnim.start()

            // 动画结束后恢复原来的背景
            colorAnim.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // 渐变回原来的背景
                    val restoreAnim = android.animation.ValueAnimator.ofObject(
                        android.animation.ArgbEvaluator(), colorTo, colorFrom
                    )
                    restoreAnim.duration = 400
                    restoreAnim.startDelay = 200
                    restoreAnim.addUpdateListener { animator ->
                        bottomBar.setBackgroundColor(animator.animatedValue as Int)
                    }
                    restoreAnim.addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            // 恢复原来的渐变背景
                            bottomBar.setBackgroundResource(R.drawable.gradient_bottom)
                        }
                    })
                    restoreAnim.start()
                }
            })
        }
    }
}
