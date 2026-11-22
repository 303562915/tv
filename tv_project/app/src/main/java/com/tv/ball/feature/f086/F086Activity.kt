package com.tv.ball.feature.f086

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tv.ball.R
import com.tv.ball.databinding.ActivityF086Binding
import com.tv.ball.update.UpdateChannel
import com.tv.ball.update.UpdateChecker
import com.tv.ball.update.UpdateInfo
import com.tv.ball.update.UpdateManager
import com.tv.ball.util.CustomToast
import kotlinx.coroutines.launch

/**
 * 「关于」页 —— 结构对照 unicanta 的 AboutScreen 一比一翻译成 View 体系：
 *
 * 1. **AppHero**：图标块 + 应用名 + 版本行（开发版带 dev 构建号）+ 安装时间 + 一句话定位；
 * 2. **UpdateCard**：整行就是按钮——空闲提示 / 检查中 / 已是最新 / 发现新版本（再点下载）/
 *    正在下载 / 失败原因（点名被墙、配额、超时、上游故障），失败的再点一次就是重试；
 * 3. **UpdateChannelCard**：一行「更新渠道 + 下拉选择」，选定即存（默认开发版）；
 * 4. **自动下载安装开关**（与倍速面板同一套开关美化）；
 * 5. **外链卡**：下载页 / 源码仓库，交给系统浏览器，没有浏览器时只提示、不崩（末位区块）。
 *
 * 电视遥控器：焦点链 返回 → 检查更新 → 渠道下拉 → 自动安装 → 下载页 →
 * 打开仓库（末位，不再向下延伸）。
 */
