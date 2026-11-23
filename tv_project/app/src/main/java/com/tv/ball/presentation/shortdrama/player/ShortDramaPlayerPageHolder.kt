package com.tv.ball.presentation.shortdrama.player

import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.tv.ball.R
import com.tv.ball.databinding.ItemShortDramaPlayerPageBinding
import kotlin.math.abs

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

    /** 剧封面，右侧头像与选集面板缩略图共用；空串表示没有封面 */
    fun episodeCover(): String

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

    /** 倍速锁定是否开启（下滑松手锁定的 2 倍速）；跨页共享，所以存在 Activity 里 */
    fun isSpeedLocked(): Boolean

    /**
     * 切换倍速锁定，返回切换后的状态。
     * 由 Activity 统一把新倍速下发给所有页面并刷新文案，holder 自己不改播放器参数。
     */
    fun onToggleSpeedLock(): Boolean

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
 * - 单击 / 双击 / 双指捏合 / 下拉锁定倍速、进度条轮询、失败重试、中间大图标都在这里，
 *   因为这些都是「这一页」的事；
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

        /** 累计缩小到 0.9 以下就切「完整显示」：手指抖一点不该把画面缩出黑边 */
        private const val ZOOM_OUT_SCALE = 0.9f

        /** 反向张开到 1.1 以上才恢复铺满，和上面留出回差，避免临界点上反复横跳 */
        private const val ZOOM_IN_SCALE = 1.1f

        /** 下拉多少 dp 才算「够阈值」，松手就生效 */
        private const val DRAG_LOCK_DP = 60f

        /** 快进 / 快退一步的时长 */
        private const val STEP_MS = 5_000L

        /** 快进封顶留出的余量：直接 seek 到 duration 会让播放器立刻 ENDED，反而跳集 */
        private const val END_SAFE_GAP_MS = 1_000L
    }

    val itemView: View get() = binding.root

    private val handler = Handler(Looper.getMainLooper())

    /** 当前绑在第几页；未绑定时是 NO_POSITION */
    var pagePosition: Int = RecyclerView.NO_POSITION
        private set

    private var player: ExoPlayer? = null
    private var item: ShortDramaQueueItem? = null
    private var errorShown = false

    /** 播完提示只报一次，避免 ENDED 状态回调重复触发时连翻两集 */
    private var endedHandled = false
    private var active = false

    /** 当前是否处在「缩小看清楚全画面」的状态（FIT + 底部控制条） */
    private var zoomedOut = false

    /** 本次捏合手势的累计缩放比例；onScaleBegin 时归 1，避免两次手势互相叠加 */
    private var pinchAccumScale = 1f

    /** 本次按下到现在是否出现过第二根手指：捏合后抬起不能当成单击去暂停 */
    private var multiTouchInGesture = false

    /** 本次手势是否已经下拉过阈值（松手时据此决定要不要切锁定） */
    private var dragLockArmed = false

    private val hideCenterIcon = Runnable { binding.centerIcon.animate().alpha(0f).setDuration(200).start() }

    private val dragLockThresholdPx: Float
        get() = DRAG_LOCK_DP * binding.root.resources.displayMetrics.density

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
                // 捏合过程中出现过第二根手指：这一下不是「点击画面」，别去暂停
                if (multiTouchInGesture) return true
                togglePlayPause()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (multiTouchInGesture) return true
                tapLike(animate = true)
                return true
            }

            /**
             * 下滑锁定倍速的判定挂在这里。
             *
             * 为什么用 onScroll 而不是自己再写一套 touch 判定：GestureDetector 已经帮我们
             * 吃掉了 touch slop，只有「按住之后真的拖动过」才会回调，单击/双击不会误入。
             * 为什么只认 distanceY：横滑、斜滑都还要留给 ViewPager2 翻页，只有竖向分量
             * 明显占主导时才认为用户在做「下拉」。
             * 这里刻意不去改父容器的触摸拦截（不请求父容器「别拦我」）：一旦拦住，
             * 正常的上滑翻下一页就没了，代价远大于这个手势本身。
             */
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (e1 == null || multiTouchInGesture) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                // 向上拖是翻下一页，斜滑也算翻页：都不进入锁定判定
                if (dy <= 0f || abs(dy) <= abs(dx)) {
                    if (dragLockArmed) disarmDragLock()
                    return false
                }
                val armed = dy >= dragLockThresholdPx
                if (armed != dragLockArmed) {
                    dragLockArmed = armed
                    if (armed) showDragHint() else hideDragHint()
                }
                return false
            }
        }
    )

    /**
     * 双指捏合：累计比例判断放松 / 收紧。
     *
     * 和 [gestureDetector] 并存，触摸事件先喂这个、再喂它：
     * ScaleGestureDetector 需要看到完整的 DOWN/MOVE/POINTER_DOWN 序列，
     * 反过来先喂 GestureDetector 虽然不影响它工作，但读起来容易以为只有一个手势源。
     */
    private val scaleDetector = ScaleGestureDetector(
        binding.root.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinchAccumScale = 1f
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                if (factor.isFinite() && factor > 0f) pinchAccumScale *= factor
                if (!zoomedOut && pinchAccumScale <= ZOOM_OUT_SCALE) {
                    enterZoomedOut()
                } else if (zoomedOut && pinchAccumScale >= ZOOM_IN_SCALE) {
                    exitZoomedOut()
                }
                return true
            }
        }
    )

    init {
        // 直接把手势交给根 View；返回值一律 true（和原来一样只表示「这一下我处理」），
        // 且刻意不去改父容器的触摸拦截：一旦让 ViewPager2 别拦事件，竖向滑动翻页就被吃掉了。
        binding.root.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    multiTouchInGesture = false
                    // 新手势开始，上一轮的下拉状态作废（上次可能在按住中途被父容器 cancel 掉）
                    disarmDragLock()
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    multiTouchInGesture = true
                    // 捏合和「下拉锁定」互斥：第二根手指一出现就把下拉状态作废，
                    // 否则「先下拖、再捏合、最后松手」会顺手把倍速锁上
                    disarmDragLock()
                }

                MotionEvent.ACTION_UP -> {
                    // 只有「本页自己收到 UP」才算松手：被 ViewPager2 抢走时来的是 CANCEL，
                    // 那种情况是用户在翻页，不该顺手把倍速锁上
                    if (dragLockArmed) toggleSpeedLock()
                    disarmDragLock()
                }

                MotionEvent.ACTION_CANCEL -> disarmDragLock()
            }
            // 先喂捏合，再喂点击/滑动
            scaleDetector.onTouchEvent(ev)
            gestureDetector.onTouchEvent(ev)
            true
        }

        binding.errorPanel.setOnClickListener { retry() }

        binding.btnEpisodes.setOnClickListener { host.onOpenEpisodes(pagePosition) }
        binding.btnSpeed.setOnClickListener { host.onSpeedClick(pagePosition) }
        binding.btnLine.setOnClickListener { host.onSwitchLineClick(pagePosition) }
        binding.btnLike.setOnClickListener { tapLike(animate = false) }

        // 评论 / 收藏 / 分享 / 拍同款 / 关注：全部是本地占位，不联网也不落库。
        // 这些数据没有后端，做出来只会是假数字，所以只给一句「暂未开放」。
        binding.btnComment.setOnClickListener { toastPlaceholder() }
        binding.btnFavorite.setOnClickListener { toastPlaceholder() }
        binding.btnShare.setOnClickListener { toastPlaceholder() }
        binding.btnShoot.setOnClickListener { toastPlaceholder() }
        binding.tvFollowBadge.setOnClickListener { toastPlaceholder() }
        binding.ivAvatar.setOnClickListener { toastPlaceholder() }

        // 底部缩放控制条
        binding.btnZoomClose.setOnClickListener { exitZoomedOut() }
        binding.btnBack5.setOnClickListener { seekBy(-STEP_MS) }
        binding.btnForward5.setOnClickListener { seekBy(STEP_MS) }
        binding.btnBarPlay.setOnClickListener {
            togglePlayPause()
            renderZoomBar()
        }
        binding.btnBarSpeed.setOnClickListener { host.onSpeedClick(pagePosition) }

        // 控制条要贴着屏幕最底，但底下还有手势条 / 三键导航。写死 dp 在不同机型上
        // 不是被压住就是悬空，所以按真实 systemBars inset 补底部间距。
        ViewCompat.setOnApplyWindowInsetsListener(binding.zoomBar) { view, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val params = view.layoutParams as ViewGroup.MarginLayoutParams
            if (params.bottomMargin != bottom) {
                params.bottomMargin = bottom
                view.layoutParams = params
            }
            insets
        }

        // 进度条不接拖动：抖音短剧这条是纯展示（布局里已设 clickable/enabled=false）。
        // 一集一两分钟，拖动时间的收益远小于「误触把进度拖走」的代价；
        // 真要跳时间用控制条的 ±5 秒，那个按钮界明确、失败也不会把进度搞乱。
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
        // 每一页都从「铺满 + 无控制条」开始：页面被复用时上一轮可能停在缩小状态，
        // 不清掉会出现「刚滑到这一集就是黑边」的莫名其妙观感
        zoomedOut = false
        pinchAccumScale = 1f
        multiTouchInGesture = false
        disarmDragLock()
        binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        binding.zoomBar.visibility = View.GONE
        // 上一轮若停在缩小态，bottomInfo 也被藏过；不复位就会「这一集底部什么都没有」
        binding.bottomInfo.visibility = View.VISIBLE
        renderLabels()
        renderAvatar()

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
        binding.dragHintPill.visibility = View.GONE
        binding.zoomBar.visibility = View.GONE
        disarmDragLock()

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
        // 倍速是跨页状态：新页面拿到实例后要立刻跟上，否则滑一页就掉回 1.0x。
        // 锁定状态下 host.playbackSpeed() 返回的就是锁定值，这里不用再判一次。
        created.setPlaybackParameters(PlaybackParameters(host.playbackSpeed()))
        binding.playerView.player = created
        player = created
        renderZoomBar()
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

        binding.tvLikeCount.text = host.likeCount().toString()
        // 只有一条线路时「换线路」是死按钮，直接收掉
        binding.btnLine.visibility = if (host.hasOtherLines()) View.VISIBLE else View.GONE
        renderSpeedLabels()
    }

    /**
     * 倍速文案（右侧栏圆圈 + 底部控制条）。
     *
     * 锁定态单独写死成 2.0x：锁定期间 host 报的档位还是用户原来选的那一档，
     * 不覆盖的话「画面按 2 倍速跑、按钮写 1.25x」会自相矛盾。
     * 圆圈里塞两行（2.0x / 锁定）而不是一行「2.0x 锁定」，是因为那一格只有 36dp。
     */
    private fun renderSpeedLabels() {
        val locked = host.isSpeedLocked()
        binding.tvSpeedValue.text = if (locked) "2.0x\n锁定" else host.speedLabel()
        binding.tvSpeedValue.contentDescription = if (locked) "倍速 2.0x 已锁定" else "倍速 ${host.speedLabel()}"
        binding.btnBarSpeed.text = if (locked) "2.0x" else host.speedLabel()
        binding.btnSpeed.contentDescription =
            if (locked) "倍速 2.0x 锁定，点击切换档位" else "切换倍速，当前 ${host.speedLabel()}"
    }

    /** 右侧头像用剧封面裁圆：这是「占位头像」，不请求任何用户资料 */
    private fun renderAvatar() {
        val cover = host.episodeCover()
        if (cover.isBlank()) {
            binding.ivAvatar.setImageDrawable(null)
            return
        }
        Glide.with(binding.ivAvatar)
            .load(cover)
            .circleCrop()
            .into(binding.ivAvatar)
    }

    // -----------------------------------------------------------------
    // 捏合缩放 + 底部控制条
    // -----------------------------------------------------------------

    /**
     * 双指缩小：画面改成完整显示（fit），底部浮出控制条。
     *
     * 为什么用 FIT 而不是「缩小到某个比例」：ExoPlayer 的 resize mode 是五种固定策略，
     * 没有任意缩放；FIT 在竖屏上的实际效果就是「画面完整收进屏幕、上下留黑边」，
     * 正好等于用户捏合缩小想要的观感，黑边也正好让控制条有地方待着。
     */
    private fun enterZoomedOut() {
        if (zoomedOut) return
        zoomedOut = true
        binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        // 抖音缩放态底部让给控制条：剧名/集数那块先淡出，否则和控制条挤在一起
        binding.bottomInfo.visibility = View.GONE
        renderZoomBar()
        binding.zoomBar.visibility = View.VISIBLE
    }

    /** 放大回到铺满，收起控制条。✕ 按钮走的就是这里 */
    private fun exitZoomedOut() {
        if (!zoomedOut) return
        zoomedOut = false
        pinchAccumScale = 1f
        binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        binding.zoomBar.visibility = View.GONE
        binding.bottomInfo.visibility = View.VISIBLE
    }

    /** 控制条上的图标 / 文案要和当前状态一致（播放中显示暂停图标） */
    private fun renderZoomBar() {
        val playing = player?.isPlaying == true
        binding.btnBarPlay.setImageResource(if (playing) R.drawable.sd_ic_pause else R.drawable.sd_ic_play)
        binding.btnBarPlay.contentDescription = if (playing) "暂停" else "播放"
        renderSpeedLabels()
    }

    /**
     * 按增量 seek。快退到负数归 0；
     * 快进越过结尾时停在「结尾前一秒」——直接落到 duration 会立刻触发 STATE_ENDED，
     * 用户看到的不是快进到结尾，而是被自动翻到下一集。
     */
    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        if (errorShown) return
        val duration = currentDuration()
        val target = exo.currentPosition + deltaMs
        val clamped = when {
            target <= 0L -> 0L
            duration > 0L && target > duration - END_SAFE_GAP_MS ->
                (duration - END_SAFE_GAP_MS).coerceAtLeast(0L)

            else -> target
        }
        exo.seekTo(clamped)
        endedHandled = false
        renderProgress()
    }

    // -----------------------------------------------------------------
    // 下滑锁定 2 倍速
    // -----------------------------------------------------------------

    private fun showDragHint() {
        binding.dragHintPill.text = if (host.isSpeedLocked()) {
            "∨ 下滑松手解除 2 倍速锁定"
        } else {
            "∨ 下滑松手锁定 2 倍速"
        }
        binding.dragHintPill.visibility = View.VISIBLE
    }

    private fun hideDragHint() {
        binding.dragHintPill.visibility = View.GONE
    }

    private fun disarmDragLock() {
        dragLockArmed = false
        hideDragHint()
    }

    /**
     * 松手后真正切换锁定。倍速本身由 Activity 下发（跨页状态），
     * 这里只负责触发 + 提示，不去直接改播放器参数，否则滑到别的页就丢了。
     */
    private fun toggleSpeedLock() {
        val locked = host.onToggleSpeedLock()
        hideDragHint()
        renderLabels()
        Toast.makeText(
            binding.root.context,
            if (locked) "已锁定 2 倍速" else "已解除倍速锁定",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun toastPlaceholder() {
        Toast.makeText(binding.root.context, "暂未开放", Toast.LENGTH_SHORT).show()
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
        // 时长还没解析出来时不动进度条：这个阶段按 0/0 算会把条打回 0，看着像「回到开头」
        if (duration <= 0) return
        val position = exo.currentPosition.coerceAtLeast(0L)
        val progress = (position * SEEK_MAX / duration).toInt().coerceIn(0, SEEK_MAX)
        if (binding.seekBar.progress != progress) binding.seekBar.progress = progress
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
        renderZoomBar()
    }

    /** 播放/暂停图标跟着实际状态走（缓冲结束、外部暂停都会走这里） */
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        renderZoomBar()
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
