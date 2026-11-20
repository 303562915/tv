package com.tv.ball.feature.f008

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF008Binding
import com.tv.ball.di.Dependencies
import com.tv.ball.util.CustomToast
import com.tv.ball.utils.ThemeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 音画同步微调页。
 *
 * 页面上不挂真实播放器：偏移值全部由 [F008Engine] 算，
 * 「步进 50ms / 夹在 ±2000ms / 退出重进沿用」三个行为在这里就能直接看全。
 * 两条时间线用 translationX 各走一半，错位幅度等于偏移，正负方向一眼可辨。
 *
 * 播放器接入时只需在渲染时间点的回调里喂 `engine.applyTo(baseUs)`。
 */
class F008Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF008Binding
    private val engine = F008Engine()

    /** 首次读取走 IO 线程，读完之前这里是空的。 */
    private var prefs: SharedPreferences? = null

    /** 长按连调是否在跑；置 false 后 [repeatTick] 自己停。 */
    private var repeating: Boolean = false

    /** 当前连调方向（毫秒）。 */
    private var repeatDelta: Int = 0

    /** 本次长按已经连调了几步，用来让第一步立刻生效之外不再重复单击那一下。 */
    private var repeatSteps: Int = 0

    /** 连调节奏：到点走一步，步长仍由引擎统一给。 */
    private val repeatTick = object : Runnable {
        override fun run() {
            if (!repeating) return
            if (!applyStep(repeatDelta)) {
                // 顶到边界：停掉连调，别让提示 Toast 排队
                stopRepeat()
                return
            }
            binding.root.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    companion object {
        /** 连调重复间隔 70ms，配 50ms 步长接近 0.7 倍速，手指不飘。 */
        private const val REPEAT_INTERVAL_MS = 70L

        /** 示意条最大位移；两条线各走一半。 */
        private const val MAX_SHIFT_DP = 24f

        private const val PREFS_FILE = "f008_av_sync"
        private const val KEY_OFFSET = "offset_ms"

        /** 置灰时的透明度，与 F002 保持一致。 */
        private const val DISABLED_ALPHA = 0.4f

        /** 示例时间点：画面 1 秒处，用来展示 applyTo 的换算结果。 */
        private const val SAMPLE_BASE_US = 1_000_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 与全站一致：进页面前先把夜间模式定下来，避免闪一次浅色
        ThemeUtils.applyNightMode(Dependencies.configRepository.getNightMode())
        super.onCreate(savedInstanceState)
        binding = ActivityF008Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        setupSteppers()

        render()
        loadPersisted()

        // 遥控器进来时焦点不能悬空
        binding.btnPlus.requestFocus()
    }

    override fun onPause() {
        stopRepeat()
        super.onPause()
    }

    override fun onStop() {
        stopRepeat()
        saveOffset()
        super.onStop()
    }

    override fun onDestroy() {
        binding.root.removeCallbacks(repeatTick)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 步进

    private fun setupSteppers() {
        bindStepper(binding.btnMinus, -F008Engine.STEP_MS)
        bindStepper(binding.btnPlus, F008Engine.STEP_MS)
        binding.btnReset.setOnClickListener {
            engine.reset()
            saveOffset()
            render()
            CustomToast.show(this, getString(R.string.f008_toast_reset))
        }
    }

    /**
     * 两步进按钮共用一套按键语义：
     * 单击由系统 click 在中心键抬起时触发，长按另起一路连调，互不重复计数。
     */
    private fun bindStepper(view: View, deltaMs: Int) {
        view.setOnClickListener { applyStep(deltaMs) }

        view.setOnLongClickListener {
            startRepeat(deltaMs)
            true
        }

        // 只认中心/确认键的抬起；方向键要留给焦点移动，不能吞
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP && isConfirmKey(keyCode)) {
                stopRepeat()
            }
            false
        }
    }

    private fun isConfirmKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    /**
     * 走一步微调。
     *
     * @return 真的改变了偏移为 true；已到边界为 false，调用方据此停止连调。
     */
    private fun applyStep(deltaMs: Int): Boolean {
        if (!engine.canAdjust(deltaMs)) {
            render()
            CustomToast.show(
                this,
                getString(if (deltaMs > 0) R.string.f008_toast_at_max else R.string.f008_toast_at_min)
            )
            return false
        }
        engine.adjust(deltaMs)
        saveOffset()
        render()
        return true
    }

    private fun startRepeat(deltaMs: Int) {
        if (!engine.canAdjust(deltaMs)) {
            CustomToast.show(
                this,
                getString(if (deltaMs > 0) R.string.f008_toast_at_max else R.string.f008_toast_at_min)
            )
            return
        }
        repeatDelta = deltaMs
        repeatSteps = 0
        repeating = true
        binding.root.removeCallbacks(repeatTick)
        binding.root.post(repeatTick)
    }

    private fun stopRepeat() {
        repeating = false
        binding.root.removeCallbacks(repeatTick)
    }

    // ------------------------------------------------------------------ 渲染

    private fun render() {
        val ms = engine.offsetMs

        binding.tvOffset.text = F008Engine.formatOffset(ms)
        binding.tvOffset.setTextColor(
            color(if (ms == 0) R.color.theme_text_primary else R.color.theme_primary)
        )
        binding.tvOffsetSeconds.text =
            getString(R.string.f008_offset_seconds, F008Engine.formatSeconds(ms))

        binding.tvStatus.text = if (ms == 0) {
            getString(R.string.f008_status_zero)
        } else {
            getString(R.string.f008_status_applied, F008Engine.formatOffset(ms))
        }

        // 到边界的那个方向必须置灰，否则用户只能反复按一个不动的按钮
        applyEnabled(binding.btnMinus, engine.canAdjust(-F008Engine.STEP_MS))
        applyEnabled(binding.btnPlus, engine.canAdjust(F008Engine.STEP_MS))

        val noticeRes = when {
            engine.isAtUpperLimit() -> R.string.f008_limit_max
            engine.isAtLowerLimit() -> R.string.f008_limit_min
            else -> 0
        }
        binding.tvLimitNotice.text = if (noticeRes != 0) getString(noticeRes) else ""
        binding.tvLimitNotice.visibility = if (noticeRes != 0) View.VISIBLE else View.GONE

        renderTimeline(ms)
        renderSample()
    }

    /** 两条时间线各平移偏移量的一半：正偏移 -> 音轨右移、画面左移。 */
    private fun renderTimeline(ms: Int) {
        val videoMs = -(ms / 2)
        val audioMs = ms - (ms / 2)
        val maxShiftPx = MAX_SHIFT_DP * resources.displayMetrics.density
        val perMsPx = maxShiftPx / F008Engine.MAX_OFFSET_MS.toFloat()

        binding.barVideo.translationX = videoMs * perMsPx
        binding.barAudio.translationX = audioMs * perMsPx

        binding.tvShiftReadout.text = if (ms == 0) {
            getString(R.string.f008_shift_zero)
        } else {
            getString(
                R.string.f008_shift_readout,
                F008Engine.formatOffset(videoMs),
                F008Engine.formatOffset(audioMs)
            )
        }
    }

    private fun renderSample() {
        val adjustedUs = engine.applyTo(SAMPLE_BASE_US)
        binding.tvSample.text = getString(
            R.string.f008_sample,
            F008Engine.formatSeconds((SAMPLE_BASE_US / 1000L).toInt()),
            F008Engine.formatSeconds((adjustedUs / 1000L).toInt())
        )
    }

    private fun applyEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else DISABLED_ALPHA
        // 置灰后不能成为焦点落点，否则遥控器会停在一个按不动的按钮上
        view.isFocusable = enabled
        if (!enabled && view.hasFocus()) {
            binding.btnReset.requestFocus()
        }
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    // ------------------------------------------------------------------ 持久化

    /** 首读要读盘，放 IO 线程；读回来再统一刷一次界面。 */
    private fun loadPersisted() {
        lifecycleScope.launch {
            val stored = withContext(Dispatchers.IO) {
                val sp = applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                prefs = sp
                sp.getInt(KEY_OFFSET, 0)
            }

            val clean = engine.restore(stored)
            if (!clean) {
                // 历史值被手改成越界数就夹回边界，并把修正结果说出来，别静默改数
                saveOffset()
                CustomToast.show(
                    this@F008Activity,
                    getString(R.string.f008_toast_restored, F008Engine.formatOffset(engine.offsetMs))
                )
            }
            render()
        }
    }

    private fun saveOffset() {
        val sp = prefs ?: applicationContext
            .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .also { prefs = it }
        // apply() 自己异步落盘，主线程只组一次提交
        sp.edit().putInt(KEY_OFFSET, engine.offsetMs).apply()
    }
}