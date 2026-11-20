package com.tv.ball.feature.f057

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF057Binding
import com.tv.ball.util.CustomToast

/**
 * 「缓冲与并发策略」页。
 *
 * 三段内容：档位预设（省流 / 均衡 / 激进，点一下即套用并保存）、自定义四项数值
 * （缓冲时长 / 最小缓冲 / 最大缓冲 / 并发连接数）、等效参数（当前生效 + 能直接粘进播放器的
 * `DefaultLoadControl.Builder` 片段 + 校验状态）。
 *
 * 数据流：输入框每变一次就把四项读成 [BufferConfig] 交给 [F057Engine.applyTo] 重算预览，
 * 所以「实时展示等效参数」是真的实时，不点保存也能看到改完是什么效果；
 * 真正落盘只发生在三处 —— 点档位卡片、点保存、点恢复默认。
 *
 * 校验分两步：越界先由 [F057Engine.validate] 列出来（状态行逐行显示），保存时 [F057Engine.save]
 * 再把值夹回合法区间。界面不会静默改数：纠正过就明说「已按上下界纠正后保存」，并把原来哪几处不对一并列出。
 *
 * 电视没有触摸屏：三张预设卡片、四个输入框、两个动作行都可聚焦，
 * 焦点顺序在 XML 里用 nextFocus* 串成 返回 → 省流 → 均衡 → 激进 → 缓冲 → 最小 → 最大 → 并发 → 保存 → 恢复默认。
 */
