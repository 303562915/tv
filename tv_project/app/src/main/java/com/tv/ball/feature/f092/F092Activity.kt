package com.tv.ball.feature.f092

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF092Binding
import com.tv.ball.util.CustomToast
import java.util.Locale

/**
 * 「内存与线程探针」页。
 *
 * 三段：
 * 1. **当前值**：堆内存（已用 / 上限 · 占比）+ 线程数 + 样本覆盖长度，每秒刷新；
 * 2. **最近 5 分钟**：一条自绘迷你折线（[F092ChartView]），绿线是堆内存、蓝线是线程数，右端圆点是"现在"；
 * 3. **判定与导出**：阈值可调，窗口涨幅超过阈值就把提示条点亮；导出按钮把缓冲导成纯文本，可一键复制。
 *
 * ## 定时器
 *
 * 用 `Handler` 自循环而不是 `Timer` / 协程：采样必须在主线程碰 UI，`Handler` 直接把回调送回主线程，
 * 省掉一层切线程；`postDelayed(this, 1000)` 是"跑完再排下一次"，页面忙的时候采样会自动拉稀，
 * 不会像 `scheduleAtFixedRate` 那样积一堆任务排队补跑。
 *
 * **onStop 停、onStart 续**：页面不可见时继续采样毫无意义——用户看不到曲线，却还在耗 CPU 和电。
 * 切回来时缓冲里还留着之前的样本，正好接着画，不会从空白重新开始。
 *
 * ## 为什么自绘
 *
 * 一条 300 点的折线，引一整个图表库（再加上它的动画、手势、字体）不划算：电视端的曲线只要
 * "看得出有没有持续向上"，[F092ChartView] 六十行 `Path` 就够，还能直接吃全站的 `theme_*` 颜色。
 */
