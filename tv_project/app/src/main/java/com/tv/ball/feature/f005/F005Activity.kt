package com.tv.ball.feature.f005

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF005Binding
import com.tv.ball.di.Dependencies
import com.tv.ball.util.CustomToast
import com.tv.ball.utils.ThemeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 下集倒计时浮层演示页。
 *
 * 页面里没有真实播放器：点「模拟到达片尾」就当作本集已经播完，
 * 浮层（布局内的 FrameLayout 覆盖层）盖住页面开始倒计时，剩余秒数由 [engine] 推进。
 * 倒计时归零 → 自动接播下一集；中途取消 → 停在片尾不再自动跳。
 *
 * 真实播放器接入时只需在进度回调里判断「剩余时长 <= 片尾阈值」时 [startRound]，
 * 收到 [F005Engine.State.DONE] 时切下一集，收到 [F005Engine.State.CANCELED] 时不做任何事。
 */
class F005Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF005Binding
    private val engine = F005Engine()

    /** 首读要读盘，放在 IO 线程，这里允许暂时为空。 */
    private var prefs: SharedPreferences? = null

    /** 用户配置的倒计时秒数（3~15）。 */
    private var seconds: Int = F005Engine.DEFAULT_SECONDS

    /** 演示用当前集数：从第 3 集起，一眼能看到「下一集」。 */
    private var episode: Int = START_EPISODE

    private val countdownTick = object : Runnable {
        override fun run() {
            if (engine.state != F005Engine.State.COUNTING) return
            engine.tick(TICK_MS)
            if (engine.state == F005Engine.State.DONE) {
                finishRound()
                return
            }
            applyState()
            binding.root.postDelayed(this, TICK_MS)
        }
    }

    companion object {
        /** 只存自己的几个偏好，不往共享的配置仓库里塞键。 */
        private const val PREFS_FILE = "f005_next_episode"
        private const val KEY_SECONDS = "countdown_seconds"

        private const val TICK_MS = F005Engine.TICK_STEP_MS

        private const val START_EPISODE = 3

        /** 置灰时的不透明度，与其它功能页一致。 */
        private const val DISABLED_ALPHA = 0.4f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 与全站一致：进页面前先把夜间模式定下来，避免闪一次浅色
        ThemeUtils.applyNightMode(Dependencies.configRepository.getNightMode())
        super.onCreate(savedInstanceState)
        binding = ActivityF005Binding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupSecondsConfig()
        setupActions()
        setupBackHandling()

        applyState()
        loadPersisted()

        // 遥控器进来时焦点不能悬空，落在主操作上
        binding.btnStart.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        // 页面不可见时 tick 会停，回来接着走：演示里没有真实播放进度，倒计时等于暂停
        if (engine.state == F005Engine.State.COUNTING) {
            startTicker()
        }
    }

    override fun onStop() {
        stopTicker()
        super.onStop()
    }

    override fun onDestroy() {
        binding.root.removeCallbacks(countdownTick)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 焦点与控件

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun setupSecondsConfig() {
        binding.btnSecondsMinus.setOnClickListener { changeSecondsBy(-1) }
        binding.btnSecondsPlus.setOnClickListener { changeSecondsBy(1) }
    }

    private fun setupActions() {
        binding.btnStart.setOnClickListener { startRound() }
        binding.btnCancel.setOnClickListener { cancelRound() }
        binding.btnContinue.setOnClickListener { continueNextEpisode() }
        binding.btnOverlayPlayNext.setOnClickListener { continueNextEpisode() }
        binding.btnOverlayCancel.setOnClickListener { cancelRound() }
    }

    /** 浮层开着时，返回键先收浮层（等价于取消），避免用户直接退出页面丢掉操作。 */
    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.overlayCountdown.visibility == View.VISIBLE) {
                    cancelRound()
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    private fun changeSecondsBy(delta: Int) {
        if (engine.state == F005Engine.State.COUNTING) {
            // 本轮正在跑，改设置会和正在显示的秒数打架，直接忽略
            return
        }
        val next = F005Engine.clampSeconds(seconds + delta)
        if (next == seconds) {
            // 到头了给个提示，否则用户会以为按键没响应
            CustomToast.show(
                this,
                getString(R.string.f005_toast_seconds_limit, F005Engine.MIN_SECONDS, F005Engine.MAX_SECONDS)
            )
            return
        }
        seconds = next
        saveSeconds()
        applyState()
    }

    // ------------------------------------------------------------------ 一轮倒计时

    /** 模拟「本集播到片尾」：浮层出现并开始倒计时。 */
    private fun startRound() {
        if (engine.state == F005Engine.State.COUNTING) return
        engine.start(F005Engine.secondsToMs(seconds))
        showOverlay()
        CustomToast.show(this, getString(R.string.f005_toast_started, seconds))
        startTicker()
        applyState()
    }

    /** 倒计时走完：自动接播下一集，浮层收起；引擎停在 DONE，等下一次 [startRound] 复位。 */
    private fun finishRound() {
        stopTicker()
        hideOverlay()
        episode += 1
        CustomToast.show(this, getString(R.string.f005_toast_done, episode))
        binding.btnStart.requestFocus()
        applyState()
    }

    /** 取消本轮：浮层收起，状态落到 CANCELED，停在片尾不跳下一集。 */
    private fun cancelRound() {
        if (engine.state != F005Engine.State.COUNTING) {
            // 浮层可能因为异常路径还亮着，先收掉，别留着遮挡
            hideOverlay()
            applyState()
            return
        }
        engine.cancel()
        stopTicker()
        hideOverlay()
        CustomToast.show(this, getString(R.string.f005_toast_canceled, episode))
        binding.btnContinue.requestFocus()
        applyState()
    }

    /** 用户主动接播下一集：取消后想换集走这里，浮层上的「立即播放下一集」也走这里。 */
    private fun continueNextEpisode() {
        stopTicker()
        hideOverlay()
        if (engine.state == F005Engine.State.COUNTING) {
            engine.cancel()
        }
        engine.reset()
        episode += 1
        CustomToast.show(this, getString(R.string.f005_toast_continue, episode))
        binding.btnStart.requestFocus()
        applyState()
    }

    private fun startTicker() {
        binding.root.removeCallbacks(countdownTick)
        if (engine.state == F005Engine.State.COUNTING) {
            binding.root.postDelayed(countdownTick, TICK_MS)
        }
    }

    private fun stopTicker() {
        binding.root.removeCallbacks(countdownTick)
    }

    // ------------------------------------------------------------------ 浮层

    private fun showOverlay() {
        // 浮层下面还有整页按钮，挡住焦点，免得 D-Pad 跑到底下的控件上
        binding.pageContent.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        binding.overlayCountdown.visibility = View.VISIBLE
        binding.btnOverlayPlayNext.requestFocus()
    }

    private fun hideOverlay() {
        binding.overlayCountdown.visibility = View.GONE
        binding.pageContent.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
    }

    // ------------------------------------------------------------------ 渲染

    private fun applyState() {
        val state = engine.state

        binding.tvNowPlaying.text = getString(R.string.f005_now_playing_value, episode)
        binding.tvEpisodeState.text = when (state) {
            F005Engine.State.COUNTING -> getString(R.string.f005_state_episode_end, episode)
            F005Engine.State.CANCELED -> getString(R.string.f005_state_canceled, episode)
            // IDLE / DONE 都是「这一集正在播」，DONE 时 episode 已经指向下一集
            else -> getString(R.string.f005_state_playing, episode)
        }

        binding.tvSecondsValue.text = getString(R.string.f005_seconds_value, seconds)

        binding.tvStatus.text = when (state) {
            F005Engine.State.IDLE -> getString(R.string.f005_status_idle)
            F005Engine.State.COUNTING -> getString(R.string.f005_status_counting, engine.remainingSeconds)
            F005Engine.State.CANCELED -> getString(R.string.f005_status_canceled, episode)
            F005Engine.State.DONE -> getString(R.string.f005_status_done, episode)
        }

        binding.tvHint.text = getString(
            when (state) {
                F005Engine.State.IDLE -> R.string.f005_hint_idle
                F005Engine.State.COUNTING -> R.string.f005_hint_counting
                F005Engine.State.CANCELED -> R.string.f005_hint_canceled
                F005Engine.State.DONE -> R.string.f005_hint_done
            }
        )

        // 空闲时按配置值显示满格；倒计时与取消都读引擎里冻结的剩余量
        val totalMs = if (state == F005Engine.State.IDLE) F005Engine.secondsToMs(seconds) else engine.totalMs
        val remainingMs = when (state) {
            F005Engine.State.COUNTING, F005Engine.State.CANCELED -> engine.remainingMs
            F005Engine.State.IDLE -> totalMs
            F005Engine.State.DONE -> 0L
        }
        binding.progressCountdown.max = totalMs.toInt().coerceAtLeast(1)
        binding.progressCountdown.progress = remainingMs.toInt().coerceIn(0, binding.progressCountdown.max)
        binding.tvRemaining.text = getString(R.string.f005_remaining_value, remainingMs / 1000.0)

        binding.progressOverlay.max = engine.totalMs.toInt().coerceAtLeast(1)
        binding.progressOverlay.progress =
            engine.remainingMs.toInt().coerceIn(0, binding.progressOverlay.max)
        binding.tvOverlayCountdown.text =
            getString(R.string.f005_overlay_countdown, engine.remainingSeconds, episode + 1)

        applyEnabled(binding.btnStart, state == F005Engine.State.IDLE || state == F005Engine.State.DONE)
        applyEnabled(binding.btnCancel, state == F005Engine.State.COUNTING)
        applyEnabled(binding.btnContinue, state == F005Engine.State.CANCELED)
        // 倒计时进行中锁住时长设置，避免本轮的数字与设置项不一致
        val editable = state != F005Engine.State.COUNTING
        applyEnabled(binding.btnSecondsMinus, editable && seconds > F005Engine.MIN_SECONDS)
        applyEnabled(binding.btnSecondsPlus, editable && seconds < F005Engine.MAX_SECONDS)
    }

    private fun applyEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else DISABLED_ALPHA
        // 置灰的按钮要从 D-Pad 焦点链里摘掉，否则遥控器会停在点不动的控件上
        view.isFocusable = enabled
    }

    // ------------------------------------------------------------------ 持久化

    private fun loadPersisted() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val sp = applicationContext
                    .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                    .also { prefs = it }
                sp.getInt(KEY_SECONDS, F005Engine.DEFAULT_SECONDS)
            }
            // 历史数据可能被手改成越界值，读回来一律夹回 3~15
            seconds = F005Engine.clampSeconds(loaded)
            applyState()
        }
    }

    private fun saveSeconds() {
        val sp = prefs ?: applicationContext
            .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .also { prefs = it }
        // apply() 自己异步落盘，主线程只组一次提交
        sp.edit().putInt(KEY_SECONDS, seconds).apply()
    }
}