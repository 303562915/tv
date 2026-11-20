package com.tv.ball.feature.f055

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF055Binding
import com.tv.ball.util.CustomToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.roundToInt

/**
 * 「自定义 DoH 解析」页。
 *
 * 三段内容：配置（DoH 端点输入 + 预设端点 + 启用开关）、查询（域名输入 + 解析 + 端点连通性测试）、
 * 结果（通道 / 耗时 / 地址数 / 地址列表 / 回退提示）。
 *
 * 数据流很直白：点「解析」把输入框里的端点与开关快照交给 [F055Engine.resolve]，IO 线程跑完回主线程渲染。
 * 端点与开关一边改一边落盘（[F055Config]），所以退出再进不用重填；域名只记最近一次，属于便利项。
 *
 * 验收里的"失败回退系统 DNS"由引擎兜住，界面只负责把回退这件事说清楚：通道显示「系统 DNS（已回退）」，
 * 状态行用警告色写明"已回退系统 DNS：<原因>"，不把失败伪装成成功。
 *
 * 电视没有触摸屏：开关、预设端点、两个动作行、返回键都是可聚焦控件，
 * 焦点顺序在 XML 里用 nextFocus* 串成 返回 → 端点输入 → 开关 → 预设 → 域名 → 解析 → 端点测试。
 */
