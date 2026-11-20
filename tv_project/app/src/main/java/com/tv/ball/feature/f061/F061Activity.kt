package com.tv.ball.feature.f061

import android.content.Context
import android.net.wifi.WifiManager
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
import com.tv.ball.databinding.ActivityF061Binding
import com.tv.ball.util.CustomToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 「DLNA 设备发现」页。
 *
 * 只有一件事：点「搜索设备」，把局域网里回 SSDP 的电视 / 盒子列出来，能看到名字、USN 和地址。
 * 数据流是「点按钮 → IO 线程跑 [F061Engine.discover] → 回主线程渲染」，
 * 没有自动轮询：SSDP 会往局域网里打组播包，跟着页面生命周期反复发容易被路由器当扫描，手动一次更可控。
 *
 * 三个必须讲清楚的状态：
 * 1. 搜索中：按钮变灰、进度条出现、状态行写明这次窗口有多长（超时就结束，不会一直转）；
 * 2. 搜到了：每台设备一张小卡，第一行名字、第二行 USN、第三行地址；
 * 3. 没搜到：区分「网络层面就没发出去」（[F061Engine.Failure]）和「发出去了但没人应答」，
 *    前者是有依据的失败（组播被系统/网卡拦了），后者给一份常见原因清单，别让用户以为功能坏了。
 *
 * 组播锁：Android 默认会把 Wi-Fi 驱动的组播包过滤掉，不拿 [WifiManager.MulticastLock] 时
 * 大部分设备只在拿到锁的那一小段时间里会回应。锁在搜索开始时拿，搜索结束立刻放，
 * 不在 onResume 里长期持有——那个锁很费电。
 */
