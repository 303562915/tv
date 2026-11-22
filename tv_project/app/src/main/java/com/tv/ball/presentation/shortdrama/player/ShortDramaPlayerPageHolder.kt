package com.tv.ball.presentation.shortdrama.player

import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import androidx.recyclerview.widget.RecyclerView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.tv.ball.R
import com.tv.ball.databinding.ItemShortDramaPlayerPageBinding
import java.util.Locale

/**
 * 一集页面需要向 Activity 要的东西，以及要通知 Activity 的事。
 *
 * 为什么用接口而不是直接把 Activity 传给 holder
 * ------------------------------------------------
 * holder 会被 ViewPager2 反复创建/回收，直接持有 Activity 很容易在回收路径上漏掉一处、
 * 变成「页面已经滑走但回调还在改已经没数据的界面」。这里只暴露必要的方法，
 * holder 拿不到 Activity 的任何 View，改错地方的机会少一半。
 *
 * 所有方法都在主线程被调用。
 */
interface ShortDramaPlayerHost {

    /** 剧 id，可能为空字符串（入口页没传时不记进度） */
    fun bookId(): String

    /** 剧名，显示在底部信息区第一行 */
    fun dramaTitle(): String

    /** 当前线路总集数，用于「第N集 / 共M集」 */
    fun totalEpisodes(): Int

    /** 当前线路名，空串表示不显示 */
    fun lineNameText(): String

    /** 是否还有别的线路可换（只有一条时右侧「线路」按钮不出现） */
    fun hasOtherLines(): Boolean

    /** 当前倍速（新页面绑定播放器时要沿用，否则换页后回到 1.0x） */
    fun playbackSpeed(): Float

    /** 当前倍速的展示文案，如 "1.25x" */
    fun speedLabel(): String

    /** 当前点赞数（本地占位计数，跨页共享） */
    fun likeCount(): Int

    /** 这一页此刻是否应该播放（用户正在看它 && Activity 处于前台） */
    fun isActivePage(position: Int): Boolean

    fun onOpenEpisodes(position: Int)

    fun onSpeedClick(position: Int)

    fun onSwitchLineClick(position: Int)

    /** 点赞 +1，返回最新计数 */
    fun onLikeTapped(position: Int): Int

    /** 某一集播完 */
    fun onEpisodeEnded(position: Int)

    /** 进度双写（每集断点 + 列表页「继续观看」），由 Activity 统一落盘 */
    fun recordProgress(item: ShortDramaQueueItem, positionMs: Long, durationMs: Long)
}

/**
 * 竖屏连播里的「一集」。
 *
 * 职责边界
 * --------
 * - 播放器实例从 [engine] 借，回收时还；holder 自己不 new ExoPlayer，也不解析地址；
 * - 单击 / 双击手势、进度条轮询、失败重试、中间大图标都在这里，因为这些都是「这一页」的事；
 * - 换线、倍速、选集面板属于跨页状态，交给 [host]，holder 只负责把按钮点击转出去。
 *
 * 为什么退出页面时不 seekTo(0)
 * ----------------------------
 * 用户上滑到下一集又滑回来，是想接着看而不是从头看，所以暂停时保留位置；
 * 真正的位置记忆交给 [ShortDramaPositionStore]（按集号存），进程死了也能回位。
 */