class F086Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF086Binding

    /** 检查更新的状态机（对照 unicanta UpdateState 的页面可见子集）。 */
    private sealed interface UiState {
        data object Idle : UiState
        data object Checking : UiState
        data object Latest : UiState
        data class Available(val info: UpdateInfo) : UiState

        /**
         * 新版本已在后台下载（自动安装开关开着，或用户点了「下载安装」）。
         * 下载链路本身不在这里，页面只负责如实说一句「正在下载」，不能报成「已是最新」。
         */
        data object Downloading : UiState

        data class Failed(val failure: UpdateChecker.Failure) : UiState
    }

    private var state: UiState = UiState.Idle
    private var channel: UpdateChannel = UpdateChannel.DEFAULT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF086Binding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = prefs()
        channel = UpdateChannel.read(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.f086UpdateCard.setOnClickListener { onUpdateCardTap() }
        binding.f086ChannelSpinner.adapter = ChannelAdapter()
        binding.f086ChannelSpinner.setSelection(channelIndex(channel), false)
        binding.f086ChannelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                switchChannel(channelAt(position))
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.f086AutoInstall.isChecked = prefs.getBoolean(KEY_AUTO_INSTALL, false)
        binding.f086AutoInstall.setOnCheckedChangeListener { _, checked ->
            prefs().edit().putBoolean(KEY_AUTO_INSTALL, checked).apply()
            // 打开开关时若已有版本在等，直接开始下载（对照 unicanta ToggleCard 的行为）
            val available = (state as? UiState.Available)?.info
            if (checked && available != null) {
                UpdateManager.downloadAndInstall(this, available) { renderIdle() }
            }
        }
        binding.f086DownloadCard.setOnClickListener {
            openUrl(F086Engine.DOWNLOAD_PAGE_URL, R.string.f086_download_page)
        }
        binding.f086RepoButton.setOnClickListener { openRepo() }
        binding.f086ChangelogRow.setOnClickListener { showChangelog() }

        renderHero()
        render(state)
    }

    // ===== AppHero =====

    /** 版本行与安装时间；开发版显示 (dev N)，由 [F086Engine.versionText] 决定。 */
    private fun renderHero() {
        binding.f086VersionValue.text = F086Engine.versionText(this)
        val info = F086Engine.appInfo(this)
        binding.f086UpdatedValue.text = if (info.updatedAtMillis > 0L) {
            getString(R.string.f086_installed_at, F086Engine.formatDate(info.updatedAtMillis))
        } else {
            getString(R.string.f086_value_unknown)
        }
    }

    // ===== 检查更新 =====

    /** 整行即按钮：空闲/失败 = 检查，发现新版本 = 下载，检查中与下载中忽略。 */
    private fun onUpdateCardTap() {
        when (val current = state) {
            is UiState.Checking -> Unit
            is UiState.Downloading -> Unit
            is UiState.Available -> {
                // 点了就开始下载：先把页面切到「正在下载」，完成回调再把状态收回空闲
                state = UiState.Downloading
                render(state)
                UpdateManager.downloadAndInstall(this, current.info) { renderIdle() }
            }
            else -> checkUpdate()
        }
    }

    private fun checkUpdate() {
        state = UiState.Checking
        render(state)
        lifecycleScope.launch {
            val result = runCatching { UpdateChecker.check(channel) }
            var autoStart: UpdateInfo? = null
            val next = result.fold(
                onSuccess = { info ->
                    when {
                        !UpdateChecker.isNewer(info) -> UiState.Latest
                        // 自动安装开着：不询问直接下载，页面照实说「正在下载」，不能报「已是最新」
                        binding.f086AutoInstall.isChecked -> {
                            autoStart = info
                            UiState.Downloading
                        }
                        else -> UiState.Available(info)
                    }
                },
                onFailure = { e ->
                    UiState.Failed(
                        (e as? UpdateChecker.CheckException)?.failure ?: UpdateChecker.Failure.UNKNOWN
                    )
                }
            )
            state = next
            render(state)
            autoStart?.let { UpdateManager.downloadAndInstall(this@F086Activity, it) { renderIdle() } }
        }
    }

    // ===== 渠道（下拉） =====

    /** 下拉位置 → 渠道：0 = 稳定版，1 = 开发版（默认）。 */
    private fun channelAt(position: Int): UpdateChannel =
        if (position == CHANNEL_INDEX_DEV) UpdateChannel.DEV else UpdateChannel.STABLE

    private fun channelIndex(target: UpdateChannel): Int =
        if (target == UpdateChannel.DEV) CHANNEL_INDEX_DEV else CHANNEL_INDEX_STABLE

    /** 选定即落盘，并把状态行刷成「点卡片检查 xx 渠道」。 */
    private fun switchChannel(target: UpdateChannel) {
        if (target == channel) return
        channel = target
        UpdateChannel.save(this, target)
        renderIdle()
    }

    /**
     * 下拉项适配器：项视图用代码造 TextView。
     *
     * 不用 `android.R.layout.simple_spinner_item`——它的文字色取自系统主题 textColorPrimary，
     * 深色 / 浅色切换后与本项目的 theme_text_primary 对不上；自己做反而能保证同色同字号。
     * 折叠态和下拉列表共用同一个视图（`getDropDownView` 走默认实现）。
     */
    private inner class ChannelAdapter : BaseAdapter() {

        private val labels = listOf(
            getString(R.string.f086_channel_stable),
            getString(R.string.f086_channel_dev)
        )

        override fun getCount(): Int = labels.size

        override fun getItem(position: Int): Any = labels[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView as? TextView ?: TextView(this@F086Activity).apply {
                setTextColor(ContextCompat.getColor(context, R.color.theme_text_primary))
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                val side = resources.getDimensionPixelSize(R.dimen.tab_padding)
                setPadding(side, 0, side, 0)
            }
            view.text = labels.getOrElse(position) { "" }
            return view
        }
    }

    // ===== 状态渲染 =====

    private fun render(target: UiState) {
        // 检查中与下载中都是「在动」的状态，行首转圈；两者的整行点击都忽略
        val busy = target is UiState.Checking || target is UiState.Downloading
        binding.f086UpdateProgress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.f086UpdateAction.setText(
            if (target is UiState.Available) R.string.f086_download_now else R.string.f086_check_action
        )
        // 下载中整行不接受点击，右侧就别再挂着「立即检查」这种可点的字样
        binding.f086UpdateAction.visibility =
            if (target is UiState.Downloading) View.INVISIBLE else View.VISIBLE
        val statusText: String
        val statusColor: Int
        when (val current = target) {
            UiState.Idle -> {
                statusText = getString(R.string.f086_check_hint_channel, channelLabel())
                statusColor = color(R.color.theme_text_secondary)
            }
            UiState.Checking -> {
                statusText = getString(R.string.f086_checking)
                statusColor = color(R.color.theme_text_secondary)
            }
            UiState.Downloading -> {
                statusText = getString(R.string.f086_downloading)
                statusColor = color(R.color.theme_primary)
            }
            UiState.Latest -> {
                statusText = getString(R.string.f086_up_to_date)
                statusColor = color(R.color.theme_primary)
            }
            is UiState.Available -> {
                statusText = getString(R.string.f086_update_available, UpdateManager.labelOf(current.info))
                statusColor = color(R.color.theme_primary)
            }
            is UiState.Failed -> {
                statusText = failureText(current.failure)
                statusColor = color(R.color.theme_error)
            }
        }
        binding.f086UpdateStatus.text = statusText
        binding.f086UpdateStatus.setTextColor(statusColor)
    }

    private fun renderIdle() {
        state = UiState.Idle
        render(state)
    }

    private fun channelLabel(): String =
        getString(if (channel == UpdateChannel.DEV) R.string.f086_channel_dev else R.string.f086_channel_stable)

    /** 失败原因的一句话（对照 unicanta UpdateFailure.reason()）。 */
    private fun failureText(failure: UpdateChecker.Failure): String = getString(
        when (failure) {
            UpdateChecker.Failure.RATE_LIMITED -> R.string.f086_fail_rate_limited
            UpdateChecker.Failure.TIMEOUT -> R.string.f086_fail_timeout
            UpdateChecker.Failure.NETWORK -> R.string.f086_fail_network
            UpdateChecker.Failure.SERVER -> R.string.f086_fail_server
            UpdateChecker.Failure.NO_RELEASE -> R.string.f086_fail_no_release
            UpdateChecker.Failure.MALFORMED -> R.string.f086_fail_malformed
            UpdateChecker.Failure.UNKNOWN -> R.string.f086_fail_unknown
        }
    )

    // ===== 更新记录 =====

    /**
     * 更新记录：最近 5 个版本各一句话。
     *
     * 数据来自 [Changelog] 的本地常量数组，**不发任何网络请求**——断网、镜像挂掉时
     * 用户仍然能看到手上这版改了什么。用系统 AlertDialog 承载，不新增界面。
     */
    private fun showChangelog() {
        if (Changelog.entries.isEmpty()) {
            CustomToast.show(this, getString(R.string.f086_changelog_empty))
            return
        }
        val text = Changelog.dialogText { version, note ->
            getString(R.string.f086_changelog_line, version, note)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.f086_changelog_title)
            .setMessage(text)
            .setPositiveButton(R.string.f086_changelog_ok, null)
            .show()
    }

    // ===== 外链 =====

    /**
     * 打开仓库：只发 `ACTION_VIEW`，尊重用户已选的默认浏览器；没有可处理的 Activity
     * 时只提示、不崩（电视盒子上没装浏览器很常见）。
     */
    private fun openRepo() {
        openUrl(F086Engine.repoUrl(), R.string.f086_action_repo)
    }

    private fun openUrl(url: String, titleRes: Int) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(intent)
            CustomToast.show(this, getString(R.string.f086_toast_repo_opening, url))
        } catch (e: ActivityNotFoundException) {
            CustomToast.show(this, getString(R.string.f086_toast_repo_failed, url))
        } catch (e: SecurityException) {
            CustomToast.show(this, getString(R.string.f086_toast_repo_failed, url))
        }
    }

    // ===== 小工具 =====

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private companion object {
        const val PREFS = "f086_about"
        const val KEY_AUTO_INSTALL = "auto_install"
        const val CHANNEL_INDEX_STABLE = 0
        const val CHANNEL_INDEX_DEV = 1
    }
}
