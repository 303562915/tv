package com.tv.ball.feature.f082

import android.os.Bundle
import android.util.TypedValue
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF082Binding
import com.tv.ball.util.CustomToast

/**
 * 「界面缩放与字号」页。
 *
 * 交互只有两件事：选一个档位、或者一键回到 100%。
 *
 * 数据流（和 [F082Engine] 的约定一致）：
 * 1. 进页面 [F082Engine.load] 读一次落盘档位，回填五个单选按钮并渲染预览；
 * 2. 选中某一档 → **立即写盘**（[F082Engine.save]）+ 立即刷新下方预览；
 * 3. 点「恢复 100%」→ 回到 [F082Engine.defaults]，同样立即写盘 + 刷新预览。
 *
 * 为什么"立即写盘"而不是像别的设置页那样先在草稿上改、再点保存：
 * 这个功能只有五个离散档位，选中即最终决定，多一次「保存」只会让人以为没生效。
 *
 * 为什么预览是 `baseSp × scale` 而不是直接改自己的 `fontScale`：
 * 直接改 `Configuration.fontScale` 会连带重排已经 inflate 出来的整页，
 * 而别的页面（含播放器全屏控件）还停在旧尺寸——一半新一半旧比"没生效"更难解释。
 * 所以本页只做**同一段文案在 100% 与当前档位下的双行对比**，
 * 真正的全站缩放交给下次进程启动（页面底部与状态行都写明了这一点）。
 */
class F082Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF082Binding

    /** 当前生效的档位；单选按钮、预览、状态行都以它为唯一依据。 */
    private var level: ScaleLevel = ScaleLevel.DEFAULT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF082Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.f082Back.setOnClickListener { finish() }
        binding.f082ResetButton.setOnClickListener { applyLevel(ScaleLevel.DEFAULT, resetted = true) }

        // 一个整数键的同步读取，不会卡住首帧；读坏了 [F082Engine.load] 内部已经吸附到最近档位
        level = runCatching { F082Engine.load(this) }.getOrDefault(ScaleLevel.DEFAULT)

        checkRadioSilently(level) // 回填 + 挂监听（内部先摘后挂，避免回填被当成用户改动）
        renderPreview()
        setStatus(getString(R.string.f082_status_loaded, F082Engine.summary(level)), R.color.theme_text_secondary)
    }

    // ===== 单选按钮 =====

    /**
     * 挂上 RadioGroup 的监听。
     *
     * 回填时（[checkRadioSilently]）监听是摘掉的，所以这里挂上之后不会立刻收到一次"假改动"；
     * 回调里再补一条"选到的档位和当前一致就 return"，作为第二道保险。
     */
    private fun installRadioListener() {
        binding.f082ScaleGroup.setOnCheckedChangeListener { _, checkedId ->
            val picked = levelOf(checkedId) ?: return@setOnCheckedChangeListener
            if (picked == level) return@setOnCheckedChangeListener
            applyLevel(picked, resetted = false)
        }
    }

    /**
     * 回填五个单选按钮：先摘监听 → 设选中 → 再挂回监听。
     *
     * RadioGroup 的 `check()` 会同步派发 `onCheckedChanged`，用"抑制标志"容易被下一帧的
     * 回调绕过（电视上焦点移动还会带来额外的 post），所以干脆在回填期间不挂监听。
     */
    private fun checkRadioSilently(target: ScaleLevel) {
        binding.f082ScaleGroup.setOnCheckedChangeListener(null)
        radioFor(target)?.isChecked = true
        highlightRadios(target)
        installRadioListener()
    }

    /** 选中项用主题色加粗视线，未选中保持正文色。 */
    private fun highlightRadios(target: ScaleLevel) {
        val active = ContextCompat.getColor(this, R.color.theme_primary)
        val idle = ContextCompat.getColor(this, R.color.theme_text_primary)
        ScaleLevel.TIERS.forEach { tier ->
            val radio = radioFor(tier) ?: return@forEach
            radio.setTextColor(if (tier == target) active else idle)
        }
    }

    /** 档位 → 布局里的按钮。用 when 而不是按下标取子 View：布局顺序变了也不会错位。 */
    private fun radioFor(target: ScaleLevel): RadioButton? = when (target) {
        ScaleLevel.P90 -> binding.f082Radio90
        ScaleLevel.P100 -> binding.f082Radio100
        ScaleLevel.P110 -> binding.f082Radio110
        ScaleLevel.P120 -> binding.f082Radio120
        ScaleLevel.P130 -> binding.f082Radio130
    }

    /** 选中 id → 档位；不认识的 id（回填期间的 -1 等）返回 null，调用方直接忽略。 */
    private fun levelOf(checkedId: Int): ScaleLevel? = when (checkedId) {
        R.id.f082Radio90 -> ScaleLevel.P90
        R.id.f082Radio100 -> ScaleLevel.P100
        R.id.f082Radio110 -> ScaleLevel.P110
        R.id.f082Radio120 -> ScaleLevel.P120
        R.id.f082Radio130 -> ScaleLevel.P130
        else -> null
    }

    // ===== 生效 =====

    /**
     * 落盘并刷新预览。
     *
     * 写盘失败时**不改** [level]、也不动单选按钮：宁可让界面停在"上一次确定生效的那档"，
     * 也不要显示一个没存进去的新档位——重启后它会变回去，用户会以为设置被偷改了。
     *
     * @param resetted true 表示由「恢复 100%」触发，只影响状态行与 Toast 的措辞。
     */
    private fun applyLevel(next: ScaleLevel, resetted: Boolean) {
        val saved = try {
            F082Engine.save(this, next)
        } catch (e: Exception) {
            val message = getString(R.string.f082_status_error, e.javaClass.simpleName)
            setStatus(message, R.color.theme_error)
            CustomToast.show(this, message)
            return
        }
        level = saved
        checkRadioSilently(saved)
        renderPreview()
        setStatus(
            getString(if (resetted) R.string.f082_status_reset else R.string.f082_status_saved, F082Engine.summary(saved)),
            R.color.theme_success
        )
        CustomToast.show(this, getString(if (resetted) R.string.f082_toast_reset else R.string.f082_toast_saved))
    }

    // ===== 预览 =====

    /**
     * 双行对比：上行固定 100%（[F082Engine.BASE_TEXT_SP]），下行按当前档位乘系数。
     *
     * 两行文字内容完全相同，只有字号不同——这样"这档到底大多少"是直接看出来的，
     * 不需要读数字。第三行再把算式写出来（`16.0 sp × 120% = 19.2 sp`），
     * 让远近两款电视上的判断都有依据。
     */
    private fun renderPreview() {
        val baseSp = F082Engine.BASE_TEXT_SP
        val scaledSp = F082Engine.scaledSp(baseSp, level)

        binding.f082PreviewBase.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp)
        binding.f082PreviewScaled.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp)
        binding.f082PreviewScaled.setTextColor(ContextCompat.getColor(this, R.color.theme_primary))

        binding.f082PreviewSize.text = getString(
            R.string.f082_preview_size,
            F082Engine.formatSp(baseSp),
            F082Engine.percentLabel(level),
            F082Engine.formatSp(scaledSp)
        )
    }

    private fun setStatus(message: CharSequence, colorRes: Int) {
        binding.f082Status.text = message
        binding.f082Status.setTextColor(ContextCompat.getColor(this, colorRes))
    }
}