class F061Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF061Binding

    /** 当前在跑的搜索；重复点击先取消旧的，避免两次结果互相覆盖。 */
    private var job: Job? = null

    /** 搜索期间持有的组播锁；拿不到（模拟器、有线网）也不阻塞搜索，只是命中率会低一些。 */
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF061Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.f061SearchButton.setOnClickListener { startSearch() }

        renderIdle()
    }

    override fun onDestroy() {
        job?.cancel()
        releaseMulticastLock()
        super.onDestroy()
    }

    // ===== 搜索 =====

    /**
     * 发起一次搜索。
     *
     * 组播锁每次搜索各拿一把、各放各的，不共用一把：上一轮被取消时它自己的 finally 只放自己那把，
     * 不会把新一轮刚拿到的锁顺手放掉。锁在 `withContext` 之前拿，释放放在 finally 里。
     */
    private fun startSearch() {
        job?.cancel()
        releaseMulticastLock()
        val lock = acquireMulticastLock()
        setBusy(true)
        renderSearching()
        CustomToast.show(this, getString(R.string.f061_toast_searching))

        job = lifecycleScope.launch {
            try {
                val devices = withContext(Dispatchers.IO) { F061Engine.discover(SEARCH_TIMEOUT_MS) }
                if (isFinishing || isDestroyed) return@launch
                setBusy(false)
                renderResult(devices, F061Engine.lastFailure, F061Engine.lastError)
            } finally {
                releaseLock(lock)
            }
        }
    }

    /** 拿一次组播锁；拿不到（有线网 / 模拟器）返回 null，搜索照跑，只是命中率低。 */
    private fun acquireMulticastLock(): WifiManager.MulticastLock? {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = try {
            wifi?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (t: Throwable) {
            null
        }
        multicastLock = lock
        return lock
    }

    /** 释放一把具体的锁；页面退出时按字段兜底再放一次，防止协程没跑完就被销毁。 */
    private fun releaseLock(lock: WifiManager.MulticastLock?) {
        if (multicastLock === lock) multicastLock = null
        if (lock == null) return
        runCatching { if (lock.isHeld) lock.release() }
    }

    private fun releaseMulticastLock() {
        releaseLock(multicastLock)
    }

    private fun setBusy(busy: Boolean) {
        binding.f061SearchButton.isEnabled = !busy
        binding.f061SearchButton.alpha = if (busy) 0.5f else 1f
        binding.f061Progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.f061DeviceList.visibility = if (busy) View.GONE else View.VISIBLE
    }

    // ===== 渲染 =====

    /** 刚进页面的样子：没搜过，设备区给一句说明，失败说明不显示。 */
    private fun renderIdle() {
        binding.f061DeviceList.removeAllViews()
        binding.f061Summary.text = getString(R.string.f061_summary_idle)
        binding.f061EmptyHint.visibility = View.VISIBLE
        binding.f061EmptyHint.text = getString(R.string.f061_empty_idle)
        binding.f061FailureHint.visibility = View.GONE
        setStatus(getString(R.string.f061_status_idle), R.color.theme_text_secondary)
    }

    private fun renderSearching() {
        binding.f061DeviceList.removeAllViews()
        binding.f061Summary.text = getString(R.string.f061_summary_searching)
        binding.f061EmptyHint.visibility = View.VISIBLE
        binding.f061EmptyHint.text = getString(R.string.f061_empty_searching)
        binding.f061FailureHint.visibility = View.GONE
        setStatus(
            getString(R.string.f061_status_searching, SEARCH_TIMEOUT_MS / 1000),
            R.color.theme_text_secondary
        )
    }

    /**
     * 结果渲染。
     *
     * [failure] 非空表示这次搜索在跑的过程中出错了（组播被拦 / 没网络），
     * 和「跑完了但一台都没回」是两码事：前者状态行写原因，后者给常见原因清单。
     */
    private fun renderResult(
        devices: List<F061Engine.DlnaDevice>,
        failure: F061Engine.Failure?,
        error: String?
    ) {
        val container = binding.f061DeviceList
        container.removeAllViews()

        if (devices.isEmpty()) {
            binding.f061Summary.text = getString(R.string.f061_summary_empty)
            binding.f061EmptyHint.visibility = View.VISIBLE
            binding.f061EmptyHint.text = getString(R.string.f061_empty_none)
            binding.f061FailureHint.visibility = View.VISIBLE
            binding.f061FailureHint.text = failureText(failure, error)
            binding.f061FailureHint.setTextColor(
                color(if (failure != null) R.color.theme_error else R.color.theme_warning)
            )
            setStatus(
                if (failure != null) getString(R.string.f061_status_failed) else getString(R.string.f061_status_empty),
                if (failure != null) R.color.theme_error else R.color.theme_warning
            )
            return
        }

        // 有结果就按每台一张小卡铺开；设备数通常个位数，动态加 View 比背一个 Adapter 划算
        binding.f061EmptyHint.visibility = View.GONE
        binding.f061Summary.text = getString(R.string.f061_summary_found, devices.size)
        setStatus(getString(R.string.f061_status_found, devices.size), R.color.theme_success)

        if (failure != null) {
            // 出了问题但已经拿到部分设备：仍然提示一句，投屏可能只对其中一部分有效
            binding.f061FailureHint.visibility = View.VISIBLE
            binding.f061FailureHint.setTextColor(color(R.color.theme_warning))
            binding.f061FailureHint.text = getString(
                R.string.f061_partial_hint,
                failureText(failure, error)
            )
        } else {
            binding.f061FailureHint.visibility = View.GONE
        }

        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        val pad = dp(12)

        devices.forEachIndexed { index, device ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(this@F061Activity, R.drawable.bg_card_surface)
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (index == 0) 0 else gap }
            }

            row.addView(TextView(this).apply {
                text = device.displayName
                setTextColor(color(R.color.theme_text_primary))
                textSize = 15f
                maxLines = 2
                isFocusable = false
            })

            row.addView(TextView(this).apply {
                text = getString(R.string.f061_row_usn, device.usn.ifBlank { getString(R.string.f061_value_none) })
                setTextColor(color(R.color.theme_text_secondary))
                textSize = 12f
                maxLines = 1
                gravity = Gravity.START
                setPadding(0, dp(4), 0, 0)
                isFocusable = false
            })

            row.addView(TextView(this).apply {
                text = getString(
                    R.string.f061_row_address,
                    device.host.ifBlank { getString(R.string.f061_value_none) }
                )
                setTextColor(color(R.color.theme_text_secondary))
                textSize = 12f
                maxLines = 1
                setPadding(0, dp(2), 0, 0)
                isFocusable = false
            })

            container.addView(row)
        }
    }

    /**
     * 失败类型 → 说明文案。
     *
     * 三种 [F061Engine.Failure] 各说各的补救办法；没有异常（纯属没人应答）时给常见原因清单，
     * 因为「一台都没搜到」在家里最常见的其实是电视没开投屏、路由开了 AP 隔离。
     */
    private fun failureText(failure: F061Engine.Failure?, error: String?): String {
        val reason = error?.takeIf { it.isNotBlank() } ?: getString(R.string.f061_value_none)
        return when (failure) {
            F061Engine.Failure.MULTICAST_UNSUPPORTED -> getString(R.string.f061_fail_multicast, reason)
            F061Engine.Failure.NO_NETWORK -> getString(R.string.f061_fail_no_network, reason)
            F061Engine.Failure.UNKNOWN -> getString(R.string.f061_fail_unknown, reason)
            null -> getString(R.string.f061_fail_none_hint)
        }
    }

    private fun setStatus(message: CharSequence, colorRes: Int) {
        binding.f061StatusLine.text = message
        binding.f061StatusLine.setTextColor(color(colorRes))
        binding.f061StatusLine.visibility = View.VISIBLE
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).roundToInt()

    companion object {

        /** 搜索窗口；跟 [F061Engine.DEFAULT_TIMEOUT_MS] 保持一致，界面文案里的秒数由它换算。 */
        private val SEARCH_TIMEOUT_MS: Int = F061Engine.DEFAULT_TIMEOUT_MS

        /** 组播锁的 tag，排查「谁一直握着组播」时按这个找。 */
        private const val MULTICAST_LOCK_TAG = "tv:dlna-discovery"
    }
}
