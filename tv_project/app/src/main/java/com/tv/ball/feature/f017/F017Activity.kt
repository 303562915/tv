package com.tv.ball.feature.f017

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF017Binding

/** F017 字幕时间轴平移 · 页面层。 */
class F017Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF017Binding

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private var shiftMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF017Binding.inflate(layoutInflater)
        setContentView(binding.root)

        shiftMs = prefs.getLong(KEY_SHIFT_MS, 0L)

        setupButtons()
        render()
    }

    private fun setupButtons() {
        binding.btnMinus1000.setOnClickListener { applyDelta(-STEP_LONG_MS) }
        binding.btnMinus100.setOnClickListener { applyDelta(-STEP_SHORT_MS) }
        binding.btnPlus100.setOnClickListener { applyDelta(STEP_SHORT_MS) }
        binding.btnPlus1000.setOnClickListener { applyDelta(STEP_LONG_MS) }
        binding.btnReset.setOnClickListener { applyDelta(-shiftMs) }
    }

    private fun applyDelta(deltaMs: Long) {
        shiftMs = (shiftMs + deltaMs).coerceIn(MIN_SHIFT_MS, MAX_SHIFT_MS)
        prefs.edit().putLong(KEY_SHIFT_MS, shiftMs).apply()
        render()
    }

    private fun render() {
        binding.shiftValueText.text = F017Engine.formatShift(shiftMs)

        val shifted = F017Engine.shift(F017Engine.sampleCues(), shiftMs)
        binding.cueCountText.text = getString(R.string.f017_cue_count, shifted.size)

        binding.cuesContainer.removeAllViews()
        if (shifted.isEmpty()) {
            binding.cuesContainer.addView(buildEmptyView())
            return
        }

        shifted.forEachIndexed { index, cue ->
            if (index > 0) {
                binding.cuesContainer.addView(buildCueView(cue, topMarginDp = 8))
            } else {
                binding.cuesContainer.addView(buildCueView(cue, topMarginDp = 0))
            }
        }
    }

    private fun buildCueView(cue: F017Cue, topMarginDp: Int): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(topMarginDp)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.bg_mode_row)
        }

        val time = TextView(this).apply {
            text = "${F017Engine.format(cue.startMs)} → ${F017Engine.format(cue.endMs)}"
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.theme_primary))
        }

        val content = TextView(this).apply {
            text = cue.text
            textSize = 15f
            gravity = Gravity.START
            setTextColor(ContextCompat.getColor(context, R.color.theme_text_primary))
        }

        row.addView(time)
        row.addView(content)
        return row
    }

    private fun buildEmptyView(): TextView = TextView(this).apply {
        text = getString(R.string.f017_cue_empty)
        textSize = 14f
        setTextColor(ContextCompat.getColor(context, R.color.theme_text_secondary))
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    private companion object {
        const val PREFS_NAME = "f017_prefs"
        const val KEY_SHIFT_MS = "f017_shift_ms"
        const val STEP_SHORT_MS = 100L
        const val STEP_LONG_MS = 1_000L
        const val MIN_SHIFT_MS = -30_000L
        const val MAX_SHIFT_MS = 30_000L
    }
}