class ShortDramaPlayerPageHolder(
    private val binding: ItemShortDramaPlayerPageBinding,
    private val host: ShortDramaPlayerHost,
    private val engine: ShortDramaPlaybackEngine
) : Player.Listener {

    companion object {
        /** SeekBar 用整数刻度，1000 份足够细（一集 2 分钟，误差 0.12 秒） */
        private const val SEEK_MAX = 1000

        /** 进度条刷新间隔：250~500ms 之间取 400ms，肉眼看是连续走，又不会每秒刷十几次 */
        private const val TICK_INTERVAL_MS = 400L

        /** 中间大图标显示多久后自动淡出 */
        private const val CENTER_ICON_HIDE_MS = 400L
    }

    val itemView: View get() = binding.root

    private val handler = Handler(Looper.getMainLooper())

    /** 当前绑在第几页；未绑定时是 NO_POSITION */
    var pagePosition: Int = RecyclerView.NO_POSITION
        private set

    private var player: ExoPlayer? = null
    private var item: ShortDramaQueueItem? = null
    private var userSeeking = false
    private var errorShown = false

    /** 播完提示只报一次，避免 ENDED 状态回调重复触发时连翻两集 */
    private var endedHandled = false
    private var active = false

    private val hideCenterIcon = Runnable { binding.centerIcon.animate().alpha(0f).setDuration(200).start() }

    /** 进度轮询。页面不可见就 stopTicking，避免后台一直空转改 UI */
    private val ticker = object : Runnable {
        override fun run() {
            renderProgress()
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    /**
     * 手势：单击切播放/暂停，双击点赞。
     *
     * onDown 必须返回 true，否则 GestureDetector 在第一下 ACTION_DOWN 就放弃这次手势，
     * onSingleTapConfirmed 永远不会来（SimpleOnGestureListener.onDown 默认 false 是个经典坑）。
     * 返回 true 只是「这一下我处理」，并不影响父容器在 MOVE 阶段拦截——所以竖滑照样翻页。
     */
    private val gestureDetector = GestureDetector(
        binding.root.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                togglePlayPause()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                tapLike(animate = true)
                return true
            }
        }
    )

    init {
        // 直接把手势交给根 View；返回值原样用 GestureDetector 的，
        // 且刻意不调用 requestDisallowInterceptTouchEvent —— 一旦调用，ViewPager2 的竖向滑动就被吃掉了。
        binding.root.setOnTouchListener { _, ev -> gestureDetector.onTouchEvent(ev) }

        binding.errorPanel.setOnClickListener { retry() }

        binding.btnEpisodes.setOnClickListener { host.onOpenEpisodes(pagePosition) }
        binding.btnSpeed.setOnClickListener { host.onSpeedClick(pagePosition) }
        binding.btnLine.setOnClickListener { host.onSwitchLineClick(pagePosition) }
        binding.btnLike.setOnClickListener { tapLike(animate = false) }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                // 拖动过程中只改时间文案，不真的 seek：边拖边 seek 在 m3u8 上会疯狂重连
                val duration = currentDuration()
                if (duration > 0) {
                    binding.tvTime.text = formatTime(duration * progress / SEEK_MAX) + " / " + formatTime(duration)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userSeeking = false
                val duration = currentDuration()
                val bar = seekBar ?: return
                if (duration > 0) {
                    player?.seekTo(duration * bar.progress / SEEK_MAX)
                }
            }
        })
    }

    // -----------------------------------------------------------------
    // 绑定 / 回收
    // -----------------------------------------------------------------

    /**
     * 绑定某一集并装载。
     * 起播位置优先用 [ShortDramaPositionStore] 的断点；bookId 为空时一律从头。
     */
    fun bind(item: ShortDramaQueueItem, position: Int) {
        this.item = item
        pagePosition = position
        endedHandled = false
        errorShown = false
        active = false
        binding.errorPanel.visibility = View.GONE
        binding.centerIcon.alpha = 0f
        renderLabels()

        val exo = ensurePlayer()
        val resumeMs = if (host.bookId().isBlank()) {
            0L
        } else {
            ShortDramaPositionStore.positionOf(host.bookId(), item.episodeNum)
        }
        // 只装载不播放：播不播由「这一页是不是当前页」决定
        engine.load(exo, item, resumeMs)
        setActive(host.isActivePage(position))
        renderProgress()
    }

    /** 页面被 ViewPager2 回收：先把进度记下来，再把播放器实例还回池子 */
    fun unbind() {
        savePosition()
        stopTicking()
        handler.removeCallbacksAndMessages(null)
        binding.centerIcon.animate().cancel()
        binding.centerIcon.alpha = 0f

        val exo = player
        player = null
        if (exo != null) {
            exo.removeListener(this)
            exo.playWhenReady = false
            // 先摘掉 PlayerView 的引用再归还，避免下一个使用者复用到仍挂在旧 Surface 上的实例
            binding.playerView.player = null
            engine.recyclePlayer(exo)
        }

        item = null
        pagePosition = RecyclerView.NO_POSITION
        active = false
    }

    /** 成为 / 不再是当前页。重复调用同一状态直接忽略（onPageSelected 会来回触发） */
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) {
            activate()
        } else {
            deactivate()
        }
    }

    private fun activate() {
        val exo = player ?: return
        // 播完的页滑回来：从头的观感比停在最后一帧自然
        if (exo.playbackState == Player.STATE_ENDED) {
            exo.seekTo(0)
            endedHandled = false
        }
        if (!errorShown) {
            exo.playWhenReady = true
        }
        startTicking()
    }

    /** 切走的页：记进度 + 暂停。保留播放位置，滑回来接着看 */
    private fun deactivate() {
        savePosition()
        player?.playWhenReady = false
        stopTicking()
    }

    // -----------------------------------------------------------------
    // 播放器
    // -----------------------------------------------------------------

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val created = engine.obtainPlayer()
        created.addListener(this)
        // 倍速是跨页状态：新页面拿到实例后要立刻跟上，否则滑一页就掉回 1.0x
        created.setPlaybackParameters(PlaybackParameters(host.playbackSpeed()))
        binding.playerView.player = created
        player = created
        return created
    }

    fun setSpeed(speed: Float) {
        player?.setPlaybackParameters(PlaybackParameters(speed))
    }

    /** 倍速 / 线路 / 点赞数变化后，已存在的页面也要跟上 */
    fun refreshLabels() = renderLabels()

    private fun renderLabels() {
        val current = item ?: return
        binding.tvDramaTitle.text = host.dramaTitle()
        binding.tvEpisodeMeta.text = "第${current.episodeNum}集 / 共${host.totalEpisodes()}集"

        val lineName = host.lineNameText()
        binding.tvLineName.text = lineName
        binding.tvLineName.visibility = if (lineName.isBlank()) View.GONE else View.VISIBLE

        binding.tvSpeedValue.text = host.speedLabel()
        binding.tvLikeCount.text = host.likeCount().toString()
        // 只有一条线路时「换线路」是死按钮，直接收掉
        binding.btnLine.visibility = if (host.hasOtherLines()) View.VISIBLE else View.GONE
    }

    // -----------------------------------------------------------------
    // 进度
    // -----------------------------------------------------------------

    private fun startTicking() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun stopTicking() {
        handler.removeCallbacks(ticker)
    }

    private fun currentDuration(): Long {
        val duration = player?.duration ?: return 0L
        return if (duration > 0) duration else 0L
    }

    /**
     * 取当前播放位置与时长。
     * 还没装载（IDLE）或正处在失败态时返回 null：这时候位置一定是 0，
     * 写下去只会把用户辛苦攒的断点清掉。
     */
    fun snapshot(): Pair<Long, Long>? {
        if (errorShown) return null
        val exo = player ?: return null
        if (exo.playbackState == Player.STATE_IDLE) return null
        val position = exo.currentPosition.coerceAtLeast(0L)
        val duration = currentDuration()
        return position to duration
    }

    fun savePosition() {
        val current = item ?: return
        val snap = snapshot() ?: return
        host.recordProgress(current, snap.first, snap.second)
    }

    private fun renderProgress() {
        val exo = player ?: return
        val duration = currentDuration()
        val position = exo.currentPosition.coerceAtLeast(0L)

        if (duration > 0 && !userSeeking) {
            val progress = (position * SEEK_MAX / duration).toInt().coerceIn(0, SEEK_MAX)
            if (binding.seekBar.progress != progress) binding.seekBar.progress = progress
        }

        binding.tvTime.text = if (duration > 0) {
            formatTime(position) + " / " + formatTime(duration)
        } else {
            formatTime(position) + " / --:--"
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0L)
        return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    // -----------------------------------------------------------------
    // 交互
    // -----------------------------------------------------------------

    /**
     * 单击画面：播放/暂停切换。
     * 中间浮出的图标给的是「刚刚做的那个动作」：暂停显示 pause、播放显示 play，
     * 这样用户的注意力在看画面时也能对齐自己的操作。
     */
    private fun togglePlayPause() {
        if (errorShown) {
            retry()
            return
        }
        val exo = player ?: return

        if (exo.playbackState == Player.STATE_ENDED) {
            exo.seekTo(0)
            exo.playWhenReady = true
            endedHandled = false
            flashCenterIcon(R.drawable.sd_ic_play, keepVisible = false)
            startTicking()
            return
        }

        val playing = exo.playWhenReady
        exo.playWhenReady = !playing
        if (playing) {
            // 暂停后让 play 图标留在画面上，用户一眼知道现在不是卡住了
            flashCenterIcon(R.drawable.sd_ic_pause, keepVisible = false)
            savePosition()
        } else {
            flashCenterIcon(R.drawable.sd_ic_play, keepVisible = true)
        }
    }

    private fun flashCenterIcon(iconRes: Int, keepVisible: Boolean) {
        handler.removeCallbacks(hideCenterIcon)
        binding.centerIcon.setImageResource(iconRes)
        binding.centerIcon.animate().cancel()
        binding.centerIcon.alpha = 0f
        binding.centerIcon.animate().alpha(1f).setDuration(120).start()
        if (!keepVisible) handler.postDelayed(hideCenterIcon, CENTER_ICON_HIDE_MS)
    }

    /**
     * 点赞。计数只存在内存里（本页占位交互，不落库也不上报）：
     * 双击画面和点按钮都走这里，区别只是一个有放大回弹动效。
     */
    private fun tapLike(animate: Boolean) {
        val count = host.onLikeTapped(pagePosition)
        binding.tvLikeCount.text = count.toString()
        if (!animate) return
        binding.btnLike.animate().cancel()
        binding.btnLike.animate()
            .scaleX(1.35f).scaleY(1.35f).setDuration(120)
            .withEndAction {
                binding.btnLike.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }
            .start()
    }

    /** 播完。先落一次进度（位置=结尾，PositionStore 会判定为看完并清掉断点），再交给上层决定下一集 */
    private fun handleEnded() {
        if (endedHandled) return
        endedHandled = true
        savePosition()
        stopTicking()
        host.onEpisodeEnded(pagePosition)
    }

    /** 「重播本集」：从 0 开始，不换页 */
    fun replayCurrent() {
        val exo = player ?: return
        errorShown = false
        endedHandled = false
        binding.errorPanel.visibility = View.GONE
        exo.seekTo(0)
        exo.playWhenReady = true
        startTicking()
    }

    /** 失败重试：重新装载这一集，断点位置照旧 */
    private fun retry() {
        val current = item ?: return
        errorShown = false
        endedHandled = false
        binding.errorPanel.visibility = View.GONE
        binding.loadingView.visibility = View.VISIBLE

        val exo = ensurePlayer()
        val resumeMs = if (host.bookId().isBlank()) {
            0L
        } else {
            ShortDramaPositionStore.positionOf(host.bookId(), current.episodeNum)
        }
        engine.load(exo, current, resumeMs)
        if (host.isActivePage(pagePosition)) exo.playWhenReady = true
    }

    // -----------------------------------------------------------------
    // Player.Listener
    // -----------------------------------------------------------------

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_BUFFERING -> {
                if (!errorShown) binding.loadingView.visibility = View.VISIBLE
            }

            Player.STATE_READY -> {
                binding.loadingView.visibility = View.GONE
                // 失败后重新 ready、或看完后拖回去，都要让「播完」可以再报一次
                endedHandled = false
            }

            Player.STATE_ENDED -> {
                binding.loadingView.visibility = View.GONE
                handleEnded()
            }

            Player.STATE_IDLE -> binding.loadingView.visibility = View.GONE
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        binding.loadingView.visibility = View.GONE
        errorShown = true
        stopTicking()
        binding.errorPanel.visibility = View.VISIBLE
        binding.tvErrorHint.visibility = if (host.hasOtherLines()) View.VISIBLE else View.GONE
        binding.tvErrorTip.text = "本集播放失败，点击重试"
    }
}
