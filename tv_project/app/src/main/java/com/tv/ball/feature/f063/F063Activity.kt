package com.tv.ball.feature.f063

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF063Binding
import com.tv.ball.util.CustomToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 「局域网设备扫描」页。
 *
 * 三段：网段（本机 IPv4 的 /24）、动作（开始扫描 / 取消 / 导出文本 + 进度条）、结果（IP + 开放端口）。
 *
 * 数据流：点「开始扫描」→ 没有网段就现取一次 [F063Engine.localSubnet]（走 IO，网卡枚举不能卡主线程）→
 * 把网段交给 [F063Engine.scan]，引擎在 IO 上按 [F063Engine.MAX_CONCURRENCY] 并发探测，
 * 每探完一台通过 onProgress 回一次进度；界面用 `runOnUiThread` 切回主线程刷新
 * 「已扫 / 总数」并即时追加一台设备，不必等 254 台全部收口才看到列表。
 *
 * 三个必须讲清楚的状态：
 * 1. 扫描中：「开始扫描」变灰、「取消扫描」可点，进度条按已扫台数推进，状态行写明正在扫哪个网段；
 * 2. 扫完了：结果按 IP 末段升序重排一遍（完成顺序是乱的），状态行给出总台数与耗时；
 * 3. 取消了：已经扫到的部分结果保留在列表里，状态行说明是用户主动停的——不当作失败。
 *
 * 取消：直接 cancel 掉协程，引擎在每台主机探下一个端口之前都会查一次 cancellation，
 * 所以最迟一个连接超时（默认 400 ms）就真的停下来。
 *
 * 导出：把 [F063Engine.exportText] 的整份文本塞进 `f063ExportText`（等宽、可选中复制），
 * 不做剪贴板写入——电视上用户拿走的路径是"看着抄 / 遥控器长按选择"，而复制到剪贴板
 * 在盒子上往往没有任何反馈。
 */
