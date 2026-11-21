package com.tv.ball.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 左滑露出右侧操作区的容器（不依赖第三方库）。
 *
 * 两个子 View 的分工：
 * - 一个「内容区」：宽度 match_parent，跟着手指左右平移，用户看到的卡片就是它；
 * - 一个「操作区」：固定宽度（例如 64dp），贴在右边，平时被内容区完整盖住。
 *
 * 为了不把下标写死，onFinishInflate 里按「固定宽度的那个 = 操作区」来认，
 * 认完再把内容区 bringChildToFront：内容必须画在上层，否则收起状态下红色操作区
 * 会从卡片右侧盖出来。布局里先写操作区、后写卡片最直观，也符合这个 z 序。
 *
 * 手势上的几个要点（都是踩过的坑）：
 * - 只在「有意义的反方向」上接管：收起态只跟左滑（dx<0），展开态只跟右滑（dx>0），
 *   否则用户从半开状态往左甩会把操作区甩到屏幕外，露出空白。
 * - dy > dx 时一律不拦：竖直手势要原样交给父级 RecyclerView，不然列表滚不动。
 * - 展开态点击内容区：先把事件抢过来，抬手时收起并吞掉这次点击，
 *   避免「收起的同时还顺手触发了条目点击」这种双动作。
 * - 被父级（RecyclerView / ItemTouchHelper）抢走手势收到 ACTION_CANCEL 时，
 *   直接把位移复位到展开前的状态，不留半开姿势。
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

    /** 操作区宽度（px），由 layout_width 读出来，也可以外部 setActionWidth 指定 */
    private var actionWidth = 0

    private var opened = false
    private var stateListener: OnRevealStateChangedListener? = null

    /** 内容区：跟着手指平移的那个孩子 */
    var contentView: View? = null
        private set

    /**
     * 是否接管横向手势。直接删除模式下由外部关掉：
     * 容器和 ItemTouchHelper 都盯同一次左滑时会互相抢，出现「先被推开几十像素再被拿走复位」的抖动。
     */
    var swipeEnabled: Boolean = true
        set(value) {
            field = value
            // 关掉手势时顺带把已经展开的行收起，避免切模式后残留
            if (!value && opened) close()
        }

    /** 操作区：贴在右边、被内容区盖住的那个孩子 */
    private var actionView: View? = null

    private var downX = 0f
    private var downY = 0f
    private var dragStartTranslation = 0f
    private var dragging = false
    private var tapToClose = false
    private var velocityTracker: VelocityTracker? = null

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
        contentView?.translationX = 0f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (actionWidth > 0) {
            // 宽度变化（首帧 / 横竖屏）后把位移拉回合法区间，别停在越界位置
            contentView?.translationX = if (opened) -actionWidth.toFloat() else 0f
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // RecyclerView 回收复用时复位展开态，避免滚出去再滚回来还露着删除区
        contentView?.animate()?.cancel()
        contentView?.translationX = 0f
        opened = false
        dragging = false
        tapToClose = false
        releaseVelocityTracker()
    }

    /** 操作区宽度：优先用代码指定，没指定就取 action 的 layout_width */
    fun setActionWidth(px: Int) {
        if (px <= 0 || px == actionWidth) return
        actionWidth = px
        contentView?.translationX = if (opened) -actionWidth.toFloat() else 0f
        requestLayout()
    }

    fun open() = animateTo(-actionWidth.toFloat(), true)

    fun close() = animateTo(0f, false)

    fun isOpened(): Boolean = opened

    fun setOnRevealStateChangedListener(listener: OnRevealStateChangedListener?) {
        stateListener = listener
    }

    private fun animateTo(target: Float, open: Boolean) {
        val content = contentView ?: return
        // 打断上一次动画，连续点两下不会出现两个状态互相打架
        content.animate().cancel()
        content.animate()
            .translationX(target)
            .setDuration(ANIM_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        if (opened != open) {
            opened = open
            stateListener?.onRevealStateChanged(this, open)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!swipeEnabled) {
            // 直接删除模式：容器不接横向手势，整条事件链留给 ItemTouchHelper
            if (opened) close()
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
                // 正在回弹的动画不用等，手指按下去就接管
                contentView?.animate()?.cancel()

                // 已展开且点在内容区：先把这条事件链抢过来，抬手时收起；
                // 点在右侧操作区上就不抢，让删除按钮正常收到点击
                if (opened && ev.x < width - actionWidth) {
                    tapToClose = true
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
            if (opened) close()
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
                content.translationX = target.coerceIn(-actionWidth.toFloat(), 0f)
                return true
            }

            MotionEvent.ACTION_UP -> {
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
                tapToClose = false
                dragging = false
                releaseVelocityTracker()
                content.translationX = if (opened) -actionWidth.toFloat() else 0f
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
        if (shouldOpen) open() else close()
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
        const val ANIM_DURATION_MS = 180L
        const val VELOCITY_UNITS = 1000
        /** 拖过操作区宽度的 40% 就认展开 */
        const val OPEN_THRESHOLD = 0.4f
    }
}
