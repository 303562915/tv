package com.tv.ball.presentation.settings

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.tv.ball.R
import com.tv.ball.data.local.UserPreferences
import com.tv.ball.databinding.FragmentSettingsBinding
import com.tv.ball.feature.f057.F057Engine
import com.tv.ball.feature.f057.Preset
import com.tv.ball.feature.f081.F081Engine
import com.tv.ball.feature.f081.PlaybackDefaults
import com.tv.ball.presentation.localvideo.LocalVideoMedia
import com.tv.ball.presentation.player.AspectRatioManager
import com.tv.ball.presentation.player.SkipIntroManager
import com.tv.ball.util.CustomToast

/**
 * 播放设置页（「我的」→ 播放设置）。
 *
 * 这一页只放**播放器真的会读**的开关和档位。每个条目在下面都写清了三件事：
 * 1. 存到哪儿（[UserPreferences] 的键 / [F057Engine] / [F081Engine] / [SkipIntroManager] / `player_settings`）；
 * 2. 播放侧在哪儿读（写在每个条目的注释里）；
 * 3. 默认值是什么。
 *
 * 这一页是**入口**：开关写进持久化键，真正读这些键的代码在播放页
 * （VideoPlayerActivity / ExoPlayerManager / SkipIntroManager）。本页不重复实现播放行为，
 * 也不留「写了没人读」的假开关 —— 拨动无效果的那种键早就删掉了。
 *
 * 没有空壳占位：写进去的值要么已经被播放器消费，要么是同批改动里补上的读取点。
 * 「长按倍速」没有放上来 —— 播放页的 getLongPressSpeed() 优先读 F004 倍速曲线，
 * `player_settings` 里的 long_press_speed 是死键，摆个改不动的开关等于骗人。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    /** 恢复默认这件事交给 ViewModel，界面重建时也不用把六处落点再写一遍。 */
    private val viewModel: SettingsViewModel by lazy {
        ViewModelProvider(this)[SettingsViewModel::class.java]
    }

    /** 播放设置的数据源：与播放侧同一个 SharedPreferences 文件（`user_prefs`）。 */
    private val prefs: UserPreferences by lazy { com.tv.ball.di.Dependencies.userPreferences }

    /** 片头 / 片尾 / 广告跳过的持久化与判定。 */
    private val skipIntroManager by lazy { SkipIntroManager(requireContext().applicationContext) }

    /** 画面比例（播放页起播时读的就是这一份）。 */
    private val aspectRatioManager by lazy { AspectRatioManager(requireContext().applicationContext) }

    /** 正在从持久化值回填开关：这段时间内的 isChecked 变化不算用户操作。 */
    private var syncingSwitches = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        setupSwitchRows()
        setupChoiceRows()
        setupResetRow()
        setupCacheRow()
        // 搜索索引要在刷显示值之前建：关键字取的是布局里的文案，
        // 先建再刷，避免把「清理缓存（12.4 MB）」这种随体积变的数字写进关键字
        setupSearch()
        refreshAll()
        refreshCacheUsage()
    }

    override fun onResume() {
        super.onResume()
        // 播放页里改过片头/片尾开关、画面比例时，回到本页要显示最新值
        refreshAll()
        // 播放页一边看一边写 ExoPlayer 缓存，回本页体积已经变了
        refreshCacheUsage()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 页面已经销毁就别再把延迟的「已保存」弹出来
        savedHandler.removeCallbacks(savedHintRunnable)
        // 视图已经没了，索引里存的都是这一份布局的 View，一起丢掉
        searchIndex.clear()
        searchDividers.clear()
        searchCards.clear()
        searchSectionTitles.clear()
        _binding = null
    }

    // ============================================================
    // 开关类
    // ============================================================

    private fun setupSwitchRows() {
        // 自动连播下一集 → playback_auto_next（默认 true）
        // 读取点：ExoPlayerManager 收到 STATE_ENDED 时由 shouldDispatchEnded() 判这个键
        binding.switchPlaybackAutoNext.setOnCheckedChangeListener { _, checked ->
            if (syncGuard()) return@setOnCheckedChangeListener
            prefs.setPlaybackAutoNext(checked)
            notifySaved()
        }

        // 记忆播放进度 → playback_remember_progress（默认 true）
        // 读取点：VideoPlayerActivity.rememberPlaybackProgress（getter 每次读盘，恢复进度前判一次）
        // 行为在播放器内生效，本页只做入口
        binding.switchPlaybackRememberProgress.setOnCheckedChangeListener { _, checked ->
            if (syncGuard()) return@setOnCheckedChangeListener
            prefs.setPlaybackRememberProgress(checked)
            notifySaved()
        }

        // 长按锁定倍速 → player_settings / long_press_lock_enabled（默认 true）
        // 读取点：VideoPlayerActivity.isLongPressLockEnabled()，该键播放页本来就在读
        binding.switchPlaybackLongPressLock.setOnCheckedChangeListener { _, checked ->
            if (syncGuard()) return@setOnCheckedChangeListener
            playerPrefs().edit().putBoolean(KEY_LONG_PRESS_LOCK, checked).apply()
            notifySaved()
        }

        // 片头跳过按钮 → skip_intro / skip_intro_enabled（默认 true）
        // 读取点：checkSkipIntro() → SkipIntroManager.shouldSkipIntro()
        binding.switchSkipIntro.setOnCheckedChangeListener { _, checked ->
            if (syncGuard()) return@setOnCheckedChangeListener
            skipIntroManager.setSkipIntroEnabled(checked)
            notifySaved()
        }

        // 自动跳过片尾 → skip_intro / skip_outro_enabled（默认 false）
        // 读取点：checkSkipIntro() → SkipIntroManager.shouldSkipOutro()
        // （内部还要求「自动连播」开启且结束行为是「自动下一集」，口径和播放页一致）
        binding.switchSkipOutro.setOnCheckedChangeListener { _, checked ->
            if (syncGuard()) return@setOnCheckedChangeListener
            skipIntroManager.setSkipOutroEnabled(checked)
            notifySaved()
        }

        // 跳过片头广告 → playback_ad_skip_enabled（默认 false）
        // 读取点：VideoPlayerActivity.checkSkipIntro() → SkipIntroManager.shouldSkipAd()（开播后一次性快进）
        binding.switchAdSkip.setOnCheckedChangeListener { _, checked ->
            if (syncGuard()) return@setOnCheckedChangeListener
            prefs.setPlaybackAdSkipEnabled(checked)
            notifySaved()
        }

        // 预加载下一集 → playback_preload_next（默认 true）
        // 读取点：VideoPlayerActivity.checkAndPreloadNextEpisode() 入口先判该键，关掉就完全不预加载
        binding.switchPreloadNext.setOnCheckedChangeListener { _, checked ->
            if (syncGuard()) return@setOnCheckedChangeListener
            prefs.setPlaybackPreloadNext(checked)
            notifySaved()
        }

        // 失败自动切换线路 → playback_auto_switch_route（默认 true）
        // 读取点：VideoPlayerActivity 出错分支里 isPlaybackAutoSwitchRoute() && … 才自动换下一条线路
        binding.switchAutoRoute.setOnCheckedChangeListener { _, checked ->
            if (syncGuard()) return@setOnCheckedChangeListener
            prefs.setPlaybackAutoSwitchRoute(checked)
            notifySaved()
        }

        // 精确跳转 → playback_exact_seek（默认 true）
        // 读取点：ExoPlayerManager.initializePlayer() 里的 setSeekParameters
        binding.switchExactSeek.setOnCheckedChangeListener { _, checked ->
            if (syncGuard()) return@setOnCheckedChangeListener
            prefs.setPlaybackExactSeek(checked)
            notifySaved()
        }

        // 允许移动网络播放 → playback_allow_mobile_data（默认 true）
        // 读取点：VideoPlayerActivity.handleNetworkChange() 的 MOBILE 分支：关掉立刻暂停并提示
        binding.switchMobileData.setOnCheckedChangeListener { _, checked ->
            if (syncGuard()) return@setOnCheckedChangeListener
            prefs.setPlaybackAllowMobileData(checked)
            notifySaved()
        }

        // 排除 .ts 分片文件 → local_video_exclude_ts（默认 true）
        // 读取点：LocalVideoMedia.excludedExtensions()，本地视频列表 / 文件夹详情 / 下载目录扫描每次扫描前各读一次
        binding.switchLocalVideoExcludeTs.setOnCheckedChangeListener { _, checked ->
            if (syncGuard()) return@setOnCheckedChangeListener
            prefs.setLocalVideoExcludeTs(checked)
            // 关掉开关时「排除的后缀」整行要跟着置灰，所以刷一次本组显示值
            refreshLocalVideoRow()
            notifySaved()
        }
    }

    // ============================================================
    // 单选类
    // ============================================================

    private fun setupChoiceRows() {
        // 播放结束行为 → playback_ended_behavior（0 停止 / 1 循环当前集 / 2 自动下一集，默认 2）
        // 读取点：ExoPlayerManager —— 循环走 REPEAT_MODE_ONE，其余由 shouldDispatchEnded() 拦 onEnded
        binding.rowPlaybackEndedBehavior.setOnClickListener {
            val labels = arrayOf(
                getString(R.string.ps_ended_stop),
                getString(R.string.ps_ended_loop),
                getString(R.string.ps_ended_next)
            )
            showSingleChoice(
                getString(R.string.ps_ended_behavior),
                labels,
                prefs.getPlaybackEndedBehavior()
            ) { index ->
                prefs.setPlaybackEndedBehavior(index)
                refreshAll()
                notifySaved()
            }
        }

        // 默认倍速 → f081_playback_defaults / speed（默认 1.0x）
        // 读取点：ExoPlayerManager 建好播放器后 setPlaybackSpeed(F081Engine.load().speed)
        binding.rowPlaybackSpeed.setOnClickListener {
            val speeds = F081Engine.SPEED_OPTIONS
            val labels = speeds.map { F081Engine.speedLabel(it) }.toTypedArray()
            val current = F081Engine.load(requireContext()).speed
            showSingleChoice(
                getString(R.string.ps_default_speed),
                labels,
                speeds.indexOf(current).takeIf { it >= 0 } ?: speeds.indexOf(1.0f)
            ) { index ->
                savePlaybackDefaults { it.copy(speed = speeds[index]) }
                refreshAll()
                notifySaved()
            }
        }

        // 快进步长 → playback_seek_step_sec（默认 10 秒）
        // 读取点：ExoPlayerManager 的 setSeekBackIncrementMs / setSeekForwardIncrementMs
        binding.rowPlaybackSeekStep.setOnClickListener {
            val steps = F081Engine.SEEK_STEP_OPTIONS
            val labels = steps.map { F081Engine.seekStepLabel(it) }.toTypedArray()
            val current = prefs.getPlaybackSeekStepSec()
            showSingleChoice(
                getString(R.string.ps_seek_step),
                labels,
                steps.indexOf(current).takeIf { it >= 0 } ?: steps.indexOf(10)
            ) { index ->
                prefs.setPlaybackSeekStepSec(steps[index])
                refreshAll()
                notifySaved()
            }
        }

        // 片头时长 → skip_intro / intro_duration（毫秒，默认 90 秒）
        // 读取点：SkipIntroManager.getSkipIntroPosition() → checkSkipIntro() 的 seekTo
        binding.rowIntroDuration.setOnClickListener {
            val seconds = intArrayOf(30, 60, 90, 120, 180)
            val current = (skipIntroManager.getIntroDuration() / 1000L).toInt()
            showSingleChoice(
                getString(R.string.ps_intro_duration),
                seconds.map { getString(R.string.ps_unit_seconds, it) }.toTypedArray(),
                seconds.indexOf(current).takeIf { it >= 0 } ?: 2
            ) { index ->
                skipIntroManager.setIntroDuration(seconds[index] * 1000L)
                refreshAll()
                notifySaved()
            }
        }

        // 片尾时长 → skip_intro / outro_duration（毫秒，默认 60 秒）
        // 读取点：SkipIntroManager.getOutroDuration() → shouldSkipOutro()
        binding.rowOutroDuration.setOnClickListener {
            val seconds = intArrayOf(30, 60, 90, 120, 180)
            val current = (skipIntroManager.getOutroDuration() / 1000L).toInt()
            showSingleChoice(
                getString(R.string.ps_outro_duration),
                seconds.map { getString(R.string.ps_unit_seconds, it) }.toTypedArray(),
                seconds.indexOf(current).takeIf { it >= 0 } ?: 1
            ) { index ->
                skipIntroManager.setOutroDuration(seconds[index] * 1000L)
                refreshAll()
                notifySaved()
            }
        }

        // 广告时长 → playback_ad_skip_seconds（默认 15 秒）
        // 读取点：SkipIntroManager.getAdSkipPositionMs()（播放页调用点待接入）
        binding.rowAdSkipSeconds.setOnClickListener {
            val seconds = intArrayOf(5, 10, 15, 20, 30, 45)
            val current = prefs.getPlaybackAdSkipSeconds()
            showSingleChoice(
                getString(R.string.ps_ad_seconds),
                seconds.map { getString(R.string.ps_unit_seconds, it) }.toTypedArray(),
                seconds.indexOf(current).takeIf { it >= 0 } ?: 2
            ) { index ->
                prefs.setPlaybackAdSkipSeconds(seconds[index])
                refreshAll()
                notifySaved()
            }
        }

        // 预加载提前量 → playback_preload_lead_seconds（默认 10 秒）
        // 读取点：VideoPlayerActivity.preloadThresholdSeconds → getPlaybackPreloadLeadSeconds()（getter 每次读盘，改完即生效）
        binding.rowPreloadLead.setOnClickListener {
            val seconds = intArrayOf(5, 10, 15, 20, 30)
            val current = prefs.getPlaybackPreloadLeadSeconds()
            showSingleChoice(
                getString(R.string.ps_preload_lead),
                seconds.map { getString(R.string.ps_unit_seconds, it) }.toTypedArray(),
                seconds.indexOf(current).takeIf { it >= 0 } ?: 1
            ) { index ->
                prefs.setPlaybackPreloadLeadSeconds(seconds[index])
                refreshAll()
                notifySaved()
            }
        }

        // 缓冲区档位 → f057_buffer_prefs（省流 / 均衡 / 激进，默认均衡）
        // 读取点：ExoPlayerManager.initializePlayer() → F057Engine.current() → setBufferDurationsMs
        binding.rowBufferPreset.setOnClickListener {
            val presets = F057Engine.presets
            val current = F057Engine.activePreset(F057Engine.load(requireContext()))
            showSingleChoice(
                getString(R.string.ps_buffer_preset),
                presets.map { it.label }.toTypedArray(),
                current?.ordinal ?: -1
            ) { index ->
                F057Engine.save(requireContext(), F057Engine.of(presets[index]))
                refreshAll()
                notifySaved()
            }
        }

        // 断线重试次数 → playback_retry_count（默认 2 次）
        // 读取点：ExoPlayerManager.initializePlayer() → DefaultLoadErrorHandlingPolicy(retry)
        binding.rowRetryCount.setOnClickListener {
            val counts = intArrayOf(0, 1, 2, 3, 5)
            val current = prefs.getPlaybackRetryCount()
            showSingleChoice(
                getString(R.string.ps_retry_count),
                counts.map { retryLabel(it) }.toTypedArray(),
                counts.indexOf(current).takeIf { it >= 0 } ?: 2
            ) { index ->
                prefs.setPlaybackRetryCount(counts[index])
                refreshAll()
                notifySaved()
            }
        }

        // 默认画面比例 → aspect_ratio / aspect_ratio_mode（默认自适应）
        // 读取点：播放页 onCreate → applyAspectRatio(aspectRatioManager.getCurrentMode())
        binding.rowAspectRatio.setOnClickListener {
            val modes = aspectRatioManager.getAllModes()
            val current = aspectRatioManager.getCurrentMode()
            showSingleChoice(
                getString(R.string.ps_aspect_ratio),
                modes.map { it.displayName }.toTypedArray(),
                modes.indexOf(current)
            ) { index ->
                aspectRatioManager.setMode(modes[index])
                refreshAll()
                notifySaved()
            }
        }

        // 字体大小 → user_prefs / font_scale（1.0 标准 / 1.15 大 / 1.3 特大）
        // 读取点只有一处：VideoPlayerActivity.onCreate 读一次，缩放竖屏详情区的
        // videoTitleDetail / videoInfoText；控制栏不受影响，所以这里只做提示、不重建 Activity。
        binding.rowFontScale.setOnClickListener {
            val scales = FONT_SCALE_OPTIONS
            val current = getFontScale()
            val checked = scales.indexOfFirst { it == current }
            showSingleChoice(
                FONT_SCALE_TITLE,
                FONT_SCALE_LABELS,
                if (checked >= 0) checked else 0
            ) { index ->
                setFontScale(scales[index])
                refreshAll()
                CustomToast.show(requireContext(), "已保存，下次进入播放页生效")
            }
        }
    }

    // ============================================================
    // 恢复默认
    // ============================================================

    /**
     * 「恢复本组默认值」：只重置本页「播放设置」这一组涉及的键，主题 / 下载 / 关于等其它小节不碰。
     *
     * 本组涉及的落点与键（resetPlaybackSettings 覆盖前 14 个，后 6 个在这里或 ViewModel 里逐个写）：
     * · `user_prefs`：playback_auto_next、playback_remember_progress、playback_ended_behavior、
     *   playback_seek_step_sec、playback_ad_skip_enabled、playback_ad_skip_seconds、
     *   playback_preload_next、playback_preload_lead_seconds、playback_retry_count、
     *   playback_auto_switch_route、playback_allow_mobile_data、playback_exact_seek、
     *   local_video_exclude_ts、local_video_exclude_ext → [UserPreferences.resetPlaybackSettings]
     * · `user_prefs`：font_scale（本页自己管，默认 1.0 标准）→ 这里补上
     * · `player_settings`：long_press_lock_enabled（默认 true）
     * · `skip_intro`：片头/片尾开关与时长（默认开片头、关片尾、90s / 60s）
     * · `f057_buffer_prefs`：缓冲档位（默认均衡）
     * · F081 播放默认值快照（倍速 / 快进 / 长按 / 字幕等）
     * · `aspect_ratio*`：画面比例（默认自适应）
     *
     * 重置完重新读一遍偏好回填 View（[refreshAll]），开关与右侧文本立刻显示新的出厂值，
     * 不需要退出再进一次页面。
     */
    private fun setupResetRow() {
        binding.rowResetPlayback.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("恢复本组默认值")
                .setMessage("本组（播放行为、跳过与省时、预加载与缓冲、画面与网络、本地视频）的开关与档位会回到出厂值；主题、下载、关于等其它设置不受影响。")
                .setPositiveButton(R.string.confirm) { _, _ ->
                    resetPlaybackGroup()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** 真的执行重置：ViewModel 管那六处落点，`font_scale` 本页自己写，最后统一刷新界面。 */
    private fun resetPlaybackGroup() {
        viewModel.resetPlaybackSettings()
        // 「字体大小」不在 UserPreferences 里（只有设置页与播放页各读一次这个键），得单独写回标准档
        setFontScale(FONT_SCALE_OPTIONS.first())
        refreshAll()
        CustomToast.show(requireContext(), "已恢复本组默认值")
    }

    // ============================================================
    // 显示值
    // ============================================================

    /**
     * 把开关与右侧当前值全部刷成持久化值。
     *
     * 开关走 [syncSwitch]，期间 [syncingSwitches] 为 true，监听器会直接返回，
     * 不会把「回填」当成一次用户操作再写一次盘、再弹一次 Toast。
     */
    private fun refreshAll() {
        val b = _binding ?: return

        syncSwitch(b.switchPlaybackAutoNext, prefs.isPlaybackAutoNext())
        syncSwitch(b.switchPlaybackRememberProgress, prefs.isPlaybackRememberProgress())
        syncSwitch(
            b.switchPlaybackLongPressLock,
            playerPrefs().getBoolean(KEY_LONG_PRESS_LOCK, true)
        )
        syncSwitch(b.switchSkipIntro, skipIntroManager.isSkipIntroEnabled())
        syncSwitch(b.switchSkipOutro, skipIntroManager.isSkipOutroEnabled())
        syncSwitch(b.switchAdSkip, prefs.isPlaybackAdSkipEnabled())
        syncSwitch(b.switchPreloadNext, prefs.isPlaybackPreloadNext())
        syncSwitch(b.switchAutoRoute, prefs.isPlaybackAutoSwitchRoute())
        syncSwitch(b.switchExactSeek, prefs.isPlaybackExactSeek())
        syncSwitch(b.switchMobileData, prefs.isPlaybackAllowMobileData())

        b.tvPlaybackEndedBehavior.text = when (prefs.getPlaybackEndedBehavior()) {
            UserPreferences.ENDED_BEHAVIOR_STOP -> getString(R.string.ps_ended_stop)
            UserPreferences.ENDED_BEHAVIOR_LOOP -> getString(R.string.ps_ended_loop)
            else -> getString(R.string.ps_ended_next)
        }
        b.tvPlaybackSpeed.text = F081Engine.speedLabel(
            runCatching { F081Engine.load(requireContext()).speed }.getOrDefault(1.0f)
        )
        b.tvPlaybackSeekStep.text = F081Engine.seekStepLabel(prefs.getPlaybackSeekStepSec())
        b.tvIntroDuration.text = secondsLabel((skipIntroManager.getIntroDuration() / 1000L).toInt())
        b.tvOutroDuration.text = secondsLabel((skipIntroManager.getOutroDuration() / 1000L).toInt())
        b.tvAdSkipSeconds.text = secondsLabel(prefs.getPlaybackAdSkipSeconds())
        b.tvPreloadLead.text = secondsLabel(prefs.getPlaybackPreloadLeadSeconds())
        b.tvBufferPreset.text = runCatching {
            F057Engine.activePreset(F057Engine.load(requireContext()))?.label ?: "自定义"
        }.getOrDefault(Preset.BALANCED.label)
        b.tvRetryCount.text = retryLabel(prefs.getPlaybackRetryCount())
        b.tvAspectRatio.text = aspectRatioManager.getCurrentMode().displayName
        b.tvFontScale.text = fontScaleLabel(getFontScale())

        // 本地视频组：开关状态与「排除的后缀」整行的显示值一起回填
        syncSwitch(b.switchLocalVideoExcludeTs, prefs.isLocalVideoExcludeTs())
        refreshLocalVideoRow()
    }

    /**
     * 「排除的后缀」整行的显示值：开关关闭时没有排除项，整行置灰且不可点；
     * 打开时把真正生效的后缀列出来（读的是 [LocalVideoMedia.excludedExtensions] 这个唯一口径）。
     */
    private fun refreshLocalVideoRow() {
        val b = _binding ?: return
        val enabled = b.switchLocalVideoExcludeTs.isChecked
        b.rowLocalVideoExcludeExt.alpha = if (enabled) 1f else 0.45f
        b.rowLocalVideoExcludeExt.isEnabled = enabled
        val exts = LocalVideoMedia.excludedExtensions(requireContext())
        b.tvLocalVideoExcludeExt.text = if (exts.isEmpty()) {
            "—"
        } else {
            exts.sorted().joinToString("、") { ".$it" }
        }
    }

    // ============================================================
    // 缓存
    // ============================================================

    /** 清理进行中的标记：连点时第二次不重复扫盘，也不重复弹 Toast。 */
    private var cacheClearing = false

    /**
     * 「清理缓存」一行：标题里带上当前占用（`清理缓存（12.4 MB）`），点一次直接清。
     *
     * 统计与删除都在 [SettingsViewModel] 的 IO 线程上跑，本页只负责把结果写回 TextView
     * ——大缓存不卡遥控器操作。
     */
    private fun setupCacheRow() {
        binding.rowClearCache.setOnClickListener {
            if (cacheClearing) return@setOnClickListener
            cacheClearing = true
            viewModel.clearCache { result ->
                cacheClearing = false
                if (_binding == null || !isAdded) return@clearCache
                renderCacheLabel(result.afterBytes)
                // 已经是 0 也要有反馈；一点没删掉说明失败，不能谎报「已释放 0 B」
                when {
                    result.beforeBytes <= 0L ->
                        CustomToast.show(requireContext(), getString(R.string.cache_already_clean))
                    result.freedBytes <= 0L ->
                        CustomToast.show(requireContext(), getString(R.string.cache_clear_failed))
                    else -> CustomToast.show(
                        requireContext(),
                        "已释放 ${SettingsViewModel.CacheUsage.formatBytes(result.freedBytes)}"
                    )
                }
            }
        }
    }

    /** 后台量一次缓存占用，回来把标题刷成「清理缓存（12.4 MB）」。 */
    private fun refreshCacheUsage() {
        if (_binding == null) return
        viewModel.measureCache { usage ->
            if (_binding == null || !isAdded) return@measureCache
            renderCacheLabel(usage.totalBytes)
        }
    }

    private fun renderCacheLabel(bytes: Long) {
        val b = _binding ?: return
        b.tvClearCacheTitle.text = getString(R.string.clear_cache) +
            "（${SettingsViewModel.CacheUsage.formatBytes(bytes)}）"
    }

    // ============================================================
    // 工具
    // ============================================================

    /** 回填开关状态；只有值真的变了才赋值，避免多余的重绘。 */
    private fun syncSwitch(switch: MaterialSwitch, checked: Boolean) {
        if (switch.isChecked == checked) return
        syncingSwitches = true
        switch.isChecked = checked
        syncingSwitches = false
    }

    /** 回填期间返回 true：监听器直接忽略这次变化。 */
    private fun syncGuard(): Boolean = syncingSwitches

    private fun showSingleChoice(
        title: String,
        labels: Array<String>,
        checkedIndex: Int,
        onPick: (Int) -> Unit
    ) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                dialog.dismiss()
                onPick(which)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 只改一项播放默认值，其余五项保持原样（F081 是整份快照，必须整体写回）。 */
    private fun savePlaybackDefaults(transform: (PlaybackDefaults) -> PlaybackDefaults) {
        runCatching {
            val current = F081Engine.load(requireContext())
            F081Engine.save(requireContext(), transform(current))
        }
    }

    // ============================================================
    // 保存反馈（节流）
    // ============================================================

    private val savedHandler = Handler(Looper.getMainLooper())

    /**
     * 连续拨动开关 / 连着改选项时，只在最后一次写入后 [SAVED_HINT_DELAY_MS] 毫秒提示一次，
     * 避免弹出一串「已保存」。执行类操作（清理缓存、恢复默认）不走这里，各有自己的结果文案。
     */
    private val savedHintRunnable = Runnable {
        if (_binding == null || !isAdded) return@Runnable
        CustomToast.show(requireContext(), "已保存 ✓")
    }

    private fun notifySaved() {
        savedHandler.removeCallbacks(savedHintRunnable)
        savedHandler.postDelayed(savedHintRunnable, SAVED_HINT_DELAY_MS)
    }

    // ============================================================
    // 字体大小
    // ============================================================

    /** 与 [UserPreferences] 同一个文件（`user_prefs`）：`font_scale` 只有设置页与播放页在用。 */
    private fun fontPrefs() =
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 当前字号档位，坏值退回 1.0（标准）。 */
    private fun getFontScale(): Float {
        val stored = fontPrefs().getFloat(KEY_FONT_SCALE, FONT_SCALE_OPTIONS.first())
        // FloatArray 没有 contains 扩展，逐项比一次更直观
        return if (FONT_SCALE_OPTIONS.any { it == stored }) stored else FONT_SCALE_OPTIONS.first()
    }

    private fun setFontScale(scale: Float) {
        fontPrefs().edit().putFloat(KEY_FONT_SCALE, scale).apply()
    }

    private fun fontScaleLabel(scale: Float): String = when (scale) {
        FONT_SCALE_OPTIONS[1] -> FONT_SCALE_LABELS[1]
        FONT_SCALE_OPTIONS[2] -> FONT_SCALE_LABELS[2]
        else -> FONT_SCALE_LABELS[0]
    }

    // ============================================================
    // 搜索
    // ============================================================

    /**
     * 设置项索引：一行一条 [Triple]，进页面时建一次，之后每次输入只查这张表，不再遍历布局。
     *
     * - `first`  = 行容器（卡片里那一行 LinearLayout；整张卡就是一行时取卡里那一行）
     * - `second` = 该行的关键字（行 `tag` + 行内所有 TextView 文案拼起来，索引时一次算好）
     * - `third`  = 这一行所属的小节标题 View
     */
    private val searchIndex = mutableListOf<Triple<View, String, View>>()

    /** 行 → 紧跟其后的 1dp 分隔线：行被过滤掉时线也收起来，免得留下孤零零一条横线。 */
    private val searchDividers = HashMap<View, View>()

    /** 卡片 → 卡片内的行：一行都没命中时整张卡收起，不留空白卡片。 */
    private val searchCards = LinkedHashMap<View, MutableList<View>>()

    /** 页面上所有小节标题，过滤时按「本小节有没有命中行」统一刷。 */
    private val searchSectionTitles = mutableListOf<View>()

    /**
     * 顶部搜索框：按行关键字过滤下方设置行。
     *
     * 索引在进页面时建一次（[buildSearchIndex]）；输入时用
     * `keywords.contains(query, ignoreCase = true)` 逐行决定 VISIBLE / GONE：
     * 关键字为空 → 全部恢复（含小节标题、卡片、分隔线）；
     * 非空 → 小节标题只在本小节至少一行命中时才显示。
     * 这里只动 visibility，不碰任何点击监听：「清理缓存」「字体大小」两行的点击与显示值逻辑原样保留。
     */
    private fun setupSearch() {
        val b = _binding ?: return
        buildSearchIndex()
        b.settingsSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applySearchFilter(s?.toString().orEmpty())
        })
        // 视图重建时 EditText 会自己恢复上次的输入：这时不会再触发 TextWatcher，
        // 所以按当前文案先过滤一次，避免「框里有字、列表却是全量」的错位
        applySearchFilter(b.settingsSearchInput.text?.toString().orEmpty())
    }

    /**
     * 建索引：沿 `settingsContainer` 的直接子节点顺序走一遍（卡片内部再往里一层）。
     *
     * 布局是固定两段式：小节标题 TextView 紧跟一张 MaterialCardView。
     * 卡片内部有两种形状，都要收：
     * 1. 竖排容器里一串「行 LinearLayout + 1dp 分隔线」（播放行为 / 跳过 / 缓冲 / 画面 / 本地视频）；
     * 2. 整张卡就是一行（存储 / 恢复默认）。
     */
    private fun buildSearchIndex() {
        val b = _binding ?: return
        searchIndex.clear()
        searchDividers.clear()
        searchCards.clear()
        searchSectionTitles.clear()

        val container = b.settingsContainer
        var sectionTitle: View? = null
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            // 搜索框自己也是 TextView 子类，别把它当成小节标题收进来
            if (child === b.settingsSearchInput) continue
            when (child) {
                is MaterialCardView -> {
                    // 每张卡片归到它上面最近那个小节标题
                    val title = sectionTitle
                    if (title != null) collectCardRows(child, title)
                }
                is TextView -> {
                    sectionTitle = child
                    searchSectionTitles.add(child)
                }
            }
        }
    }

    /** 一张卡片的行全部进索引；分隔线挂到它上面那一行，卡片本身记进 [searchCards]。 */
    private fun collectCardRows(card: MaterialCardView, sectionTitle: View) {
        val rows = cardRows(card)
        if (rows.isEmpty()) return
        searchCards[card] = rows
        for (row in rows) {
            searchIndex.add(Triple(row, rowKeywords(row), sectionTitle))
        }
    }

    /** 卡片里的行：竖排容器拆开逐行取；整张卡就是一行时直接取卡里那一行。 */
    private fun cardRows(card: MaterialCardView): MutableList<View> {
        val rows = mutableListOf<View>()
        if (card.childCount == 0) return rows
        val top = card.getChildAt(0)
        if (top is LinearLayout && top.orientation == LinearLayout.VERTICAL && hasRowChildren(top)) {
            var lastRow: View? = null
            for (i in 0 until top.childCount) {
                val item = top.getChildAt(i)
                if (item is LinearLayout) {
                    rows.add(item)
                    lastRow = item
                } else {
                    // 分隔线归到它上面那一行：那一行被过滤掉时线也跟着收起来
                    val owner = lastRow
                    if (owner != null) searchDividers[owner] = item
                }
            }
            return rows
        }
        // 整张卡就是一行（存储 / 恢复默认）
        rows.add(top)
        return rows
    }

    /** 竖排容器里是否真的装着行（行本身也是 LinearLayout）。 */
    private fun hasRowChildren(column: LinearLayout): Boolean {
        for (i in 0 until column.childCount) {
            if (column.getChildAt(i) is LinearLayout) return true
        }
        return false
    }

    /**
     * 一行的关键字：先取布局给行写的 `tag`（没有就只用文案），
     * 再把行内**所有** TextView 的文案按出现顺序拼进来 —— 第一个是标题，
     * 说明文字、右侧当前值（倍速「1.0x」、缓存「12.4 MB」）都算，输入任一关键词都搜得到。
     */
    private fun rowKeywords(row: View): String {
        val builder = StringBuilder()
        val tag = row.tag as? String
        if (!tag.isNullOrBlank()) builder.append(tag).append(' ')
        appendRowTexts(row, builder)
        return builder.toString()
    }

    /** 递归收 [view] 之下所有 TextView 的文案（行、竖排容器、开关都按这个走）。 */
    private fun appendRowTexts(view: View, builder: StringBuilder) {
        if (view is TextView) {
            val text = view.text?.toString()
            if (!text.isNullOrBlank()) builder.append(text).append(' ')
            return
        }
        if (view !is ViewGroup) return
        for (i in 0 until view.childCount) {
            appendRowTexts(view.getChildAt(i), builder)
        }
    }

    /**
     * 输入即过滤：关键字为空全部恢复（含小节标题、卡片、分隔线）；
     * 否则每行按 `keywords.contains(query, ignoreCase = true)` 决定 VISIBLE / GONE。
     *
     * 只改 visibility：清理缓存行的点击、字体大小行的点击、本地视频「排除的后缀」行的
     * alpha / isEnabled 都不受影响。
     */
    private fun applySearchFilter(rawKeyword: String) {
        val query = rawKeyword.trim()
        val visibleRows = HashSet<View>()
        val visibleSections = HashSet<View>()

        for ((row, keywords, sectionTitle) in searchIndex) {
            val matched = query.isEmpty() || keywords.contains(query, ignoreCase = true)
            row.visibility = if (matched) View.VISIBLE else View.GONE
            if (matched) {
                visibleRows.add(row)
                visibleSections.add(sectionTitle)
            }
        }
        // 分隔线跟着上面那一行走
        for ((row, divider) in searchDividers) {
            divider.visibility = if (visibleRows.contains(row)) View.VISIBLE else View.GONE
        }
        // 卡片里一行都没命中就收起整张卡
        for ((card, rows) in searchCards) {
            val anyVisible = rows.any { visibleRows.contains(it) }
            card.visibility = if (anyVisible) View.VISIBLE else View.GONE
        }
        // 小节标题：本小节至少一行可见时才显示
        for (title in searchSectionTitles) {
            title.visibility = if (visibleSections.contains(title)) View.VISIBLE else View.GONE
        }
    }

    private fun secondsLabel(seconds: Int): String =
        getString(R.string.ps_unit_seconds, seconds)

    private fun retryLabel(count: Int): String =
        if (count == 0) getString(R.string.ps_retry_none) else getString(R.string.ps_retry_times, count)

    /** 播放页自己那份小配置：长按锁定倍速就存在这里，播放页本来就在读它。 */
    private fun playerPrefs() =
        requireContext().getSharedPreferences("player_settings", Context.MODE_PRIVATE)

    private companion object {
        const val KEY_LONG_PRESS_LOCK = "long_press_lock_enabled"

        /** 与 [UserPreferences] 共用的 SharedPreferences 文件名。 */
        const val PREFS_NAME = "user_prefs"

        /** 字体大小档位：1.0 标准 / 1.15 大 / 1.3 特大。播放页只读这一个键。 */
        const val KEY_FONT_SCALE = "font_scale"

        val FONT_SCALE_OPTIONS = floatArrayOf(1.0f, 1.15f, 1.3f)
        val FONT_SCALE_LABELS = arrayOf("标准", "大", "特大")
        const val FONT_SCALE_TITLE = "字体大小"

        /** 连续改设置时的提示节流窗口：只在最后一次写入后提示一次。 */
        const val SAVED_HINT_DELAY_MS = 800L
    }
}
