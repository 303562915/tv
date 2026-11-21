package com.tv.ball.presentation.nav

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tv.ball.R
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 悬浮胶囊底栏：逐条对照 FlClash `NavigationDock` / `FloatingNavigationBar`
 * （`.tmp_flclash/prism_ui/lib/navigation_dock.dart`）重做。
 *
 * 与 FlClash 一一对应的机制：
 * 1. 几何：胶囊高 62dp、内边距 4dp；**窄胶囊居中悬浮**，宽度上限
 *    `count >= 5 ? 全宽 : count * 86 + 4*4`（3 项 = 274dp），见 [onMeasure]。
 * 2. `_dockLayout`：pitch = (span - 2*padding)/count，透镜宽 = pitch + 2*padding；
 *    放不下 84dp 时走 closed 间距 + inset 的第二分支。
 * 3. `_lensRect`：lensWidth = (lens + 16*lift)*(1+stretch)，
 *    lensHeight = (barHeight + 16*lift)*(1 - stretch*1.4)。
 * 4. `_Lens`：底色 `@color/nav_lens`（浮动层比胶囊深一级），抬升时叠 onSurface@8%*lift。
 * 5. `dockShadows`：两层柔和阴影（24dp/8dp、3dp/1dp），深色强度 ×3 → 用 elevation 6/10dp。
 * 6. 按下：整条 bar 轻微膨胀（ElasticPress，growth = min(0.25, 16/最长边)）。
 * 7. 拖动：跟手、跨项 haptic、两端 rubber band 拉伸。
 * 8. 松手：`_release` → 透镜弹到落点，抬满并到位后 `_dropLift` 才落下。
 * 9. 甩动：`_fling`，100ms 速度投影，最多越过一项。
 * 10. hitSlop：落点离胶囊 8dp 内仍命中，按 pill 的 spine 重投影（x 夹到 [radius, width-radius]）。
 * 11. 文字：图标 28dp、间距 2dp、label 10sp / w500 / 行高 14.5sp 单行省略；
 *     放不进 pitch - 2*labelInset 时整体等比缩到最小 9sp。
 * 12. 底部间距：`_bottomMarginOf = max(21dp, navigationBarInset)`，见窗口 insets 监听。
 *
 * 颜色只用资源 token：选中 `@color/theme_primary`，未选中 `@color/nav_item_idle`
 * （= onSurfaceVariant），胶囊 `@color/nav_capsule`，透镜 `@color/nav_lens`。
 */
