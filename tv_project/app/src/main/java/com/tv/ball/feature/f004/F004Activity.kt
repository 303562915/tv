package com.tv.ball.feature.f004

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF004Binding
import com.tv.ball.di.Dependencies
import com.tv.ball.util.CustomToast
import kotlin.math.abs

/**
 * 「倍速曲线与长按倍速」页。
 *
 * 页面上三个区域都是真状态，不是演示动画：
 * 1. 档位集合 —— 每行是一次 addSpeed / removeSpeed，结果与失败原因都来自 [F004Engine]；
 * 2. 长按演示 —— 按住确认键真的走 [F004Engine.applyLongPress]，松手走 releaseLongPress，
 *    顶部的「当前倍速」显示的是 engine.effectiveSpeed，所以能直接看到临时倍速与常驻倍速的差别；
 * 3. 常驻倍速 —— 变更后同时写 ConfigRepository 的 playback_speed，播放页读的也是这一份，
 *    因此返回播放页、切集、进程重建后倍速都保持。
 */
class F004Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF004Binding

    private lateinit var engine: F004Engine

    private val configRepository by lazy { Dependencies.configRepository }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 自动演示到点自动松手。 */
    private val autoDemoEnd = Runnable {
        autoDemoRunning = false
        stopLongPress(announce = true)
    }

    private var autoDemoRunning = false

    /** 候选倍速：只服务「新增档位」区，不写盘。 */
    private var candidateSpeed: Float = DEFAULT_CANDIDATE

    /** 上一次渲染出来的档位集合；集合没变就不重建行，免得焦点被打断。 */
    private var renderedTiers: List<Float> = emptyList()

    private val speedRows = ArrayList<SpeedRowView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF004Binding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = F004Engine.get(this)
        // 播放页的倍速入口写的是 ConfigRepository，进页先对齐一次，
        // 这样「播放页调到 1.5x 再进来」看到的是 1.5x，而不是 F004 自己记的旧值
        engine.syncCurrent(configRepository.getPlaybackSpeed())
        candidateSpeed = nextFreeCandidate(engine.currentSpeed)

        setupActions()
        setupPresetRows()
        setupLongPressDemo()

        renderAll()
        // 电视没有触摸屏：进页面就把焦点落在当前倍速那一行，遥控器立刻能勾选
        binding.f004SpeedList.post { focusSpeedRow(engine.currentSpeed) }
    }

    override fun onResume() {
        super.onResume()
        // 播放页可能自己改过倍速，回到这里先读回来再重画
        engine.syncCurrent(configRepository.getPlaybackSpeed())
        renderAll()
    }

    override fun onPause() {
        super.onPause()
        cancelAutoDemo()
        // 离开页面不保留长按态：播放页读 effectiveSpeed 时必须是常驻倍速
        while (engine.isLongPressing) {
            engine.releaseLongPress()
        }
        configRepository.setPlaybackSpeed(engine.currentSpeed)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
    }

    // ===== 交互接线 =====

    private fun setupActions() {
        binding.f004ActionPrev.setOnClickListener { cycle(forward = false) }
        binding.f004ActionNext.setOnClickListener { cycle(forward = true) }
        binding.f004CandidateMinus.setOnClickListener { stepCandidate(-F004Speeds.STEP) }
        binding.f004CandidatePlus.setOnClickListener { stepCandidate(F004Speeds.STEP) }
        binding.f004CandidateAdd.setOnClickListener { addCandidate() }
        binding.f004ActionAuto.setOnClickListener { startAutoDemo() }
        binding.f004ActionReset.setOnClickListener { resetDefault() }
    }

    private fun setupPresetRows() {
        binding.f004Preset15.setOnClickListener { choosePreset(PRESET_15) }
        binding.f004Preset20.setOnClickListener { choosePreset(PRESET_20) }
        binding.f004Preset30.setOnClickListener { choosePreset(PRESET_30) }
    }

    private fun setupLongPressDemo() {
        // 触摸设备没有确认键，点一下等价于「自动长按一次」，保证两种输入都能验证
        binding.f004LongPressArea.isClickable = true
        binding.f004LongPressArea.setOnClickListener { startAutoDemo() }
        binding.f004LongPressArea.setOnKeyListener { _, keyCode, event -> handleLongPressKey(keyCode, event) }
    }

    /**
     * 只认确认键：方向键必须放行，否则焦点会被长按演示区关在里面出不去。
     * 按下即压栈（遥控器会连续发 repeat 事件，只认第一次），抬起即出栈。
     */
    private fun handleLongPressKey(keyCode: Int, event: KeyEvent): Boolean {
        if (!isConfirmKey(keyCode)) return false
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    // 手动按下就取消还没跑完的自动演示，避免两次长按的栈叠在一起
                    cancelAutoDemo()
                    pressDown()
                }
                true
            }

            KeyEvent.ACTION_UP -> {
                stopLongPress(announce = true)
                true
            }

            else -> false
        }
    }

    private fun isConfirmKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> true
        else -> false
    }

    private fun choosePreset(value: Float) {
        val result = engine.setLongPressPreset(value)
        if (!result.success) {
            reportFailure(result)
            return
        }
        clearFailure()
        renderPresets()
        CustomToast.show(this, getString(R.string.f004_toast_preset, F004Speeds.label(value)))
    }

    private fun cycle(forward: Boolean) {
        val result = engine.cycleNext(forward)
        if (!result.success) {
            reportFailure(result)
            return
        }
        clearFailure()
        persistCurrentSpeed()
        renderAll()
        CustomToast.show(this, getString(R.string.f004_toast_current, F004Speeds.label(result.speed)))
    }

    private fun stepCandidate(delta: Float) {
        candidateSpeed = F004Speeds.step(candidateSpeed, delta)
        renderCandidate()
    }

    private fun addCandidate() {
        val result = engine.addSpeed(candidateSpeed)
        if (!result.success) {
            reportFailure(result)
            return
        }
        clearFailure()
        candidateSpeed = nextFreeCandidate(result.speed)
        renderAll()
        focusSpeedRow(result.speed)
        CustomToast.show(this, getString(R.string.f004_toast_added, F004Speeds.label(result.speed)))
    }

    private fun resetDefault() {
        engine.resetDefault()
        persistCurrentSpeed()
        candidateSpeed = nextFreeCandidate(engine.currentSpeed)
        clearFailure()
        renderAll()
        focusSpeedRow(engine.currentSpeed)
        CustomToast.show(this, getString(R.string.f004_toast_reset))
    }

    /** 档位行的确认键：已启用就删，未启用就加，失败原因来自引擎。 */
    private fun toggleSpeed(value: Float) {
        val adding = !engine.hasSpeed(value)
        val result = if (adding) engine.addSpeed(value) else engine.removeSpeed(value)
        if (!result.success) {
            reportFailure(result)
            return
        }
        clearFailure()
        persistCurrentSpeed()
        renderCurrentState()
        renderSpeedRows(focusValue = value)
        val message = if (adding) R.string.f004_toast_added else R.string.f004_toast_removed
        CustomToast.show(this, getString(message, F004Speeds.label(value)))
    }

    // ===== 长按态 =====

    /** 压一次长按倍速，成功返回 true。 */
    private fun pressDown(): Boolean {
        val result = engine.applyLongPress(engine.longPressPreset)
        if (!result.success) {
            reportFailure(result)
            return false
        }
        clearFailure()
        binding.f004LongPressResult.text =
            getString(R.string.f004_demo_pressing, F004Speeds.label(engine.effectiveSpeed))
        renderCurrentState()
        return true
    }

    private fun stopLongPress(announce: Boolean) {
        // 兜底清栈：多层长按同时触发时也要一次回到常驻倍速
        while (engine.isLongPressing) {
            engine.releaseLongPress()
        }
        binding.f004LongPressResult.text =
            getString(R.string.f004_demo_released, F004Speeds.label(engine.effectiveSpeed))
        renderCurrentState()
        if (announce) {
            CustomToast.show(
                this,
                getString(R.string.f004_toast_released, F004Speeds.label(engine.effectiveSpeed))
            )
        }
    }

    private fun startAutoDemo() {
        if (autoDemoRunning) return
        cancelAutoDemo()
        if (!pressDown()) return
        autoDemoRunning = true
        mainHandler.postDelayed(autoDemoEnd, AUTO_DEMO_MS)
    }

    private fun cancelAutoDemo() {
        mainHandler.removeCallbacks(autoDemoEnd)
        autoDemoRunning = false
    }

    // ===== 渲染 =====

    private fun renderAll() {
        renderCurrentState()
        renderSpeedRows()
        renderCandidate()
        renderPresets()
    }

    private fun renderCurrentState() {
        val current = engine.currentSpeed
        val effective = engine.effectiveSpeed

        binding.f004CurrentValue.text =
            getString(R.string.f004_current_value, F004Speeds.label(effective))
        binding.f004CurrentMode.text = if (engine.isLongPressing) {
            getString(R.string.f004_mode_long, F004Speeds.label(current))
        } else {
            getString(R.string.f004_mode_idle, F004Speeds.label(current))
        }
        binding.f004PersistNote.text =
            getString(R.string.f004_persist_value, F004Speeds.label(current))
        // 长按中的数字染成警示色，一眼分出「临时倍速」与「存下来的常驻倍速」
        binding.f004CurrentValue.setTextColor(
            ContextCompat.getColor(
                this,
                if (engine.isLongPressing) R.color.theme_warning else R.color.theme_primary
            )
        )
    }

    private fun renderSpeedRows(focusValue: Float? = null) {
        val tiers = engine.speeds
        val changed = tiers != renderedTiers
        if (changed) {
            renderedTiers = tiers
            rebuildSpeedRows()
        }
        updateRowStates()
        if (changed) {
            focusSpeedRow(focusValue ?: engine.currentSpeed)
        }
    }

    private fun rebuildSpeedRows() {
        val container = binding.f004SpeedList
        container.removeAllViews()
        speedRows.clear()

        val rowHeight = resources.getDimensionPixelSize(R.dimen.list_row_min_height)
        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        val pad = dp(12)
        val checkSize = dp(20)

        engine.speeds.forEachIndexed { index, value ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (index == 0) 0 else gap }
                minimumHeight = rowHeight
                setPadding(pad, 0, pad, 0)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                background = ContextCompat.getDrawable(this@F004Activity, R.drawable.bg_mode_card)
                foreground = ContextCompat.getDrawable(this@F004Activity, R.drawable.bg_focus_ring)
                setOnClickListener { toggleSpeed(value) }
            }

            val label = TextView(this).apply {
                text = F004Speeds.label(value)
                TextViewCompat.setTextAppearance(this, R.style.TextAppearance_TVPlayer_Body)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val status = TextView(this).apply {
                TextViewCompat.setTextAppearance(this, R.style.TextAppearance_TVPlayer_Caption)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val check = ImageView(this).apply {
                setImageResource(R.drawable.ic_check_primary)
                contentDescription = null
                layoutParams = LinearLayout.LayoutParams(checkSize, checkSize).apply {
                    marginStart = pad
                }
            }

            row.addView(label)
            row.addView(status)
            row.addView(check)
            container.addView(row)
            speedRows.add(SpeedRowView(value, row, status, check))
        }

        // 动态行之间显式串 nextFocus：默认焦点搜索会跨到卡片外的按钮上，遥控器手感很差
        speedRows.forEachIndexed { index, item ->
            item.root.id = View.generateViewId()
            if (index > 0) item.root.nextFocusUpId = speedRows[index - 1].root.id
            if (index < speedRows.lastIndex) item.root.nextFocusDownId = speedRows[index + 1].root.id
        }
    }

    private fun updateRowStates() {
        speedRows.forEach { row ->
            val enabled = engine.hasSpeed(row.value)
            val isCurrent = abs(row.value - engine.currentSpeed) < F004Speeds.EPS
            row.root.background = ContextCompat.getDrawable(
                this,
                if (enabled) R.drawable.bg_mode_card_selected else R.drawable.bg_mode_card
            )
            row.check.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
            row.status.text = getString(
                when {
                    isCurrent -> R.string.f004_tier_current
                    enabled -> R.string.f004_tier_enabled
                    else -> R.string.f004_tier_disabled
                }
            )
        }
    }

    private fun renderCandidate() {
        binding.f004CandidateValue.text = F004Speeds.label(candidateSpeed)
        binding.f004CandidateState.setText(
            if (engine.hasSpeed(candidateSpeed)) R.string.f004_add_state_in
            else R.string.f004_add_state_out
        )
    }

    private fun renderPresets() {
        applyRowState(
            binding.f004Preset15,
            binding.f004CheckPreset15,
            abs(engine.longPressPreset - PRESET_15) < F004Speeds.EPS
        )
        applyRowState(
            binding.f004Preset20,
            binding.f004CheckPreset20,
            abs(engine.longPressPreset - PRESET_20) < F004Speeds.EPS
        )
        applyRowState(
            binding.f004Preset30,
            binding.f004CheckPreset30,
            abs(engine.longPressPreset - PRESET_30) < F004Speeds.EPS
        )
        binding.f004PresetHint.text =
            getString(R.string.f004_preset_hint, F004Speeds.label(engine.longPressPreset))
    }

    /** 选中态只用 2dp primary 描边 + 对勾，和外观主题页保持一致。 */
    private fun applyRowState(option: View, check: View, selected: Boolean) {
        option.setBackgroundResource(
            if (selected) R.drawable.bg_mode_card_selected else R.drawable.bg_mode_card
        )
        check.visibility = if (selected) View.VISIBLE else View.INVISIBLE
    }

    private fun focusSpeedRow(preferred: Float) {
        if (speedRows.isEmpty()) return
        val target = speedRows.firstOrNull { abs(it.value - preferred) < F004Speeds.EPS }
            ?: speedRows.firstOrNull { abs(it.value - engine.currentSpeed) < F004Speeds.EPS }
            ?: speedRows.first()
        target.root.requestFocus()
    }

    // ===== 小工具 =====

    private fun persistCurrentSpeed() {
        // 播放页与这里共用 ConfigRepository 的 playback_speed，两个入口写同一份就不会打架
        configRepository.setPlaybackSpeed(engine.currentSpeed)
        binding.f004PersistNote.text =
            getString(R.string.f004_persist_value, F004Speeds.label(engine.currentSpeed))
    }

    private fun reportFailure(result: F004Result) {
        val reason = result.reason
        binding.f004ActionNote.text = getString(R.string.f004_reject_note, reason)
        CustomToast.show(this, getString(R.string.f004_toast_rejected, reason))
    }

    private fun clearFailure() {
        binding.f004ActionNote.setText(R.string.f004_reject_empty)
    }

    /** 候选值默认落在第一个还没被占用的档位上，连着点「加入列表」不会一直撞重复。 */
    private fun nextFreeCandidate(from: Float): Float {
        var value = F004Speeds.roundTo2(from)
        repeat(F004Speeds.MAX_TIERS * 20) {
            if (!engine.hasSpeed(value)) return value
            value = F004Speeds.step(value, F004Speeds.STEP)
        }
        return value
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    private companion object {
        /** 自动演示的长按时长，够看清倍速变化又不至于等太久。 */
        const val AUTO_DEMO_MS = 900L

        /** 候选倍速初值：默认曲线里正好没有 1.75x。 */
        const val DEFAULT_CANDIDATE = 1.75f

        const val PRESET_15 = 1.5f
        const val PRESET_20 = 2.0f
        const val PRESET_30 = 3.0f
    }
}

/** 档位行里需要按状态改写的几个子控件。 */
private class SpeedRowView(
    val value: Float,
    val root: LinearLayout,
    val status: TextView,
    val check: ImageView
)