class F057Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF057Binding

    /** 程序回填输入框时置位，避免回填本身触发一轮重算。 */
    private var filling: Boolean = false

    /** 四个输入框共用：任一变化就刷新等效参数预览。 */
    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(s: Editable?) {
            if (filling) return
            renderPreview(readInputs())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF057Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        // 电视遥控器没有软键盘，数字键盘只在触摸设备上出现；输入过滤统一交给 toIntOrNull
        inputs().forEach { input ->
            input.inputType = InputType.TYPE_CLASS_NUMBER
            input.addTextChangedListener(watcher)
        }

        binding.f057PresetSaver.setOnClickListener { applyPreset(Preset.SAVER) }
        binding.f057PresetBalanced.setOnClickListener { applyPreset(Preset.BALANCED) }
        binding.f057PresetAggressive.setOnClickListener { applyPreset(Preset.AGGRESSIVE) }
        binding.f057SaveButton.setOnClickListener { save() }
        binding.f057ResetButton.setOnClickListener { reset() }

        renderAll(F057Engine.load(this))
        // 电视进页面就把焦点放在第一张卡片上，遥控器立刻能选档
        binding.f057PresetSaver.requestFocus()
    }

    // ===== 输入 =====

    private fun inputs(): List<EditText> = listOf(
        binding.f057BufferInput,
        binding.f057MinInput,
        binding.f057MaxInput,
        binding.f057ConnInput
    )

    /** 读四项输入；任何一个为空或不是数字就返回 null，预览与保存都据此提示「先补全」。 */
    private fun readInputs(): BufferConfig? {
        val buffer = binding.f057BufferInput.text?.toString()?.trim()?.toIntOrNull() ?: return null
        val min = binding.f057MinInput.text?.toString()?.trim()?.toIntOrNull() ?: return null
        val max = binding.f057MaxInput.text?.toString()?.trim()?.toIntOrNull() ?: return null
        val parallel = binding.f057ConnInput.text?.toString()?.trim()?.toIntOrNull() ?: return null
        return BufferConfig(
            bufferMs = buffer,
            minBufferMs = min,
            maxBufferMs = max,
            parallelConnections = parallel
        )
    }

    private fun fillInputs(config: BufferConfig) {
        filling = true
        binding.f057BufferInput.setText(config.bufferMs.toString())
        binding.f057MinInput.setText(config.minBufferMs.toString())
        binding.f057MaxInput.setText(config.maxBufferMs.toString())
        binding.f057ConnInput.setText(config.parallelConnections.toString())
        filling = false
    }

    // ===== 动作 =====

    /**
     * 套用预设：预设本身就是一组合法参数，直接落盘，不要求再点一次保存。
     * 套用后输入框一起改掉，用户想微调可以接着改，改完再点保存。
     */
    private fun applyPreset(preset: Preset) {
        val saved = F057Engine.save(this, F057Engine.of(preset))
        renderAll(saved)
        setStatus(getString(R.string.f057_preset_status, labelOf(preset), saved.summary), R.color.theme_success)
        CustomToast.show(this, getString(R.string.f057_preset_applied, labelOf(preset)))
    }

    private fun save() {
        val raw = readInputs()
        if (raw == null) {
            setStatus(getString(R.string.f057_input_invalid), R.color.theme_error)
            CustomToast.show(this, getString(R.string.f057_input_invalid))
            return
        }

        // 先记下原始输入有哪些毛病，再交给引擎纠正并落盘，两步分开才能把「改了什么」说清楚
        val issues = F057Engine.validate(raw)
        val saved = F057Engine.save(this, raw)
        renderAll(saved)

        if (issues.isEmpty()) {
            setStatus(getString(R.string.f057_saved, saved.summary), R.color.theme_success)
            CustomToast.show(this, getString(R.string.f057_saved_toast))
        } else {
            setStatus(
                getString(R.string.f057_saved_fixed, issues.joinToString("\n")),
                R.color.theme_warning
            )
            CustomToast.show(this, getString(R.string.f057_saved_fixed_toast))
        }
    }

    private fun reset() {
        val config = F057Engine.reset(this)
        renderAll(config)
        setStatus(getString(R.string.f057_reset_status, config.summary), R.color.theme_success)
        CustomToast.show(this, getString(R.string.f057_reset_done))
    }

    // ===== 渲染 =====

    private fun renderAll(config: BufferConfig) {
        fillInputs(config)
        renderPresets(config)
        renderSavedInfo(config)
        renderPreview(config)
    }

    private fun renderPresets(config: BufferConfig) {
        val active = F057Engine.activePreset(config)
        renderPreset(Preset.SAVER, binding.f057PresetSaver, binding.f057PresetSaverDetail, binding.f057CheckSaver, active)
        renderPreset(
            Preset.BALANCED,
            binding.f057PresetBalanced,
            binding.f057PresetBalancedDetail,
            binding.f057CheckBalanced,
            active
        )
        renderPreset(
            Preset.AGGRESSIVE,
            binding.f057PresetAggressive,
            binding.f057PresetAggressiveDetail,
            binding.f057CheckAggressive,
            active
        )
    }

    /** 选中态换底 + 对勾显隐；卡片上的数值永远由引擎给出，界面不另抄一份。 */
    private fun renderPreset(
        preset: Preset,
        card: View,
        detail: TextView,
        check: ImageView,
        active: Preset?
    ) {
        val selected = preset == active
        card.setBackgroundResource(
            if (selected) R.drawable.bg_mode_card_selected else R.drawable.bg_mode_card
        )
        check.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        detail.text = F057Engine.of(preset).summary
    }

    private fun renderSavedInfo(config: BufferConfig) {
        binding.f057CurrentSummary.text = config.summary
        binding.f057ActivePreset.text =
            getString(R.string.f057_active_preset, labelOf(F057Engine.activePreset(config)))
    }

    /**
     * 预览一版参数：null 表示输入还不完整。
     *
     * 这里只读不写，所以输入框里改到一半（例如把 18000 删成 18）也只会弹提示，不会污染已保存的配置。
     */
    private fun renderPreview(config: BufferConfig?) {
        if (config == null) {
            binding.f057PreviewParams.text = getString(R.string.f057_preview_empty)
            setStatus(getString(R.string.f057_input_invalid), R.color.theme_warning)
            return
        }

        binding.f057PreviewParams.text = F057Engine.applyTo(config)

        val issues = F057Engine.validate(config)
        if (issues.isEmpty()) {
            setStatus(getString(R.string.f057_status_ok), R.color.theme_text_secondary)
        } else {
            setStatus(
                getString(R.string.f057_status_issues, issues.size, issues.joinToString("\n")),
                R.color.theme_warning
            )
        }
    }

    private fun setStatus(message: CharSequence, colorRes: Int) {
        binding.f057PreviewStatus.text = message
        binding.f057PreviewStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun labelOf(preset: Preset?): String = getString(
        when (preset) {
            Preset.SAVER -> R.string.f057_preset_saver
            Preset.BALANCED -> R.string.f057_preset_balanced
            Preset.AGGRESSIVE -> R.string.f057_preset_aggressive
            null -> R.string.f057_preset_custom
        }
    )
}
