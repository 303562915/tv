package com.tv.ball.feature.f053

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF053Binding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * F053「网络诊断面板」页面。
 *
 * 探测全在 [F053Engine]，这里只负责三块渲染：
 * 1. 目标：host / port 两个输入框，默认 8.8.8.8:443，用户可改；
 * 2. 结果卡：DNS 耗时与地址数、TCP 耗时与成败、三档结论；
 * 3. 最近 5 次：每次诊断插到队首，超过 5 条截断。
 *
 * 诊断本身在 IO 线程跑，按钮在跑的过程中禁用，避免并发打同一个目标。
 */
class F053Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF053Binding

    private val history = mutableListOf<F053Engine.Report>()
    private var runningJob: Job? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF053Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.inputHost.setText(F053Engine.DEFAULT_HOST)
        binding.inputPort.setText(F053Engine.DEFAULT_PORT.toString())

        binding.inputHost.nextFocusDownId = binding.inputPort.id
        binding.inputPort.nextFocusDownId = binding.btnDiagnose.id

        binding.btnDiagnose.setOnClickListener { startDiagnose() }

        refreshIdle()
        renderHistory()
    }

    override fun onDestroy() {
        runningJob?.cancel()
        super.onDestroy()
    }

    /** 读输入 -> 跑诊断 -> 渲染并入库。 */
    private fun startDiagnose() {
        if (runningJob?.isActive == true) {
            binding.statusValue.text = getString(R.string.f053_status_busy)
            return
        }

        val host = binding.inputHost.text?.toString()?.trim().orEmpty()
        val port = F053Engine.parsePort(binding.inputPort.text?.toString().orEmpty())

        if (host.isEmpty()) {
            binding.statusValue.text = getString(R.string.f053_host_hint)
            binding.inputHost.requestFocus()
            return
        }

        binding.inputPort.setText(port.toString())
        setBusy(true)
        binding.statusValue.text = getString(R.string.f053_status_running, host, port)

        runningJob = lifecycleScope.launch {
            val report = try {
                F053Engine.diagnose(host, port, F053Engine.DEFAULT_TIMEOUT_MS)
            } catch (t: Throwable) {
                F053Engine.Report(
                    dns = F053Engine.DnsResult(host = host, error = t.message ?: "诊断异常"),
                    tcp = F053Engine.TcpResult(host = host, port = port, error = t.message ?: "诊断异常"),
                    advice = F053Engine.Advice(
                        F053Engine.Level.BROKEN,
                        F053Engine.Code.TCP_UNREACHABLE
                    ),
                    atMillis = System.currentTimeMillis()
                )
            }
            val next = F053Engine.pushHistory(history.toList(), report)
            history.clear()
            history.addAll(next)
            renderReport(report)
            renderHistory()
            binding.statusValue.text = getString(
                R.string.f053_status_done,
                report.dns.host.ifEmpty { host },
                port,
                timeFormat.format(Date(report.atMillis))
            )
            setBusy(false)
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.btnDiagnose.isEnabled = !busy
        binding.inputHost.isEnabled = !busy
        binding.inputPort.isEnabled = !busy
        binding.btnDiagnose.text = if (busy) {
            getString(R.string.f053_diagnosing)
        } else {
            getString(R.string.f053_diagnose)
        }
    }

    /** 一次诊断的三块：DNS 行、TCP 行、结论行。颜色跟结论档位走。 */
    private fun renderReport(report: F053Engine.Report) {
        val dns = report.dns
        val tcp = report.tcp

        // DNS
        val dnsColor = levelColor(F053Engine.dnsLevel(dns.ms))
        binding.dnsValue.text = if (dns.ok) {
            getString(R.string.f053_dns_value, F053Engine.formatMs(dns.ms))
        } else {
            getString(R.string.f053_dns_fail_value, F053Engine.formatMs(dns.ms))
        }
        binding.dnsValue.setTextColor(color(dnsColor))
        binding.dnsDetail.text = if (dns.ok) {
            val first = dns.addresses.first()
            if (dns.addressCount == 1) {
                getString(R.string.f053_dns_detail_one, first)
            } else {
                getString(
                    R.string.f053_dns_detail,
                    dns.addressCount,
                    first + if (dns.addressCount > 1) " 等" else ""
                )
            }
        } else {
            getString(R.string.f053_dns_detail_error, dns.error.orEmpty())
        }

        // TCP
        val tcpColor = levelColor(F053Engine.tcpLevel(tcp.ok, tcp.ms))
        binding.tcpValue.text = if (tcp.ok) {
            getString(R.string.f053_tcp_value_ok, F053Engine.formatMs(tcp.ms))
        } else {
            getString(R.string.f053_tcp_value_fail, F053Engine.formatMs(tcp.ms))
        }
        binding.tcpValue.setTextColor(color(tcpColor))
        binding.tcpDetail.text = if (tcp.ok) {
            getString(R.string.f053_tcp_detail_ok, tcp.host, tcp.port)
        } else {
            getString(
                R.string.f053_tcp_detail_fail,
                tcp.host,
                tcp.port,
                tcp.error.orEmpty()
            )
        }

        // 结论
        val code = report.advice.code
        binding.adviceTitle.text = getString(adviceTitleRes(code))
        binding.adviceTitle.setTextColor(color(levelColor(report.advice.level)))
        binding.adviceBody.text = getString(adviceBodyRes(code))
    }

    private fun refreshIdle() {
        binding.dnsValue.text = getString(R.string.f053_dns_idle)
        binding.dnsValue.setTextColor(color(R.color.theme_text_secondary))
        binding.dnsDetail.text = getString(R.string.f053_dns_idle_detail)
        binding.tcpValue.text = getString(R.string.f053_tcp_idle)
        binding.tcpValue.setTextColor(color(R.color.theme_text_secondary))
        binding.tcpDetail.text = getString(R.string.f053_tcp_idle_detail)
        binding.adviceTitle.text = getString(R.string.f053_advice_idle_title)
        binding.adviceTitle.setTextColor(color(R.color.theme_primary))
        binding.adviceBody.text = getString(R.string.f053_advice_idle_body)
    }

    /** 最近 5 次：队首是最新一条，空列表给提示文案。 */
    private fun renderHistory() {
        binding.historyContainer.removeAllViews()
        binding.historyEmpty.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE

        history.forEach { report ->
            val dnsText = if (report.dns.ok) {
                F053Engine.formatMs(report.dns.ms)
            } else {
                getString(R.string.f053_history_dns_fail)
            }
            val tcpText = if (report.tcp.ok) {
                F053Engine.formatMs(report.tcp.ms)
            } else {
                getString(R.string.f053_history_tcp_fail)
            }

            val row = TextView(this).apply {
                text = getString(
                    R.string.f053_history_item,
                    timeFormat.format(Date(report.atMillis)),
                    report.dns.host,
                    report.tcp.port,
                    dnsText,
                    tcpText
                )
                setTextColor(color(levelColor(report.advice.level)))
                textSize = 12f
                isFocusable = false
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(this@F053Activity, R.drawable.bg_mode_row)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            }
            binding.historyContainer.addView(row)
        }
    }

    private fun levelColor(level: F053Engine.Level): Int = when (level) {
        F053Engine.Level.HEALTHY -> R.color.theme_success
        F053Engine.Level.DEGRADED -> R.color.theme_warning
        F053Engine.Level.BROKEN -> R.color.theme_error
    }

    private fun adviceTitleRes(code: F053Engine.Code): Int = when (code) {
        F053Engine.Code.DNS_SLOW -> R.string.f053_advice_dns_slow_title
        F053Engine.Code.TCP_UNREACHABLE -> R.string.f053_advice_tcp_unreachable_title
        F053Engine.Code.ALL_SLOW -> R.string.f053_advice_all_slow_title
        F053Engine.Code.NETWORK_OK -> R.string.f053_advice_network_ok_title
    }

    private fun adviceBodyRes(code: F053Engine.Code): Int = when (code) {
        F053Engine.Code.DNS_SLOW -> R.string.f053_advice_dns_slow_body
        F053Engine.Code.TCP_UNREACHABLE -> R.string.f053_advice_tcp_unreachable_body
        F053Engine.Code.ALL_SLOW -> R.string.f053_advice_all_slow_body
        F053Engine.Code.NETWORK_OK -> R.string.f053_advice_network_ok_body
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()
}
