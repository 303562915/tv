package com.tv.ball.feature.f048

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF048Binding

/**
 * F048「搜索防抖与竞态取消」页面。
 *
 * 防抖与序号全在 [F048Engine]；这里只负责把两条线画出来：
 * 1. 输入线：EditText 每次文本变化 -> [F048Engine.Debouncer]，窗口内又被输入就把上一个候选
 *    记一条「丢弃」，窗口走完才 [dispatch]；
 * 2. 结果线：[F048Engine.QueryRegistry] 只认最新序号，晚回来的旧结果记一条「被新请求覆盖」，
 *    结果卡片永远只有最后一次被接受的那个查询。
 *
 * 「模拟连打 5 次」把 5 次敲键压进 120ms 间隔，防抖 300ms 时只会发 1 个请求；
 * 把窗口调到 0ms 则 5 个都发，前 4 个结果回来时已经被覆盖。
 */
class F048Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF048Binding

    private val handler = Handler(Looper.getMainLooper())
    private val startedAt = SystemClock.elapsedRealtime()
    private val clock = F048Engine.Clock { SystemClock.elapsedRealtime() }
    private val registry = F048Engine.QueryRegistry()

    private var debounceMs: Long = F048Engine.DEFAULT_DEBOUNCE_MS
    private var debouncer = F048Engine.Debouncer(F048Engine.DEFAULT_DEBOUNCE_MS, clock)

    private var sentCount = 0
    private var droppedCount = 0
    private var overriddenCount = 0

    /** 已登记、还在等窗口结束的候选文本；被新输入顶掉就记一次「丢弃」。 */
    private var scheduledText: String? = null

    /** 「模拟连打」的批次号，连点两次时旧的连打自动作废。 */
    private var burstToken = 0

    /** 窗口到点后的补发动作，只留一个实例方便 removeCallbacks。 */
    private val flushRunnable = object : Runnable {
        override fun run() {
            val text = scheduledText ?: return
            if (!debouncer.isQuiet()) {
                handler.postDelayed(this, debouncer.quietRemainingMs().coerceAtLeast(MIN_TICK_MS))
                return
            }
            scheduledText = null
            dispatch(text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF048Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.inputQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onQueryChanged(s?.toString().orEmpty())
        })

        binding.btnBurst.setOnClickListener { simulateBurst() }
        binding.btnClearLog.setOnClickListener { resetAll() }
        binding.btnDebounceMinus.setOnClickListener { changeDebounce(-F048Engine.DEBOUNCE_STEP_MS) }
        binding.btnDebouncePlus.setOnClickListener { changeDebounce(F048Engine.DEBOUNCE_STEP_MS) }

        binding.inputQuery.nextFocusDownId = binding.btnBurst.id
        binding.btnBurst.nextFocusUpId = binding.inputQuery.id

        refreshDebounceLabel()
        refreshStats()
        renderIdleResult()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** 输入线：每次文本变化都过一遍防抖，决定「记一条丢弃」还是「排一次补发」。 */
    private fun onQueryChanged(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) {
            cancelInFlight()
            return
        }

        val previous = scheduledText
        if (previous != null && previous != text) {
            droppedCount += 1
            appendLog(
                F048Engine.EventKind.DROPPED,
                getString(R.string.f048_log_dropped, previous, debounceMs)
            )
        }

        handler.removeCallbacks(flushRunnable)

        if (debouncer.onInput(text)) {
            scheduledText = null
            dispatch(text)
            return
        }

        scheduledText = text
        handler.postDelayed(flushRunnable, debounceMs.coerceAtLeast(MIN_TICK_MS))
    }

    /** 输入被清空：窗口、候选、在飞请求一起作废。 */
    private fun cancelInFlight() {
        val hadPending = scheduledText != null
        handler.removeCallbacks(flushRunnable)
        scheduledText = null
        debouncer.reset()
        val cancelled = registry.cancelAll()
        if (hadPending || cancelled > 0) {
            appendLog(F048Engine.EventKind.DROPPED, getString(R.string.f048_log_cancel, cancelled))
        }
        refreshStats()
        renderIdleResult()
    }

    /** 结果线：领序号 -> 记「发出」-> 按模拟时延回包，只有最新序号能渲染。 */
    private fun dispatch(text: String) {
        val seq = registry.begin(text)
        sentCount += 1
        appendLog(F048Engine.EventKind.SENT, getString(R.string.f048_log_sent, seq, text))
        refreshStats()

        val latency = F048Engine.latencyFor(seq)
        handler.postDelayed({
            if (registry.accept(seq)) {
                renderResult(seq, text, latency)
            } else {
                overriddenCount += 1
                appendLog(
                    F048Engine.EventKind.OVERRIDDEN,
                    getString(R.string.f048_log_overridden, seq, text, registry.latestSeq)
                )
            }
            refreshStats()
        }, latency)
    }

    /** 只有走到这里的查询才会被画到结果卡上。 */
    private fun renderResult(seq: Int, query: String, latency: Long) {
        val result = F048Engine.search(seq, query)
        val at = SystemClock.elapsedRealtime() - startedAt
        binding.resultTitle.text = getString(R.string.f048_result_title, seq, result.query)
        binding.resultTitle.setTextColor(color(R.color.theme_primary))
        binding.resultBody.text =
            getString(R.string.f048_result_body, result.hits, at, latency)
    }

    private fun renderIdleResult() {
        binding.resultTitle.text = getString(R.string.f048_result_idle_title)
        binding.resultTitle.setTextColor(color(R.color.theme_text_secondary))
        binding.resultBody.text = getString(R.string.f048_result_idle_body)
    }

    /** 模拟连打 5 次：间隔 120ms，故意短于默认 300ms 窗口。 */
    private fun simulateBurst() {
        burstToken += 1
        val token = burstToken
        val target = getString(R.string.f048_burst_text)
        binding.inputQuery.requestFocus()

        target.indices.forEach { index ->
            handler.postDelayed({
                if (token != burstToken) return@postDelayed
                binding.inputQuery.setText(target.substring(0, index + 1))
                binding.inputQuery.setSelection(binding.inputQuery.text.length)
            }, index * F048Engine.BURST_INTERVAL_MS)
        }
    }

    /** 调防抖参数：换一个新的窗口对象，旧候选作废，避免停在半路的输入永远发不出去。 */
    private fun changeDebounce(delta: Long) {
        val target = F048Engine.clampDebounce(debounceMs + delta)
        if (target == debounceMs) return

        val previous = debounceMs
        debounceMs = target
        handler.removeCallbacks(flushRunnable)
        scheduledText = null
        debouncer = F048Engine.Debouncer(target, clock)

        refreshDebounceLabel()
        appendLog(
            F048Engine.EventKind.CONFIG,
            getString(R.string.f048_log_config, previous, target)
        )
    }

    /** 清空日志与计数，回到初始态。 */
    private fun resetAll() {
        burstToken += 1
        handler.removeCallbacks(flushRunnable)
        scheduledText = null
        debouncer.reset()
        registry.reset()
        sentCount = 0
        droppedCount = 0
        overriddenCount = 0
        binding.logContainer.removeAllViews()
        refreshStats()
        renderIdleResult()
    }

    private fun appendLog(kind: F048Engine.EventKind, message: String) {
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@F048Activity, R.drawable.bg_mode_row)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }

        row.addView(TextView(this).apply {
            text = getString(R.string.f048_log_time, elapsed)
            setTextColor(color(R.color.theme_text_secondary))
            textSize = 12f
            isFocusable = false
            setPadding(0, 0, dp(8), 0)
        })

        row.addView(TextView(this).apply {
            text = message
            setTextColor(color(kindColor(kind)))
            textSize = 12f
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        binding.logContainer.addView(row)
        binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun kindColor(kind: F048Engine.EventKind): Int = when (kind) {
        F048Engine.EventKind.SENT -> R.color.theme_primary
        F048Engine.EventKind.DROPPED -> R.color.theme_text_secondary
        F048Engine.EventKind.OVERRIDDEN -> R.color.theme_warning
        F048Engine.EventKind.CONFIG -> R.color.theme_info
    }

    private fun refreshStats() {
        binding.statsValue.text =
            getString(R.string.f048_stats, sentCount, droppedCount, overriddenCount)
    }

    private fun refreshDebounceLabel() {
        binding.debounceValue.text = if (debounceMs <= 0L) {
            getString(R.string.f048_debounce_off)
        } else {
            getString(R.string.f048_debounce_label, debounceMs)
        }
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    private companion object {
        /** Handler 最小重排间隔，避免 0ms 时自旋。 */
        const val MIN_TICK_MS: Long = 16L
    }
}