class FloatingNavDock @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        // ---- 与 FlClash navigation_dock.dart 同名同值的常量（dp）----
        private const val BAR_HEIGHT = 62f
        private const val BAR_PADDING = 4f
        private const val EDGE_MARGIN = 21f
        private const val SHADOW_ROOM = 8f
        private const val HIT_SLOP = 8f
        private const val MAX_ITEM_EXTENT = 86f
        private const val MIN_LENS_WIDTH = 84f
        private const val FULL_WIDTH_COUNT = 5
        private const val ICON_SIZE = 28f
        private const val LABEL_GAP = 2f
        private const val LABEL_INSET = 2f
        private const val LABEL_SIZE = 10f
        private const val LABEL_LEADING = 14.5f
        private const val MIN_LABEL_SIZE = 9f
        private const val LABEL_LETTER_SPACING = 0.01f
        private const val PRESS_GROWTH = 0.125f // 1/8
        private const val MAX_PRESS_GROWTH = 16f
        private const val LENS_GROWTH = 8f
        private const val JELLY_ASPECT = 1.4f
        private const val JELLY_STRETCH = 0.2f
        private const val JELLY_SQUASH = 0.15f
        private const val OVERDRAG = 0.35f
        private const val PULL_LIMIT = 0.21875f // 7/32
        private const val FLING_PROJECTION_MS = 100f

        // ---- 动画（Android 上用克制曲线近似 FlClash 弹簧）----
        // travel 400ms bounce .15 → 320ms + Overshoot(.55)
        // lift 250ms：升起用 Decelerate，落下用 Overshoot(.45) 260ms
        // press spring 311 / ratio .593 ≈ 140ms
        private const val TRAVEL_MS = 320L
        private const val LIFT_UP_MS = 200L
        private const val LIFT_DOWN_MS = 260L
        private const val PRESS_MS = 140L
        private const val SETTLE_MS = 200L
    }

    data class Destination(@DrawableRes val icon: Int, val label: String)

    /** 与 FlClash 的 onSelected 对应：只有“松手提交”才会回调。 */
    var onSelected: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dpf(value: Float): Float = value * density
    private fun dp(value: Float): Int = (value * density).roundToInt()
    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val barRadius = dpf(BAR_HEIGHT) / 2f
    private val barPadding = dpf(BAR_PADDING)
    private val lensGrowth = dpf(LENS_GROWTH)
    private val minLensWidth = dpf(MIN_LENS_WIDTH)

    private val labelTypeface: Typeface by lazy {
        Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val measurePaint = TextPaint().apply {
        isAntiAlias = true
        // 与 label 的 w500 一致，否则量出来的宽度偏窄、缩放阈值失真。
        typeface = labelTypeface
        letterSpacing = LABEL_LETTER_SPACING
        textSize = sp(LABEL_SIZE)
    }

    private val activeColor by lazy { ContextCompat.getColor(context, R.color.theme_primary) }
    private val idleColor by lazy { ContextCompat.getColor(context, R.color.nav_item_idle) }
    private val capsuleColor by lazy { ContextCompat.getColor(context, R.color.nav_capsule) }

    private val isDark: Boolean by lazy {
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    // ---------------------------------------------------------------- 视图

    /** 胶囊底 + 悬浮阴影（outline 为圆角胶囊，elevation 决定柔和度）。 */
    private val capsuleView = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = barRadius
            setColor(capsuleColor)
        }
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val radius = min(view.width, view.height) / 2f
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        clipToOutline = false
        // dockShadows：浅色 8%/4% 两层 → 6dp；深色强度 ×3 → 10dp。
        elevation = dpf(if (isDark) 10f else 6f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val alpha = if (isDark) 180 else 80
            val shadow = Color.argb(alpha, 0, 0, 0)
            outlineAmbientShadowColor = shadow
            outlineSpotShadowColor = shadow
        }
    }

    /** 选中透镜：纯色全圆角（bg_nav_lens），抬满时由胶囊底透出更深一级的浮动层感。 */
    private val lens = View(context).apply {
        background = ContextCompat.getDrawable(context, R.drawable.bg_nav_lens)?.mutate()
    }

    private val itemsContainer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val itemViews = mutableListOf<LinearLayout>()
    private val iconViews = mutableListOf<ImageView>()
    private val labelViews = mutableListOf<TextView>()

    private var destinations: List<Destination> = emptyList()

    /** 以「项索引」为单位的透镜位置（0..count-1，可带小数）。 */
    private var lensPosition = 0f
    private var lensAnimator: ValueAnimator? = null

    /** lift：0 = 静止，1 = 抬满（透镜上下各多 8dp）。 */
    private var lift = 0f
    private var liftAnimator: ValueAnimator? = null

    /** stretch：FlClash 的 jelly——透镜随速度/刹车横向拉伸。 */
    private var jelly = 0f
    private var lastJellyPos = 0f
    private var lastJellyTime = 0L
    private var lastJellySpeed = 0f

    /** pull：拖动越界时整条 bar 的 rubber band 拉伸。 */
    private var pull = 0f
    private var pullAnimator: ValueAnimator? = null

    /** 按下时整条 bar 的膨胀（ElasticPress）。 */
    private var pressSwell = 0f
    private var pressAnimator: ValueAnimator? = null

    private var dropping = false
    private var selectedIndex = 0
    private var pressedIndex: Int? = null
    private var pressX = 0f
    private var dragging = false
    private var pointerId = -1
    private var labelSizePx = -1f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // 甩动的速度投影。
    private var lastMoveX = 0f
    private var lastMoveTime = 0L
    private var velocityX = 0f

    private val barRect = RectF()
    private val lensRectF = RectF()

    private val dropLift = Runnable {
        if (dropping && pointerId == -1) {
            dropping = false
            liftTo(0f, LIFT_DOWN_MS, OvershootInterpolator(0.45f))
        }
    }

    /** 胶囊的 Z（阴影高度）：上层视图必须比它高，否则会被胶囊整片盖住。 */
    private val shadowZ = dpf(if (isDark) 10f else 6f)

    init {
        addView(capsuleView, LayoutParams(0, 0))
        addView(lens, LayoutParams(0, 0))
        addView(itemsContainer, LayoutParams(0, 0))

        // FrameLayout 里带 elevation 的兄弟按 Z 排序绘制，不按 addView 顺序：
        // 胶囊有 6/10dp elevation（用来出阴影），不把透镜和图标行抬到它上面的话，
        // 整条底栏只剩一块纯色胶囊——图标、文字、选中透镜全被盖住。
        lens.translationZ = shadowZ + dpf(1f)
        itemsContainer.translationZ = shadowZ + dpf(2f)

        clipChildren = false
        clipToPadding = false

        // 对应 NavigationDock 的 Padding(LTRB: edge, shadowRoom, edge, bottomMargin)：
        // 左右 8dp 给 hitSlop 与阴影，顶部 8dp 给阴影，底部 = max(21, navigationBarInset)。
        setPadding(dp(HIT_SLOP), dp(SHADOW_ROOM), dp(HIT_SLOP), dp(EDGE_MARGIN))

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            applyBottomMargin(insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or
                    WindowInsetsCompat.Type.displayCutout()
            ).bottom)
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(dropLift)
        lensAnimator?.cancel()
        liftAnimator?.cancel()
        pullAnimator?.cancel()
        pressAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    /** `_bottomMarginOf = max(21, navigationBarInset)`。 */
    private fun applyBottomMargin(insetBottom: Int) {
        val bottom = max(dp(EDGE_MARGIN), insetBottom)
        if (paddingBottom != bottom) {
            setPadding(paddingLeft, paddingTop, paddingRight, bottom)
        }
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
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                includeFontPadding = false
                typeface = labelTypeface
                letterSpacing = LABEL_LETTER_SPACING
                setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_SIZE)
            }
            column.addView(icon, LinearLayout.LayoutParams(dp(ICON_SIZE), dp(ICON_SIZE)))
            column.addView(
                label,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(LABEL_LEADING).coerceAtLeast(1)
                ).apply {
                    topMargin = dp(LABEL_GAP)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            )
            // 触摸由 dock 统一处理（onTouchEvent）；这里的点击只服务键盘 / 遥控器确认键。
            column.setOnClickListener { commitSelection(index) }
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
        resetJelly()
        labelSizePx = -1f
        renderItems()
        requestLayout()
    }

    fun getSelected(): Int = selectedIndex

    fun setSelected(index: Int, animate: Boolean = true) {
        if (destinations.isEmpty()) return
        val target = index.coerceIn(0, destinations.size - 1)
        val changed = target != selectedIndex
        selectedIndex = target
        renderItems()
        moveLensTo(target.toFloat(), animate)
        if (changed && animate) liftThenDrop()
    }

    // ---------------------------------------------------------------- 量测
    // FlClash：ConstrainedBox(maxWidth: count >= 5 ? ∞ : count*86 + padding*4) 外套居中 Row。
    // 这里等价实现为「onMeasure 夹住内容宽度，水平居中由 ConstraintLayout 的左右约束完成」。

    private fun maxContentWidth(): Int {
        val count = destinations.size
        return if (count in 1 until FULL_WIDTH_COUNT) {
            (count * dpf(MAX_ITEM_EXTENT) + barPadding * 4f).roundToInt()
        } else {
            Int.MAX_VALUE
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val hPad = paddingLeft + paddingRight
        val vPad = paddingTop + paddingBottom
        val barHeight = dp(BAR_HEIGHT).coerceAtLeast(1)

        val availWidth = MeasureSpec.getSize(widthMeasureSpec)
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        // Row 的左右 _edgeMargin 由 dock 的左右 padding 承担，这里再扣 2*(21-8)。
        val edgeRoom = 2 * (dp(EDGE_MARGIN) - dp(HIT_SLOP))
        val maxAvail = when (mode) {
            MeasureSpec.UNSPECIFIED -> dpf(MAX_ITEM_EXTENT).roundToInt() *
                max(1, destinations.size) + barPadding.roundToInt() * 4
            else -> (availWidth - hPad - edgeRoom).coerceAtLeast(0)
        }
        val contentWidth = min(maxAvail, maxContentWidth())

        val exactly = MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY)
        val exactH = MeasureSpec.makeMeasureSpec(barHeight, MeasureSpec.EXACTLY)
        capsuleView.measure(exactly, exactH)
        lens.measure(exactly, exactH)
        itemsContainer.measure(exactly, exactH)

        setMeasuredDimension(
            View.resolveSize(contentWidth + hPad, widthMeasureSpec),
            View.resolveSize(barHeight + vPad, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val contentLeft = paddingLeft
        val contentTop = paddingTop
        val contentWidth = right - left - paddingLeft - paddingRight
        val barHeight = bottom - top - paddingTop - paddingBottom
        if (contentWidth <= 0 || barHeight <= 0) return

        capsuleView.layout(contentLeft, contentTop, contentLeft + contentWidth, contentTop + barHeight)

        // items 行 = 胶囊内部再让出 barPadding（对应 Surface 的 Padding(all: _barPadding)）。
        val insetPad = dockLayout().inset.roundToInt()
        if (itemsContainer.paddingLeft != insetPad || itemsContainer.paddingRight != insetPad) {
            itemsContainer.setPadding(insetPad, 0, insetPad, 0)
        }
        val rowLeft = contentLeft + barPadding.roundToInt()
        val rowWidth = contentWidth - (barPadding * 2f).roundToInt()
        itemsContainer.measure(
            MeasureSpec.makeMeasureSpec(rowWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(barHeight, MeasureSpec.EXACTLY)
        )
        itemsContainer.layout(rowLeft, contentTop, rowLeft + rowWidth, contentTop + barHeight)

        // 抬升时透镜会画出胶囊轮廓，靠 clipChildren=false（init 已设）实现。
        syncLabelSize()
        // 每次布局后重刷一次颜色：setDestinations 可能发生在 attach 之前，
        // 那时 icon/label 还没量过，颜色容易丢。
        renderItems()
        layoutLens()
    }

    // ---------------------------------------------------------------- 几何（_dockLayout / _lensRect）

    private class DockLayout(val pitch: Float, val lens: Float, val inset: Float)

    /** 对应 `_dockLayout(span, count)`，span = 胶囊宽度 - 2*barPadding。 */
    private fun dockLayout(): DockLayout {
        val span = (width - paddingLeft - paddingRight - barPadding * 2f).coerceAtLeast(0f)
        val count = destinations.size
        if (count <= 1) {
            val pitch = (span - barPadding * 2f).coerceAtLeast(0f)
            return DockLayout(pitch = pitch, lens = span, inset = barPadding)
        }
        val pitch = (span - barPadding * 2f).coerceAtLeast(0f) / count
        if (pitch + barPadding * 2f >= minLensWidth) {
            return DockLayout(pitch = pitch, lens = pitch + barPadding * 2f, inset = barPadding)
        }
        val lensWidth = min(minLensWidth, span)
        val closed = (span - lensWidth) / (count - 1)
        return DockLayout(pitch = closed, lens = lensWidth, inset = (lensWidth - closed) / 2f)
    }

    /**
     * 对应 `_lensRect()`：
     * lensWidth = (lens + 16*lift) * (1 + stretch)
     * lensHeight = (barHeight + 16*lift) * (1 - stretch*1.4)
     */
    private fun computeLensRect(): RectF {
        val layout = dockLayout()
        val barHeight = dpf(BAR_HEIGHT)
        val growth = lensGrowth * 2f * lift
        val lensW = (layout.lens + growth) * (1f + jelly)
        val lensH = (barHeight + growth) * (1f - jelly * JELLY_ASPECT)
        val rowLeft = paddingLeft + barPadding + layout.inset
        // 越界拖动时整条 bar 由 translationX 平移（对应 _PressTransform 的 pull），
        // 透镜不额外偏移，避免双重位移。
        val centerX = rowLeft + (lensPosition + 0.5f) * layout.pitch
        val top = paddingTop + (barHeight - lensH) / 2f
        val w = lensW.coerceAtLeast(1f)
        val h = lensH.coerceAtLeast(1f)
        lensRectF.set(centerX - w / 2f, top, centerX + w / 2f, top + h)
        return lensRectF
    }

    private fun layoutLens() {
        if (width <= 0 || destinations.isEmpty()) return
        val r = computeLensRect()
        val l = r.left.roundToInt()
        val t = r.top.roundToInt()
        lens.layout(l, t, l + r.width().roundToInt(), t + r.height().roundToInt())
    }

    private fun positionAt(x: Float): Float {
        if (destinations.isEmpty()) return 0f
        val layout = dockLayout()
        if (layout.pitch <= 0f) return 0f
        val rowLeft = paddingLeft + barPadding + layout.inset
        val position = (x - rowLeft) / layout.pitch - 0.5f
        val last = (destinations.size - 1).toFloat()
        return when {
            position < 0f -> rubberBand(position, OVERDRAG)
            position > last -> last + rubberBand(position - last, OVERDRAG)
            else -> position
        }
    }

    private fun indexAt(position: Float): Int =
        position.roundToInt().coerceIn(0, max(0, destinations.size - 1))

    /** `_rubberBand`：越过端点后逐渐变硬。 */
    private fun rubberBand(overshoot: Float, limit: Float): Float {
        if (limit <= 0f) return 0f
        val amount = limit * (1f - 1f / (abs(overshoot) * 0.55f / limit + 1f))
        return if (overshoot < 0f) -amount else amount
    }

    // ---------------------------------------------------------------- 手势

    private fun barBounds(): RectF {
        barRect.set(
            paddingLeft.toFloat(),
            paddingTop.toFloat(),
            (width - paddingRight).toFloat(),
            (height - paddingBottom).toFloat()
        )
        return barRect
    }

    /**
     * `_HitSlop`：落点离胶囊 8dp 内仍命中；命中点按 pill 的 spine 重投影
     * （y 投到中线、x 夹到 [radius, width-radius]），圆角端点外侧也能按到端点项。
     */
    private fun slopPositionFor(x: Float, y: Float): Float? {
        val bar = barBounds()
        val slop = dpf(HIT_SLOP)
        if (x < bar.left - slop || x > bar.right + slop ||
            y < bar.top - slop || y > bar.bottom + slop
        ) {
            return null
        }
        val radius = bar.height() / 2f
        val spineX = x.coerceIn(bar.left + radius, bar.right - radius)
        return positionAt(spineX)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = destinations.isNotEmpty()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (destinations.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(0)
                pressX = event.x
                lastMoveX = event.x
                lastMoveTime = event.eventTime
                velocityX = 0f
                dragging = false
                removeCallbacks(dropLift)
                dropping = false
                val position = slopPositionFor(event.x, event.y) ?: positionAt(event.x)
                val index = indexAt(position)
                pressedIndex = index
                swell(true)
                // `_press`：按下即吸到该项并抬满。
                liftTo(1f, LIFT_UP_MS, DecelerateInterpolator())
                moveLensTo(index.toFloat(), animate = false)
                applyPull(event.x)
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.getPointerId(0) != pointerId) return true
                val dt = (event.eventTime - lastMoveTime).coerceAtLeast(1L)
                velocityX = (event.x - lastMoveX) / dt * 1000f
                lastMoveX = event.x
                lastMoveTime = event.eventTime
                if (!dragging) {
                    if (abs(event.x - pressX) < touchSlop) return true
                    dragging = true
                }
                // `_slide`：跟手 + 跨项 haptic + 两端拉伸。
                val position = positionAt(event.x)
                lensAnimator?.cancel()
                lensPosition = position
                layoutLens()
                val index = indexAt(position)
                if (index != pressedIndex) {
                    pressedIndex = index
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                applyPull(event.x)
            }

            MotionEvent.ACTION_UP -> {
                if (event.getPointerId(0) != pointerId) return true
                pointerId = -1
                resetPull()
                swell(false)
                val committed = fling() ?: pressedIndex ?: selectedIndex
                pressedIndex = null
                dragging = false
                // `_release(commit: true)`
                commitSelection(committed)
            }

            MotionEvent.ACTION_CANCEL -> {
                pointerId = -1
                resetPull()
                swell(false)
                val hadPress = pressedIndex != null
                pressedIndex = null
                dragging = false
                // `_release(commit: false)`：透镜回到原选中项。
                if (hadPress) {
                    moveLensTo(selectedIndex.toFloat(), animate = true)
                    liftThenDrop()
                }
            }
        }
        return true
    }

    /** `_fling`：按 100ms 速度投影，最多越过当前项一项。 */
    private fun fling(): Int? {
        val pressed = pressedIndex ?: return null
        if (!dragging) return null
        val pitch = dockLayout().pitch
        if (pitch <= 0f) return null
        val lead = velocityX / pitch * (FLING_PROJECTION_MS / 1000f)
        val index = (lensPosition + lead).roundToInt()
            .coerceIn(pressed - 1, pressed + 1)
            .coerceIn(0, max(0, destinations.size - 1))
        if (index != pressed) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        return index
    }

    private fun commitSelection(index: Int) {
        val changed = index != selectedIndex
        if (changed) {
            selectedIndex = index
            renderItems()
            onSelected?.invoke(index)
        }
        moveLensTo(index.toFloat(), animate = true)
        liftThenDrop()
    }

    /** `_dropLift`：等透镜到位（travel 结束）后才落下。 */
    private fun liftThenDrop() {
        dropping = true
        removeCallbacks(dropLift)
        postDelayed(dropLift, TRAVEL_MS)
    }

    private fun moveLensTo(target: Float, animate: Boolean) {
        lensAnimator?.cancel()
        if (!animate) {
            lensPosition = target
            resetJelly()
            layoutLens()
            return
        }
        lastJellyTime = 0L
        lensAnimator = ValueAnimator.ofFloat(lensPosition, target).apply {
            duration = TRAVEL_MS
            interpolator = OvershootInterpolator(0.55f)
            addUpdateListener {
                lensPosition = it.animatedValue as Float
                noteJelly()
                layoutLens()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    resetJelly()
                    layoutLens()
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    resetJelly()
                    layoutLens()
                }
            })
            start()
        }
    }

    private fun liftTo(target: Float, duration: Long, interpolator: TimeInterpolator) {
        liftAnimator?.cancel()
        liftAnimator = ValueAnimator.ofFloat(lift, target).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener {
                lift = (it.animatedValue as Float).coerceIn(0f, 1f)
                layoutLens()
            }
            start()
        }
    }

    /** 速度 → jelly：快则横向拉伸，急停则压缩（_wobble 的简化实现）。 */
    private fun noteJelly() {
        val now = System.nanoTime()
        if (lastJellyTime != 0L) {
            val dt = ((now - lastJellyTime) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
            val speed = abs(lensPosition - lastJellyPos) / dt
            val braking = max(0f, lastJellySpeed - speed)
            val target =
                JELLY_STRETCH * saturate(speed / 3f) - JELLY_SQUASH * saturate(braking / 25f)
            jelly += (target - jelly) * 0.35f
            lastJellySpeed = if (speed < 0.05f) 0f else speed
        }
        lastJellyPos = lensPosition
        lastJellyTime = now
    }

    private fun resetJelly() {
        jelly = 0f
        lastJellyPos = lensPosition
        lastJellyTime = 0L
        lastJellySpeed = 0f
    }

    private fun saturate(value: Float): Float = 1f - 2f / (exp(2f * value) + 1f)

    /** ElasticPress：growth = min(2/8, 16/side)，整条 bar 随按下轻微膨胀。 */
    private fun swell(pressed: Boolean) {
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressSwell, if (pressed) 1f else 0f).apply {
            duration = if (pressed) PRESS_MS else SETTLE_MS
            interpolator = if (pressed) OvershootInterpolator(0.6f) else DecelerateInterpolator()
            addUpdateListener {
                pressSwell = it.animatedValue as Float
                applyPressTransform()
            }
            start()
        }
    }

    private fun applyPressTransform() {
        val side = (width - paddingLeft - paddingRight).toFloat()
        if (side <= 0f) return
        val growth = if (pressSwell <= 0f) {
            0f
        } else {
            pressSwell * min(PRESS_GROWTH * 2f, MAX_PRESS_GROWTH * density / side)
        }
        scaleX = 1f + growth
        scaleY = 1f + growth
        translationX = pull
    }

    private fun applyPull(x: Float) {
        val bar = barBounds()
        val overshoot = when {
            x < bar.left -> x - bar.left
            x > bar.right -> x - bar.right
            else -> 0f
        }
        val next = rubberBand(overshoot, bar.height() * PULL_LIMIT)
        if (abs(next - pull) < 0.0005f) return
        pull = next
        layoutLens()
        applyPressTransform()
    }

    private fun resetPull() {
        pullAnimator?.cancel()
        if (abs(pull) < 0.0005f) return
        pullAnimator = ValueAnimator.ofFloat(pull, 0f).apply {
            duration = SETTLE_MS
            interpolator = OvershootInterpolator(0.6f)
            addUpdateListener {
                pull = it.animatedValue as Float
                layoutLens()
                applyPressTransform()
            }
            start()
        }
    }

    // ---------------------------------------------------------------- 内容

    /** 10sp 打底；最宽 label 放不进 pitch - 2*labelInset 时整体等比缩到最小 9sp。 */
    private fun syncLabelSize() {
        if (labelViews.isEmpty() || width <= 0) return
        val layout = dockLayout()
        val room = layout.pitch - dpf(LABEL_INSET) * 2f
        val widest = labelViews.maxOfOrNull { view ->
            measurePaint.textSize = sp(LABEL_SIZE)
            measurePaint.measureText(view.text.toString())
        } ?: 0f
        val scale = when {
            widest <= 0f || room <= 0f || widest <= room -> 1f
            else -> max(room / widest, MIN_LABEL_SIZE / LABEL_SIZE)
        }
        val sizePx = sp(LABEL_SIZE * scale)
        if (abs(sizePx - labelSizePx) < 0.01f) return
        labelSizePx = sizePx
        labelViews.forEach { view ->
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        }
    }

    private fun renderItems() {
        iconViews.forEachIndexed { index, icon ->
            val selected = index == selectedIndex
            // 图标本身是全白描边矢量图，必须显式 SRC_IN 上色，否则在浅色胶囊上看不见。
            icon.setColorFilter(
                if (selected) activeColor else idleColor,
                PorterDuff.Mode.SRC_IN
            )
            icon.alpha = 1f
            labelViews.getOrNull(index)?.let { label ->
                label.setTextColor(if (selected) activeColor else idleColor)
                label.alpha = 1f
            }
        }
    }
}