package com.tv.ball.presentation.player

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
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
    private var onPipClicked: (() -> Unit)? = null
    private var pipEnabled = false
    
    private var currentSpeed = 1.0f
    private var skipIntroEnabled = true
    private var skipOutroEnabled = false
    private var currentRepeatMode = com.google.android.exoplayer2.Player.REPEAT_MODE_OFF

    // 睡眠定时器的倒计时活在播放页里，菜单只拿状态、回传档位，避免菜单一关计时就断
    private var sleepTimerStateProvider: (() -> SleepTimerState)? = null
    private var onSleepTimerSelected: ((Int) -> Unit)? = null
    private var sleepTimerDialog: AlertDialog? = null
    private var subtitleDialog: AlertDialog? = null
    private val sleepTimerRefreshHandler = Handler(Looper.getMainLooper())
    private val sleepTimerRefreshRunnable = object : Runnable {
        override fun run() {
            refreshSleepTimerState()
            sleepTimerRefreshHandler.postDelayed(this, SLEEP_TIMER_REFRESH_INTERVAL_MS)
        }
    }

    /**
     * 睡眠定时器的显示状态：[optionIndex] 取 [SLEEP_TIMER_OPTIONS] 里的值给弹窗勾选用
     * （关闭 = 0，正数 = 分钟，[SLEEP_TIMER_END_OF_EPISODE] = 播完当前集），
     * [statusText] 直接贴到行尾。
     */
    data class SleepTimerState(val optionIndex: Int, val statusText: String)

    // 字幕状态只由播放页给：字幕文件属于当前片源，菜单不缓存也不轮询，
    // 打开菜单时取一次就够了（字幕不会自己变）
    private var subtitleStateProvider: (() -> SubtitleUiState)? = null
    private var onSubtitleAction: ((Int) -> Unit)? = null

    /**
     * 字幕行的显示状态：[statusText] 贴到行尾（未加载 / 文件名（cue 数）· 延迟），
     * [loaded] 只用来决定弹窗标题要不要标「未加载」。
     */
    data class SubtitleUiState(val statusText: String, val loaded: Boolean)
    
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

        // 睡眠定时器
        binding.rowSleepTimer.setOnClickListener {
            showSleepTimerDialog()
        }
        refreshSleepTimerState()
        // 30 秒刷一次：剩余时间只要分钟粒度，每秒刷纯属白烧电
        sleepTimerRefreshHandler.removeCallbacks(sleepTimerRefreshRunnable)
        sleepTimerRefreshHandler.postDelayed(sleepTimerRefreshRunnable, SLEEP_TIMER_REFRESH_INTERVAL_MS)

        // 画中画：设备不支持时整行置灰（由调用方按 FEATURE_PICTURE_IN_PICTURE 判）
        binding.rowPip.setOnClickListener {
            onPipClicked?.invoke()
            dismiss()
        }
        refreshPipState()

        // 字幕：菜单打开时刷一次行尾文字；延迟改了由本页回调直接刷新，不轮询
        binding.rowSubtitle.setOnClickListener {
            showSubtitleDialog()
        }
        refreshSubtitleState()
    }

    private fun refreshPipState() {
        val binding = _binding ?: return
        binding.rowPip.isEnabled = pipEnabled
        // 置灰靠透明度和文字一起表达，光靠 isEnabled 在深色底上看不出来
        binding.rowPip.alpha = if (pipEnabled) 1f else 0.4f
        binding.tvPipValue.text = if (pipEnabled) "进入" else "不支持"
    }
    
    private fun refreshSleepTimerState() {
        val binding = _binding ?: return
        val state = sleepTimerStateProvider?.invoke() ?: SleepTimerState(0, "关闭")
        binding.tvSleepTimerValue.text = state.statusText
    }

    private fun showSleepTimerDialog() {
        val ctx = context ?: return
        val currentOption = sleepTimerStateProvider?.invoke()?.optionIndex ?: 0
        sleepTimerDialog?.dismiss()
        // 弹窗用 activity 的 context 建，才能盖在底部菜单的窗口之上，且菜单不跟着关
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("睡眠定时器")
            .setSingleChoiceItems(SLEEP_TIMER_LABELS, sleepTimerIndexOf(currentOption)) { dialog, which ->
                dialog.dismiss()
                onSleepTimerSelected?.invoke(SLEEP_TIMER_OPTIONS[which])
                refreshSleepTimerState()
            }
            .setNegativeButton("取消", null)
            .create()
        sleepTimerDialog = dialog
        dialog.show()
    }

    private fun sleepTimerIndexOf(option: Int): Int {
        val index = SLEEP_TIMER_OPTIONS.indexOf(option)
        return if (index >= 0) index else 0
    }

    private fun refreshSubtitleState() {
        val binding = _binding ?: return
        val state = subtitleStateProvider?.invoke() ?: SubtitleUiState("未加载", false)
        binding.tvSubtitleValue.text = state.statusText
    }

    private fun showSubtitleDialog() {
        val ctx = context ?: return
        val state = subtitleStateProvider?.invoke() ?: SubtitleUiState("未加载", false)
        subtitleDialog?.dismiss()
        // 弹窗用 activity 的 context 建，才能盖在底部菜单的窗口之上，且菜单不跟着关
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (state.loaded) "字幕" else "字幕（未加载）")
            // 不预选任何一项：这里的每一项都是动作，不是当前状态
            .setSingleChoiceItems(SUBTITLE_ACTION_LABELS, -1) { dialog, which ->
                dialog.dismiss()
                onSubtitleAction?.invoke(SUBTITLE_ACTIONS[which])
                refreshSubtitleState()
            }
            .setNegativeButton("取消", null)
            .create()
        subtitleDialog = dialog
        dialog.show()
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

    /** 睡眠定时器的状态由播放页提供：菜单不自己算剩余时间，否则菜单一关就断了。 */
    fun setSleepTimerStateProvider(provider: () -> SleepTimerState) {
        this.sleepTimerStateProvider = provider
        refreshSleepTimerState()
    }

    fun setOnSleepTimerSelectedListener(listener: (Int) -> Unit) {
        this.onSleepTimerSelected = listener
    }

    /** 设备不支持画中画时置灰整行；调用方用 FEATURE_PICTURE_IN_PICTURE 判完再调。 */
    fun setPipEnabled(enabled: Boolean) {
        this.pipEnabled = enabled
        refreshPipState()
    }

    fun setOnPipClickedListener(listener: () -> Unit) {
        this.onPipClicked = listener
    }

    /** 字幕行的状态由播放页提供：字幕文件活在播放页，菜单只负责显示与回传动作。 */
    fun setSubtitleStateProvider(provider: () -> SubtitleUiState) {
        this.subtitleStateProvider = provider
        refreshSubtitleState()
    }

    /** [action] 取 ACTION_* 常量：菜单只回传「用户点了哪一项」，具体怎么做由播放页决定。 */
    fun setOnSubtitleActionListener(listener: (Int) -> Unit) {
        this.onSubtitleAction = listener
    }

    override fun onDestroyView() {
        super.onDestroyView()
        sleepTimerRefreshHandler.removeCallbacks(sleepTimerRefreshRunnable)
        sleepTimerDialog?.dismiss()
        sleepTimerDialog = null
        subtitleDialog?.dismiss()
        subtitleDialog = null
        _binding = null
    }

    companion object {
        const val TAG = "PlayerMenuBottomSheet"

        /** 字幕动作：选文件（要播放页去 launch SAF）/ 关闭 / 延迟三档。 */
        const val ACTION_PICK_SUBTITLE_FILE = 0
        const val ACTION_DISABLE_SUBTITLE = 1
        const val ACTION_DELAY_MINUS_HALF_SECOND = 2
        const val ACTION_DELAY_PLUS_HALF_SECOND = 3
        const val ACTION_DELAY_RESET = 4

        // 关闭 / 15 / 30 / 60 / 播完当前集；数组下标就是单选项和勾选状态的下标，两者必须同序。
        // 「播完当前集」没有分钟数，用哨兵值 [SLEEP_TIMER_END_OF_EPISODE] 表示。
        const val SLEEP_TIMER_END_OF_EPISODE = -1
        private val SLEEP_TIMER_OPTIONS = intArrayOf(0, 15, 30, 60, SLEEP_TIMER_END_OF_EPISODE)
        private val SLEEP_TIMER_LABELS =
            arrayOf<CharSequence>("关闭", "15 分钟", "30 分钟", "60 分钟", "播完当前集")
        private const val SLEEP_TIMER_REFRESH_INTERVAL_MS = 30_000L

        // 下标与 [SUBTITLE_ACTIONS] 一一对应：单选项的 which 直接当下标用
        private val SUBTITLE_ACTIONS = intArrayOf(
            ACTION_PICK_SUBTITLE_FILE,
            ACTION_DISABLE_SUBTITLE,
            ACTION_DELAY_MINUS_HALF_SECOND,
            ACTION_DELAY_PLUS_HALF_SECOND,
            ACTION_DELAY_RESET
        )
        private val SUBTITLE_ACTION_LABELS = arrayOf<CharSequence>(
            "选择字幕文件…",
            "关闭字幕",
            "延迟 −0.5 秒",
            "延迟 +0.5 秒",
            "延迟归零"
        )

        fun newInstance(): PlayerMenuBottomSheet {
            return PlayerMenuBottomSheet()
        }
    }
}