class F092Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF092Binding

    private lateinit var chart: F092ChartView

    /** 主线程定时器：每次回调采一条，然后把下一次排进队列。 */
    private val handler = Handler(Looper.getMainLooper())

    /** 定时器是否在跑。onStop 置 false，回调看到它就不会再排下一次。 */
    private var autoSampling = false

    /** 增长判定阈值（MB），页面上 ± 按钮改的就是它。 */
    private var thresholdMb = F092Engine.DEFAULT_GROWTH_MB

    /** 导出区是否已经渲染过内容：没渲染前显示说明，渲染后显示文本。 */
    private var exportShown = false

    private val tick = object : Runnable {
        override fun run() {
            sampleOnce()
            if (autoSampling) handler.postDelayed(this, F092Engine.SAMPLE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF092Binding.inflate(layoutInflater)
        setContentView(binding.root)

        thresholdMb = savedInstanceState?.getFloat(KEY_THRESHOLD, F092Engine.DEFAULT_GROWTH_MB)
            ?.coerceIn(F092Engine.MIN_THRESHOLD_MB, F092Engine.MAX_THRESHOLD_MB)
            ?: F092Engine.DEFAULT_GROWTH_MB

        binding.btnBack.setOnClickListener { finish() }

        // 曲线是运行时塞进宿主的：自绘 View 不需要 XML 标签，也就不用在布局里写全限定类名。
        chart = F092ChartView(this)
        binding.f092ChartHost.addView(
            chart,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        binding.f092ThresholdDown.setOnClickListener { stepThreshold(-F092Engine.THRESHOLD_STEP_MB) }
        binding.f092ThresholdUp.setOnClickListener { stepThreshold(F092Engine.THRESHOLD_STEP_MB) }
        binding.f092ExportButton.setOnClickListener { exportNow() }
        binding.f092CopyButton.setOnClickListener { copyExport() }
        binding.f092ResetButton.setOnClickListener { resetSamples() }

        renderThreshold()
        // 进页面立刻采一条：不用等一秒才看到第一组数字。
        sampleOnce()
    }

    override fun onStart() {
        super.onStart()
        autoSampling = true
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, F092Engine.SAMPLE_INTERVAL_MS)
    }

    override fun onStop() {
        autoSampling = false
        handler.removeCallbacks(tick)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putFloat(KEY_THRESHOLD, thresholdMb)
    }

    // ===== 采样与渲染 =====

    /** 采一条、留档、刷新三段显示。定时器和"进页面先采一条"走的是同一条路径。 */
    private fun sampleOnce() {
        val sample = F092Engine.sample()
        F092Engine.append(sample)
        renderLive(sample)
        chart.submit(F092Engine.window())
        renderTrend()
    }

    /** 当前值那一块。堆占比按 60% / 80% 两档换色，逼近上限时不用算就知道要看一眼。 */
    private fun renderLive(sample: F092Engine.Sample) {
        binding.f092HeapText.text = if (sample.hasHeapCeiling) {
            getString(
                R.string.f092_heap_value,
                sample.heapUsedMb,
                sample.heapMaxMb,
                percentLabel(sample.heapRatio)
            )
        } else {
            getString(R.string.f092_heap_value_nocap, sample.heapUsedMb)
        }
        binding.f092HeapText.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    !sample.hasHeapCeiling -> R.color.theme_primary
                    sample.heapRatio >= 0.8f -> R.color.theme_error
                    sample.heapRatio >= 0.6f -> R.color.theme_warning
                    else -> R.color.theme_primary
                }
            )
        )

        binding.f092ThreadText.text = getString(R.string.f092_thread_value, sample.threadCount)

        val count = F092Engine.snapshot().size
        binding.f092SpanText.text = getString(
            R.string.f092_span_value,
            count,
            F092Engine.RING_CAPACITY,
            F092Engine.spanLabel(F092Engine.windowSpanMs())
        )
    }

    /**
     * 判定区：一句话结论 + 线程变化。
     *
     * 判定完全交给 [F092Engine.trend]——页面不自己算"涨了多少"，否则阈值、窗口、最短跨度
     * 这三条规则就会在两处各写一遍，迟早对不上。
     */
    private fun renderTrend() {
        val trend = F092Engine.trend(thresholdMb)
        val growing = trend.growing

        binding.f092TrendText.text = getString(
            if (growing) R.string.f092_trend_growing else R.string.f092_trend_stable,
            signedMb(trend.deltaMb),
            mb(thresholdMb)
        )
        binding.f092TrendText.setTextColor(
            ContextCompat.getColor(this, if (growing) R.color.theme_error else R.color.theme_success)
        )

        val threadDelta = F092Engine.threadDelta()
        binding.f092ThreadDeltaText.text = getString(R.string.f092_thread_delta, signedInt(threadDelta))

        // 提示条只在异常时出现：正常的时候页面应该是安静的，不需要一行"一切正常"占着地方。
        binding.f092AlertText.visibility = if (growing) View.VISIBLE else View.GONE
        binding.f092AlertText.text = getString(R.string.f092_alert_body, mb(thresholdMb))
    }

    // ===== 阈值 =====

    /**
     * 调阈值。
     *
     * 撞到上下限就明确说一句，而不是静默无效——遥控器按了几下没反应，用户只会以为按钮坏了。
     */
    private fun stepThreshold(delta: Float) {
        val next = (thresholdMb + delta)
            .coerceIn(F092Engine.MIN_THRESHOLD_MB, F092Engine.MAX_THRESHOLD_MB)
        if (next == thresholdMb) {
            CustomToast.show(
                this,
                getString(
                    if (delta < 0f) R.string.f092_toast_threshold_min else R.string.f092_toast_threshold_max,
                    mb(thresholdMb)
                )
            )
            return
        }
        thresholdMb = next
        renderThreshold()
        renderTrend()
        // 导出区已经渲染过就一起刷新，免得文本上的阈值和页面上的不一致。
        if (exportShown) exportNow()
    }

    private fun renderThreshold() {
        binding.f092ThresholdText.text = getString(R.string.f092_threshold_value, mb(thresholdMb))
    }

    // ===== 导出 =====

    /** 生成导出文本并贴进页面。导出的是一份快照，之后继续采样不会回头改它。 */
    private fun exportNow() {
        if (F092Engine.snapshot().isEmpty()) {
            CustomToast.show(this, getString(R.string.f092_toast_export_empty))
            return
        }
        binding.f092ExportText.text = F092Engine.exportText(thresholdMb)
        binding.f092ExportText.visibility = View.VISIBLE
        binding.f092ExportHint.visibility = View.GONE
        exportShown = true
    }

    /** 复制当前显示的导出文本。没生成过就先生成，省一次点击。 */
    private fun copyExport() {
        if (!exportShown) exportNow()
        val text = binding.f092ExportText.text?.toString().orEmpty()
        if (text.isBlank()) return

        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            CustomToast.show(this, getString(R.string.f092_toast_clipboard_unavailable))
            return
        }
        manager.setPrimaryClip(ClipData.newPlainText(getString(R.string.f092_clip_label), text))
        CustomToast.show(
            this,
            getString(R.string.f092_toast_copied, F092Engine.snapshot().size)
        )
    }

    /** 清空缓冲重新观察：验证"某个操作之后内存还涨不涨"时，需要一条干净的基线。 */
    private fun resetSamples() {
        F092Engine.clear()
        exportShown = false
        binding.f092ExportText.text = ""
        binding.f092ExportText.visibility = View.GONE
        binding.f092ExportHint.visibility = View.VISIBLE
        sampleOnce()
        CustomToast.show(this, getString(R.string.f092_toast_reset))
    }

    // ===== 小工具 =====

    private fun mb(value: Float): String = String.format(Locale.US, "%.1f", value)

    private fun signedMb(value: Float): String = String.format(Locale.US, "%+.1f", value)

    private fun signedInt(value: Int): String = if (value > 0) "+$value" else value.toString()

    /** 字节级精度在电视上没意义，占比取整就够。 */
    private fun percentLabel(ratio: Float): String = "${(ratio * 100f).toInt()}%"

    private companion object {
        const val KEY_THRESHOLD = "f092_threshold_mb"
    }
}

