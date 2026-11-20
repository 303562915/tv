package com.tv.ball.feature.f007

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF007Binding

/**
 * 画面比例中心。
 *
 * 预览区里那块实心色块就是「画面实际占位」：FIT 会缩进去留边，
 * CROP/STRETCH 铺满容器，ORIGINAL 按片源比例往外撑。
 * 真正的缩放交给播放页读取 [F007Engine.mode] 后转成 resizeMode。
 */
class F007Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF007Binding
    private lateinit var engine: F007Engine

    private var sourceAspect = 16f / 9f

    override fun onCreate(savedInstanceState: Bundle?) {
        val nightMode = com.tv.ball.di.Dependencies.configRepository.getNightMode()
        com.tv.ball.utils.ThemeUtils.applyNightMode(nightMode)
        super.onCreate(savedInstanceState)
        binding = ActivityF007Binding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = F007Engine.create(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnCycle.setOnClickListener {
            engine.cycle()
            render()
        }
        binding.btnReset.setOnClickListener {
            engine.reset()
            render()
        }
        binding.btnSource169.setOnClickListener { selectSource(16f / 9f) }
        binding.btnSource43.setOnClickListener { selectSource(4f / 3f) }
        binding.btnSource219.setOnClickListener { selectSource(21f / 9f) }

        binding.btnCycle.requestFocus()
        render()
    }

    private fun selectSource(aspect: Float) {
        sourceAspect = aspect
        render()
    }

    private fun render() {
        val mode = engine.mode
        binding.tvModeName.text = getString(mode.labelRes)
        binding.tvModeDesc.text = getString(descOf(mode))

        val frame = binding.previewFrame
        val containerW = frame.width.coerceAtLeast(1)
        val containerH = frame.height.coerceAtLeast(1)
        val rect = engine.displaySize(mode, containerW, containerH, sourceAspect)
        val lp = FrameLayout.LayoutParams(rect.width, rect.height).apply {
            gravity = Gravity.CENTER
        }
        binding.videoSurface.layoutParams = lp

        binding.tvLetterbox.text = if (engine.hasLetterbox(mode, containerW, containerH, sourceAspect)) {
            getString(R.string.f007_letterbox_yes)
        } else {
            getString(R.string.f007_letterbox_no)
        }

        // 裁剪模式下给个视觉提示：色块用主色，避免和「留黑边」看起来一样
        binding.videoSurface.setBackgroundColor(
            if (mode == AspectMode.CROP || mode == AspectMode.STRETCH) {
                getColor(R.color.theme_primary_container)
            } else {
                getColor(R.color.surface_inset)
            }
        )
        binding.videoSurface.layoutParams = lp
        binding.previewFrame.requestLayout()

    }

    private fun descOf(mode: AspectMode): Int = when (mode) {
        AspectMode.FIT -> R.string.f007_desc_fit
        AspectMode.CROP -> R.string.f007_desc_crop
        AspectMode.STRETCH -> R.string.f007_desc_stretch
        AspectMode.ORIGINAL -> R.string.f007_desc_original
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) render()
    }
}
