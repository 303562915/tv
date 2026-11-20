package com.tv.ball.feature.f062

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF062Binding
import com.tv.ball.util.CustomToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「投屏会话控制」页。
 *
 * 三段内容：会话（控制地址 + 设备名 + 当前状态 / 音量 / 进度）、控制（播放 / 重播 / 暂停 / 停止、
 * 进度跳转、音量、刷新）、最近一次 SOAP 响应（默认折叠，点标题展开）。
 *
 * 数据流：按钮 → [F062Engine] 的某个动作（阻塞，放 Dispatchers.IO）→ 拿回 [Outcome] →
 * 用 outcome.session 覆盖本地会话快照 → 渲染。界面**不自己猜状态**：按钮点完显示什么，
 * 完全取决于设备回执，所以"点了暂停但设备回了 701"会立刻显示成红色失败，而不是假装修好了。
 *
 * 错误收敛交给引擎（超时 / 拒绝连接 / HTTP 码 / UPnP 码都变成一句话），这里只按
 * error / note / 成功三档上色：错误红、部分成功警告黄、成功主题绿。
 *
 * 电视没有触摸屏：返回、两个输入框、九个动作、折叠头都可聚焦，焦点顺序在 XML 里用
 * nextFocus* 串成 返回 → 地址 → 设备名 → 播放 → 重播 → 暂停 → 停止 → 进度 → 跳转 →
 * 音量输入 → 减 → 设置 → 加 → 刷新 → 回执。
 */
