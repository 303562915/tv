package com.tv.ball.presentation.settings

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityThemeBinding
import com.tv.ball.di.Dependencies
import com.tv.ball.util.CustomToast
import com.tv.ball.utils.ThemeUtils

class ThemeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThemeBinding
    private val configRepository = Dependencies.configRepository
    private var selectedColor: Int = 0
    private var selectedNightMode: Int = 0

    /** 主题色板单项的子 View 引用：选中只按 tag 判定，不依赖 addView 顺序 */
    private class ColorSwatch(
        val root: View,
        val circle: FrameLayout,
        val check: ImageView,
        val label: TextView
    )

    private val colorSwatches = mutableListOf<ColorSwatch>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThemeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 重建（换色 / 换夜间模式）时优先恢复保存的状态，否则回读配置，避免闪回旧值
        selectedColor = if (savedInstanceState?.containsKey(KEY_SELECTED_COLOR) == true) {
            savedInstanceState.getInt(KEY_SELECTED_COLOR)
        } else {
            configRepository.getThemeColor()
        }
        selectedNightMode = if (savedInstanceState?.containsKey(KEY_SELECTED_NIGHT_MODE) == true) {
            savedInstanceState.getInt(KEY_SELECTED_NIGHT_MODE)
        } else {
            configRepository.getNightMode()
        }

        setupThemeOptions()
        setupNightModeOptions()
        updateColorSelection()
        updateCurrentColorPreview()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // recreate() 前落下当前选择，重建后不会有旧选中态闪一下
        outState.putInt(KEY_SELECTED_COLOR, selectedColor)
        outState.putInt(KEY_SELECTED_NIGHT_MODE, selectedNightMode)
    }

    // ---------------------------------------------------------------- 主题色

    private fun setupThemeOptions() {
        val colors = ThemeUtils.ThemeColor.values().toList()
        binding.themeColorGrid.removeAllViews()
        colorSwatches.clear()

        colors.chunked(COLUMN_COUNT).forEachIndexed { rowIndex, rowColors ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (rowIndex > 0) topMargin = dp(6) }
            }

            rowColors.forEach { themeColor ->
                row.addView(createColorItem(themeColor))
            }
            // 补空位，保证最后一行也是 4 等分宽度
            repeat(COLUMN_COUNT - rowColors.size) {
                row.addView(Space(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }

            binding.themeColorGrid.addView(row)
        }
    }

    private fun createColorItem(themeColor: ThemeUtils.ThemeColor): LinearLayout {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            setPadding(dp(4), dp(8), dp(4), dp(8))
            // 宽度由父行 layout_weight 等分，不写死 dp，窄屏不溢出
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            background = ContextCompat.getDrawable(
                this@ThemeActivity,
                R.drawable.selector_theme_swatch_focus
            )
            contentDescription = "${themeColor.colorName}主题色"
            tag = themeColor
        }

        val circle = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(SWATCH_SIZE_DP), dp(SWATCH_SIZE_DP))
        }

        val check = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                dp(CHECK_SIZE_DP),
                dp(CHECK_SIZE_DP),
                Gravity.CENTER
            )
            setImageResource(R.drawable.ic_check_white)
            contentDescription = null
        }

        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            gravity = Gravity.CENTER_HORIZONTAL
            text = themeColor.colorName
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        circle.addView(check)
        item.addView(circle)
        item.addView(label)
        item.setOnClickListener { selectThemeColor(themeColor) }

        colorSwatches.add(ColorSwatch(item, circle, check, label))
        return item
    }

    /** 统一刷新全部色板：选中 = 2dp primary 描边 + 圆内白色对勾 */
    private fun updateColorSelection() {
        colorSwatches.forEach { swatch ->
            val themeColor = swatch.root.tag as? ThemeUtils.ThemeColor ?: return@forEach
            val selected = themeColor.colorInt == selectedColor

            swatch.circle.background = swatchDrawable(themeColor.colorInt, selected)
            swatch.check.visibility = if (selected) View.VISIBLE else View.GONE
            swatch.label.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.theme_text_primary else R.color.theme_text_secondary
                )
            )
            swatch.root.isSelected = selected
        }
    }

    private fun swatchDrawable(colorInt: Int, selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorInt)
            if (selected) {
                setStroke(
                    dp(2),
                    ContextCompat.getColor(this@ThemeActivity, R.color.theme_primary)
                )
            } else {
                setStroke(
                    dp(1),
                    ContextCompat.getColor(this@ThemeActivity, R.color.theme_divider)
                )
            }
        }

    private fun selectThemeColor(themeColor: ThemeUtils.ThemeColor) {
        if (themeColor.colorInt == selectedColor) {
            CustomToast.show(applicationContext, "当前已是${themeColor.colorName}主题色")
            return
        }

        selectedColor = themeColor.colorInt
        configRepository.setThemeColor(selectedColor)

        updateColorSelection()
        updateCurrentColorPreview()

        // 状态栏 / 导航栏立刻换色
        ThemeUtils.applyThemeColor(this, selectedColor)

        CustomToast.show(applicationContext, "主题色已更换为${themeColor.colorName}")

        // applyThemeColor 只改窗口装饰色，不会重绘本页与宿主页里由 ThemeHelper
        // 运行时上色的控件；这些控件在创建时读一次配置，必须重建才会拿到新主色。
        // 重建前的选择已写入 configRepository 与 onSaveInstanceState，不会闪回。
        binding.root.post {
            if (!isFinishing && !isDestroyed) recreate()
        }
    }

    private fun updateCurrentColorPreview() {
        val currentTheme = ThemeUtils.ThemeColor.fromColorInt(selectedColor)

        binding.currentColorPreview.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(selectedColor)
            setStroke(
                dp(2),
                ContextCompat.getColor(this@ThemeActivity, R.color.surface_card)
            )
        }
        binding.currentColorName.text = currentTheme.colorName
    }

    // ---------------------------------------------------------------- 夜间模式

    private fun setupNightModeOptions() {
        updateNightModeChecks()

        // 跟随系统
        binding.nightModeFollowSystem.setOnClickListener {
            selectNightMode(0)
        }

        // 浅色模式
        binding.nightModeOff.setOnClickListener {
            selectNightMode(1)
        }

        // 深色模式
        binding.nightModeOn.setOnClickListener {
            selectNightMode(2)
        }
    }

    private fun selectNightMode(mode: Int) {
        if (mode == selectedNightMode) {
            CustomToast.show(applicationContext, "当前已是${nightModeName(mode)}")
            return
        }

        selectedNightMode = mode
        configRepository.setNightMode(mode)
        updateNightModeChecks()

        // AppCompatDelegate.setDefaultNightMode 在 uiMode 真正变化时会自行重建已启动的
        // Activity，这里再调 recreate() 会重建两次并闪屏，所以只依赖它的重建。
        ThemeUtils.applyNightMode(mode)

        CustomToast.show(applicationContext, "已切换到${nightModeName(mode)}")
    }

    private fun nightModeName(mode: Int): String = when (mode) {
        0 -> "跟随系统"
        1 -> "浅色模式"
        2 -> "深色模式"
        else -> ""
    }

    private fun updateNightModeChecks() {
        // 选中态：卡片 2dp primary 描边 + 右侧对勾（不铺 primaryContainer 底色）
        applyModeState(binding.nightModeFollowSystem, binding.checkFollowSystem, selectedNightMode == 0)
        applyModeState(binding.nightModeOff, binding.checkLightMode, selectedNightMode == 1)
        applyModeState(binding.nightModeOn, binding.checkDarkMode, selectedNightMode == 2)
    }

    private fun applyModeState(option: View, check: View, selected: Boolean) {
        option.setBackgroundResource(
            if (selected) R.drawable.bg_mode_card_selected else R.drawable.bg_mode_card
        )
        option.isFocusable = true
        option.isClickable = true
        option.isSelected = selected
        check.visibility = if (selected) View.VISIBLE else View.INVISIBLE
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    companion object {
        private const val KEY_SELECTED_COLOR = "theme_activity_selected_color"
        private const val KEY_SELECTED_NIGHT_MODE = "theme_activity_selected_night_mode"
        private const val COLUMN_COUNT = 4
        private const val SWATCH_SIZE_DP = 44
        private const val CHECK_SIZE_DP = 18
    }
}