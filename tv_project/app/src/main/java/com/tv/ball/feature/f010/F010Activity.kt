package com.tv.ball.feature.f010

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.KeyEvent
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF010Binding
import com.tv.ball.util.CustomToast
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 「手势灵敏度配置」页。
 *
 * 三块内容：阈值滑杆（1~60dp）、增益滑杆（0.2~3.0x）、预览条。
 * 预览条不是动画：每拖一格都把位移交给 [F010Engine] 算一遍，界面上的百分比就是
 * 播放页在同样参数下真会得到的变化量，所以调参时有确定的参照。
 *
 * 电视没有触摸屏：两个滑杆和预览条都能用遥控器左右键调（XML 里 keyProgressIncrement = 1），
 * 确认键在任意焦点下一键恢复默认。
 */
class F010Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF010Binding
    private lateinit var engine: F010Engine

    /** 程序化回填滑杆进度时置位，避免把回填当成用户操作再存一次盘。 */
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF010Binding.inflate(layoutInflater)
        setContentView(binding.root)

        // 引擎只认端口：参数落 video_player_prefs，播放页接线后读的是同一份
        engine = F010Engine(F010PrefsStore(applicationContext))

        binding.f010ThresholdSeek.setOnSeekBarChangeListener(onSeekChanged { progress ->
            engine.setThresholdDp(progress + F010Engine.MIN_THRESHOLD_DP)
            renderThreshold()
            renderPreview()
        })
        binding.f010GainSeek.setOnSeekBarChangeListener(onSeekChanged { progress ->
            engine.setGain(F010Engine.MIN_GAIN + progress * F010Engine.GAIN_STEP)
            renderGain()
            renderPreview()
        })
        binding.f010PreviewSeek.setOnSeekBarChangeListener(onSeekChanged { renderPreview() })
        binding.f010ResetButton.setOnClickListener { resetToDefaults() }

        binding.f010PreviewSeek.progress = PREVIEW_CENTER
        renderAll()

        // 进页面焦点落在阈值滑杆，遥控器立刻能按左右键
        binding.f010ThresholdSeek.requestFocus()
    }

    /**
     * 遥控器确认键 = 一键恢复默认。
     *
     * 焦点已经在「恢复默认」行上时放行，让那一行自己的 click 去处理，避免同一次按键响两遍。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && isConfirmKey(event.keyCode)) {
            if (binding.f010ResetButton.isFocused) {
                return super.dispatchKeyEvent(event)
            }
            resetToDefaults()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isConfirmKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    private fun resetToDefaults() {
        val changed = engine.resetToDefaults()
        renderAll()
        CustomToast.show(
            this,
            getString(if (changed) R.string.f010_reset_done else R.string.f010_reset_already)
        )
    }

    private fun renderAll() {
        renderThreshold()
        renderGain()
        renderPreview()
        binding.f010DefaultSummary.text = getString(
            R.string.f010_default_summary,
            F010Engine.DEFAULT_THRESHOLD_DP,
            F010Engine.DEFAULT_GAIN
        )
    }

    private fun renderThreshold() {
        setProgressSilently(
            binding.f010ThresholdSeek,
            engine.thresholdDp - F010Engine.MIN_THRESHOLD_DP
        )
        binding.f010ThresholdValue.text =
            getString(R.string.f010_threshold_value, engine.thresholdDp)
    }

    private fun renderGain() {
        val progress = ((engine.gain - F010Engine.MIN_GAIN) / F010Engine.GAIN_STEP).roundToInt()
        setProgressSilently(binding.f010GainSeek, progress)
        binding.f010GainValue.text = getString(R.string.f010_gain_value, engine.gain)
    }

    /**
     * 预览区：把预览条的位移当成一次手指竖直滑动，按当前阈值/增益算亮度与音量变化。
     *
     * 亮度口径用本机屏高（dp），和播放页手势区高度同一个算法；
     * 音量口径引擎里固定为 480dp 全程，所以这里不需要再传高度。
     */
    private fun renderPreview() {
        val dyDp = (binding.f010PreviewSeek.progress - PREVIEW_CENTER).toFloat()
        val screenHeightDp = resources.configuration.screenHeightDp.toFloat().coerceAtLeast(1f)

        val brightnessPercent = engine.deltaToBrightness(dyDp, screenHeightDp) * 100f
        val volumePercent = engine.deltaToVolume(dyDp) * 100f
        val hit = engine.applyThreshold(dyDp)

        binding.f010PreviewDelta.text = if (dyDp == 0f) {
            getString(R.string.f010_preview_drag_none)
        } else {
            getString(R.string.f010_preview_drag_value, dyDp.roundToInt())
        }

        binding.f010PreviewHit.setText(if (hit) R.string.f010_hit_yes else R.string.f010_hit_no)
        binding.f010PreviewHit.setTextColor(
            ContextCompat.getColor(
                this,
                if (hit) R.color.theme_primary else R.color.theme_text_secondary
            )
        )

        binding.f010BrightnessValue.text = deltaText(brightnessPercent)
        binding.f010VolumeValue.text = deltaText(volumePercent)
        binding.f010BrightnessBar.progress = barProgress(brightnessPercent)
        binding.f010VolumeBar.progress = barProgress(volumePercent)

        binding.f010PreviewStatus.text = when {
            dyDp == 0f ->
                getString(R.string.f010_preview_status_none, engine.thresholdDp)
            !hit ->
                getString(
                    R.string.f010_preview_status_waiting,
                    (engine.thresholdDp - abs(dyDp)).roundToInt().coerceAtLeast(1)
                )
            else ->
                getString(
                    R.string.f010_preview_status_active,
                    deltaText(brightnessPercent),
                    deltaText(volumePercent)
                )
        }
    }

    /** 变化量的带符号文案；小于 0.05% 视为没有变化，避免显示 "-0.0%"。 */
    private fun deltaText(percent: Float): String = when {
        percent > 0.05f -> getString(R.string.f010_preview_delta_up, percent)
        percent < -0.05f -> getString(R.string.f010_preview_delta_down, abs(percent))
        else -> getString(R.string.f010_preview_delta_zero)
    }

    /** 变化条只表达幅度：进度 0~1000 对应 0~100% 行程。 */
    private fun barProgress(percent: Float): Int =
        (abs(percent) * BAR_MAX / 100f).roundToInt().coerceIn(0, BAR_MAX)

    private fun setProgressSilently(seekBar: SeekBar, progress: Int) {
        syncing = true
        seekBar.progress = progress
        syncing = false
    }

    private fun onSeekChanged(onProgress: (Int) -> Unit): SeekBar.OnSeekBarChangeListener =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (syncing) return
                onProgress(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }

    private companion object {
        /** 预览条中点：progress = 300 表示位移 0dp，范围 -300dp ~ +300dp。 */
        const val PREVIEW_CENTER = 300

        /** 变化条满量程。 */
        const val BAR_MAX = 1000
    }
}

/**
 * 参数的 SharedPreferences 实现。
 *
 * 用 applicationContext，不持有 Activity；prefs 文件名和 [com.tv.ball.data.local.LocalDataSource]
 * 一致（video_player_prefs），播放页接线时直接读同一份键即可。
 */
private class F010PrefsStore(context: Context) : F010SettingsStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): F010Settings? {
        if (!prefs.contains(KEY_THRESHOLD_DP) && !prefs.contains(KEY_GAIN)) return null
        return F010Settings(
            prefs.getInt(KEY_THRESHOLD_DP, F010Engine.DEFAULT_THRESHOLD_DP),
            prefs.getFloat(KEY_GAIN, F010Engine.DEFAULT_GAIN)
        )
    }

    override fun save(settings: F010Settings) {
        prefs.edit()
            .putInt(KEY_THRESHOLD_DP, settings.thresholdDp)
            .putFloat(KEY_GAIN, settings.gain)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "video_player_prefs"
        const val KEY_THRESHOLD_DP = "f010_threshold_dp"
        const val KEY_GAIN = "f010_gain"
    }
}