/**
 * 迷你折线：把最近 5 分钟的样本画成两条线。
 *
 * - **绿线 = 堆内存**：纵轴按 `heapMaxMb`（堆上限）归一，所以线的高度直接对应"离上限还有多远"；
 *   上限未知时退化成按窗口内的最大值归一。
 * - **蓝线 = 线程数**：量纲和堆不一样，单独按窗口内最大值归一，只用来回答"线程数是不是也在往上走"。
 * - 面积填充只做在堆那条线上，两条都填就糊成一片了。
 *
 * 少于两个样本时什么都不画：一个点连不成线，硬画一条横线会让人以为"非常平稳"。
 * 画布尺寸变化不需要额外处理，[onDraw] 每次都按当前 `width/height` 重新算坐标。
 */
internal class F092ChartView(context: Context) : View(context) {

    private var samples: List<F092Engine.Sample> = emptyList()

    private val heapLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.theme_primary)
    }

    private val heapFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.theme_primary)
        alpha = 36
    }

    private val threadLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.theme_tertiary)
    }

    private val gridLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = ContextCompat.getColor(context, R.color.theme_outline_variant)
    }

    private val headDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.theme_primary)
    }

    init {
        // 曲线是看的，不是按的：别让它抢走遥控器焦点。
        isFocusable = false
        isClickable = false
    }

    /** 换一批样本并重绘。传进来的应该是窗口内的样本（[F092Engine.window]）。 */
    fun submit(list: List<F092Engine.Sample>) {
        samples = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val list = samples
        if (list.size < 2) return

        val left = paddingLeft + dp(4f)
        val right = width - paddingRight - dp(4f)
        val top = paddingTop + dp(6f)
        val bottom = height - paddingBottom - dp(6f)
        val plotWidth = right - left
        val plotHeight = bottom - top
        if (plotWidth <= 0f || plotHeight <= 0f) return

        // 底 / 中 / 顶三条参考线：给个"有多高"的尺度感即可，刻度数字在电视上会糊成一团。
        for (i in 0..2) {
            val y = top + plotHeight * i / 2f
            canvas.drawLine(left, y, right, y, gridLine)
        }

        val firstMs = list.first().atMs
        val spanMs = (list.last().atMs - firstMs).coerceAtLeast(1L).toFloat()
        val heapCeiling = list.maxOf { if (it.hasHeapCeiling) it.heapMaxMb else it.heapUsedMb }
            .coerceAtLeast(1f)
        val threadCeiling = (list.maxOf { it.threadCount } * 1.2f).coerceAtLeast(1f)

        val heapPath = Path()
        val areaPath = Path()
        val threadPath = Path()
        list.forEachIndexed { index, sample ->
            val x = left + plotWidth * ((sample.atMs - firstMs) / spanMs)
            val heapY = top + plotHeight * (1f - (sample.heapUsedMb / heapCeiling).coerceIn(0f, 1f))
            val threadY = top + plotHeight * (1f - (sample.threadCount / threadCeiling).coerceIn(0f, 1f))
            if (index == 0) {
                heapPath.moveTo(x, heapY)
                threadPath.moveTo(x, threadY)
                areaPath.moveTo(x, bottom)
                areaPath.lineTo(x, heapY)
            } else {
                heapPath.lineTo(x, heapY)
                threadPath.lineTo(x, threadY)
                areaPath.lineTo(x, heapY)
            }
        }
        areaPath.lineTo(right, bottom)
        areaPath.close()

        canvas.drawPath(areaPath, heapFill)
        canvas.drawPath(heapPath, heapLine)
        canvas.drawPath(threadPath, threadLine)

        // 右端圆点 = "现在"。曲线是从左往右长出来的，标出最新一点，用户就不用猜哪头是新的。
        val last = list.last()
        val lastX = left + plotWidth * ((last.atMs - firstMs) / spanMs)
        val lastY = top + plotHeight * (1f - (last.heapUsedMb / heapCeiling).coerceIn(0f, 1f))
        canvas.drawCircle(lastX, lastY, dp(2.5f), headDot)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
