package com.tv.ball.feature.f006

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF006Binding
import com.tv.ball.di.Dependencies
import com.tv.ball.feature.f006.F006Engine.RetryDecision
import com.tv.ball.util.CustomToast
import com.tv.ball.utils.ThemeUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播放失败自动重试与降级页。
 *
 * 页面里没有真实播放器：点「模拟失败」就等价于播放器报了一次错，之后的重试由
 * [handler] 按 [F006Engine] 给出的 1s / 2s / 4s 退避自动推进。每次重试都默认再次失败
 * （真实场景里重试失败也就是又报一次错），所以一次点击就能看到完整的退避链，
 * 直到 3 次额度用尽给出「建议切换线路」的结论。
 *
 * 等待用 `postDelayed` + `removeCallbacks`：不占用线程、随时可取消，
 * 不会像 `Thread.sleep` 那样把主线程钉死。终止后再也不会自动重试，必须点「重置」重新开一轮。
 */
class F006Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF006Binding
    private val engine = F006Engine()

    private val handler = Handler(Looper.getMainLooper())

    /** 正在等待的那次重试；null 表示当前没有排队的延时任务。 */
    private var pending: RetryDecision.Retry? = null

    /** 已经给出「不再重试」结论；置位后再点「模拟失败」只提示，不会偷偷重来。 */
    private var gaveUp: Boolean = false

    /** 时间线文案，最新的在最上面，最多保留 [MAX_TIMELINE_ROWS] 行。 */
    private val timeline = ArrayDeque<String>()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 真正发起重试的执行体，必须是同一个实例，才能被 removeCallbacks 取消。 */
    private val fireRetry = Runnable {
        val target = pending ?: return@Runnable
        pending = null
        renderState()
        // 这次重试同样失败，再问一次引擎：进入下一档退避，或者直接收尾
        handleFailure(target.attempt)
    }

    companion object {
        private const val MAX_TIMELINE_ROWS = 40

        /** 控件置灰时的透明度，与全站一致。 */
        private const val DISABLED_ALPHA = 0.4f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 与全站一致：进页面前先把夜间模式定下来，避免闪一次浅色
        ThemeUtils.applyNightMode(Dependencies.configRepository.getNightMode())
        super.onCreate(savedInstanceState)
        binding = ActivityF006Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSimulate.setOnClickListener { simulateFailure() }
        binding.btnAbort.setOnClickListener { abortRetry() }
        binding.btnReset.setOnClickListener { resetAll() }

        renderState()
        // 遥控器进来时焦点不能悬空
        binding.btnSimulate.requestFocus()
    }

    override fun onDestroy() {
        // 页面销毁时丢掉待执行的延时，避免 Runnable 再碰已销毁的 View
        cancelPending()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 交互

    /** 播放失败（用户点「模拟失败」或上一次重试失败）。 */
    private fun simulateFailure() {
        if (engine.aborted) {
            CustomToast.show(this, getString(R.string.f006_status_aborted))
            return
        }
        if (gaveUp) {
            CustomToast.show(this, getString(R.string.f006_toast_exhausted))
            return
        }
        // 上一轮延时还没走完就再次失败：取消旧排程，交给引擎重新分配退避档位
        cancelPending()
        appendTimeline(getString(R.string.f006_timeline_fail, nowText()))
        handleFailure(null)
    }

    /**
     * 一次失败事件的处理。
     *
     * @param retriedAttempt 刚刚失败的那次重试序号；首次失败传 null，只影响收尾文案
     */
    private fun handleFailure(retriedAttempt: Int?) {
        when (val decision = engine.onFailure()) {
            is RetryDecision.Retry -> {
                pending = decision
                appendTimeline(
                    getString(
                        R.string.f006_timeline_schedule,
                        nowText(),
                        decision.attempt,
                        F006Engine.formatDelay(decision.delayMs)
                    )
                )
                handler.postDelayed(fireRetry, decision.delayMs)
                renderState()
            }

            RetryDecision.GiveUp -> {
                gaveUp = true
                cancelPending()
                appendTimeline(
                    if (retriedAttempt != null) {
                        getString(R.string.f006_timeline_give_up, nowText(), retriedAttempt)
                    } else {
                        getString(R.string.f006_timeline_give_up_fast, nowText(), engine.attempts)
                    }
                )
                if (engine.isExhausted) {
                    CustomToast.showLong(this, getString(R.string.f006_toast_exhausted))
                }
                renderState()
            }
        }
    }

    /** 手动终止：取消排队中的延时，之后不再自动重试。 */
    private fun abortRetry() {
        if (engine.attempts == 0 && pending == null) {
            CustomToast.show(this, getString(R.string.f006_status_idle))
            return
        }
        cancelPending()
        engine.abort()
        gaveUp = false
        appendTimeline(getString(R.string.f006_timeline_abort, nowText()))
        renderState()
        CustomToast.show(this, getString(R.string.f006_toast_aborted))
    }

    private fun resetAll() {
        cancelPending()
        engine.reset()
        gaveUp = false
        timeline.clear()
        appendTimeline(getString(R.string.f006_timeline_reset, nowText()))
        renderState()
        CustomToast.show(this, getString(R.string.f006_toast_reset))
    }

    private fun cancelPending() {
        handler.removeCallbacks(fireRetry)
        pending = null
    }

    // ------------------------------------------------------------------ 渲染

    private fun renderState() {
        val waiting = pending

        binding.tvAttempt.text = if (engine.attempts == 0) {
            getString(R.string.f006_attempt_empty)
        } else {
            getString(R.string.f006_attempt_value, engine.attempts, F006Engine.MAX_ATTEMPTS)
        }
        binding.tvRemainder.text = if (engine.remainingAttempts == 0) {
            getString(R.string.f006_remaining_empty)
        } else {
            getString(R.string.f006_remaining_value, engine.remainingAttempts)
        }

        binding.tvStatus.text = when {
            engine.aborted -> getString(R.string.f006_status_aborted)
            waiting != null -> getString(
                R.string.f006_status_waiting,
                waiting.attempt,
                F006Engine.MAX_ATTEMPTS,
                F006Engine.formatDelay(waiting.delayMs)
            )

            gaveUp -> getString(R.string.f006_status_exhausted)
            engine.attempts > 0 -> getString(
                R.string.f006_status_retrying,
                engine.attempts,
                F006Engine.MAX_ATTEMPTS
            )

            else -> getString(R.string.f006_status_idle)
        }

        // 降级结论：3 次重试都失败（gaveUp）才给「建议切换线路」，颜色全部走 colors.xml token
        val degradeText = when {
            gaveUp -> R.string.f006_degrade_advice
            engine.aborted -> R.string.f006_degrade_aborted
            else -> R.string.f006_degrade_idle
        }
        binding.tvDegrade.setText(degradeText)
        binding.tvDegrade.setTextColor(
            getSemanticColor(
                if (gaveUp) R.color.theme_error else R.color.theme_text_secondary
            )
        )

        // 一轮里没东西可终止时置灰，避免用户点了却毫无反馈
        applyEnabled(binding.btnAbort, pending != null || (engine.attempts > 0 && !engine.aborted))
        applyEnabled(binding.btnSimulate, !engine.aborted && !gaveUp)
        applyEnabled(binding.btnReset, engine.attempts > 0 || engine.aborted || gaveUp)
    }

    private fun appendTimeline(text: String) {
        timeline.addFirst(text)
        while (timeline.size > MAX_TIMELINE_ROWS) {
            timeline.removeLast()
        }
        renderTimeline()
    }

    private fun renderTimeline() {
        binding.timelineList.removeAllViews()
        binding.tvTimelineEmpty.visibility = if (timeline.isEmpty()) View.VISIBLE else View.GONE
        val rowPadding = dp(4)
        timeline.forEach { line ->
            val row = TextView(this)
            row.text = line
            row.setTextColor(getSemanticColor(R.color.theme_text_primary))
            row.textSize = 12f
            row.setPadding(rowPadding, rowPadding, rowPadding, rowPadding)
            binding.timelineList.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun applyEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else DISABLED_ALPHA
        view.isFocusable = enabled
    }

    private fun getSemanticColor(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    /** 时间线用的 `HH:mm:ss.SSS` 时间戳，让「等了多久才重试」一眼可读。 */
    private fun nowText(): String = timeFormat.format(Date())
}