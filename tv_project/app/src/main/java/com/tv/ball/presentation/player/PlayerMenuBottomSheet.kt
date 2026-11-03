package com.tv.ball.presentation.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tv.ball.databinding.BottomSheetPlayerMenuBinding

/**
 * 播放器侧滑菜单
 */
class PlayerMenuBottomSheet : BottomSheetDialogFragment() {
    
    private var _binding: BottomSheetPlayerMenuBinding? = null
    private val binding get() = _binding!!
    
    private var onAspectRatioSelected: ((AspectRatioManager.AspectRatioMode) -> Unit)? = null
    private var onSpeedChanged: ((Float) -> Unit)? = null
    private var onCastClicked: (() -> Unit)? = null
    private var onDownloadClicked: (() -> Unit)? = null
    private var onSkipIntroToggled: ((Boolean) -> Unit)? = null
    private var onSkipOutroToggled: ((Boolean) -> Unit)? = null
    private var onRepeatModeChanged: ((Int) -> Unit)? = null
    
    private var currentSpeed = 1.0f
    private var skipIntroEnabled = true
    private var skipOutroEnabled = false
    private var currentRepeatMode = com.google.android.exoplayer2.Player.REPEAT_MODE_OFF
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetPlayerMenuBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
    }
    
    private fun setupViews() {
        // 画面比例选择
        binding.aspectRatioGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                binding.aspectRatioFit.id -> AspectRatioManager.AspectRatioMode.FIT
                binding.aspectRatio169.id -> AspectRatioManager.AspectRatioMode.FIXED_WIDTH
                binding.aspectRatio43.id -> AspectRatioManager.AspectRatioMode.FIXED_HEIGHT
                binding.aspectRatioFill.id -> AspectRatioManager.AspectRatioMode.FILL
                binding.aspectRatioZoom.id -> AspectRatioManager.AspectRatioMode.ZOOM
                else -> AspectRatioManager.AspectRatioMode.FIT
            }
            onAspectRatioSelected?.invoke(mode)
        }
        
        // 倍速滑动条
        binding.speedSeekBar.max = 30 // 0.5x - 2.0x，步进0.05
        binding.speedSeekBar.progress = ((currentSpeed - 0.5f) / 0.05f).toInt()
        binding.speedValueText.text = String.format("%.2fx", currentSpeed)
        
        binding.speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = 0.5f + progress * 0.05f
                binding.speedValueText.text = String.format("%.2fx", speed)
                if (fromUser) {
                    currentSpeed = speed
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                onSpeedChanged?.invoke(currentSpeed)
            }
        })
        
        // 快速倍速按钮
        binding.speed05Button.setOnClickListener { setSpeed(0.5f) }
        binding.speed075Button.setOnClickListener { setSpeed(0.75f) }
        binding.speed10Button.setOnClickListener { setSpeed(1.0f) }
        binding.speed125Button.setOnClickListener { setSpeed(1.25f) }
        binding.speed15Button.setOnClickListener { setSpeed(1.5f) }
        binding.speed20Button.setOnClickListener { setSpeed(2.0f) }
        
        // 投屏按钮
        binding.castButton.setOnClickListener {
            onCastClicked?.invoke()
            dismiss()
        }
        
        // 下载按钮
        binding.downloadButton.setOnClickListener {
            onDownloadClicked?.invoke()
            dismiss()
        }
        
        // 片头跳过开关
        binding.skipIntroSwitch.isChecked = skipIntroEnabled
        binding.skipIntroSwitch.setOnCheckedChangeListener { _, isChecked ->
            skipIntroEnabled = isChecked
            onSkipIntroToggled?.invoke(isChecked)
        }
        
        // 片尾跳过开关
        binding.skipOutroSwitch.isChecked = skipOutroEnabled
        binding.skipOutroSwitch.setOnCheckedChangeListener { _, isChecked ->
            skipOutroEnabled = isChecked
            onSkipOutroToggled?.invoke(isChecked)
        }
        
        // 重复播放模式
        binding.repeatModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                binding.repeatModeOff.id -> com.google.android.exoplayer2.Player.REPEAT_MODE_OFF
                binding.repeatModeOne.id -> com.google.android.exoplayer2.Player.REPEAT_MODE_ONE
                binding.repeatModeAll.id -> com.google.android.exoplayer2.Player.REPEAT_MODE_ALL
                else -> com.google.android.exoplayer2.Player.REPEAT_MODE_OFF
            }
            onRepeatModeChanged?.invoke(mode)
        }
    }
    
    private fun setSpeed(speed: Float) {
        currentSpeed = speed
        binding.speedSeekBar.progress = ((speed - 0.5f) / 0.05f).toInt()
        binding.speedValueText.text = String.format("%.2fx", speed)
        onSpeedChanged?.invoke(speed)
    }
    
    fun setCurrentSpeed(speed: Float) {
        this.currentSpeed = speed
        if (_binding != null) {
            binding.speedSeekBar.progress = ((speed - 0.5f) / 0.05f).toInt()
            binding.speedValueText.text = String.format("%.2fx", speed)
        }
    }
    
    fun setSkipIntroEnabled(enabled: Boolean) {
        this.skipIntroEnabled = enabled
        if (_binding != null) {
            binding.skipIntroSwitch.isChecked = enabled
        }
    }
    
    fun setSkipOutroEnabled(enabled: Boolean) {
        this.skipOutroEnabled = enabled
        if (_binding != null) {
            binding.skipOutroSwitch.isChecked = enabled
        }
    }
    
    fun setCurrentAspectRatio(mode: AspectRatioManager.AspectRatioMode) {
        if (_binding != null) {
            val checkedId = when (mode) {
                AspectRatioManager.AspectRatioMode.FIT -> binding.aspectRatioFit.id
                AspectRatioManager.AspectRatioMode.FIXED_WIDTH -> binding.aspectRatio169.id
                AspectRatioManager.AspectRatioMode.FIXED_HEIGHT -> binding.aspectRatio43.id
                AspectRatioManager.AspectRatioMode.FILL -> binding.aspectRatioFill.id
                AspectRatioManager.AspectRatioMode.ZOOM -> binding.aspectRatioZoom.id
            }
            binding.aspectRatioGroup.check(checkedId)
        }
    }
    
    fun setOnAspectRatioSelectedListener(listener: (AspectRatioManager.AspectRatioMode) -> Unit) {
        this.onAspectRatioSelected = listener
    }
    
    fun setOnSpeedChangedListener(listener: (Float) -> Unit) {
        this.onSpeedChanged = listener
    }
    
    fun setOnCastClickedListener(listener: () -> Unit) {
        this.onCastClicked = listener
    }
    
    fun setOnDownloadClickedListener(listener: () -> Unit) {
        this.onDownloadClicked = listener
    }
    
    fun setOnSkipIntroToggledListener(listener: (Boolean) -> Unit) {
        this.onSkipIntroToggled = listener
    }
    
    fun setOnSkipOutroToggledListener(listener: (Boolean) -> Unit) {
        this.onSkipOutroToggled = listener
    }
    
    fun setCurrentRepeatMode(mode: Int) {
        this.currentRepeatMode = mode
        if (_binding != null) {
            val checkedId = when (mode) {
                com.google.android.exoplayer2.Player.REPEAT_MODE_OFF -> binding.repeatModeOff.id
                com.google.android.exoplayer2.Player.REPEAT_MODE_ONE -> binding.repeatModeOne.id
                com.google.android.exoplayer2.Player.REPEAT_MODE_ALL -> binding.repeatModeAll.id
                else -> binding.repeatModeOff.id
            }
            binding.repeatModeGroup.check(checkedId)
        }
    }
    
    fun setOnRepeatModeChangedListener(listener: (Int) -> Unit) {
        this.onRepeatModeChanged = listener
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    
    companion object {
        const val TAG = "PlayerMenuBottomSheet"
        
        fun newInstance(): PlayerMenuBottomSheet {
            return PlayerMenuBottomSheet()
        }
    }
}
