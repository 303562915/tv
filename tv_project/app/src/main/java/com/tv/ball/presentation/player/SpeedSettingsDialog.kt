package com.tv.ball.presentation.player

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import com.tv.ball.R
import com.tv.ball.databinding.DialogSpeedSettingsBinding

class SpeedSettingsDialog(
    context: Context,
    private val currentSpeed: Float,
    private val currentSensitivity: Float,
    private val onSpeedSelected: (Float) -> Unit,
    private val onSensitivitySelected: (Float) -> Unit
) : Dialog(context, R.style.TransparentDialog) {  // 使用透明主题

    private lateinit var binding: DialogSpeedSettingsBinding
    private var selectedSpeed = currentSpeed
    private var selectedSensitivity = currentSensitivity
    private val speedButtons = mutableListOf<TextView>()
    private val sensitivityButtons = mutableListOf<TextView>()
    private val longPressSpeedButtons = mutableListOf<TextView>()
    private var isBasicSpeedTab = true
    
    // SharedPreferences 用于保存设置
    private val prefs = context.getSharedPreferences("player_settings", Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogSpeedSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWindow()
        setupViews()
    }

    private fun setupWindow() {
        window?.apply {
            // 设置宽度为侧栏宽度，高度为全屏
            setLayout(
                280.dpToPx(context),
                WindowManager.LayoutParams.MATCH_PARENT
            )
            
            // 清除所有标志
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            
            // 添加全屏标志，确保覆盖状态栏
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            // 移除 FLAG_NOT_TOUCH_MODAL，让点击外部区域可以关闭对话框
            
            // 设置重力为右侧顶部
            setGravity(Gravity.END or Gravity.TOP)
            
            // 设置动画
            setWindowAnimations(R.style.DialogSlideAnimation)
            
            // 移除所有padding
            decorView.setPadding(0, 0, 0, 0)
            decorView.setBackgroundColor(0x00000000)  // 透明背景
            
            // 设置系统UI可见性，确保全屏布局
            decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
        
        // 设置点击外部区域关闭对话框
        setCanceledOnTouchOutside(true)
    }

    private fun Int.dpToPx(context: Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
    
    private fun getStatusBarHeight(): Int {
        var result = 0
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = context.resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    private fun setupViews() {
        // 不添加状态栏高度的padding，让对话框从顶部开始
        // val statusBarHeight = getStatusBarHeight()
        // binding.sidebarContainer.setPadding(0, statusBarHeight, 0, 0)
        
        // 收集所有倍速按钮
        speedButtons.addAll(
            listOf(
                binding.speed05Button,
                binding.speed10Button,
                binding.speed125Button,
                binding.speed15Button,
                binding.speed20Button,
                binding.speed30Button
            )
        )

        // 收集所有灵敏度按钮
        sensitivityButtons.addAll(
            listOf(
                binding.sensitivity115Button,
                binding.sensitivity125Button,
                binding.sensitivity135Button,
                binding.sensitivity145Button,
                binding.sensitivity155Button,
                binding.sensitivity165Button
            )
        )

        // 收集所有长按速度按钮
        longPressSpeedButtons.addAll(
            listOf(
                binding.longPressSpeed05Button,
                binding.longPressSpeed10Button,
                binding.longPressSpeed125Button,
                binding.longPressSpeed15Button,
                binding.longPressSpeed20Button,
                binding.longPressSpeed30Button
            )
        )

        // 设置倍速按钮点击事件
        binding.speed05Button.setOnClickListener { selectSpeed(0.5f) }
        binding.speed10Button.setOnClickListener { selectSpeed(1.0f) }
        binding.speed125Button.setOnClickListener { selectSpeed(1.25f) }
        binding.speed15Button.setOnClickListener { selectSpeed(1.5f) }
        binding.speed20Button.setOnClickListener { selectSpeed(2.0f) }
        binding.speed30Button.setOnClickListener { selectSpeed(3.0f) }

        // 设置灵敏度按钮点击事件
        binding.sensitivity115Button.setOnClickListener { selectSensitivity(1.15f) }
        binding.sensitivity125Button.setOnClickListener { selectSensitivity(1.25f) }
        binding.sensitivity135Button.setOnClickListener { selectSensitivity(1.35f) }
        binding.sensitivity145Button.setOnClickListener { selectSensitivity(1.45f) }
        binding.sensitivity155Button.setOnClickListener { selectSensitivity(1.55f) }
        binding.sensitivity165Button.setOnClickListener { selectSensitivity(1.65f) }

        // 设置长按速度按钮点击事件
        binding.longPressSpeed05Button.setOnClickListener { selectLongPressSpeed(0.5f) }
        binding.longPressSpeed10Button.setOnClickListener { selectLongPressSpeed(1.0f) }
        binding.longPressSpeed125Button.setOnClickListener { selectLongPressSpeed(1.25f) }
        binding.longPressSpeed15Button.setOnClickListener { selectLongPressSpeed(1.5f) }
        binding.longPressSpeed20Button.setOnClickListener { selectLongPressSpeed(2.0f) }
        binding.longPressSpeed30Button.setOnClickListener { selectLongPressSpeed(3.0f) }

        // 关闭按钮
        binding.closeButton.setOnClickListener { dismiss() }
        
        // 点击根布局（左侧空白区域）关闭对话框
        binding.rootLayout.setOnClickListener { dismiss() }
        
        // 阻止侧栏内容的点击事件传播到根布局
        binding.sidebarContainer.setOnClickListener { 
            // 不做任何事，只是阻止事件传播
        }
        
        // 确保点击外部区域可以关闭
        setCanceledOnTouchOutside(true)

        // 标签页切换
        binding.basicSpeedTab.setOnClickListener {
            switchToBasicSpeed()
        }

        binding.smartSpeedTab.setOnClickListener {
            switchToSensitivity()
        }

        // 长按上滑锁定倍速开关
        val isLongPressLockEnabled = prefs.getBoolean("long_press_lock_enabled", true)
        binding.longPressLockSwitch.isChecked = isLongPressLockEnabled
        binding.longPressLockSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("long_press_lock_enabled", isChecked).apply()
        }

        // 初始化标签页状态
        switchToBasicSpeed()
        
        // 初始化选中状态
        updateSelectedButton()
        updateLongPressSpeedButtons()
    }

    private fun switchToBasicSpeed() {
        if (!isBasicSpeedTab) {
            isBasicSpeedTab = true
            binding.basicSpeedTab.setBackgroundResource(R.drawable.tab_selected_bg)
            binding.basicSpeedTab.setTextColor(0xFF1A1A1A.toInt())
            binding.smartSpeedTab.setBackgroundResource(R.drawable.tab_unselected_bg)
            binding.smartSpeedTab.setTextColor(0xFF888888.toInt())
            
            binding.basicSpeedContent.visibility = View.VISIBLE
            binding.smartSpeedContent.visibility = View.GONE
        }
    }

    private fun switchToSensitivity() {
        if (isBasicSpeedTab) {
            isBasicSpeedTab = false
            binding.basicSpeedTab.setBackgroundResource(R.drawable.tab_unselected_bg)
            binding.basicSpeedTab.setTextColor(0xFF888888.toInt())
            binding.smartSpeedTab.setBackgroundResource(R.drawable.tab_selected_bg)
            binding.smartSpeedTab.setTextColor(0xFF1A1A1A.toInt())
            
            binding.basicSpeedContent.visibility = View.GONE
            binding.smartSpeedContent.visibility = View.VISIBLE
            
            updateSensitivityButtons()
        }
    }

    private fun selectSpeed(speed: Float) {
        selectedSpeed = speed
        updateSelectedButton()
        onSpeedSelected(speed)
    }

    private fun selectSensitivity(sensitivity: Float) {
        selectedSensitivity = sensitivity
        updateSensitivityButtons()
        onSensitivitySelected(sensitivity)
    }

    private fun updateSelectedButton() {
        speedButtons.forEach { button ->
            val speed = when (button.id) {
                R.id.speed05Button -> 0.5f
                R.id.speed10Button -> 1.0f
                R.id.speed125Button -> 1.25f
                R.id.speed15Button -> 1.5f
                R.id.speed20Button -> 2.0f
                R.id.speed30Button -> 3.0f
                else -> 1.0f
            }

            if (speed == selectedSpeed) {
                button.setBackgroundResource(R.drawable.speed_button_selected_bg)
                button.isSelected = true
            } else {
                button.setBackgroundResource(R.drawable.speed_button_bg)
                button.isSelected = false
            }
        }
    }

    private fun updateSensitivityButtons() {
        sensitivityButtons.forEach { button ->
            val sensitivity = when (button.id) {
                R.id.sensitivity115Button -> 1.15f
                R.id.sensitivity125Button -> 1.25f
                R.id.sensitivity135Button -> 1.35f
                R.id.sensitivity145Button -> 1.45f
                R.id.sensitivity155Button -> 1.55f
                R.id.sensitivity165Button -> 1.65f
                else -> 1.25f
            }

            if (sensitivity == selectedSensitivity) {
                button.setBackgroundResource(R.drawable.speed_button_selected_bg)
                button.isSelected = true
            } else {
                button.setBackgroundResource(R.drawable.speed_button_bg)
                button.isSelected = false
            }
        }
    }
    
    /**
     * 选择长按速度
     */
    private fun selectLongPressSpeed(speed: Float) {
        prefs.edit().putFloat("long_press_speed", speed).apply()
        updateLongPressSpeedButtons()
    }
    
    /**
     * 更新长按速度按钮选中状态
     */
    private fun updateLongPressSpeedButtons() {
        val longPressSpeed = prefs.getFloat("long_press_speed", 3.0f)
        
        longPressSpeedButtons.forEach { button ->
            val speed = when (button.id) {
                R.id.longPressSpeed05Button -> 0.5f
                R.id.longPressSpeed10Button -> 1.0f
                R.id.longPressSpeed125Button -> 1.25f
                R.id.longPressSpeed15Button -> 1.5f
                R.id.longPressSpeed20Button -> 2.0f
                R.id.longPressSpeed30Button -> 3.0f
                else -> 3.0f
            }

            if (speed == longPressSpeed) {
                button.setBackgroundResource(R.drawable.speed_button_selected_bg)
                button.isSelected = true
            } else {
                button.setBackgroundResource(R.drawable.speed_button_bg)
                button.isSelected = false
            }
        }
    }
}
