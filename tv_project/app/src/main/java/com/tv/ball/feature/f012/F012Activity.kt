package com.tv.ball.feature.f012

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF012Binding
import com.tv.ball.util.CustomToast

/**
 * 「字幕样式设置」页。
 *
 * 三段设置（字号 / 颜色 / 描边 / 背景）加一个常驻预览区。
 * 任何改动都走 [applyStyle]：夹值 → 存盘 → 重绘，所以「实时预览」与「重进沿用」是同一段逻辑，
 * 不会出现预览改了但没存、或者重进样式跑偏的割裂。
 */
class F012Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF012Binding
    private lateinit var style: SubtitleStyle

    /** 六个色块：容器（焦点态底板）+ 选中对勾，顺序与 [Palette.ALL] 一致。 */
    private val swatchChecks = mutableListOf<ImageView>()

    /** 滑杆监听器复用同一份，重绘时先摘后挂，避免 setProgress 反过来触发保存。 */
    private val sizeListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (!fromUser) return
            applyStyle(style.withFontSize(SubtitleStyle.FONT_MIN_SP + progress))
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private val outlineListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (!fromUser) return
            applyStyle(style.withOutlineWidth(SubtitleStyle.OUTLINE_MIN_DP + progress))
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF012Binding.inflate(layoutInflater)
        setContentView(binding.root)

        style = F012Engine.load(this)

        setupFontSize()
        setupColorSwatches()
        setupOutline()
        setupBackground()
        binding.f012Reset.setOnClickListener { resetToDefault() }

        // 预览台固定深色：模拟播放器画面，不能跟主题走，否则浅色主题下白字看不见
        binding.f012PreviewStage.setBackgroundColor(PREVIEW_STAGE_COLOR)

        renderAll()
        // 电视没有触摸屏：焦点直接落在字号条上，遥控器左右键就能调
        binding.f012SizeSeek.requestFocus()
    }

    // ===== 字号 =====

    private fun setupFontSize() {
        binding.f012SizeSeek.max = SubtitleStyle.FONT_MAX_SP - SubtitleStyle.FONT_MIN_SP
        // 遥控器每按一次走 1sp；粗调交给左右两侧的 ±2 按钮
        binding.f012SizeSeek.keyProgressIncrement = 1
        binding.f012SizeSeek.setOnSeekBarChangeListener(sizeListener)
        binding.f012SizeMinus.setOnClickListener {
            applyStyle(style.withFontSize(style.fontSizeSp - COARSE_STEP))
        }
        binding.f012SizePlus.setOnClickListener {
            applyStyle(style.withFontSize(style.fontSizeSp + COARSE_STEP))
        }
    }

    // ===== 文字颜色 =====

    private fun setupColorSwatches() {
        val box = dp(64)
        val inner = dp(44)

        Palette.ALL.forEach { color ->
            val circle = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    // 浅色块压在浅色卡片上也要有边界
                    setStroke(dp(1), ContextCompat.getColor(this@F012Activity, R.color.theme_divider))
                }
            }
            val check = ImageView(this).apply {
                setImageResource(R.drawable.ic_check_primary)
                // 黑块上的 primary 对勾几乎看不见，换成白色
                if (color == Palette.BLACK) {
                    setColorFilter(ContextCompat.getColor(this@F012Activity, R.color.white))
                }
                visibility = View.INVISIBLE
            }
            val container = FrameLayout(this).apply {
                layoutParams = ViewGroup.MarginLayoutParams(box, box).apply {
                    setMargins(0, 0, dp(8), 0)
                }
                background = ContextCompat.getDrawable(
                    this@F012Activity,
                    R.drawable.bg_card_surface
                )
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
                contentDescription = colorName(color)
                setOnClickListener { applyStyle(style.withTextColor(color)) }
                addView(circle, FrameLayout.LayoutParams(inner, inner, Gravity.CENTER))
                addView(check, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            }
            swatchChecks.add(check)
            binding.f012ColorGrid.addView(container)
        }

        // 遥控器从色块往下走要跳出网格，否则会被背景几行抢走焦点
        binding.f012ColorGrid.nextFocusDownId = R.id.f012OutlineSwitch
    }

    private fun colorName(color: Int): String = getString(
        when (color) {
            Palette.WHITE -> R.string.f012_color_white
            Palette.YELLOW -> R.string.f012_color_yellow
            Palette.CYAN -> R.string.f012_color_cyan
            Palette.GREEN -> R.string.f012_color_green
            Palette.PINK -> R.string.f012_color_pink
            else -> R.string.f012_color_black
        }
    )

    private fun renderColorSelection() {
        swatchChecks.forEachIndexed { index, check ->
            check.visibility =
                if (Palette.ALL.getOrNull(index) == style.textColor) View.VISIBLE else View.INVISIBLE
        }
        binding.f012ColorName.text = colorName(style.textColor)
    }

    // ===== 描边 =====

    private fun setupOutline() {
        binding.f012OutlineSwitch.setOnClickListener {
            applyStyle(style.withOutlineEnabled(!style.outlineEnabled))
        }
        binding.f012OutlineSeek.max = SubtitleStyle.OUTLINE_MAX_DP - SubtitleStyle.OUTLINE_MIN_DP
        binding.f012OutlineSeek.keyProgressIncrement = 1
        binding.f012OutlineSeek.setOnSeekBarChangeListener(outlineListener)
    }

    // ===== 背景 =====

    private fun setupBackground() {
        binding.f012BgNone.setOnClickListener {
            applyStyle(style.withBackground(SubtitleBackground.NONE))
        }
        binding.f012BgTranslucent.setOnClickListener {
            applyStyle(style.withBackground(SubtitleBackground.TRANSLUCENT))
        }
        binding.f012BgOpaque.setOnClickListener {
            applyStyle(style.withBackground(SubtitleBackground.OPAQUE))
        }
    }

    // ===== 统一出口 =====

    /** 改样式的唯一入口。预览与持久化同源，两边不可能不一致。 */
    private fun applyStyle(next: SubtitleStyle) {
        style = next.sanitized()
        F012Engine.save(this, style)
        renderAll()
    }

    private fun resetToDefault() {
        style = F012Engine.reset(this)
        renderAll()
        CustomToast.show(this, getString(R.string.f012_toast_reset))
    }

    private fun renderAll() {
        // 字号
        binding.f012SizeSeek.setOnSeekBarChangeListener(null)
        binding.f012SizeSeek.progress = style.fontSizeSp - SubtitleStyle.FONT_MIN_SP
        binding.f012SizeSeek.setOnSeekBarChangeListener(sizeListener)
        binding.f012SizeValue.text = getString(R.string.f012_size_value, style.fontSizeSp)
        binding.f012SizeMinus.isEnabled = style.fontSizeSp > SubtitleStyle.FONT_MIN_SP
        binding.f012SizePlus.isEnabled = style.fontSizeSp < SubtitleStyle.FONT_MAX_SP

        // 颜色
        renderColorSelection()

        // 描边
        binding.f012OutlineState.text = getString(
            if (style.outlineEnabled) R.string.f012_outline_on else R.string.f012_outline_off
        )
        binding.f012OutlineState.setTextColor(
            ContextCompat.getColor(
                this,
                if (style.outlineEnabled) R.color.theme_primary else R.color.theme_text_secondary
            )
        )
        // 关掉描边时滑杆变灰并让出焦点，但宽度值留着，重新打开还是上次那个宽度
        binding.f012OutlineSeek.isEnabled = style.outlineEnabled
        binding.f012OutlineSeek.alpha = if (style.outlineEnabled) 1f else DISABLED_ALPHA
        binding.f012OutlineSeek.setOnSeekBarChangeListener(null)
        binding.f012OutlineSeek.progress = style.outlineWidthDp - SubtitleStyle.OUTLINE_MIN_DP
        if (style.outlineEnabled) {
            binding.f012OutlineSeek.setOnSeekBarChangeListener(outlineListener)
        }
        binding.f012OutlineValue.text = getString(
            R.string.f012_outline_width_value,
            style.outlineWidthDp
        )

        // 背景
        renderBackgroundSelection()

        // 预览：四项分别落到同一个 TextView 上
        applyPreview()

        binding.f012Summary.text = getString(
            R.string.f012_summary,
            style.fontSizeSp,
            colorName(style.textColor),
            if (style.outlineEffective) {
                getString(R.string.f012_summary_outline, style.outlineWidthDp)
            } else {
                getString(R.string.f012_summary_outline_off)
            },
            backgroundName(style.background)
        )
    }

    private fun renderBackgroundSelection() {
        applyBackgroundState(binding.f012BgNone, binding.f012CheckBgNone, style.background == SubtitleBackground.NONE)
        applyBackgroundState(
            binding.f012BgTranslucent,
            binding.f012CheckBgTranslucent,
            style.background == SubtitleBackground.TRANSLUCENT
        )
        applyBackgroundState(
            binding.f012BgOpaque,
            binding.f012CheckBgOpaque,
            style.background == SubtitleBackground.OPAQUE
        )
    }

    /** 选中态：2dp primary 描边 + 右侧对勾，与外观主题页口径一致，不铺 primaryContainer 底色。 */
    private fun applyBackgroundState(row: View, check: View, selected: Boolean) {
        row.setBackgroundResource(
            if (selected) R.drawable.bg_mode_card_selected else R.drawable.bg_mode_card
        )
        check.visibility = if (selected) View.VISIBLE else View.INVISIBLE
    }

    private fun backgroundName(bg: SubtitleBackground): String = getString(
        when (bg) {
            SubtitleBackground.NONE -> R.string.f012_bg_none
            SubtitleBackground.TRANSLUCENT -> R.string.f012_bg_translucent
            SubtitleBackground.OPAQUE -> R.string.f012_bg_opaque
        }
    )

    /** 预览重绘：字号 / 颜色 / 描边 / 背景四项独立生效。 */
    private fun applyPreview() {
        val text = binding.f012Preview
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSizeSp.toFloat())
        text.setTextColor(style.textColor)

        if (style.outlineEffective) {
            // 半径即描边宽度，dx/dy 给 0 才是纯描边而不是投影
            text.setShadowLayer(style.outlineWidthDp.toFloat(), 0f, 0f, Color.BLACK)
        } else {
            text.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }

        val padH = if (style.background == SubtitleBackground.NONE) dp(4) else dp(10)
        val padV = if (style.background == SubtitleBackground.NONE) dp(2) else dp(4)
        text.setPadding(padH, padV, padH, padV)
        text.background = previewBackground(style.background)
    }

    private fun previewBackground(mode: SubtitleBackground): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(6).toFloat()
            when (mode) {
                SubtitleBackground.NONE -> setColor(Color.TRANSPARENT)
                // 半透明黑是电视字幕最通用的基线：亮暗场景都不抢画面
                SubtitleBackground.TRANSLUCENT -> setColor(PREVIEW_TRANSLUCENT)
                SubtitleBackground.OPAQUE -> setColor(Color.BLACK)
            }
        }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    private companion object {
        /** 描边关掉时滑杆半透明，表示当前不可操作。 */
        const val DISABLED_ALPHA = 0.38f

        /** 粗调按钮一次走几 sp；滑杆留给 1sp 精调。 */
        const val COARSE_STEP = 2

        /** 预览台底色：模拟播放器黑场，不跟主题走。 */
        const val PREVIEW_STAGE_COLOR = 0xFF101010.toInt()

        /** 半透明字幕底 50% 黑。 */
        const val PREVIEW_TRANSLUCENT = 0x80000000.toInt()
    }
}