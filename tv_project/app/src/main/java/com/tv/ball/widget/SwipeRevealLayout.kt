package com.tv.ball.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 左滑露出右侧操作区的容器（不依赖第三方库）。
 *
 * 两个子 View 的分工：
 * - 一个「内容区」：宽度 match_parent，跟着手指左右平移，用户看到的卡片就是它；
 * - 一个「操作区」：固定宽度（例如 64dp），贴着右边，平时被推到容器右侧外面、完全看不见。
 *
 * 为了不把下标写死，onFinishInflate 里按「固定宽度的那个 = 操作区」来认，
 * 认完再把内容区 bringChildToFront：内容必须画在上层，否则收起状态下红色操作区
 * 会从卡片右侧盖出来。布局里先写操作区、后写卡片最直观，也符合这个 z 序。
 *
 * ── 为什么必须自己写 onMeasure（「滑不动」的真正原因）──
 * 布局给操作区写的是 layout_height="match_parent"，而外层这里是 layout_height="wrap_content"。
 * 如果不重写 onMeasure，FrameLayout 的默认测量会把 match_parent 的操作区当成
 * 「高度最多给到父级可用高度」（AT_MOST = RecyclerView 给整条列表的可用高度），
 * 于是每个 item 都被撑到接近一屏高；列表每次滚动都在重排/重算，手指按下去时
 * 条目还在长高，横向手势根本走不完就被判定成竖向滚动，表现出来就是「滑不动、闪」。
 * 所以这里先量内容区（真实高度由卡片决定），再用 EXACTLY 把内容区高度塞给操作区，
 * 最后把自己高度钉成内容区高度：高度只由内容决定，操作区再也不会反向撑高条目。
 *
 * ── 为什么会闪（旧实现的坑，别踩回去）──
 * 旧版 onSizeChanged 里无条件把 translationX 写回 0 或 -actionWidth。
 * RecyclerView 滚动 / 复用 / 高度重排都会触发 sizeChanged，拖动过程中也会触发，
 * 于是「刚滑出 30px 又被写回 0」，肉眼看到的就是滑一点、弹回来、再滑一点。
 * 现在的规则是：只有 actionWidth 或高度**真的变了**才校正一次，且拖动中绝不写 translationX。
 *
 * ── 手势上的其它要点 ──
 * - 只在「有意义的反方向」上接管：收起态只跟左滑（dx<0），展开态只跟右滑（dx>0），
 *   否则用户从半开状态往左甩会把操作区甩到屏幕外，露出空白。
 * - dy > dx 时一律不拦：竖直手势要原样交给父级 RecyclerView，不然列表滚不动。
 * - 一旦真的开始横向拖动，立刻 requestDisallowInterceptTouchEvent(true)，
 *   否则 ItemTouchHelper / RecyclerView 会在中途把手势抢走，我们只收到 CANCEL 然后复位。
 * - 展开态点击内容区：先把事件抢过来，抬手时收起并吞掉这次点击，
 *   避免「收起的同时还顺手触发了条目点击」这种双动作。
 */
class SwipeRevealLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 展开 / 收起状态变化回调（可选，用来做「同时只允许一项展开」） */
    interface OnRevealStateChangedListener {
        fun onRevealStateChanged(layout: SwipeRevealLayout, opened: Boolean)
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity

    /** 收尾动画的插值器：1.6 比默认更「起步快、尾巴长」，跟手感和回弹手感更接近 */
    private val settleInterpolator = DecelerateInterpolator(DECELERATE_FACTOR)

    /** 操作区宽度（px），由 layout_width 读出来，也可以外部 setActionWidth 指定 */
    private var actionWidth = 0

    private var opened = false
    private var stateListener: OnRevealStateChangedListener? = null

    /** 内容区：跟着手指平移的那个孩子 */
    var contentView: View? = null
        private set

    /** 操作区：贴在右边、被内容区盖住（并被推出容器外）的那个孩子 */
    private var actionView: View? = null

    /**
     * 是否接管横向手势。直接删除模式下由外部关掉：
     * 容器和 ItemTouchHelper 都盯同一次左滑时会互相抢，出现「先被推开几十像素再被拿走复位」的抖动。
     */
    var swipeEnabled: Boolean = true
        set(value) {
            field = value
            // 关掉手势时立刻把姿态收干净（不留半开、不残留操作区位移），让 ItemTouchHelper 接手
            if (!value) resetToRestImmediately()
        }

    private var downX = 0f
    private var downY = 0f
    private var dragStartTranslation = 0f
    private var dragging = false
    private var tapToClose = false
    private var velocityTracker: VelocityTracker? = null

    /** 当前正在跑的收尾动画；拖动 / 新的收尾都要先把它打断 */
    private var revealAnimator: ValueAnimator? = null

    /** 上次测量出来的高度与宽度，用来判断「尺寸是不是真的变了」 */
    private var lastWidth = -1
    private var lastHeight = -1
    private var lastActionWidth = -1

    override fun onFinishInflate() {
        super.onFinishInflate()
        if (childCount < 2) return

        val first = getChildAt(0)
        val second = getChildAt(1)
        val firstFixedWidth = (first.layoutParams?.width ?: 0) > 0
        val secondFixedWidth = (second.layoutParams?.width ?: 0) > 0
        // 固定宽度的那个是操作区；两个都不是固定宽度时退回「第 0 个内容、第 1 个操作区」的约定
        val firstIsAction = firstFixedWidth && !secondFixedWidth
        actionView = if (firstIsAction) first else second
        contentView = if (firstIsAction) second else first

        if (actionWidth <= 0) {
            val width = actionView?.layoutParams?.width ?: 0
            if (width > 0) actionWidth = width
        }
        // 内容区必须在上层，收起时才盖得住操作区
        contentView?.let { bringChildToFront(it) }
        // 初始姿态：内容在原位，操作区整块推到容器右侧之外（不可见、也不抢点击）
        applyProgress(0f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val content = contentView
        val action = actionView
        if (content == null || action == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        // 1. 内容区按父级给的约束正常量：它才是条目高度的唯一来源
        measureChild(content, widthMeasureSpec, heightMeasureSpec)
        val contentHeight = content.measuredHeight

        // 2. 操作区高度和内容区**完全相等**：用 EXACTLY 顶掉 layout_height="match_parent"
        //    原本会拿到的 AT_MOST(整列表可用高度)，条目就不会被删除区撑成一屏高
        measureChild(
            action,
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY)
        )

        // 3. 自身高度锁成内容区高度
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), contentHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val sizeChanged = w != lastWidth || h != lastHeight || actionWidth != lastActionWidth
        lastWidth = w
        lastHeight = h
        lastActionWidth = actionWidth
        // 尺寸没真的变（滚动重排常见）就别动位移——旧版在这里无条件复位，正是闪动的来源
        if (!sizeChanged) return
        // 拖动中绝不动 translationX：手指的位置必须由手指说了算
        if (dragging) return
        if (actionWidth <= 0 || contentView == null) return

        // 真的变了（首帧 / 横竖屏 / 操作区宽度变化）：打断动画，content 与 action 一起校正
        revealAnimator?.cancel()
        revealAnimator = null
        applyProgress(if (opened) 1f else 0f)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // RecyclerView 回收复用时复位展开态，避免滚出去再滚回来还露着删除区
        // 注意：这里不回调 stateListener，避免回收路径上触发 adapter 的状态维护
        resetToRestImmediately(notify = false)
    }

    /** 操作区宽度：优先用代码指定，没指定就取 action 的 layout_width */
    fun setActionWidth(px: Int) {
        if (px <= 0 || px == actionWidth) return
        revealAnimator?.cancel()
        revealAnimator = null
        actionWidth = px
        lastActionWidth = px
        applyProgress(if (opened) 1f else 0f)
        requestLayout()
    }

    fun open() = animateTo(1f, open = true, velocityX = 0f)

    fun close() = animateTo(0f, open = false, velocityX = 0f)

    fun isOpened(): Boolean = opened

    fun setOnRevealStateChangedListener(listener: OnRevealStateChangedListener?) {
        stateListener = listener
    }

    // ─────────────────────────── 位移驱动 ───────────────────────────

    /**
     * 唯一的位移入口：content ∈ [-actionWidth, 0]，action 同步放在 actionWidth + content 处。
     * 这样拖动时删除区是从右侧被卡片「推」进来的，而不是原地冒出来。
     */
    private fun applyOffset(contentX: Float) {
        val aw = actionWidth.toFloat()
        val x = contentX.coerceIn(-aw, 0f)
        contentView?.translationX = x
        actionView?.translationX = aw + x
    }

    /** 0 = 完全收起，1 = 完全展开；content 与 action 由同一个 fraction 驱动，保证两轴严格同步 */
    private fun applyProgress(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        applyOffset(-actionWidth * p)
    }

    private fun setOpened(open: Boolean) {
        if (opened == open) return
        opened = open
        stateListener?.onRevealStateChanged(this, open)
    }

    /**
     * 收尾到目标状态。
     * 时长按「剩余距离 + 甩速」算，clamp 在 120~220ms；有甩速时距离/速度估时，甩得越快越早停。
     */
    private fun animateTo(targetProgress: Float, open: Boolean, velocityX: Float) {
        val content = contentView
        setOpened(open)
        if (content == null || actionWidth <= 0) {
            applyProgress(targetProgress)
            return
        }
        revealAnimator?.cancel()
        revealAnimator = null

        val target = targetProgress.coerceIn(0f, 1f)
        val from = (-content.translationX / actionWidth).coerceIn(0f, 1f)
        val remaining = abs(target - from) * actionWidth
        if (remaining < MIN_VISIBLE_DISTANCE_PX) {
            // 几乎已经在目标位置：直接落位，不开动画（避免「抖一下」）
            applyProgress(target)
            return
        }

        val animator = ValueAnimator.ofFloat(from, target)
        animator.duration = computeDuration(remaining, velocityX)
        animator.interpolator = settleInterpolator
        animator.addUpdateListener { animation ->
            applyProgress(animation.animatedValue as Float)
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                // 被打断的旧动画不许落位，否则会把新动画的进度写回旧目标
                if (revealAnimator !== animator) return
                if (!cancelled) applyProgress(target)
                revealAnimator = null
            }
        })
        revealAnimator = animator
        animator.start()
    }

    private fun computeDuration(remainingPx: Float, velocityX: Float): Long {
        val speed = abs(velocityX)
        val ratio = (remainingPx / actionWidth).coerceIn(0f, 1f)
        val raw = if (speed > minFlingVelocity) {
            // 有甩速：按物理距离/速度估时，甩得越快动画越短
            remainingPx / speed * 1000f * FLING_DURATION_FACTOR
        } else {
            // 纯拖拽松手：距离越远给的时间越充裕
            MIN_DURATION_MS + (MAX_DURATION_MS - MIN_DURATION_MS) * ratio
        }
        return raw.toLong().coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
    }

    /** 立刻回到初始姿态并清掉所有进行中的状态（复用 / 关手势 / 打开新的一行时用） */
    private fun resetToRestImmediately(notify: Boolean = true) {
        revealAnimator?.cancel()
        revealAnimator = null
        dragging = false
        tapToClose = false
        releaseVelocityTracker()
        applyOffset(0f)
        if (notify) {
            setOpened(false)
        } else {
            opened = false
        }
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    // ─────────────────────────── 手势 ───────────────────────────

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!swipeEnabled) {
            // 直接删除模式：容器不接横向手势，整条事件链留给 ItemTouchHelper，同时把姿态收干净
            if (opened || (contentView?.translationX ?: 0f) != 0f) resetToRestImmediately()
            return false
        }
        if (actionWidth <= 0 || contentView == null) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                tapToClose = false
                releaseVelocityTracker()
                // 正在回弹的动画不用等，手指按下去就接管（取消的动画不会写回目标位置）
                revealAnimator?.cancel()
                revealAnimator = null

                // 已展开且点在内容区：先把这条事件链抢过来，抬手时收起；
                // 点在右侧操作区上就不抢，让删除按钮正常收到点击
                if (opened && ev.x < width - actionWidth) {
                    tapToClose = true
                    // 同样禁止父级插嘴，否则可能收不到 UP，收起动作走不完
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (tapToClose) return true
                val dx = ev.x - downX
                val dy = ev.y - downY
                // 竖向手势优先让给 RecyclerView：dy 更大就不拦，否则列表滚不动
                if (abs(dy) > abs(dx)) return false
                if (abs(dx) <= touchSlop) return false

                // 收起态只跟左滑，展开态只跟右滑
                val shouldHandle = if (opened) dx > 0 else dx < 0
                if (shouldHandle) {
                    dragging = true
                    dragStartTranslation = contentView?.translationX ?: 0f
                    // 真的接管了：禁止父级（RecyclerView / ItemTouchHelper）中途抢走
                    parent?.requestDisallowInterceptTouchEvent(true)
                    startVelocityTracker(ev)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 手势关掉时不能 return false：那会打断父级链路，走 super 把事件交回去
        if (!swipeEnabled) {
            if (opened || (contentView?.translationX ?: 0f) != 0f) resetToRestImmediately()
            return super.onTouchEvent(event)
        }
        val content = contentView ?: return super.onTouchEvent(event)
        if (actionWidth <= 0) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 能走到这里的 DOWN 只有「展开态点内容区」那条链路，交给 UP 决定要不要收起
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (tapToClose) return true
                if (!dragging) return true
                velocityTracker?.addMovement(event)
                // 位移限制在 [-actionWidth, 0]：往左最多露出操作区，往右不会越过原位
                val target = dragStartTranslation + (event.x - downX)
                applyOffset(target)
                return true
            }

            MotionEvent.ACTION_UP -> {
                // 无论走哪条分支，抬手都把手势拦截权还给父级
                parent?.requestDisallowInterceptTouchEvent(false)
                if (tapToClose) {
                    tapToClose = false
                    // 收起并消费这次点击：不再把点击转给卡片
                    close()
                    return true
                }
                if (dragging) {
                    dragging = false
                    settle()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                // 父级把手势抢走了（例如直接删除模式的 ItemTouchHelper）：回到展开前的状态
                parent?.requestDisallowInterceptTouchEvent(false)
                tapToClose = false
                dragging = false
                releaseVelocityTracker()
                applyProgress(if (opened) 1f else 0f)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 抬手判定：位移过半或甩得够快就认展开，两个条件互相兜底，短促快滑也算 */
    private fun settle() {
        val content = contentView ?: return
        val offset = content.translationX
        val velocityX = velocityTracker?.let {
            it.computeCurrentVelocity(VELOCITY_UNITS)
            it.xVelocity
        } ?: 0f
        releaseVelocityTracker()

        val shouldOpen = offset <= -actionWidth * OPEN_THRESHOLD || velocityX <= -minFlingVelocity
        animateTo(if (shouldOpen) 1f else 0f, open = shouldOpen, velocityX = velocityX)
    }

    private fun startVelocityTracker(ev: MotionEvent) {
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        tracker.clear()
        tracker.addMovement(ev)
    }

    private fun releaseVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private companion object {
        /** 收尾动画时长上下限（ms）：太短像瞬移，太长像粘住 */
        const val MIN_DURATION_MS = 120L
        const val MAX_DURATION_MS = 220L
        /** 有甩速时按距离/速度估时再乘一个系数，让动画略长于纯物理时间，观感更顺 */
        const val FLING_DURATION_FACTOR = 1.6f
        /** DecelerateInterpolator 的减速强度 */
        const val DECELERATE_FACTOR = 1.6f
        /** 剩余距离小于 1px 视为已到位，直接落位不开动画 */
        const val MIN_VISIBLE_DISTANCE_PX = 1f
        const val VELOCITY_UNITS = 1000
        /** 拖过操作区宽度的 40% 就认展开 */
        const val OPEN_THRESHOLD = 0.4f
    }
}