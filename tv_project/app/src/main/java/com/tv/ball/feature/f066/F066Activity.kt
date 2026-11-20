package com.tv.ball.feature.f066

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF066Binding
import com.tv.ball.util.CustomToast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「远程遥控通道」页。
 *
 * 三段：通道（ws 地址 + 连接 / 断开 + 状态行）、指令（播放 / 暂停 / 上一集 / 下一集 / 进度跳转，
 * 外加音量微调）、指令日志（时间 + 指令 + 送达状态，最新的在最上面）。
 *
 * 数据流：按钮 → [RemoteControlServer.send] → 一行 JSON 出门；对面回来的报文在
 * [RemoteControlServer.onCommand] 里回调到主线程 → 记一条"收到"日志。
 * 地址留空时那条通道走**本地回环**：不连服务器，自己发自己收，用来在没服务端时把
 * 六种指令、状态机和日志跑一遍。
 *
 * 状态指示只有三档：连接中（警告色）/ 已连接（成功色）/ 已断开（次要文字色）。
 * 指令按钮只在 OPEN 时可用——不然点下去只会攒出一堆"未发送"，日志里全是噪声。
 *
 * 电视没有触摸屏：返回、地址框、连接、断开、五个指令键、进度框、音量行、清空日志都可聚焦，
 * nextFocus* 在 XML 里串成 返回 → 地址 → 连接 → 断开 → 播放 → 暂停 → 上一集 → 下一集 →
 * 进度 → 跳转 → 音量减 → 音量框 → 设置 → 音量加 → 清空日志。
 */
