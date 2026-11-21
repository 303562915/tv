package com.tv.ball.feature.f086

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
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
import kotlin.math.roundToInt

/**
 * 「关于页与许可证」页 —— 结构对照 unicanta 的 AboutScreen 一比一翻译成 View 体系：
 *
 * 1. **AppHero**：图标块 + 应用名 + 版本行（开发版带 dev 构建号）+ 安装时间 + 一句话定位；
 * 2. **UpdateCard**：整行就是按钮——空闲提示 / 检查中 / 已是最新 / 发现新版本（再点下载）/
 *    失败原因（点名被墙、配额、超时、上游故障），失败的再点一次就是重试；
 * 3. **UpdateChannelCard**：稳定版 / 开发版两块渠道芯片，确认键切换；
 * 4. **自动下载安装开关**；
 * 5. **外链卡**：下载页 / 源码仓库，交给系统浏览器，没有浏览器时只提示、不崩；
 * 6. **第三方依赖 + 许可证全文**（本项目自有的开源合规区）。
 *
 * 电视遥控器：焦点链 返回 → 检查更新 → 稳定版 → 开发版 → 自动安装 → 下载页 →
 * 打开仓库 → 许可证长文本区；依赖行不可聚焦（见 addDepRow 注释）。
 */
class F086Activity : AppCompatActivity() {

    private lateinit var binding: ActivityF086Binding

    /** 检查更新的状态机（对照 unicanta UpdateState 的页面可见子集）。 */
    private sealed interface UiState {
        data object Idle : UiState
        data object Checking : UiState
        data object Latest : UiState
        data class Available(val info: UpdateInfo) : UiState
        data class Failed(val failure: UpdateChecker.Failure) : UiState
    }

    private var state: UiState = UiState.Idle
    private var channel: UpdateChannel = UpdateChannel.STABLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityF086Binding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = prefs()
        channel = UpdateChannel.from(prefs.getString(KEY_CHANNEL, null))

        binding.btnBack.setOnClickListener { finish() }
        binding.f086UpdateCard.setOnClickListener { onUpdateCardTap() }
        binding.f086ChannelStable.setOnClickListener { switchChannel(UpdateChannel.STABLE) }
        binding.f086ChannelDev.setOnClickListener { switchChannel(UpdateChannel.DEV) }
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

        renderHero()
        renderDeps()
        renderLicense()
        renderChannel()
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

    /** 整行即按钮：空闲/失败 = 检查，发现新版本 = 下载，检查中忽略。 */
    private fun onUpdateCardTap() {
        when (val current = state) {
            is UiState.Checking -> Unit
            is UiState.Available -> {
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
            state = result.fold(
                onSuccess = { info ->
                    if (UpdateChecker.isNewer(info)) {
                        if (binding.f086AutoInstall.isChecked) {
                            UpdateManager.downloadAndInstall(this@F086Activity, info) { renderIdle() }
                            UiState.Latest
                        } else {
                            UiState.Available(info)
                        }
                    } else {
                        UiState.Latest
                    }
                },
                onFailure = { e ->
                    UiState.Failed(
                        (e as? UpdateChecker.CheckException)?.failure ?: UpdateChecker.Failure.UNKNOWN
                    )
                }
            )
            render(state)
        }
    }

    // ===== 渠道 =====

    private fun switchChannel(target: UpdateChannel) {
        if (target == channel) return
        channel = target
        prefs().edit().putString(KEY_CHANNEL, if (target == UpdateChannel.DEV) "dev" else "stable").apply()
        renderChannel()
        renderIdle()
    }

    /** 渠道芯片的选中态：选中 = 主题色实底 + on_primary 文字，未选 = 原始灰底。 */
    private fun renderChannel() {
        val primary = ContextCompat.getColor(this, R.color.theme_primary)
        val onPrimary = ContextCompat.getColor(this, R.color.theme_on_primary)
        val plainText = ContextCompat.getColor(this, R.color.theme_text_primary)
        bindChip(binding.f086ChannelStable, channel == UpdateChannel.STABLE, primary, onPrimary, plainText)
        bindChip(binding.f086ChannelDev, channel == UpdateChannel.DEV, primary, onPrimary, plainText)
    }

    private fun bindChip(chip: TextView, selected: Boolean, primary: Int, onPrimary: Int, plain: Int) {
        if (selected) {
            chip.backgroundTintList = ColorStateList.valueOf(primary)
            chip.setTextColor(onPrimary)
        } else {
            chip.backgroundTintList = null
            chip.setTextColor(plain)
        }
    }

    // ===== 状态渲染 =====

    private fun render(target: UiState) {
        val busy = target is UiState.Checking
        binding.f086UpdateProgress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.f086UpdateAction.setText(
            if (target is UiState.Available) R.string.f086_download_now else R.string.f086_check_action
        )
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

    // ===== 依赖列表 =====

    /** 依赖卡：先写"共 N 项"，再按 [F086Engine.deps] 的顺序逐行生成。 */
    private fun renderDeps() {
        val deps = F086Engine.deps()
        binding.f086DepsCount.text = getString(R.string.f086_deps_count, deps.size)

        binding.f086DepList.removeAllViews()
        deps.forEach { addDepRow(it) }
    }

    /**
     * 一行依赖：名字 / `版本 x · 许可证 y` / 上游地址。
     * 行本身不可聚焦：它没有点击行为，可聚焦只会让遥控器的焦点停在死位置上。
     */
    private fun addDepRow(dep: Dep) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = ContextCompat.getDrawable(this@F086Activity, R.drawable.bg_card_surface)
        }

        row.addView(
            TextView(this).apply {
                text = dep.name
                setTextColor(color(R.color.theme_text_primary))
                textSize = 15f
            }
        )

        row.addView(
            TextView(this).apply {
                text = getString(R.string.f086_dep_line, dep.version, dep.license)
                setTextColor(color(R.color.theme_text_secondary))
                textSize = 12f
                setPadding(0, dp(3), 0, 0)
            }
        )

        row.addView(
            TextView(this).apply {
                text = dep.url
                setTextColor(color(R.color.theme_primary))
                textSize = 11f
                setPadding(0, dp(2), 0, 0)
            }
        )

        binding.f086DepList.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )
    }

    // ===== 许可证 =====

    /** 许可证区：一行统计 + 可滚动长文本，都由引擎生成，页面不做字符串拼接。 */
    private fun renderLicense() {
        binding.f086LicenseSummary.text = F086Engine.licenseSummary()
        binding.f086LicenseText.text = F086Engine.licenseText()
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

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).roundToInt()

    private companion object {
        const val PREFS = "f086_about"
        const val KEY_CHANNEL = "update_channel"
        const val KEY_AUTO_INSTALL = "auto_install"
    }
}
