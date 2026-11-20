package com.tv.ball.feature.f056

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF056Binding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * F056「代理配置」页面。
 *
 * 三块：协议单选（HTTP / SOCKS5）、主机端口输入 + 启用开关、测试连接与保存。
 * 所有网络动作在 [F056Engine]，持久化在 [F056ProxyStore]，这里只做收集、校验、渲染。
 *
 * 交互口径：
 * - 开关是「即时生效」——拨一下立刻落盘，不等保存按钮；
 * - 主机/端口/协议改动要按「保存配置」才落盘（避免半截输入被记住）；
 * - 测试连接跑的时候整页控件禁用，防止一边改参数一边看结果造成误判。
 */
class F056Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF056Binding

    private val store by lazy { F056ProxyStore(this) }

    /** 正在跑的测试任务；重复点击先提示，避免两个结果互相覆盖。 */
    private var testJob: Job? = null

    /** 程序改开关状态时用它屏蔽监听，否则回滚会再触发一次落盘。 */
    private var suppressSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF056Binding.inflate(layoutInflater)
        setContentView(binding.root)

        val saved = store.load()
        if (saved.type == F056Engine.ProxyType.SOCKS) {
            binding.radioSocks.isChecked = true
        } else {
            binding.radioHttp.isChecked = true
        }
        binding.inputHost.setText(saved.host)
        binding.inputPort.setText(saved.port.toString())

        // 先 setChecked 再挂监听：恢复配置那一下不该被当成用户操作
        suppressSwitch = true
        binding.switchEnabled.isChecked = saved.enabled
        suppressSwitch = false

        binding.radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val type = if (checkedId == R.id.radio_socks) {
                F056Engine.ProxyType.SOCKS
            } else {
                F056Engine.ProxyType.HTTP
            }
            onTypeChanged(type)
        }

        binding.switchEnabled.setOnCheckedChangeListener { _, checked ->
            if (!suppressSwitch) onSwitchChanged(checked)
        }

        binding.btnTest.setOnClickListener { startTest() }
        binding.btnSave.setOnClickListener { saveConfig() }

        // 遥控器一路往下：协议 → 主机 → 端口 → 开关 → 测试 → 保存
        binding.radioHttp.nextFocusDownId = binding.radioSocks.id
        binding.radioSocks.nextFocusDownId = binding.inputHost.id
        binding.inputHost.nextFocusDownId = binding.inputPort.id
        binding.inputPort.nextFocusDownId = binding.switchEnabled.id
        binding.switchEnabled.nextFocusDownId = binding.btnTest.id
        binding.btnTest.nextFocusDownId = binding.btnSave.id

        renderSummary(saved)
        showStatus(getString(R.string.f056_status_ready), F056Engine.Level.IDLE)
    }

    override fun onPause() {
        super.onPause()
        // 用户可能改完输入框直接按 Home，这里补一次静默落盘（非法输入不写）
        persistQuietly()
    }

    override fun onDestroy() {
        testJob?.cancel()
        super.onDestroy()
    }

    // ===== 交互 =====

    /**
     * 切换协议：端口还是上一个协议的默认值时顺手换成新协议的默认端口。
     *
     * 只动「明显没改过」的端口，用户自己填的 9050 之类的值不去覆盖。
     */
    private fun onTypeChanged(type: F056Engine.ProxyType) {
        val typed = F056Engine.parsePort(binding.inputPort.text?.toString().orEmpty(), -1)
        val otherDefault = F056Engine.defaultPortFor(
            if (type == F056Engine.ProxyType.HTTP) F056Engine.ProxyType.SOCKS else F056Engine.ProxyType.HTTP
        )
        if (typed == -1 || typed == otherDefault) {
            binding.inputPort.setText(F056Engine.defaultPortFor(type).toString())
        }
        showStatus(
            getString(R.string.f056_status_type_changed, getString(typeLabelRes(type))),
            F056Engine.Level.IDLE
        )
    }

    /** 开关：即时生效。配置本身非法时回滚开关，别让界面显示"已启用"而磁盘上没存。 */
    private fun onSwitchChanged(checked: Boolean) {
        val config = collect()
        if (!config.valid) {
            showProblems(config.problems())
            suppressSwitch = true
            binding.switchEnabled.isChecked = !checked
            suppressSwitch = false
            return
        }
        store.save(config.copy(enabled = checked))
        renderSummary(config.copy(enabled = checked))
        showStatus(
            getString(if (checked) R.string.f056_status_enabled else R.string.f056_status_disabled),
            if (checked) F056Engine.Level.OK else F056Engine.Level.IDLE
        )
    }

    /** 保存：校验 → 规整 → 落盘 → 回填。 */
    private fun saveConfig() {
        val config = collect()
        if (!config.valid) {
            showProblems(config.problems())
            return
        }
        val normalized = config.normalized()
        store.save(normalized)
        binding.inputHost.setText(normalized.host)
        binding.inputPort.setText(normalized.port.toString())
        renderSummary(normalized)
        showStatus(
            getString(
                R.string.f056_status_saved,
                getString(typeLabelRes(normalized.type)),
                normalized.host,
                normalized.port
            ),
            F056Engine.Level.OK
        )
    }

    /** 测试连接：真实请求 `generate_204`，回报状态码与耗时，或异常文本。 */
    private fun startTest() {
        if (testJob?.isActive == true) {
            showStatus(getString(R.string.f056_status_busy), F056Engine.Level.IDLE)
            return
        }
        val config = collect()
        if (!config.valid) {
            showProblems(config.problems())
            return
        }

        setBusy(true)
        showStatus(
            getString(
                R.string.f056_status_testing,
                getString(typeLabelRes(config.type)),
                config.host.trim(),
                config.port
            ),
            F056Engine.Level.IDLE
        )

        testJob = lifecycleScope.launch {
            val result = try {
                F056Engine.testConnection(config)
            } catch (t: Throwable) {
                F056Engine.TestResult(
                    ok = false,
                    error = "${t.javaClass.simpleName}: ${t.message ?: "测试异常"}"
                )
            }
            renderTest(result)
            setBusy(false)
        }
    }

    private fun persistQuietly() {
        val config = collect()
        if (config.valid) store.save(config)
    }

    // ===== 渲染 =====

    /** 收集界面上的当前值；端口非法时用 0 占位，交给 [F056Engine.validate] 报错。 */
    private fun collect(): F056ProxyConfig {
        val type = if (binding.radioSocks.isChecked) {
            F056Engine.ProxyType.SOCKS
        } else {
            F056Engine.ProxyType.HTTP
        }
        val port = F056Engine.parsePort(binding.inputPort.text?.toString().orEmpty(), -1)
        return F056Engine.ProxyConfig(
            type = type,
            host = binding.inputHost.text?.toString().orEmpty(),
            port = if (port == -1) 0 else port,
            enabled = binding.switchEnabled.isChecked
        )
    }

    private fun renderTest(result: F056Engine.TestResult) {
        val text = when {
            result.error != null -> getString(R.string.f056_test_error, result.error.orEmpty())
            result.ok -> getString(R.string.f056_test_ok, result.code, result.ms)
            else -> getString(R.string.f056_test_fail, result.code, result.ms)
        }
        showStatus(text, F056Engine.levelOf(result))
    }

    /** 摘要：当前落盘的那份配置长什么样，避免用户分不清"界面上填的"和"已经存了的"。 */
    private fun renderSummary(config: F056ProxyConfig) {
        binding.summaryValue.text = if (config.host.isBlank()) {
            getString(R.string.f056_summary_none)
        } else {
            getString(
                R.string.f056_summary,
                getString(typeLabelRes(config.type)),
                config.host,
                config.port,
                getString(if (config.enabled) R.string.f056_switch_on else R.string.f056_switch_off)
            )
        }
    }

    private fun showProblems(problems: List<F056Engine.Problem>) {
        val detail = problems.joinToString(separator = getString(R.string.f056_problem_sep)) {
            getString(problemRes(it))
        }
        showStatus(
            getString(R.string.f056_status_invalid, detail),
            F056Engine.Level.BAD
        )
    }

    private fun showStatus(text: String, level: F056Engine.Level) {
        binding.statusValue.text = text
        binding.statusValue.setTextColor(color(levelColor(level)))
    }

    private fun setBusy(busy: Boolean) {
        binding.radioGroup.isEnabled = !busy
        binding.radioHttp.isEnabled = !busy
        binding.radioSocks.isEnabled = !busy
        binding.inputHost.isEnabled = !busy
        binding.inputPort.isEnabled = !busy
        binding.switchEnabled.isEnabled = !busy
        binding.btnTest.isEnabled = !busy
        binding.btnSave.isEnabled = !busy
        binding.btnTest.text = if (busy) {
            getString(R.string.f056_testing)
        } else {
            getString(R.string.f056_test)
        }
    }

    private fun typeLabelRes(type: F056Engine.ProxyType): Int = when (type) {
        F056Engine.ProxyType.HTTP -> R.string.f056_type_http
        F056Engine.ProxyType.SOCKS -> R.string.f056_type_socks
    }

    private fun problemRes(problem: F056Engine.Problem): Int = when (problem) {
        F056Engine.Problem.HOST_EMPTY -> R.string.f056_problem_host_empty
        F056Engine.Problem.PORT_RANGE -> R.string.f056_problem_port_range
    }

    private fun levelColor(level: F056Engine.Level): Int = when (level) {
        F056Engine.Level.OK -> R.color.theme_success
        F056Engine.Level.BAD -> R.color.theme_error
        F056Engine.Level.IDLE -> R.color.theme_text_secondary
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)
}
