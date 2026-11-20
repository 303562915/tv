package com.tv.ball.presentation.settings

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tv.ball.R
import com.tv.ball.databinding.ActivityThemeBinding
import com.tv.ball.di.Dependencies
import com.tv.ball.utils.ThemeUtils

class ThemeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThemeBinding
    private val configRepository = Dependencies.configRepository
    private var selectedColor: Int = 0
    private var selectedNightMode: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThemeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 获取当前主题色和夜间模式
        selectedColor = configRepository.getThemeColor()
        selectedNightMode = configRepository.getNightMode()

        setupThemeOptions()
        setupNightModeOptions()
        updateCurrentColorPreview()
    }

    private fun setupThemeOptions() {
        val colors = ThemeUtils.ThemeColor.values()

        // 动态创建主题色选项
        colors.forEach { themeColor ->
            val colorItem = createColorItem(themeColor)
            binding.themeColorGrid.addView(colorItem)
        }
    }

    private fun createColorItem(themeColor: ThemeUtils.ThemeColor): FrameLayout {
        val size = (resources.displayMetrics.density * 72).toInt()
        val padding = (resources.displayMetrics.density * 8).toInt()

        val container = FrameLayout(this).apply {
            layoutParams = ViewGroup.MarginLayoutParams(size, size).apply {
                setMargins(padding, padding, padding, padding)
            }
            setPadding(padding, padding, padding, padding)
        }

        // 颜色圆形
        val colorCircle = android.view.View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColor.colorInt)
            }
        }

        // 选中标记
        val checkIcon = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                (resources.displayMetrics.density * 24).toInt(),
                (resources.displayMetrics.density * 24).toInt(),
                Gravity.CENTER
            )
            setImageResource(android.R.drawable.ic_menu_info_details)
            setColorFilter(Color.WHITE)
            visibility = if (themeColor.colorInt == selectedColor) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        }

        container.addView(colorCircle)
        container.addView(checkIcon)

        // 点击事件
        container.setOnClickListener {
            selectThemeColor(themeColor, checkIcon)
        }

        return container
    }

    private fun selectThemeColor(themeColor: ThemeUtils.ThemeColor, checkIcon: ImageView) {
        // 隐藏所有选中标记
        for (i in 0 until binding.themeColorGrid.childCount) {
            val child = binding.themeColorGrid.getChildAt(i) as? FrameLayout
            val icon = child?.getChildAt(1) as? ImageView
            icon?.visibility = android.view.View.GONE
        }

        // 显示当前选中标记
        checkIcon.visibility = android.view.View.VISIBLE

        // 保存选择
        selectedColor = themeColor.colorInt
        configRepository.setThemeColor(selectedColor)

        // 更新预览
        updateCurrentColorPreview()

        // 应用主题色
        ThemeUtils.applyThemeColor(this, selectedColor)

        Toast.makeText(this, "主题色已更换为${themeColor.colorName}", Toast.LENGTH_SHORT).show()
    }

    private fun updateCurrentColorPreview() {
        val currentTheme = ThemeUtils.ThemeColor.fromColorInt(selectedColor)

        // 更新预览颜色
        val drawable = binding.currentColorPreview.background as? GradientDrawable
        if (drawable == null) {
            binding.currentColorPreview.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(selectedColor)
            }
        } else {
            drawable.setColor(selectedColor)
        }

        // 更新颜色名称
        binding.currentColorName.text = currentTheme.colorName
    }

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
        selectedNightMode = mode
        configRepository.setNightMode(mode)
        updateNightModeChecks()

        // 应用夜间模式
        ThemeUtils.applyNightMode(mode)

        val modeText = when (mode) {
            0 -> "跟随系统"
            1 -> "浅色模式"
            2 -> "深色模式"
            else -> ""
        }
        Toast.makeText(this, "已切换到$modeText", Toast.LENGTH_SHORT).show()
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
        check.visibility = if (selected) View.VISIBLE else View.INVISIBLE
    }
}
