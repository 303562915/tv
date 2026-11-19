package com.tv.ball.presentation.nav

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.tv.ball.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 仿 FlClash `NavigationDock` / `FloatingNavigationBar` 的悬浮胶囊底栏。
 *
 * 移植自 prism_ui/lib/navigation_dock.dart，保留其关键机制：
 * 1. 布局：胶囊高 62dp、内边距 4dp、每项等宽；透镜宽 = 每项宽 + 8dp（左右各溢出 4dp）。
 * 2. 透镜位置 = _lensRect()：以“项索引”为单位的浮点位置，可介于两项之间。
 * 3. 按下即把透镜吸到按下的那项，并抬起（lift）——透镜上下同时长大 8dp*2，凸出胶囊边缘。
 * 4. 拖动跟手（超过 touchSlop 才生效），跨项时给一次 selection haptic。
 * 5. 松手：落点项提交选中；透镜先弹到落点，抬起的透镜等它到位（_dropLift）再落下。
 * 6. 甩动：按 100ms 速度投影，最多越过一项（_fling）。
 * 7. 拖动到两端时胶囊横向拉伸（rubber band），松手回弹。
 */
class FloatingNavDock @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        // 与 FlClash 一致的常量（dp）
        private const val BAR_HEIGHT_DP = 62
        private const val BAR_PADDING_DP = 4
        private const val LENS_GROWTH = 16f // _lensGrowth * 2：抬满时上下各多 8dp
        private const val FLING_PROJECTION_MS = 100f
        private const val TRAVEL_MS = 280L
        private const val LIFT_MS = 220L
        private const val LIFT_SNAP = 0.975f
        private const val LABEL_SP = 10f
        private const val MIN_LABEL_SP = 9f
    }

    data class Destination(@DrawableRes val icon: Int, val label: String)

    /** 与 FlClash 的 onSelected 对应：只有“松手提交”才会回调。 */
    var onSelected: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(value: Number): Int = (value.toFloat() * density).roundToInt()

    private val barPadding = dp(BAR_PADDING_DP)
    private val lensGrowth = dp(LENS_GROWTH)

    private val lens = View(context).apply {
        background = ContextCompat.getDrawable(context, R.drawable.bg_nav_lens)
        elevation = dp(2).toFloat()
    }

    private val itemsContainer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(barPadding, 0, barPadding, 0)
    }

    private val itemViews = mutableListOf<LinearLayout>()
    private val iconViews = mutableListOf<ImageView>()
    private val labelViews = mutableListOf<TextView>()

    private var destinations: List<Destination> = emptyList()

    /** 以“项索引”为单位的透镜位置（0..count-1，可带小数）。 */
    private var lensPosition = 0f
    private var lensAnimator: ValueAnimator? = null

    /** lift：0 = 静止，1 = 抬满（透镜凸出胶囊）。 */
    private var lift = 0f
    private var liftAnimator: ValueAnimator? = null
    private var dropping = false

    private var selectedIndex = 0
    private var pressedIndex: Int? = null
    private var pressX = 0f
    private var dragging = false
    private var pointerIndex = -1
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val activeColor by lazy { ContextCompat.getColor(context, R.color.theme_primary) }
    private val idleColor by lazy { ContextCompat.getColor(context, R.color.nav_item_idle) }

    init {
        addView(lens, LayoutParams(dp(84), dp(BAR_HEIGHT_DP - BAR_PADDING_DP * 2)))
        addView(
            itemsContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        clipChildren = false
        clipToPadding = false
    }

    // ---------------------------------------------------------------- 公共 API

    fun setDestinations(list: List<Destination>) {
        destinations = list
        itemsContainer.removeAllViews()
        itemViews.clear()
        iconViews.clear()
        labelViews.clear()

        list.forEachIndexed { index, destination ->
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
            }
            val icon = ImageView(context).apply {
                setImageResource(destination.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = destination.label
            }
            val label = TextView(context).apply {
                text = destination.label
                textSize = LABEL_SP
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setAutoSizeTextTypeUniformWithConfiguration(
                    MIN_LABEL_SP.toInt(), LABEL_SP.toInt(), 1,
                    android.widget.TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM
                )
            }
            column.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))
            column.addView(
                label,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            )
            column.setOnClickListener {
                if (index != selectedIndex) {
                    setSelected(index, animate = true)
                    onSelected?.invoke(index)
                }
            }
            itemsContainer.addView(
                column,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            )
            itemViews += column
            iconViews += icon
            labelViews += label
        }

        selectedIndex = selectedIndex.coerceIn(0, max(0, list.size - 1))
        lensPosition = selectedIndex.toFloat()
        requestLayout()
        renderItems()
    }

    fun getSelected(): Int = selectedIndex

    fun setSelected(index: Int, animate: Boolean = true) {
        if (destinations.isEmpty()) return
        val target = index.coerceIn(0, destinations.size - 1)
        val changed = target != selectedIndex
        selectedIndex = target
        renderItems()
        moveLensTo(target.toFloat(), animate)
        if (changed) liftThenDrop()
    }

    // ---------------------------------------------------------------- 手势

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (destinations.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerIndex = event.getPointerId(0)
                pressX = event.x
                dragging = false
                // 对应 _press()：按下就把透镜吸到按下的项并抬起
                val index = indexAt(positionAt(event.x))
                pressedIndex = index
                dropping = false
                moveLensTo(index.toFloat(), animate = false)
                animateLift(1f)
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.getPointerId(0) != pointerIndex) return true
                if (!dragging) {
                    if (abs(event.x - pressX) < touchSlop) return true
                    dragging = true
                }
                // 对应 _slide()：跟手 + 跨项 haptic + 两端拉伸
                val position = positionAt(event.x)
                setLensPositionDirect(position)
                val index = indexAt(position)
                if (index != pressedIndex) {
                    pressedIndex = index
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                applyStretch(event.x)
            }

            MotionEvent.ACTION_UP -> {
                if (event.getPointerId(0) != pointerIndex) return true
                pointerIndex = -1
                resetStretch()
                val committed = pressedIndex ?: selectedIndex
                pressedIndex = null
                dragging = false
                // 对应 _release(commit: true)
                commitSelection(committed)
            }

            MotionEvent.ACTION_CANCEL -> {
                pointerIndex = -1
                resetStretch()
                pressedIndex = null
                dragging = false
                commitSelection(selectedIndex)
            }
        }
        return true
    }

    private fun commitSelection(index: Int) {
        dropping = true
        moveLensTo(index.toFloat(), animate = true)
        if (index != selectedIndex) {
            setSelected(index, animate = true)
        } else {
            liftThenDrop()
        }
    }

    /** 对应 _dropLift()：等透镜到位后才允许抬起的透镜落下。 */
    private fun liftThenDrop() {
        dropping = true
        postDelayed({ if (dropping) { dropping = false; animateLift(0f) } }, 120)
    }

    // ---------------------------------------------------------------- 布局/绘制

    private fun itemWidth(): Int {
        if (itemViews.isEmpty()) return 0
        val available = width - barPadding * 2
        return if (available <= 0) 0 else available / itemViews.size
    }

    private fun positionAt(x: Float): Float {
        val w = itemWidth().toFloat()
        if (w <= 0f) return 0f
        val raw = (x - barPadding - w / 2f) / w
        val last = (destinations.size - 1).toFloat()
        return raw.coerceIn(0f, last)
    }

    private fun indexAt(position: Float): Int =
        position.roundToInt().coerceIn(0, max(0, destinations.size - 1))

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        layoutItems()
        layoutLens()
    }

    private fun layoutItems() {
        // itemsContainer 由 LinearLayout 自行布局，这里只需保证高度撑满
        itemsContainer.layout(
            barPadding, 0,
            width - barPadding, height
        )
    }

    private fun layoutLens() {
        val w = itemWidth()
        if (w <= 0) return
        val lensWidth = w + dp(8)
        val lensHeight = (height - barPadding * 2) + (lensGrowth * lift).roundToInt()
        val centerX = barPadding + (lensPosition + 0.5f) * w
        val left = (centerX - lensWidth / 2f).roundToInt()
        val top = (height - lensHeight) / 2
        lens.layout(left, top, left + lensWidth, top + lensHeight)
        lens.pivotX = lensWidth / 2f
        lens.pivotY = lensHeight / 2f
    }

    private fun setLensPositionDirect(position: Float) {
        lensAnimator?.cancel()
        lensPosition = position
        layoutLens()
    }

    private fun moveLensTo(target: Float, animate: Boolean) {
        lensAnimator?.cancel()
        if (!animate) {
            lensPosition = target
            layoutLens()
            return
        }
        val start = lensPosition
        lensAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = TRAVEL_MS
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener {
                lensPosition = it.animatedValue as Float
                layoutLens()
            }
            start()
        }
    }

    private fun animateLift(target: Float) {
        liftAnimator?.cancel()
        val start = lift
        liftAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = if (target > start) 140L else LIFT_MS
            interpolator = if (target > start) DecelerateInterpolator() else OvershootInterpolator(0.9f)
            addUpdateListener {
                lift = it.animatedValue as Float
                layoutLens()
            }
            start()
        }
    }

    private fun applyStretch(x: Float) {
        val overshoot = x - x.coerceIn(0f, width.toFloat())
        if (overshoot == 0f) {
            resetStretch()
            return
        }
        val pull = overshoot / width * 7f / 32f
        scaleX = 1f + pull
        translationX = overshoot * 0.35f
    }

    private fun resetStretch() {
        animate().scaleX(1f).translationX(0f).setDuration(220).start()
    }

    private fun renderItems() {
        iconViews.forEachIndexed { index, icon ->
            val selected = index == selectedIndex
            icon.setColorFilter(if (selected) activeColor else idleColor)
            labelViews[index].setTextColor(if (selected) activeColor else idleColor)
            labelViews[index].alpha = if (selected) 1f else 0.85f
        }
    }
}

