package com.tv.ball.feature.f065

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF065Binding
import com.tv.ball.util.CustomToast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「局域网目录浏览」页。
 *
 * 三段：服务（端口 + 启动 / 停止 + 状态行 + 访问地址）、浏览目录（根目录路径 + 说明）、
 * 最近访问（时间 + 对方 IP + 路径 + 状态码，最新的在最上面）。
 *
 * 数据流：点「启动」→ [F065Engine.start] 按候选端口绑上 → 成功后把地址写进状态区；
 * 之后每个请求由引擎顺手回调一条 [F065Access]，这里 post 回主线程落进列表，
 * 用户看到「192.168.1.23 要了 /photo.jpg，回了 200」就知道手机确实连上了。
 *
 * 服务只在页面打开期间活着：退出页面就 [F065Engine.stop]。这样做是因为访问回调是按页面
 * 传进去的 lambda，页面销毁后留着回调会抓住已销毁的 Activity；宁可关页面就停服务。
 *
 * 电视没有触摸屏：返回、端口框、启动、停止、清空日志都可聚焦，
 * nextFocus* 串成 返回 → 端口 → 启动 → 停止 → 清空 一条线。
 */
class F065Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF065Binding

    /** 日志行，最新的在 index 0，超过 [MAX_LOG_LINES] 掐尾。 */
    private val logLines = ArrayList<LogLine>()

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val clock by lazy { SimpleDateFormat("HH:mm:ss", Locale.US) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF065Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.f065PortInput.inputType = InputType.TYPE_CLASS_NUMBER
        val savedPort = prefs.getInt(KEY_PORT, F065Engine.DEFAULT_PORT)
            .coerceIn(F065Engine.MIN_PORT, F065Engine.MAX_PORT)
        binding.f065PortInput.setText(savedPort.toString())

        binding.f065StartButton.setOnClickListener { startServer() }
        binding.f065StopButton.setOnClickListener { stopServer(announce = true) }
        binding.f065LogClear.setOnClickListener { clearLog() }

        binding.f065RootPath.text = rootDirectory().absolutePath

        renderState(running = false, message = getString(R.string.f065_state_idle))
        renderAddress("")
        renderLog()
    }

    override fun onPause() {
        super.onPause()
        // 输入框可能刚改过还没失焦就被 Home 切走，这里补一次落盘
        F065Engine.parsePort(binding.f065PortInput.text?.toString())?.let { port ->
            prefs.edit().putInt(KEY_PORT, port).apply()
        }
    }

    override fun onDestroy() {
        F065Engine.stop()
        super.onDestroy()
    }

    // ===== 启停 =====

    /**
     * 启动。
     *
     * 先停旧的再起新的：换端口 = 换实例（NanoHTTPD 的端口构造后改不了），而「改完端口点一次」
     * 是最常见的操作，所以这里每次都重建。端口填得不对就只提示，不擅自换成默认值。
     */
    private fun startServer() {
        val requested = F065Engine.parsePort(binding.f065PortInput.text?.toString())
        if (requested == null) {
            pushLine(getString(R.string.f065_log_start_failed, getString(R.string.f065_err_port)), true)
            CustomToast.show(this, getString(R.string.f065_err_port))
            return
        }

        F065Engine.stop()

        val root = rootDirectory()
        if (!root.isDirectory && !root.mkdirs()) {
            val reason = getString(R.string.f065_err_root)
            renderState(running = false, message = reason, failed = true)
            pushLine(getString(R.string.f065_log_start_failed, reason), true)
            CustomToast.show(this, reason)
            return
        }
        binding.f065RootPath.text = root.absolutePath

        renderState(running = false, message = getString(R.string.f065_state_starting))
        val result = F065Engine.start(requested, root) { access ->
            runOnUiThread { appendAccess(access) }
        }

        if (!result.ok) {
            val reason = result.error.ifEmpty { getString(R.string.f065_state_stopped) }
            renderState(running = false, message = getString(R.string.f065_state_failed, reason), failed = true)
            renderAddress("")
            pushLine(getString(R.string.f065_log_start_failed, reason), true)
            CustomToast.show(this, getString(R.string.f065_toast_start_failed))
            return
        }

        // 端口被占时实际端口会比输入的大，把输入框和偏好都对齐到实际值，别留着一条假端口
        binding.f065PortInput.setText(result.port.toString())
        prefs.edit().putInt(KEY_PORT, result.port).apply()
        renderAddress(result.address)
        val detail = if (result.port == requested) {
            getString(R.string.f065_state_listening)
        } else {
            getString(R.string.f065_hint_port_shifted, requested, result.port)
        }
        renderState(running = true, message = detail)
        pushLine(getString(R.string.f065_log_started, result.port), false)
        CustomToast.show(this, getString(R.string.f065_toast_started, result.address))
    }

    /** 停止并释放；[announce] 决定要不要在日志里留一条痕迹。 */
    private fun stopServer(announce: Boolean) {
        if (!F065Engine.isRunning) {
            // 已经没在跑：仍然刷一次界面，避免状态行停在「运行中」
            renderState(running = false, message = getString(R.string.f065_state_stopped))
            renderAddress("")
            return
        }
        F065Engine.stop()
        renderState(running = false, message = getString(R.string.f065_state_stopped))
        renderAddress("")
        if (announce) {
            pushLine(getString(R.string.f065_log_stopped), false)
        }
    }

    /**
     * 浏览根目录：应用自己的外部目录。
     *
     * 选它是因为不需要任何存储权限，拷进来的文件删应用时一起清掉，也不会把整机文件系统
     * 暴露到局域网里。第一次进来目录可能还不存在，这里顺手建出来。
     */
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
        binding.f065StatusLine.text = getString(R.string.f065_status_line, stateLabel(running), message)
        val colorRes = when {
            failed -> R.color.theme_error
            running -> R.color.theme_success
            else -> R.color.theme_text_secondary
        }
        binding.f065StatusLine.setTextColor(ContextCompat.getColor(this, colorRes))

        binding.f065StopButton.isEnabled = running
        binding.f065StopButton.alpha = if (running) 1f else 0.45f
        // 启动键是 LinearLayout 壳（内含文案 + 提示），改文案要落在子 TextView 上
        binding.f065StartLabel.text =
            getString(if (running) R.string.f065_action_restart else R.string.f065_action_start)
    }

    private fun stateLabel(running: Boolean): String = getString(
        if (running) R.string.f065_state_running else R.string.f065_state_not_running
    )

    /** 地址区：没起来就显示占位；提示语按有没有取到局域网 IPv4 二选一。 */
    private fun renderAddress(address: String) {
        binding.f065AddressLine.text = address.ifEmpty { getString(R.string.f065_address_empty) }
        binding.f065AddressLine.setTextColor(
            ContextCompat.getColor(
                this,
                if (address.isEmpty()) R.color.theme_text_secondary else R.color.theme_primary
            )
        )
        val hasLan = F065Engine.localAddress().isNotEmpty()
        binding.f065AddressHint.text =
            getString(if (hasLan) R.string.f065_hint_lan else R.string.f065_hint_no_lan)
    }

    // ===== 日志 =====

    /** 一条访问记录：对方 IP + 路径 + 状态码。4xx / 5xx 标红，一眼能看出「点了个不存在的文件」。 */
    private fun appendAccess(access: F065Access) {
        val failed = access.status >= 400
        pushLine(getString(R.string.f065_log_access, access.ip, access.path, access.status), failed)
    }

    /** 追加一行：时间 + 内容，插到最前面，超过上限掐尾。 */
    private fun pushLine(text: String, failed: Boolean) {
        logLines.add(0, LogLine(getString(R.string.f065_log_line, clock.format(Date()), text), failed))
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
        val container = binding.f065LogList
        container.removeAllViews()
        binding.f065LogEmpty.visibility = if (logLines.isEmpty()) View.VISIBLE else View.GONE
        logLines.forEach { line ->
            container.addView(TextView(this).apply {
                text = line.text
                textSize = 13f
                setTextColor(
                    ContextCompat.getColor(
                        this@F065Activity,
                        if (line.failed) R.color.theme_error else R.color.theme_text_secondary
                    )
                )
                setPadding(0, dp(4), 0, dp(4))
            })
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** 一行日志：文案 + 是不是失败请求（决定颜色）。 */
    private class LogLine(val text: String, val failed: Boolean)

    companion object {
        private const val PREFS = "f065_directory_browser"
        private const val KEY_PORT = "port"

        /** 日志行上限：再多也没人看，还会把页面撑得很长。 */
        private const val MAX_LOG_LINES = 40
    }
}
