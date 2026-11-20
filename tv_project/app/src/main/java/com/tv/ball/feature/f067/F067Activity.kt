package com.tv.ball.feature.f067

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF067Binding
import com.tv.ball.util.CustomToast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「内置媒体服务器」页。
 *
 * 三段：服务（端口 + 启动 / 停止 + 状态行 + 访问地址）、媒体目录（根目录路径 + 说明）、
 * 请求日志（时间 + 方法 + 路径 + 状态码，最新的在最上面）。
 *
 * 数据流：点「启动」→ [F067Engine.createServer] 造一个 [MediaServer] → [MediaServer.startServer]
 * 绑端口（被占就顺延，最多 5 个）→ 成功就把地址写进状态区；之后每个请求由 [MediaServer.serve]
 * 处理，顺手通过 onLog 回调一行日志 → 这里 post 回主线程落进列表。
 *
 * 电视没有触摸屏：返回、端口框、启动、停止、清空日志都可聚焦，
 * nextFocus* 串成 返回 → 端口 → 启动 → 停止 → 清空 一条线。
 */
class F067Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF067Binding

    /** 当前这组端口上的服务器；换端口或退出时换新的，旧的一定 stop 掉。 */
    private var server: MediaServer? = null

    /** 日志行，最新的在 index 0，超过 [MAX_LOG_LINES] 掐尾。 */
    private val logLines = ArrayList<String>()

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val clock by lazy { SimpleDateFormat("HH:mm:ss", Locale.US) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF067Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.f067PortInput.inputType = InputType.TYPE_CLASS_NUMBER
        val savedPort = prefs.getInt(KEY_PORT, F067Engine.DEFAULT_PORT)
            .coerceIn(F067Engine.MIN_PORT, F067Engine.MAX_PORT)
        binding.f067PortInput.setText(savedPort.toString())

        binding.f067StartButton.setOnClickListener { startServer() }
        binding.f067StopButton.setOnClickListener { stopServer(announce = true) }
        binding.f067LogClear.setOnClickListener { clearLog() }

        binding.f067RootPath.text = rootDirectory().absolutePath

        renderState(running = false, message = getString(R.string.f067_state_idle))
        renderAddress("")
        renderLog()
    }

    override fun onPause() {
        super.onPause()
        // 输入框可能刚改过还没失焦就被 Home 切走，这里补一次落盘
        F067Engine.parsePort(binding.f067PortInput.text?.toString())?.let { port ->
            prefs.edit().putInt(KEY_PORT, port).apply()
        }
    }

    override fun onDestroy() {
        stopServer(announce = false)
        super.onDestroy()
    }

    // ===== 启停 =====

    /**
     * 启动。
     *
     * 先停旧的再建新的：换端口 = 换实例（NanoHTTPD 的端口构造后改不了），而「改完端口点一次」
     * 是最常见的操作，所以这里每次都重建。端口填得不对就只提示，不擅自换成默认值。
     */
    private fun startServer() {
        val requested = F067Engine.parsePort(binding.f067PortInput.text?.toString())
        if (requested == null) {
            CustomToast.show(this, getString(R.string.f067_err_port))
            appendLog(getString(R.string.f067_log_start_failed, getString(R.string.f067_err_port)))
            return
        }

        stopServer(announce = false)

        val root = rootDirectory()
        if (!root.isDirectory && !root.mkdirs()) {
            val reason = getString(R.string.f067_err_root)
            renderState(running = false, message = reason, failed = true)
            appendLog(getString(R.string.f067_log_start_failed, reason))
            CustomToast.show(this, reason)
            return
        }
        binding.f067RootPath.text = root.absolutePath

        renderState(running = false, message = getString(R.string.f067_state_starting))
        val created = F067Engine.createServer(requested, root) { line ->
            runOnUiThread { appendLog(line) }
        }
        server = created

        val result = created.startServer()
        if (!result.ok) {
            server = null
            val reason = result.error.ifEmpty { getString(R.string.f067_state_stopped) }
            renderState(running = false, message = getString(R.string.f067_state_failed, reason), failed = true)
            renderAddress("")
            appendLog(getString(R.string.f067_log_start_failed, reason))
            CustomToast.show(this, getString(R.string.f067_toast_start_failed))
            return
        }

        // 端口被占时 actualPort 会比输入的大，把输入框和偏好都对齐到实际值，别留着一条假端口
        binding.f067PortInput.setText(result.actualPort.toString())
        prefs.edit().putInt(KEY_PORT, result.actualPort).apply()
        renderAddress(result.address)
        val detail = if (result.actualPort == requested) {
            getString(R.string.f067_state_listening)
        } else {
            getString(R.string.f067_hint_port_shifted, requested, result.actualPort)
        }
        renderState(running = true, message = detail)
        appendLog(getString(R.string.f067_log_started, result.actualPort))
        CustomToast.show(this, getString(R.string.f067_toast_started, result.address))
    }

    /** 停止并释放；[announce] 决定要不要在日志里留一条痕迹。 */
    private fun stopServer(announce: Boolean) {
        val current = server ?: return
        server = null
        current.stopServer()
        renderState(running = false, message = getString(R.string.f067_state_stopped))
        renderAddress("")
        if (announce) {
            appendLog(getString(R.string.f067_log_stopped))
        }
    }

    /** 媒体根目录：应用自己的外部目录，删应用时一起清掉，不需要任何存储权限。 */
    private fun rootDirectory(): File {
        val root = getExternalFilesDir(null) ?: filesDir
        if (!root.exists()) {
            root.mkdirs()
        }
        return root
    }

    // ===== 渲染 =====

    /** 状态行：运行中成功绿、失败错误红、其余次要灰；停止键只在跑的时候可用。 */
    private fun renderState(running: Boolean, message: String, failed: Boolean = false) {
        binding.f067StatusLine.text = getString(R.string.f067_status_line, stateLabel(running), message)
        val colorRes = when {
            failed -> R.color.theme_error
            running -> R.color.theme_success
            else -> R.color.theme_text_secondary
        }
        binding.f067StatusLine.setTextColor(ContextCompat.getColor(this, colorRes))

        binding.f067StopButton.isEnabled = running
        binding.f067StopButton.alpha = if (running) 1f else 0.45f
        // 启动键是 LinearLayout 壳（内含图标位 + 文案），改文案要落在子 TextView 上
        binding.f067StartLabel.text =
            getString(if (running) R.string.f067_action_restart else R.string.f067_action_start)
    }

    private fun stateLabel(running: Boolean): String = getString(
        if (running) R.string.f067_state_running else R.string.f067_state_not_running
    )

    /** 地址区：没起来就显示占位；提示语按有没有取到局域网 IPv4 二选一。 */
    private fun renderAddress(address: String) {
        binding.f067AddressLine.text = address.ifEmpty { getString(R.string.f067_address_empty) }
        binding.f067AddressLine.setTextColor(
            ContextCompat.getColor(
                this,
                if (address.isEmpty()) R.color.theme_text_secondary else R.color.theme_primary
            )
        )
        val hasLan = F067Engine.localAddress().isNotEmpty()
        binding.f067AddressHint.text =
            getString(if (hasLan) R.string.f067_hint_lan else R.string.f067_hint_no_lan)
    }

    // ===== 日志 =====

    /** 追加一行日志：时间 + 内容，插到最前面，超过上限掐尾。 */
    private fun appendLog(line: String) {
        logLines.add(0, getString(R.string.f067_log_line, clock.format(Date()), line))
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
        val container = binding.f067LogList
        container.removeAllViews()
        binding.f067LogEmpty.visibility = if (logLines.isEmpty()) View.VISIBLE else View.GONE
        logLines.forEach { line ->
            container.addView(TextView(this).apply {
                text = line
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@F067Activity, R.color.theme_text_secondary))
                setPadding(0, dp(4), 0, dp(4))
            })
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS = "f067_media_server"
        private const val KEY_PORT = "port"

        /** 日志行上限：再多也没人看，还会把页面撑得很长。 */
        private const val MAX_LOG_LINES = 40
    }
}