class F062Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF062Binding

    private val engine by lazy { F062Engine() }

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** 当前会话快照；null 表示还没建立过（第一次点动作时按输入框建立）。 */
    private var session: Session? = null

    /** 当前在跑的动作；连点先取消旧的，避免两次回执互相覆盖。 */
    private var job: Job? = null

    /** 回执面板是否折叠。 */
    private var collapsed: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF062Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.f062ControlUrl.setText(
            prefs.getString(KEY_CONTROL_URL, F062Engine.DEFAULT_CONTROL_URL).orEmpty()
        )
        binding.f062ControlUrl.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        binding.f062DeviceName.setText(prefs.getString(KEY_DEVICE, DEFAULT_DEVICE).orEmpty())
        binding.f062SeekInput.setText(DEFAULT_SEEK)
        binding.f062VolumeInput.setText(
            prefs.getInt(KEY_VOLUME, DEFAULT_VOLUME).coerceIn(F062Engine.MIN_VOLUME, F062Engine.MAX_VOLUME)
                .toString()
        )

        binding.f062Play.setOnClickListener { runAction { engine.play(it) } }
        binding.f062Restart.setOnClickListener { runAction { engine.restart(it) } }
        binding.f062Pause.setOnClickListener { runAction { engine.pause(it) } }
        binding.f062Stop.setOnClickListener { runAction { engine.stop(it) } }
        binding.f062Refresh.setOnClickListener { runAction { engine.refresh(it) } }

        binding.f062SeekButton.setOnClickListener { startSeek() }
        binding.f062VolumeDown.setOnClickListener { stepVolume(-F062Engine.VOLUME_STEP) }
        binding.f062VolumeUp.setOnClickListener { stepVolume(F062Engine.VOLUME_STEP) }
        binding.f062VolumeSet.setOnClickListener { applyVolumeInput() }

        setupToggle()

        renderSession(null)
        binding.f062StatusLine.text = getString(R.string.f062_status_idle)
        binding.f062StatusLine.setTextColor(ContextCompat.getColor(this, R.color.theme_text_secondary))
        binding.f062ResponseBody.text = getString(R.string.f062_response_empty)
    }

    override fun onPause() {
        super.onPause()
        // 输入框里可能刚改过还没失焦就被 Home 键切走，这里补一次落盘
        prefs.edit()
            .putString(
                KEY_CONTROL_URL,
                binding.f062ControlUrl.text?.toString()?.trim().orEmpty()
                    .ifEmpty { F062Engine.DEFAULT_CONTROL_URL }
            )
            .putString(KEY_DEVICE, binding.f062DeviceName.text?.toString()?.trim().orEmpty())
            .apply()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    // ===== 动作 =====

    /**
     * 统一的动作入口。
     *
     * 先按输入框取/更新会话，再把动作扔去 IO；回执回来后主线程渲染。
     * [block] 收到的一定是**动作发起时**的会话快照，中途用户改地址也不会串味。
     */
    private fun runAction(block: (Session) -> Outcome) {
        val target = ensureSession()
        if (target == null) {
            CustomToast.show(this, getString(R.string.f062_err_no_url))
            return
        }
        setBusy(true)
        job?.cancel()
        job = lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { block(target) }
            if (isFinishing || isDestroyed) return@launch
            setBusy(false)
            applyOutcome(outcome)
            binding.f062ResponseBody.text = outcome.trace.ifBlank {
                getString(R.string.f062_response_empty)
            }
            CustomToast.show(this@F062Activity, toastFor(outcome))
        }
    }

    /** 进度跳转：先本地校验格式，不合格直接提示，不白发一发会让设备回 711 的请求。 */
    private fun startSeek() {
        val raw = binding.f062SeekInput.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            CustomToast.show(this, getString(R.string.f062_err_seek_empty))
            return
        }
        val target = F062Engine.parseRelTime(raw)
        if (target == null) {
            CustomToast.show(this, getString(R.string.f062_err_seek_invalid))
            return
        }
        runAction { engine.seek(it, target) }
    }

    /** ± 步进：以当前会话音量为基准，不读输入框，避免"输入框里是手打字"时跳得莫名其妙。 */
    private fun stepVolume(delta: Int) {
        val base = session?.volume ?: readVolumeInput()
        val target = (base + delta).coerceIn(F062Engine.MIN_VOLUME, F062Engine.MAX_VOLUME)
        if (target == base) {
            CustomToast.show(
                this,
                getString(R.string.f062_toast_volume_limit, target)
            )
            return
        }
        runAction { engine.setVolume(it, target) }
    }

    /** "设置"按钮：用输入框里的绝对值下发 SetVolume。 */
    private fun applyVolumeInput() {
        val raw = binding.f062VolumeInput.text?.toString()?.trim().orEmpty()
        val value = raw.toIntOrNull()
        if (value == null || value !in F062Engine.MIN_VOLUME..F062Engine.MAX_VOLUME) {
            CustomToast.show(this, getString(R.string.f062_err_volume_invalid))
            return
        }
        runAction { engine.setVolume(it, value) }
    }

    /**
     * 按输入框取当前会话；地址变了就重建（状态回 IDLE，不把旧设备的播放态带过去）。
     *
     * 返回 null 只有一个原因：控制地址是空的——这种情况连请求都不该发。
     */
    private fun ensureSession(): Session? {
        val url = binding.f062ControlUrl.text?.toString()?.trim().orEmpty()
        if (url.isEmpty()) return null
        val device = binding.f062DeviceName.text?.toString()?.trim().orEmpty().ifEmpty { DEFAULT_DEVICE }

        val current = session
        if (current != null && current.controlUrl == url) {
            val renamed = current.copy(device = device)
            session = renamed
            return renamed
        }
        val fresh = Session(device = device, controlUrl = url, volume = readVolumeInput())
        session = fresh
        return fresh
    }

    private fun readVolumeInput(): Int =
        binding.f062VolumeInput.text?.toString()?.trim()?.toIntOrNull()
            ?.coerceIn(F062Engine.MIN_VOLUME, F062Engine.MAX_VOLUME)
            ?: DEFAULT_VOLUME

    // ===== 渲染 =====

    private fun applyOutcome(outcome: Outcome) {
        session = outcome.session
        renderSession(outcome.session)
        persist(outcome.session)

        val error = outcome.error
        val note = outcome.note
        binding.f062StatusLine.text = when {
            error != null -> error
            note != null -> note
            else -> getString(R.string.f062_status_ok, outcome.session.lastSummary)
        }
        binding.f062StatusLine.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    error != null -> R.color.theme_error
                    note != null -> R.color.theme_warning
                    else -> R.color.theme_success
                }
            )
        )
    }

    private fun renderSession(current: Session?) {
        if (current == null) {
            binding.f062StateValue.text = getString(R.string.f062_state_idle)
            binding.f062VolumeValue.text = getString(R.string.f062_value_none)
            binding.f062RelTimeValue.text = getString(R.string.f062_value_none)
            binding.f062SessionValue.text = getString(R.string.f062_session_value, DEFAULT_DEVICE, getString(R.string.f062_value_none))
            return
        }
        binding.f062StateValue.text = stateLabel(current.state)
        binding.f062VolumeValue.text = getString(R.string.f062_volume_value, current.volume)
        binding.f062RelTimeValue.text = F062Engine.formatRelTime(current.relTimeMs)
        binding.f062SessionValue.text =
            getString(R.string.f062_session_value, current.device, current.controlUrl)
        // 设备回的音量才是准的，回填输入框让"设置"从真实值起步
        binding.f062VolumeInput.setText(current.volume.toString())
    }

    private fun stateLabel(state: Session.State): String = getString(
        when (state) {
            Session.State.IDLE -> R.string.f062_state_idle
            Session.State.PLAYING -> R.string.f062_state_playing
            Session.State.PAUSED -> R.string.f062_state_paused
        }
    )

    private fun toastFor(outcome: Outcome): String {
        val error = outcome.error
        val note = outcome.note
        return when {
            error != null -> getString(R.string.f062_toast_fail, error)
            note != null -> note
            else -> getString(R.string.f062_toast_ok, outcome.session.lastAction)
        }
    }

    /** 动作在跑的时候把九个动作行都禁掉：投屏设备本来就慢，连点只会排一长队。 */
    private fun setBusy(busy: Boolean) {
        val targets = listOf(
            binding.f062Play,
            binding.f062Restart,
            binding.f062Pause,
            binding.f062Stop,
            binding.f062SeekButton,
            binding.f062VolumeDown,
            binding.f062VolumeSet,
            binding.f062VolumeUp,
            binding.f062Refresh
        )
        val alpha = if (busy) 0.5f else 1f
        targets.forEach {
            it.isEnabled = !busy
            it.alpha = alpha
        }
    }

    // ===== 回执折叠 =====

    private fun setupToggle() {
        binding.f062ResponseToggle.setOnClickListener { setCollapsed(!collapsed) }
        setCollapsed(true)
    }

    private fun setCollapsed(value: Boolean) {
        collapsed = value
        binding.f062ResponseBody.visibility = if (value) View.GONE else View.VISIBLE
        binding.f062ResponseArrow.text = getString(
            if (value) R.string.f062_arrow_collapsed else R.string.f062_arrow_expanded
        )
        binding.f062ResponseToggle.contentDescription = getString(
            if (value) R.string.f062_response_show else R.string.f062_response_hide
        )
    }

    private fun persist(current: Session) {
        prefs.edit()
            .putString(KEY_CONTROL_URL, current.controlUrl)
            .putString(KEY_DEVICE, current.device)
            .putInt(KEY_VOLUME, current.volume)
            .apply()
    }

    companion object {
        private const val PREFS = "f062_cast_session"
        private const val KEY_CONTROL_URL = "control_url"
        private const val KEY_DEVICE = "device"
        private const val KEY_VOLUME = "volume"

        /** 设备名的兜底值，用户改成客厅电视 / 卧室投影都行。 */
        private const val DEFAULT_DEVICE = "客厅电视"

        /** 进度输入框的初值：从 0 开始，改这一格就能跳。 */
        private const val DEFAULT_SEEK = "00:00:00"

        /** 音量输入框初值。 */
        private const val DEFAULT_VOLUME = 30
    }
}
