package com.tv.ball.feature.f002

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF002Binding
import com.tv.ball.di.Dependencies
import com.tv.ball.util.CustomToast
import com.tv.ball.utils.ThemeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A-B 段落复读设置页。
 *
 * 页面里没有真实播放器：时间轴上跑的是「模拟播放位置」，由 [playTick] 自己推进，
 * 越过 B 点就按 [F002Engine.shouldLoop] / [F002Engine.loopTo] 的结果跳回 A，
 * 于是「设点 -> 到 B 跳回 -> 清除后不再跳」三步在这里就能直接验证。
 * 真实播放器接入时只需在进度回调里喂 `engine.shouldLoop(position)` 并在 true 时 seek 到 `loopTo()`。
 */
class F002Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF002Binding
    private val engine = F002Engine()

    /** 首次读取要在 IO 线程完成，这里允许暂时为空。 */
    private var prefs: SharedPreferences? = null

    /** 模拟播放位置（毫秒）。 */
    private var positionMs: Long = 0L

    /** 拖动时间轴期间进度条会自己改位置，渲染时不要回写把它顶回去。 */
    private var seeking: Boolean = false

    /** 是否正在跑模拟播放。 */
    private var playing: Boolean = false

    /** 「到 B 点自动跳回 A」开关；关掉即普通顺序播放，用于对照。 */
    private var loopEnabled: Boolean = true

    /** 本轮播累计跳回次数。 */
    private var loopCount: Int = 0

    private val playTick = object : Runnable {
        override fun run() {
            if (!playing) return
            advance(STEP_MS)
            binding.root.postDelayed(this, TICK_MS)
        }
    }

    private data class PersistedMarks(
        val aMs: Long,
        val bMs: Long,
        val positionMs: Long,
        val loopEnabled: Boolean
    )

    companion object {
        /** 时间轴一格 100ms，最长 60 分钟，够覆盖语言学习用的整段课程。 */
        private const val STEP_MS = 100L
        private const val MAX_POSITION_MS = 60L * 60L * 1000L

        /** 遥控器左右键的步长：100ms 一格太黏，1 秒更顺手。 */
        private const val FINE_STEP_MS = 1000L

        /** 模拟播放每 200ms 推进 100ms，节奏接近真实 1.0x 播放。 */
        private const val TICK_MS = 200L

        private const val PREFS_FILE = "f002_ab_loop"
        private const val KEY_A = "a_ms"
        private const val KEY_B = "b_ms"
        private const val KEY_POS = "position_ms"
        private const val KEY_LOOP = "loop_enabled"

        /** 设点被拒 / 控件置灰时的透明度。 */
        private const val DISABLED_ALPHA = 0.4f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 与全站一致：进页面前先把夜间模式定下来，避免闪一次浅色
        ThemeUtils.applyNightMode(Dependencies.configRepository.getNightMode())
        super.onCreate(savedInstanceState)
        binding = ActivityF002Binding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupTimeline()
        setupActions()
        setupLoopSwitch()

        applyState()
        loadPersisted()

        // 遥控器进来时焦点不能悬空，落在第一个可点的单元格上
        binding.btnSetA.requestFocus()
    }

    override fun onStop() {
        stopPlayback()
        savePersisted()
        super.onStop()
    }

    override fun onDestroy() {
        binding.root.removeCallbacks(playTick)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 焦点与控件

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun setupTimeline() {
        binding.seekTimeline.max = (MAX_POSITION_MS / STEP_MS).toInt()
        binding.seekTimeline.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                positionMs = progress.toLong() * STEP_MS
                renderPosition()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                seeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seeking = false
                savePersisted()
            }
        })

        // 左右键自己按 1 秒步进，不依赖 SeekBar 的默认步长（不同机型不一致）
        binding.seekTimeline.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    nudgeBy(-FINE_STEP_MS)
                    true
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    nudgeBy(FINE_STEP_MS)
                    true
                }

                else -> false
            }
        }
    }

    private fun setupActions() {
        binding.btnSetA.setOnClickListener {
            if (engine.setA(positionMs)) {
                savePersisted()
                applyState()
                CustomToast.show(this, getString(R.string.f002_toast_set_a, F002Engine.formatMillis(positionMs)))
            } else {
                applyState()
                CustomToast.show(this, engine.rejectReason ?: getString(R.string.f002_err_generic))
            }
        }

        binding.btnSetB.setOnClickListener {
            if (engine.setB(positionMs)) {
                savePersisted()
                applyState()
                CustomToast.show(this, getString(R.string.f002_toast_set_b, F002Engine.formatMillis(positionMs)))
            } else {
                applyState()
                CustomToast.show(this, engine.rejectReason ?: getString(R.string.f002_err_generic))
            }
        }

        binding.btnJumpA.setOnClickListener {
            val a = engine.aMs
            if (a == null) {
                CustomToast.show(this, getString(R.string.f002_err_no_a))
                return@setOnClickListener
            }
            stopPlayback()
            positionMs = a
            savePersisted()
            applyState()
            CustomToast.show(this, getString(R.string.f002_toast_jump_a, F002Engine.formatMillis(a)))
        }

        binding.btnJumpB.setOnClickListener {
            val b = engine.bMs
            if (b == null) {
                CustomToast.show(this, getString(R.string.f002_err_no_b))
                return@setOnClickListener
            }
            stopPlayback()
            positionMs = b
            savePersisted()
            applyState()
            CustomToast.show(this, getString(R.string.f002_toast_jump_b, F002Engine.formatMillis(b)))
        }

        binding.btnClear.setOnClickListener {
            engine.clear()
            loopCount = 0
            savePersisted()
            applyState()
            CustomToast.show(this, getString(R.string.f002_toast_cleared))
        }

        binding.btnPlayPause.setOnClickListener {
            if (playing) {
                stopPlayback()
                applyState()
            } else {
                startPlayback()
            }
        }
    }

    private fun setupLoopSwitch() {
        binding.switchLoop.setOnCheckedChangeListener { _, checked ->
            loopEnabled = checked
            savePersisted()
            applyState()
        }
    }

    // ------------------------------------------------------------------ 模拟播放

    private fun startPlayback() {
        // 标记完整且开着循环时，播放头在段外要归位到 A，
        // 否则要跑很久才碰得到一次 B，看不出「跳回」。
        val a = engine.aMs
        val b = engine.bMs
        if (loopEnabled && a != null && b != null && (positionMs < a || positionMs >= b)) {
            positionMs = a
        }
        playing = true
        binding.root.removeCallbacks(playTick)
        binding.root.post(playTick)
        applyState()
    }

    private fun stopPlayback() {
        playing = false
        binding.root.removeCallbacks(playTick)
    }

    /** 推进模拟位置；到 B 点按引擎给出的目标跳回，未设标记或关了循环就继续往后走。 */
    private fun advance(deltaMs: Long) {
        if (seeking) return

        positionMs = (positionMs + deltaMs).coerceAtMost(MAX_POSITION_MS)

        if (engine.shouldLoop(positionMs)) {
            if (loopEnabled) {
                val target = engine.loopTo()
                if (target != null) {
                    loopCount++
                    positionMs = target.coerceAtMost(MAX_POSITION_MS)
                    renderPosition()
                    applyState()
                    if (loopCount == 1) {
                        // 只在第一圈提示一次，后续靠计数展示，避免 Toast 排队
                        CustomToast.show(
                            this,
                            getString(R.string.f002_toast_looped, loopCount, F002Engine.formatMillis(positionMs))
                        )
                    }
                    return
                }
            }
            // 关了循环：不跳回，按普通播放继续往后走
        }

        if (positionMs >= MAX_POSITION_MS) {
            stopPlayback()
            applyState()
            return
        }

        renderPosition()
        applyState()
    }

    private fun nudgeBy(deltaMs: Long) {
        positionMs = (positionMs + deltaMs).coerceIn(0L, MAX_POSITION_MS)
        renderPosition()
        applyState()
    }

    // ------------------------------------------------------------------ 渲染

    private fun applyState() {
        if (binding.switchLoop.isChecked != loopEnabled) {
            binding.switchLoop.isChecked = loopEnabled
        }

        val a = engine.aMs
        val b = engine.bMs

        binding.tvAValue.text = a?.let { F002Engine.formatMillis(it) } ?: getString(R.string.f002_value_unset)
        binding.tvBValue.text = b?.let { F002Engine.formatMillis(it) } ?: getString(R.string.f002_value_unset)
        binding.tvAValue.setTextColor(getSemanticColor(if (a != null) R.color.theme_primary else R.color.theme_text_secondary))
        binding.tvBValue.setTextColor(getSemanticColor(if (b != null) R.color.theme_primary else R.color.theme_text_secondary))

        binding.tvSegment.text = if (a != null && b != null) {
            getString(
                R.string.f002_segment_value,
                F002Engine.formatMillis(a),
                F002Engine.formatMillis(b),
                (b - a) / 1000.0
            )
        } else {
            getString(R.string.f002_segment_empty)
        }

        binding.tvLoopCount.text = if (loopCount == 0) {
            getString(R.string.f002_loop_count_empty)
        } else {
            getString(R.string.f002_loop_count_value, loopCount)
        }
        binding.tvStatus.text =
            getString(if (engine.isMarked) R.string.f002_status_marked else R.string.f002_status_idle)

        binding.tvHint.text = getString(
            when {
                playing && engine.isMarked && loopEnabled -> R.string.f002_hint_looping
                playing -> R.string.f002_hint_playing
                engine.isMarked -> R.string.f002_hint_ready
                else -> R.string.f002_hint_setting
            }
        )

        val reason = engine.rejectReason
        binding.tvReject.visibility = if (reason.isNullOrEmpty()) View.GONE else View.VISIBLE
        binding.tvReject.text = reason ?: ""

        binding.btnPlayPause.setText(if (playing) R.string.f002_action_pause else R.string.f002_action_play)
        applyEnabled(binding.btnJumpA, a != null)
        applyEnabled(binding.btnJumpB, b != null)

        renderPosition()
    }

    private fun renderPosition() {
        binding.tvPosition.text = getString(R.string.f002_position_value, F002Engine.formatMillis(positionMs))
        val progress = ((positionMs / STEP_MS).toInt()).coerceIn(0, binding.seekTimeline.max)
        if (!seeking && binding.seekTimeline.progress != progress) {
            binding.seekTimeline.progress = progress
        }
    }

    private fun applyEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else DISABLED_ALPHA
        view.isFocusable = enabled
    }

    private fun getSemanticColor(resId: Int): Int =
        androidx.core.content.ContextCompat.getColor(this, resId)

    // ------------------------------------------------------------------ 持久化

    /** 首读要读盘，放 IO 线程；读回来再统一刷一次状态。 */
    private fun loadPersisted() {
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val sp = applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                PersistedMarks(
                    aMs = sp.getLong(KEY_A, -1L),
                    bMs = sp.getLong(KEY_B, -1L),
                    positionMs = sp.getLong(KEY_POS, 0L),
                    loopEnabled = sp.getBoolean(KEY_LOOP, true)
                ).also { prefs = sp }
            }

            val restored = engine.restore(
                aMs = if (snapshot.aMs >= 0) snapshot.aMs else null,
                bMs = if (snapshot.bMs >= 0) snapshot.bMs else null
            )
            loopEnabled = snapshot.loopEnabled
            positionMs = snapshot.positionMs.coerceIn(0L, MAX_POSITION_MS)

            if (!restored) {
                // 历史数据不合法就整组丢掉，别带着坏标记进播放
                val reason = engine.rejectReason
                engine.clear()
                savePersisted()
                reason?.let { CustomToast.show(this@F002Activity, it) }
            }
            applyState()
        }
    }

    private fun savePersisted() {
        val sp = prefs ?: applicationContext
            .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .also { prefs = it }
        // apply() 自己异步落盘，主线程只组一次提交
        sp.edit()
            .putLong(KEY_A, engine.aMs ?: -1L)
            .putLong(KEY_B, engine.bMs ?: -1L)
            .putLong(KEY_POS, positionMs)
            .putBoolean(KEY_LOOP, loopEnabled)
            .apply()
    }
}