class F063Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF063Binding

    /** 当前这一轮扫描；不管是开始、取消还是重扫，都先把它清掉再起新的。 */
    private var job: Job? = null

    /** 本机网段（`192.168.1.0/24`）；取不到时为 null，界面显示"未获取"。 */
    private var subnet: String? = null

    /** 已经扫到的设备，导出文本用的就是这一份。 */
    private val found = ArrayList<HostResult>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF063Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.f063ScanButton.setOnClickListener { startScan() }
        binding.f063CancelButton.setOnClickListener { cancelScan() }
        binding.f063ExportButton.setOnClickListener { exportToText() }

        renderIdle()
        setBusy(false)
        refreshSubnet()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    // ===== 网段 =====

    /**
     * 取一次本机网段。
     *
     * 网卡枚举在某些盒子上要几百毫秒（尤其是插着网线 + Wi-Fi 同时在线），
     * 所以放 IO 线程；取不到就显示"未获取"，不弹错——用户可能只是想先看一眼端口表。
     */
    private fun refreshSubnet() {
        lifecycleScope.launch {
            val value = withContext(Dispatchers.IO) {
                try {
                    F063Engine.localSubnet()
                } catch (e: Exception) {
                    null
                }
            }
            if (isFinishing || isDestroyed) return@launch
            subnet = value
            binding.f063SubnetValue.text = value ?: getString(R.string.f063_subnet_unknown)
        }
    }

    // ===== 扫描 =====

    /** 开始一轮扫描；已经在扫时不重入，只提示一句。 */
    private fun startScan() {
        if (job?.isActive == true) {
            CustomToast.show(this, getString(R.string.f063_toast_scanning))
            return
        }
        resetResults()
        setBusy(true)
        setStatus(getString(R.string.f063_status_scanning), R.color.theme_text_secondary)
        binding.f063Progress.progress = 0
        binding.f063ProgressText.text = getString(R.string.f063_progress_start)

        job = lifecycleScope.launch {
            val target = subnet ?: withContext(Dispatchers.IO) {
                try {
                    F063Engine.localSubnet()
                } catch (e: Exception) {
                    null
                }
            }
            if (target == null) {
                setBusy(false)
                setStatus(getString(R.string.f063_toast_no_subnet), R.color.theme_error)
                CustomToast.show(this@F063Activity, getString(R.string.f063_toast_no_subnet))
                return@launch
            }
            subnet = target
            binding.f063SubnetValue.text = target

            val startedAt = System.currentTimeMillis()
            try {
                val results = withContext(Dispatchers.IO) {
                    F063Engine.scan(
                        subnet = target,
                        onProgress = { progress ->
                            // 引擎在 IO 线程回调，视图只能在主线程碰
                            runOnUiThread { onScanProgress(progress) }
                        }
                    )
                }
                if (isFinishing || isDestroyed) return@launch
                renderResults(results, System.currentTimeMillis() - startedAt)
                setStatus(
                    if (results.isEmpty()) {
                        getString(R.string.f063_status_empty)
                    } else {
                        getString(R.string.f063_status_done, results.size)
                    },
                    if (results.isEmpty()) R.color.theme_warning else R.color.theme_success
                )
            } catch (cancel: CancellationException) {
                // 取消是用户主动动作，别吞掉：scope 还要靠这个异常把子任务收干净
                throw cancel
            } catch (e: Exception) {
                setStatus(
                    getString(R.string.f063_status_error, e.javaClass.simpleName),
                    R.color.theme_error
                )
            } finally {
                setBusy(false)
            }
        }
    }

    /** 取消当前扫描；没在扫时只提示。已经扫到的结果留在列表里。 */
    private fun cancelScan() {
        if (job?.isActive != true) {
            CustomToast.show(this, getString(R.string.f063_toast_not_scanning))
            return
        }
        job?.cancel()
        job = null
        setBusy(false)
        setStatus(getString(R.string.f063_status_cancelled, found.size), R.color.theme_warning)
    }

    /** 一台探完：进度条、计数行、即时追加一行结果。 */
    private fun onScanProgress(progress: ScanProgress) {
        if (isFinishing || isDestroyed) return
        binding.f063Progress.max = progress.total.coerceAtLeast(1)
        binding.f063Progress.progress = progress.scanned
        binding.f063ProgressText.text =
            getString(R.string.f063_progress_value, progress.scanned, progress.total)

        val hit = progress.found ?: return
        found += hit
        addResultRow(hit)
        binding.f063ResultSummary.text = getString(R.string.f063_summary_partial, found.size)
    }

    // ===== 渲染 =====

    private fun renderIdle() {
        binding.f063Progress.progress = 0
        binding.f063ProgressText.text = getString(R.string.f063_progress_idle)
        binding.f063ResultSummary.text = getString(R.string.f063_summary_empty)
        binding.f063ExportText.text = getString(R.string.f063_export_hint)
        binding.f063ExportText.setTextColor(ContextCompat.getColor(this, R.color.theme_text_secondary))
        setStatus(getString(R.string.f063_status_idle), R.color.theme_text_secondary)
    }

    /** 清掉上一轮的结果与导出文本；开始新一轮扫描前调用。 */
    private fun resetResults() {
        found.clear()
        binding.f063ResultList.removeAllViews()
        binding.f063ResultSummary.text = getString(R.string.f063_summary_empty)
        binding.f063ExportText.text = getString(R.string.f063_export_hint)
        binding.f063ExportText.setTextColor(ContextCompat.getColor(this, R.color.theme_text_secondary))
    }

    /**
     * 扫描结束时整体重排一遍。
     *
     * 扫描过程中是按完成顺序追加的（谁先回包谁在前），收口后按 IP 末段升序重排，
     * 结果列表和导出文本才对得上一份能看的清单。
     */
    private fun renderResults(results: List<HostResult>, elapsedMs: Long) {
        found.clear()
        found.addAll(results)
        binding.f063ResultList.removeAllViews()
        results.forEach { addResultRow(it) }
        binding.f063ResultSummary.text = if (results.isEmpty()) {
            getString(R.string.f063_summary_empty)
        } else {
            getString(R.string.f063_summary_done, results.size, (elapsedMs / 1000L).toInt())
        }
    }

    /** 结果列表的一行：左边 IP（等宽），右边端口（有开放端口用成功色）。 */
    private fun addResultRow(result: HostResult) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }

        val ipView = TextView(this).apply {
            text = result.ip
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(context, R.color.theme_text_primary))
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                0.38f
            )
        }

        val portView = TextView(this).apply {
            text = portsText(result)
            textSize = 13f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (result.openPorts.isEmpty()) R.color.theme_text_secondary else R.color.theme_success
                )
            )
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                0.62f
            )
        }

        row.addView(ipView)
        row.addView(portView)

        val added = binding.f063ResultList.childCount
        binding.f063ResultList.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (added > 0) topMargin = dp(2) }
        )
    }

    /** 一行的端口文案：`80 HTTP · 443 HTTPS`；只有 RST 的主机写明"无开放端口"。 */
    private fun portsText(result: HostResult): String {
        if (result.openPorts.isEmpty()) return getString(R.string.f063_ports_none)
        val ports = F063Engine.describePorts(result)
        return if (result.rttMs > 0L) {
            getString(R.string.f063_ports_value, ports, result.rttMs)
        } else {
            ports
        }
    }

    // ===== 导出 =====

    /**
     * 把这一轮结果写进下方等宽文本区。
     *
     * 结果为空时直接提示，不生成一份只有表头的文本——那种文本复制走没有意义。
     */
    private fun exportToText() {
        if (found.isEmpty()) {
            CustomToast.show(this, getString(R.string.f063_toast_export_empty))
            return
        }
        binding.f063ExportText.text = F063Engine.exportText(found)
        binding.f063ExportText.setTextColor(ContextCompat.getColor(this, R.color.theme_text_primary))
        setStatus(getString(R.string.f063_status_exported, found.size), R.color.theme_success)
        CustomToast.show(this, getString(R.string.f063_toast_exported))
    }

    // ===== 状态 =====

    private fun setBusy(busy: Boolean) {
        binding.f063ScanButton.isEnabled = !busy
        binding.f063ScanButton.isFocusable = !busy
        binding.f063ScanButton.alpha = if (busy) 0.4f else 1f

        binding.f063ExportButton.isEnabled = !busy
        binding.f063ExportButton.isFocusable = !busy
        binding.f063ExportButton.alpha = if (busy) 0.4f else 1f

        binding.f063CancelButton.isEnabled = busy
        binding.f063CancelButton.isFocusable = busy
        binding.f063CancelButton.alpha = if (busy) 1f else 0.4f
    }

    private fun setStatus(message: CharSequence, colorRes: Int) {
        binding.f063Status.text = message
        binding.f063Status.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).roundToInt()
}