class F055Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF055Binding

    private val config by lazy { F055Config(this) }

    private val engine by lazy { F055Engine(config) }

    /** 当前在跑的解析 / 测试任务；重复点击先取消旧的，避免两次结果互相覆盖。 */
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF055Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.f055DohUrl.setText(config.dohUrl)
        // 失焦即落盘：电视上从输入框走到开关，用户的心理动作就是"配置完了"
        binding.f055DohUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                commitUrl()
                renderStatus()
            }
        }

        binding.f055HostInput.setText(config.lastHost.ifBlank { DEFAULT_PROBE_HOST })
        binding.f055HostInput.setSelection(binding.f055HostInput.text?.length ?: 0)
        binding.f055HostInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI

        // 先 setChecked 再挂监听：否则恢复配置时会触发一次多余的落盘
        binding.f055DohSwitch.isChecked = config.enabled
        binding.f055DohSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) commitUrl()
            config.enabled = checked
            renderStatus()
            CustomToast.show(
                this,
                getString(if (checked) R.string.f055_switch_on else R.string.f055_switch_off)
            )
        }

        buildPresets()

        binding.f055ResolveButton.setOnClickListener { startResolve() }
        binding.f055ProbeButton.setOnClickListener { startProbe() }

        // 遥控器从上往下走：端点输入 → 开关 → 解析 → 端点测试；开关按"下"直接跳解析，
        // 预设端点挂在解析行上方，只在输入框里按"上"时作为中间停靠点，不打断主流程
        binding.f055DohSwitch.nextFocusDownId = R.id.f055ResolveButton

        renderStatus()
        renderIdle()
    }

    override fun onPause() {
        super.onPause()
        // 输入框里可能刚改过还没失焦就被 Home 键切走，这里补一次落盘
        commitUrl()
        config.lastHost = binding.f055HostInput.text?.toString().orEmpty()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    // ===== 配置 =====

    /**
     * 把输入框里的端点写进配置。
     *
     * 空串回填默认值、非 http(s) 地址回滚上一次的合法值——宁可"看起来没改成功"，
     * 也不要存一个解析必然失败的地址进去。
     */
    private fun commitUrl() {
        val raw = binding.f055DohUrl.text?.toString()?.trim().orEmpty()
        when {
            raw.isEmpty() -> {
                binding.f055DohUrl.setText(F055Engine.DEFAULT_DOH_URL)
                config.dohUrl = F055Engine.DEFAULT_DOH_URL
            }
            raw.toHttpUrlOrNull() == null -> {
                CustomToast.show(this, getString(R.string.f055_url_invalid))
            binding.f055DohUrl.setText(config.dohUrl)
                binding.f055DohUrl.setSelection(config.dohUrl.length)
            }
            raw != config.dohUrl -> {
                config.dohUrl = raw
                CustomToast.show(this, getString(R.string.f055_config_saved))
            }
        }
    }

    /**
     * 预设端点：一排可聚焦的块，点一下填入并立即落盘。
     *
     * 顺序来自 [F055Engine.PRESETS]，XML 里只留一个空容器——端点清单属于引擎，
     * 界面上不该再抄一份。
     */
    private fun buildPresets() {
        binding.f055PresetRow.removeAllViews()
        F055Engine.PRESETS.forEachIndexed { index, (label, url) ->
            binding.f055PresetRow.addView(presetChip(label, url).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { if (index > 0) marginStart = dp(8) }
            })
        }
    }

    private fun presetChip(label: String, url: String): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        minHeight = dp(40)
        setTextColor(ContextCompat.getColor(context, R.color.theme_text_primary))
        background = ContextCompat.getDrawable(context, R.drawable.bg_card_surface)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setOnClickListener { applyPreset(label, url) }
    }

    private fun applyPreset(label: String, url: String) {
        binding.f055DohUrl.setText(url)
        binding.f055DohUrl.setSelection(url.length)
        config.dohUrl = url
        renderStatus()
        CustomToast.show(this, getString(R.string.f055_preset_applied, label))
    }

    // ===== 解析 =====

    private fun startResolve() {
        val host = binding.f055HostInput.text?.toString()?.trim().orEmpty()
        if (host.isEmpty()) {
            CustomToast.show(this, getString(R.string.f055_host_empty))
            return
        }
        commitUrl()
        val url = binding.f055DohUrl.text?.toString()?.trim().orEmpty()
        val enabled = binding.f055DohSwitch.isChecked
        config.lastHost = host

        setBusy(true)
        renderBusy(host, enabled)
        job?.cancel()
        job = lifecycleScope.launch {
            // 网络 + InetAddress 都是阻塞调用，一律挪去 IO
            val result = withContext(Dispatchers.IO) { engine.resolve(host, url, enabled) }
            if (isFinishing || isDestroyed) return@launch
            setBusy(false)
            renderResult(result)
        }
    }

    /** 端点连通性测试：只关心"这个地址到底通不通"，不看 DNS 语义。 */
    private fun startProbe() {
        val url = binding.f055DohUrl.text?.toString()?.trim().orEmpty()
            .ifEmpty { F055Engine.DEFAULT_DOH_URL }
        commitUrl()
        setBusy(true)
        CustomToast.show(this, getString(R.string.f055_probing))
        job?.cancel()
        job = lifecycleScope.launch {
            val (body, error) = withContext(Dispatchers.IO) { engine.probe(url) }
            if (isFinishing || isDestroyed) return@launch
            setBusy(false)
            val message = if (body != null) {
                getString(R.string.f055_probe_ok, body.length)
            } else {
                getString(R.string.f055_probe_fail, error ?: "—")
            }
            CustomToast.show(this@F055Activity, message)
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.f055ResolveButton.isEnabled = !busy
        binding.f055ProbeButton.isEnabled = !busy
        val alpha = if (busy) 0.5f else 1f
        binding.f055ResolveButton.alpha = alpha
        binding.f055ProbeButton.alpha = alpha
    }

    // ===== 渲染 =====

    private fun renderStatus() {
        val enabled = config.enabled
        binding.f055StatusLine.text = if (enabled) {
            getString(R.string.f055_status_on, config.dohUrl)
        } else {
            getString(R.string.f055_status_off)
        }
        binding.f055StatusLine.setTextColor(
            ContextCompat.getColor(
                this,
                if (enabled) R.color.theme_primary else R.color.theme_text_secondary
            )
        )
    }

    private fun renderIdle() {
        binding.f055ResultSource.text = getString(R.string.f055_value_none)
        binding.f055ResultElapsed.text = getString(R.string.f055_value_none)
        binding.f055ResultCount.text = getString(R.string.f055_value_none)
        binding.f055ResultAddresses.text = getString(R.string.f055_result_idle)
        setStatus(getString(R.string.f055_result_idle_hint), R.color.theme_text_secondary)
    }

    private fun renderBusy(host: String, enabled: Boolean) {
        binding.f055ResultSource.text = getString(R.string.f055_value_none)
        binding.f055ResultElapsed.text = getString(R.string.f055_value_none)
        binding.f055ResultCount.text = getString(R.string.f055_value_none)
        binding.f055ResultAddresses.text = getString(R.string.f055_result_idle)
        setStatus(
            getString(
                if (enabled) R.string.f055_resolving_doh else R.string.f055_resolving_system,
                host
            ),
            R.color.theme_text_secondary
        )
    }

    private fun renderResult(result: DohResult) {
        binding.f055ResultSource.text = result.source
        binding.f055ResultElapsed.text = getString(R.string.f055_elapsed_value, result.ms)
        binding.f055ResultCount.text = getString(R.string.f055_count_value, result.addresses.size)
        binding.f055ResultAddresses.text = if (result.addresses.isEmpty()) {
            getString(R.string.f055_address_none)
        } else {
            result.addresses.joinToString("\n")
        }

        val reason = result.error?.takeIf { it.isNotBlank() } ?: "—"
        when {
            // DoH 成功
            result.addresses.isNotEmpty() && !result.fellBack -> setStatus(
                getString(R.string.f055_result_doh_ok, result.ms),
                R.color.theme_success
            )
            // 走回退但拿到了地址：功能可用，只是通道降级了
            result.addresses.isNotEmpty() -> setStatus(
                getString(R.string.f055_fallback_hint, reason),
                R.color.theme_warning
            )
            // 两条路都不通
            else -> setStatus(
                getString(R.string.f055_both_failed, reason),
                R.color.theme_error
            )
        }
    }

    private fun setStatus(message: CharSequence, colorRes: Int) {
        binding.f055ResultStatus.text = message
        binding.f055ResultStatus.setTextColor(ContextCompat.getColor(this, colorRes))
        binding.f055ResultStatus.visibility = View.VISIBLE
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).roundToInt()

    companion object {
        /**
         * 默认回填的测试域名：国内直连可达，用来快速确认端点是否生效。
         * 用户真要看某部片的源站就把它改成那个域名。
         */
        private const val DEFAULT_PROBE_HOST = "www.baidu.com"
    }
}