class F066Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF066Binding

    /** 当前这条通道；换地址或退出时换新的，旧的一定 release 掉。 */
    private var server: RemoteControlServer? = null

    /** 通道状态快照，决定指令按钮能不能点、状态行走什么颜色。 */
    private var linkState: RemoteControlServer.State = RemoteControlServer.State.CLOSED

    /** 当前音量（本地快照，实际值以电视端回执为准，这里只在没回执时兜底）。 */
    private var volume: Int = DEFAULT_VOLUME

    /** 日志行，最新的在 index 0，超过 [MAX_LOG_LINES] 掐尾。 */
    private val logLines = ArrayList<String>()

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val clock by lazy { SimpleDateFormat("HH:mm:ss", Locale.US) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF066Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.f066UrlInput.setText(prefs.getString(KEY_URL, F066Engine.DEFAULT_URL).orEmpty())
        binding.f066UrlInput.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        binding.f066SeekInput.setText(DEFAULT_SEEK)
        volume = prefs.getInt(KEY_VOLUME, DEFAULT_VOLUME)
            .coerceIn(F066Engine.MIN_VOLUME, F066Engine.MAX_VOLUME)
        binding.f066VolumeInput.setText(volume.toString())
        binding.f066VolumeValue.text = getString(R.string.f066_volume_value, volume)

        binding.f066ConnectButton.setOnClickListener { startChannel() }
        binding.f066DisconnectButton.setOnClickListener { stopChannel(announce = true) }

        binding.f066CmdPlay.setOnClickListener { fire(Command(CommandKind.PLAY)) }
        binding.f066CmdPause.setOnClickListener { fire(Command(CommandKind.PAUSE)) }
        binding.f066CmdPrev.setOnClickListener { fire(Command(CommandKind.PREV)) }
        binding.f066CmdNext.setOnClickListener { fire(Command(CommandKind.NEXT)) }
        binding.f066SeekButton.setOnClickListener { fireSeek() }

        binding.f066VolumeDown.setOnClickListener { stepVolume(-F066Engine.VOLUME_STEP) }
        binding.f066VolumeUp.setOnClickListener { stepVolume(F066Engine.VOLUME_STEP) }
        binding.f066VolumeSet.setOnClickListener { applyVolumeInput() }

        binding.f066LogClear.setOnClickListener { clearLog() }

        renderState(RemoteControlServer.State.CLOSED, getString(R.string.f066_state_idle))
        renderLog()
    }

    override fun onPause() {
        super.onPause()
        // 输入框可能刚改过还没失焦就被 Home 切走，这里补一次落盘
        prefs.edit()
            .putString(KEY_URL, binding.f066UrlInput.text?.toString()?.trim().orEmpty())
            .putInt(KEY_VOLUME, volume)
            .apply()
    }

    override fun onDestroy() {
        stopChannel(announce = false)
        super.onDestroy()
    }

    // ===== 通道 =====

    /**
     * 连一条通道。
     *
     * 先停旧的再建新的：同一条连接上换地址是做不到的，而"改完地址点连接"又是最常见的操作，
     * 所以这里干脆每次重建。地址为空就是回环演示，界面会多显示一句提示。
     */
    private fun startChannel() {
        val url = binding.f066UrlInput.text?.toString()?.trim().orEmpty()
        stopChannel(announce = false)

        val created = F066Engine.newServer(url)
        created.onState { state, reason -> onLinkState(state, reason) }
        created.onCommand { command -> onCommandArrived(command) }
        server = created

        linkState = RemoteControlServer.State.CONNECTING
        renderState(linkState, getString(R.string.f066_state_connecting))
        appendLog(
            getString(R.string.f066_log_dial),
            url.ifEmpty { getString(R.string.f066_mode_loopback) },
            getString(R.string.f066_log_pending)
        )
        binding.f066ModeHint.visibility = if (url.isEmpty()) View.VISIBLE else View.GONE
        created.connect()
    }

    /** 断开并释放当前通道；[announce] 决定要不要在日志里留一条痕迹。 */
    private fun stopChannel(announce: Boolean) {
        val current = server ?: return
        server = null
        current.release()
        linkState = RemoteControlServer.State.CLOSED
        renderState(linkState, getString(R.string.f066_state_closed))
        if (announce) {
            appendLog(
                getString(R.string.f066_log_dial),
                getString(R.string.f066_state_closed),
                getString(R.string.f066_log_pending)
            )
        }
    }

    /** 状态回调（已经在主线程）：换指示色，状态变化记一行日志。 */
    private fun onLinkState(state: RemoteControlServer.State, reason: String) {
        // 用户已经切走这条通道时迟到的回调直接丢掉，别把新连接的状态覆盖了
        if (server == null && state != RemoteControlServer.State.CLOSED) return
        linkState = state
        renderState(state, reason)
        appendLog(getString(R.string.f066_log_state), stateLabel(state), reason)
    }

    /** 收到对面（或回环）的指令：记一行"收到"，顺带把音量快照同步过来。 */
    private fun onCommandArrived(command: Command) {
        if (command.kind == CommandKind.VOLUME) {
            command.value?.let { value ->
                volume = value.coerceIn(F066Engine.MIN_VOLUME, F066Engine.MAX_VOLUME)
                binding.f066VolumeValue.text = getString(R.string.f066_volume_value, volume)
            }
        }
        appendLog(
            F066Engine.describe(command),
            getString(R.string.f066_log_received),
            getString(R.string.f066_log_delivered)
        )
    }

    // ===== 指令 =====

    /** 统一的发送入口：没连上就只记一行"未发送"，不假装修好了。 */
    private fun fire(command: Command) {
        val current = server
        if (current == null || linkState != RemoteControlServer.State.OPEN) {
            appendLog(
                F066Engine.describe(command),
                getString(R.string.f066_log_offline),
                getString(R.string.f066_log_failed)
            )
            CustomToast.show(this, getString(R.string.f066_toast_offline))
            return
        }
        val queued = current.send(command)
        appendLog(
            F066Engine.describe(command),
            if (queued) getString(R.string.f066_log_sent) else getString(R.string.f066_log_failed),
            if (queued) getString(R.string.f066_log_delivered) else getString(R.string.f066_log_not_delivered)
        )
        if (!queued) {
            CustomToast.show(this, getString(R.string.f066_toast_send_fail))
        }
    }

    /** 进度跳转：先在本地校验，不合格就提示，不白发一条电视端必然忽略的 SEEK。 */
    private fun fireSeek() {
        val raw = binding.f066SeekInput.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            CustomToast.show(this, getString(R.string.f066_err_seek_empty))
            return
        }
        val target = F066Engine.parseSeek(raw)
        if (target == null) {
            CustomToast.show(this, getString(R.string.f066_err_seek_invalid))
            return
        }
        fire(Command(CommandKind.SEEK, target))
    }

    /** ± 步进：以本地音量快照为基准，越界夹紧并提示，发出去的永远是合法值。 */
    private fun stepVolume(delta: Int) {
        val target = (volume + delta).coerceIn(F066Engine.MIN_VOLUME, F066Engine.MAX_VOLUME)
        if (target == volume) {
            CustomToast.show(this, getString(R.string.f066_volume_limit, target))
            return
        }
        volume = target
        binding.f066VolumeValue.text = getString(R.string.f066_volume_value, target)
        fire(Command(CommandKind.VOLUME, target))
    }

    /** "设置"按钮：用输入框里的绝对值发一条 VOLUME。 */
    private fun applyVolumeInput() {
        val value = F066Engine.parseVolume(binding.f066VolumeInput.text?.toString())
        if (value == null) {
            CustomToast.show(this, getString(R.string.f066_err_volume_invalid))
            return
        }
        volume = value
        binding.f066VolumeValue.text = getString(R.string.f066_volume_value, value)
        fire(Command(CommandKind.VOLUME, value))
    }

    // ===== 渲染 =====

    private fun renderState(state: RemoteControlServer.State, reason: String) {
        binding.f066StatusLine.text = getString(R.string.f066_status_line, stateLabel(state), reason)
        binding.f066StatusLine.setTextColor(
            ContextCompat.getColor(
                this,
                when (state) {
                    RemoteControlServer.State.OPEN -> R.color.theme_success
                    RemoteControlServer.State.CONNECTING -> R.color.theme_warning
                    RemoteControlServer.State.CLOSED -> R.color.theme_text_secondary
                }
            )
        )

        val open = state == RemoteControlServer.State.OPEN
        val commands = listOf(
            binding.f066CmdPlay,
            binding.f066CmdPause,
            binding.f066CmdPrev,
            binding.f066CmdNext,
            binding.f066SeekButton,
            binding.f066VolumeDown,
            binding.f066VolumeUp,
            binding.f066VolumeSet
        )
        commands.forEach {
            it.isEnabled = open
            it.alpha = if (open) 1f else 0.45f
        }
        binding.f066DisconnectButton.isEnabled = state != RemoteControlServer.State.CLOSED
        binding.f066DisconnectButton.alpha = if (binding.f066DisconnectButton.isEnabled) 1f else 0.45f
        // 连接按钮是 LinearLayout 壳（内含图标+文案），改文案要落在子 TextView 上
        binding.f066ConnectButton.findViewById<android.widget.TextView>(R.id.f066ConnectLabel)?.text =
            getString(
                if (state == RemoteControlServer.State.CLOSED) {
                    R.string.f066_action_connect
                } else {
                    R.string.f066_action_reconnect
                }
            )
    }

    private fun stateLabel(state: RemoteControlServer.State): String = getString(
        when (state) {
            RemoteControlServer.State.CONNECTING -> R.string.f066_state_connecting
            RemoteControlServer.State.OPEN -> R.string.f066_state_open
            RemoteControlServer.State.CLOSED -> R.string.f066_state_closed
        }
    )

    // ===== 日志 =====

    /** 追加一行日志：时间 + 指令 + 送达状态，插到最前面，超过上限掐尾。 */
    private fun appendLog(command: String, status: String, delivery: String) {
        logLines.add(0, getString(R.string.f066_log_line, clock.format(Date()), command, status, delivery))
        while (logLines.size > MAX_LOG_LINES) {
            logLines.removeAt(logLines.size - 1)
        }
        renderLog()
    }

    private fun clearLog() {
        logLines.clear()
        renderLog()
    }

    /** 日志区按 [logLines] 重建；空的时候显示一句占位说明，不留白板。 */
    private fun renderLog() {
        val container = binding.f066LogList
        container.removeAllViews()
        binding.f066LogEmpty.visibility = if (logLines.isEmpty()) View.VISIBLE else View.GONE
        logLines.forEach { line ->
            container.addView(TextView(this).apply {
                text = line
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@F066Activity, R.color.theme_text_secondary))
                setPadding(0, dp(4), 0, dp(4))
            })
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS = "f066_remote_channel"
        private const val KEY_URL = "ws_url"
        private const val KEY_VOLUME = "volume"

        /** 进度输入框初值：从 0 开始，改这一格就能跳。 */
        private const val DEFAULT_SEEK = "00:00:00"

        /** 音量初值：和电视出厂差不多的一档，不是 0（0 在有些播放器上是静音而不是音量）。 */
        private const val DEFAULT_VOLUME = 30

        /** 日志行上限：再多也没人看，还会把页面撑得很长。 */
        private const val MAX_LOG_LINES = 40
    }
}