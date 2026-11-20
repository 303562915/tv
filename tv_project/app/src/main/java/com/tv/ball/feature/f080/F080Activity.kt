package com.tv.ball.feature.f080

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF080Binding
import com.tv.ball.util.CustomToast

/**
 * 「隐私模式」页。
 *
 * 四段：
 * 1. **总开关**：一拨即生效并落盘（[F080Engine.save]），关掉立刻恢复记录；
 * 2. **子开关**：观看历史 / 续播进度各自还要不要写，只在总开关打开时可拨；
 * 3. **标题打码**：可选的"首字保留、其余换成 •"，演示区会把打码前后的样子摆在一起；
 * 4. **演示**：按「播放一条」现算一遍——"若开启会写入 / 现在"两行对照，
 *    开着的时候第二行直接是「未记录」，不必真去翻历史列表验证。
 *
 * 所有判断都委托给 [PrivacyMode]，页面自己不做 `if (enabled && ...)`，
 * 免得演示逻辑和真实写入点各写一套、哪天对不上。
 */
class F080Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF080Binding

    /** 当前状态；`onCreate` 里从 prefs 读，之后每次改动都写回 prefs。 */
    private lateinit var mode: PrivacyMode

    /** 演示下标：每按一次「播放一条」加一，标题与进度轮流换。 */
    private var sampleIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF080Binding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = F080Engine.load(this)

        binding.btnBack.setOnClickListener { finish() }
        bindSwitches()
        renderAll()
    }

    // ===== 开关 =====

    /**
     * 绑定 4 个开关。
     *
     * 先拨状态再挂监听：`setOnCheckedChangeListener` 之后 `isChecked = x` 也会回调，
     * 顺序反了会在 `onCreate` 里白落盘一次、还会把演示行重算一遍。
     */
    private fun bindSwitches() {
        binding.f080MasterSwitch.isChecked = mode.enabled
        binding.f080HistorySwitch.isChecked = mode.hideHistory
        binding.f080ProgressSwitch.isChecked = mode.hideProgress
        binding.f080MaskSwitch.isChecked = mode.maskTitles

        binding.f080MasterSwitch.setOnCheckedChangeListener { _, checked ->
            update(mode.withEnabled(checked))
        }
        binding.f080HistorySwitch.setOnCheckedChangeListener { _, checked ->
            update(mode.withHideHistory(checked))
        }
        binding.f080ProgressSwitch.setOnCheckedChangeListener { _, checked ->
            update(mode.withHideProgress(checked))
        }
        binding.f080MaskSwitch.setOnCheckedChangeListener { _, checked ->
            update(mode.withMaskTitles(checked))
        }

        binding.f080DemoButton.setOnClickListener { playOne() }
    }

    /** 一处改动 → 落盘 → 重绘。所有开关都走这里，不存在"改了没存"的路径。 */
    private fun update(next: PrivacyMode) {
        mode = F080Engine.save(this, next)
        renderAll()
    }

    // ===== 渲染 =====

    /** 状态行 + 子开关可用性 + 打码预览 + 演示行，一次全刷。 */
    private fun renderAll() {
        renderStatus()
        renderSubSwitches()
        renderMaskPreview()
        renderDemo()
    }

    /**
     * 状态行：引擎给的一行摘要 + 一句人话结论。
     *
     * 摘要用引擎的 [PrivacyMode.summary]，和首页 / 日志里看到的字面完全一致；
     * 颜色按总开关切（开=成功色、关=次要色），一眼能认出当前是哪种状态。
     */
    private fun renderStatus() {
        binding.f080StatusText.text = mode.summary()
        binding.f080StatusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (mode.enabled) R.color.theme_success else R.color.theme_text_secondary
            )
        )
        binding.f080StatusHint.text = getString(
            if (mode.enabled) R.string.f080_status_hint_on else R.string.f080_status_hint_off
        )
    }

    /**
     * 子开关：总开关关着的时候置灰并显示"总开关关着"，
     * 但**不**改它们自己的值——这样用户把总开关再打开时，之前的选择还在。
     */
    private fun renderSubSwitches() {
        val active = mode.enabled
        for (switch in listOf(binding.f080HistorySwitch, binding.f080ProgressSwitch, binding.f080MaskSwitch)) {
            switch.isEnabled = active
            switch.alpha = if (active) 1f else 0.5f
        }
        binding.f080SubStateText.text = getString(
            if (active) R.string.f080_sub_state_on else R.string.f080_sub_state_off
        )
    }

    /**
     * 打码预览：同一份标题「原样 / 打码后」上下对照。
     *
     * 用引擎里同一个 [PrivacyMode.redactTitle]，所以这张卡永远和真实写入行为一致，
     * 不会出现"示例说会打码、实际没打"的尴尬。总开关关着时 `redactTitle` 原样返回，
     * 两行一样——这本身就说明了"关闭后恢复"。
     */
    private fun renderMaskPreview() {
        val raw = F080Engine.SAMPLE_TITLES.firstOrNull() ?: getString(R.string.f080_title)
        val masked = mode.redactTitle(raw)
        binding.f080MaskSample.text = getString(R.string.f080_mask_sample_body, raw, masked)
        binding.f080MaskChanged.visibility = if (masked != raw) View.VISIBLE else View.GONE
    }

    /**
     * 演示：把"若开启会写入"和"现在"两行写出来。
     *
     * - 上面一行固定用 `PrivacyMode(enabled = false)` 演算，也就是没有任何拦截时的样子；
     * - 下面一行用当前 `mode` 演算，被拦下时直接写「未记录」。
     *
     * 两行都由 [F080Engine.simulate] 算，差异只来自 [PrivacyMode] 的取值。
     */
    private fun renderDemo() {
        val record = F080Engine.sampleRecord(sampleIndex)
        val openMode = F080Engine.defaultMode() // enabled = false，即"若没开隐私模式"
        val wouldWrite = F080Engine.simulate(openMode, record)
        val now = F080Engine.simulate(mode, record)

        binding.f080DemoTitle.text = getString(R.string.f080_demo_current, record.title, F080Engine.progressText(record.progressSeconds))
        binding.f080DemoWould.text = getString(
            R.string.f080_demo_would_write,
            wouldWrite.line.ifEmpty { getString(R.string.f080_demo_none) }
        )
        binding.f080DemoNow.text = if (now.kept) {
            getString(R.string.f080_demo_now_recorded, now.line)
        } else {
            getString(R.string.f080_demo_now_skipped)
        }
        binding.f080DemoNow.setTextColor(
            ContextCompat.getColor(
                this,
                if (now.kept) R.color.theme_text_primary else R.color.theme_success
            )
        )
    }

    /** 演示按一下：换下一条记录并重算两行，同时给一句 Toast 说清结果。 */
    private fun playOne() {
        sampleIndex++
        renderDemo()
        val kept = F080Engine.simulate(mode, F080Engine.sampleRecord(sampleIndex)).kept
        CustomToast.show(
            this,
            getString(if (kept) R.string.f080_toast_recorded else R.string.f080_toast_skipped)
        )
    }
}