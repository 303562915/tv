package com.tv.ball.update

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.tv.ball.BuildConfig
import com.tv.ball.R
import com.tv.ball.util.CustomToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「检查更新 → 下载 → 安装」的完整编排，界面用纯代码弹窗，不依赖布局文件。
 *
 * 安装顺序：PackageInstaller / 系统安装器 → Shizuku → root → 网页。
 * 「我的」页的检查走默认弹窗流（[start]）；关于页（F086）需要渠道与
 * 失败原因，直接用 [UpdateChecker.check] 并把 [failureMessage] 显示在页面上，
 * 下载安装仍委托 [downloadAndInstall]。
 *
 * 前台自动检查（[autoCheckLoop]）是第四条入口：它不弹对话框，只用
 * [UpdatePrompt] 挂一条主题化轻提示，在前台期间每 30 秒查一次（同一版本只提示一次）。
 */
object UpdateManager {

    private const val DOWNLOAD_PAGE = "https://303562915.github.io/tv/"

    /** 前台自动检查的节流与周期：每 30 秒查一次（进前台立刻查一次）。 */
    private const val AUTO_PREFS = "update_auto"
    private const val KEY_LAST_AUTO_CHECK = "last_auto_check_at"
    private const val AUTO_CHECK_INTERVAL_MILLIS = 30L * 1000L

    /** 关于页「自动安装」开关的键（与 F086Activity 共用同一份 SharedPreferences）。 */
    private const val KEY_AUTO_INSTALL = "auto_install"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 同一时刻只跑一次检查：重复触发（启动 + 手动 + 回前台）直接丢掉后到的那个。 */
    private val checkRunning = AtomicBoolean(false)

    /** 已经提示（或已自动开始下载）过的版本标识：30 秒轮询时同一版本只打扰一次。 */
    private var handledVersion: String? = null

    private var dialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var sessionReceiver: BroadcastReceiver? = null

    // ---------------------------------------------------------------- 状态

    /**
     * 轻量状态：给提示条消费的那一套，对照 Canta 的 `UpdateState` 只留要给人看的几个。
     *
     * 既有入口（[start] / [downloadAndInstall] / [install]）的签名与弹窗行为一行没动，
     * 这里只是新增的、可旁听的视图，UI 是 View 体系所以它纯数据、不画界面。
     */
    sealed interface Status {
        /** 正在读取最新版本。 */
        data object Checking : Status

        /** 手上已经是最新（[info] 是远端那份，用来说「已是最新 …」）。 */
        data class Latest(val info: UpdateInfo) : Status

        /** 有更新的版本可用。 */
        data class Available(val info: UpdateInfo) : Status

        /** 正在下载，[percent] 为 0..100，-1 表示服务器没给总大小。 */
        data class Downloading(val info: UpdateInfo, val percent: Int) : Status

        /** 已经交给安装链路（Shizuku → root → 系统安装器）。 */
        data class Installing(val info: UpdateInfo) : Status

        /** 检查或下载失败，[reason] 是人话。 */
        data class Failed(val reason: String) : Status
    }

    // ---------------------------------------------------------------- 入口

    /** 弹窗流：按当前渠道（默认开发版）检查，发现新版本就问要不要下载安装。 */
    fun start(activity: Activity, onDone: () -> Unit = {}) {
        start(activity, UpdateChannel.read(activity), onDone)
    }

    /** 弹窗流 + 渠道：关于页的「立即检查」也走这里时保持行为一致。 */
    fun start(activity: Activity, channel: UpdateChannel, onDone: () -> Unit = {}) {
        showProgress(activity, "检查更新", "正在读取最新版本…")

        scope.launch {
            val result = runCatching { UpdateChecker.check(channel) }
            dismissProgress()

            result.fold(
                onSuccess = { info ->
                    if (!UpdateChecker.isNewer(info)) {
                        toast(activity, "已经是最新版本（${labelOf(info)}）")
                        onDone()
                        return@launch
                    }
                    askDownload(activity, info, onDone)
                },
                onFailure = { e ->
                    val failure = (e as? UpdateChecker.CheckException)?.failure
                        ?: UpdateChecker.Failure.UNKNOWN
                    toast(activity, UpdateChecker.failureMessage(failure))
                    onDone()
                }
            )
        }
    }

    /** 版本标签：开发版带构建号，稳定版只带版本名。 */
    fun labelOf(info: UpdateInfo): String = when {
        info.buildNumber > 0 -> "v${info.versionName} (dev ${info.buildNumber})"
        else -> "v${info.versionName}"
    }

    // ---------------------------------------------------------------- 启动自动检查

    /**
     * 前台周期自动检查：进入前台先查一次，之后每 30 秒查一次。
     *
     * 周期与节流：
     *  - 每 [AUTO_CHECK_INTERVAL_MILLIS]（30 秒）查一次，时间戳存 `update_auto`，
     *    同一进程里重复进前台不会把间隔压碎；
     *  - 已是最新、检查失败都**静默**：自动轮询不该被网络问题变成一串弹窗，
     *    失败原因留给关于页的手动检查去说；
     *  - 只有「发现新版本」才提示：主题化轻提示 + 版本号 + 「立即更新 / 稍后」，
     *    并且同一个版本只提示（或自动下载）一次，否则每 30 秒会挂出一条同样的提示。
     *
     * 关于页的「自动下载更新」开关开着时直接走下载→安装（不再多问一次），
     * 关着就提示；两条路的下载与安装都复用 [downloadQuietly] / [downloadAndInstall] /
     * [install]，安装顺序仍是 Shizuku → root → 系统安装器 → 提示降级。
     *
     * 轮询由调用方的作用域控制：主界面用 `repeatOnLifecycle(RESUMED)` 调用，
     * 切后台就停，回前台立刻再查一次。
     *
     * @param scope 调用方的作用域：Activity 传前台生命周期作用域，Application 传应用级作用域
     */
    fun autoCheckLoop(activity: Activity, scope: CoroutineScope = this.scope) {
        if (!activity.alive()) return
        if (!scope.isActive) return
        scope.launch {
            while (scope.isActive) {
                if (!activity.alive()) break
                autoCheckOnce(activity, scope)
                delay(AUTO_CHECK_INTERVAL_MILLIS)
            }
        }
    }

    /** 一轮自动检查：没到期就什么都不做，结果按 [autoCheckLoop] 的规则处理。 */
    private fun autoCheckOnce(activity: Activity, scope: CoroutineScope) {
        val app = activity.applicationContext
        if (!autoCheckDue(app)) return
        // 先盖时间戳再查：这次没查成也不该在这轮里继续追问
        markAutoChecked(app)

        checkForUpdate(
            activity = activity,
            channel = UpdateChannel.read(app),
            scope = scope
        ) { status ->
            if (!activity.alive()) return@checkForUpdate
            when (status) {
                is Status.Available -> {
                    // 30 秒一轮：同一个版本只处理一次，后来的轮次静默等到新构建出现
                    val key = labelOf(status.info)
                    if (key == handledVersion) return@checkForUpdate
                    handledVersion = key
                    if (autoInstallEnabled(app)) {
                        downloadQuietly(activity, status.info, scope)
                    } else {
                        askByPrompt(activity, status.info)
                    }
                }
                // 检查中 / 已是最新 / 失败：自动检查不打扰
                else -> Unit
            }
        }
    }

    /**
     * 静默检查：不弹进度框，结果回 [onStatus]。
     *
     * 新增的重载，[start] 那三个既有签名（「我的」页、关于页在用）保持原样。
     * 重复触发由 [checkRunning] 去重：第二个调用直接返回，不会出现两条提示打架。
     */
    fun checkForUpdate(
        activity: Activity,
        channel: UpdateChannel = UpdateChannel.read(activity),
        scope: CoroutineScope = this.scope,
        onStatus: (Status) -> Unit
    ) {
        if (!checkRunning.compareAndSet(false, true)) return
        // 作用域已经关了就别占着闸门，否则这个进程里再也不会有第二次检查
        if (!scope.isActive) {
            checkRunning.set(false)
            return
        }
        onStatus(Status.Checking)
        scope.launch {
            val status = try {
                val info = UpdateChecker.check(channel)
                if (UpdateChecker.isNewer(info)) Status.Available(info) else Status.Latest(info)
            } catch (e: CancellationException) {
                // 作用域被取消（页面走了）：把闸门放开，否则之后再也不会检查
                checkRunning.set(false)
                throw e
            } catch (e: Exception) {
                Status.Failed(failureReason(e))
            }
            checkRunning.set(false)
            onStatus(status)
        }
    }

    /**
     * 后台下载 + 安装：下载期间只挂一条轻提示（带进度），不挡界面。
     *
     * 下载完成后再交给 [install]，安装顺序与手动更新完全一致；下载失败只给一条提示，
     * 不弹框——手动重试的入口在关于页。
     */
    fun downloadQuietly(
        activity: Activity,
        info: UpdateInfo,
        scope: CoroutineScope = this.scope,
        onStatus: (Status) -> Unit = {}
    ) {
        if (!activity.alive()) return
        val label = labelOf(info)
        UpdatePrompt.show(activity, activity.getString(R.string.update_prompt_downloading, label))
        onStatus(Status.Downloading(info, -1))

        scope.launch {
            val result = try {
                ApkDownloader.download(activity, info) { percent ->
                    onStatus(Status.Downloading(info, percent))
                    if (activity.alive()) {
                        val message = if (percent >= 0) {
                            activity.getString(
                                R.string.update_prompt_downloading_percent,
                                label,
                                percent
                            )
                        } else {
                            activity.getString(R.string.update_prompt_downloading, label)
                        }
                        UpdatePrompt.show(activity, message)
                    }
                }
            } catch (e: CancellationException) {
                UpdatePrompt.dismiss()
                throw e
            } catch (e: Exception) {
                Result.failure<File>(e)
            }

            UpdatePrompt.dismiss()
            val apk = result.getOrNull()
            if (apk == null) {
                val reason = result.exceptionOrNull()?.message
                    ?: activity.getString(R.string.update_prompt_reason_unknown)
                onStatus(Status.Failed(reason))
                if (activity.alive()) {
                    UpdatePrompt.show(
                        activity,
                        activity.getString(R.string.update_prompt_download_failed, reason)
                    )
                }
                return@launch
            }
            onStatus(Status.Installing(info))
            if (activity.alive()) {
                // 安装链路内部已经会把「没 Shizuku / 没 root / 没安装权限」降级成提示；
                // 这里再兜一层 runCatching，异常也只变成一条提示，不让启动流程崩
                runCatching { install(activity, apk) }.onFailure { e ->
                    UpdatePrompt.show(
                        activity,
                        activity.getString(
                            R.string.update_prompt_install_failed,
                            e.message ?: activity.getString(R.string.update_prompt_reason_unknown)
                        )
                    )
                }
            }
        }
    }

    /** 发现新版本时的主题化轻提示：版本号 + 「立即更新 / 稍后」。 */
    private fun askByPrompt(activity: Activity, info: UpdateInfo) {
        UpdatePrompt.show(
            activity = activity,
            message = activity.getString(R.string.update_prompt_available, labelOf(info)),
            actionText = activity.getString(R.string.update_prompt_update_now),
            onAction = { if (activity.alive()) downloadAndInstall(activity, info) },
            laterText = activity.getString(R.string.update_prompt_later)
        )
    }

    // ---------------------------------------------------------------- 节流与开关

    /** 距上次自动检查是否已过 30 秒；系统时间被回拨时也算到期，免得永远不再检查。 */
    private fun autoCheckDue(context: Context): Boolean = runCatching {
        val prefs = context.getSharedPreferences(AUTO_PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_AUTO_CHECK, 0L)
        if (last <= 0L) return@runCatching true
        val now = System.currentTimeMillis()
        now < last || now - last >= AUTO_CHECK_INTERVAL_MILLIS
    }.getOrDefault(true)

    private fun markAutoChecked(context: Context) {
        runCatching {
            context.getSharedPreferences(AUTO_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_AUTO_CHECK, System.currentTimeMillis())
                .apply()
        }
    }

    /**
     * 关于页那个「自动安装」开关：开着就自动下载安装，关着就只提示。
     *
     * 键与 F086Activity 里的 `f086_about/auto_install` 是同一份，开关只写一次、两个入口共用。
     */
    private fun autoInstallEnabled(context: Context): Boolean = runCatching {
        context.getSharedPreferences(UpdateChannel.PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_INSTALL, false)
    }.getOrDefault(false)

    /** Activity 还活着才动界面：检查是异步的，回来时页面可能已经关了。 */
    private fun Activity.alive(): Boolean = !isFinishing && !isDestroyed

    private fun failureReason(e: Throwable): String {
        val failure = (e as? UpdateChecker.CheckException)?.failure ?: UpdateChecker.Failure.UNKNOWN
        return UpdateChecker.failureMessage(failure)
    }

    /**
     * 把一个 [Status] 翻成一句人话，供提示条或页面直接显示。
     *
     * 文案集中在这里，启动提示、关于页以后要接同一套状态时不必各写一份。
     */
    fun statusText(context: Context, status: Status): String = when (status) {
        Status.Checking -> context.getString(R.string.update_prompt_checking)
        is Status.Latest -> context.getString(R.string.update_prompt_up_to_date, labelOf(status.info))
        is Status.Available -> context.getString(R.string.update_prompt_available, labelOf(status.info))
        is Status.Downloading ->
            if (status.percent >= 0) {
                context.getString(
                    R.string.update_prompt_downloading_percent,
                    labelOf(status.info),
                    status.percent
                )
            } else {
                context.getString(R.string.update_prompt_downloading, labelOf(status.info))
            }
        is Status.Installing -> context.getString(R.string.update_prompt_installing, labelOf(status.info))
        is Status.Failed -> context.getString(R.string.update_prompt_failed, status.reason)
    }

    // ---------------------------------------------------------------- 下载与安装

    /** 本地已有 APK 时可直接调用。 */
    fun install(activity: Activity, apk: File, onDone: () -> Unit = {}) {
        clearReceiver(activity)

        showProgress(activity, "安装更新", "正在安装…")
        scope.launch {
            // pm install 会阻塞，必须在 IO 线程；只有最后的系统安装器要回主线程发 Intent。
            val outcome = withContext(Dispatchers.IO) {
                ApkInstaller.install(
                    context = activity,
                    apk = apk,
                    onPending = {},
                    onMessage = { }
                )
            }
            dismissProgress()

            when (outcome) {
                is ApkInstaller.Outcome.Pending -> onDone()
                is ApkInstaller.Outcome.InstalledSilently -> {
                    toast(activity, "已静默安装新版本，请重新打开应用")
                    onDone()
                }
                is ApkInstaller.Outcome.Failed -> {
                    toast(activity, outcome.reason)
                    askOpenPage(activity, onDone)
                }
            }
        }
    }

    // ---------------------------------------------------------------- 流程

    private fun askDownload(activity: Activity, info: UpdateInfo, onDone: () -> Unit) {
        val size = if (info.size > 0) " · %.1f MB".format(info.size / 1048576.0) else ""
        AlertDialog.Builder(activity)
            .setTitle("发现新版本 ${labelOf(info)}$size")
            .setMessage(info.notes.ifBlank { "是否下载并安装？" }.take(800))
            .setPositiveButton("下载并安装") { _, _ -> downloadAndInstall(activity, info, onDone) }
            .setNeutralButton("打开下载页") { _, _ ->
                openUrl(activity, if (info.pageUrl.isNotBlank()) info.pageUrl else DOWNLOAD_PAGE)
                onDone()
            }
            .setNegativeButton("以后再说") { _, _ -> onDone() }
            .show()
    }

    /** 下载并安装一个已确认的更新；关于页的下载入口也走这里。 */
    fun downloadAndInstall(activity: Activity, info: UpdateInfo, onDone: () -> Unit = {}) {
        showProgress(activity, "下载更新", "0%", 0)
        scope.launch {
            val result = ApkDownloader.download(activity, info) { percent ->
                updateProgress(percent)
            }
            dismissProgress()

            val apk = result.getOrNull()
            if (apk == null) {
                toast(activity, "下载失败：${result.exceptionOrNull()?.message ?: "未知错误"}")
                onDone()
                return@launch
            }
            install(activity, apk, onDone)
        }
    }

    private fun askOpenPage(activity: Activity, onDone: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("需要手动安装")
            .setMessage("可以到下载页取最新 APK，或装好 Shizuku 后再试。")
            .setPositiveButton("打开下载页") { _, _ ->
                openUrl(activity, DOWNLOAD_PAGE)
                onDone()
            }
            .setNegativeButton("取消") { _, _ -> onDone() }
            .show()
    }

    private fun openUrl(activity: Activity, url: String) {
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun clearReceiver(activity: Activity) {
        sessionReceiver?.let { runCatching { activity.unregisterReceiver(it) } }
        sessionReceiver = null
    }

    // ---------------------------------------------------------------- 进度

    private fun showProgress(context: Context, title: String, message: String, percent: Int? = null) {
        dismissProgress()
        val bar = ProgressBar(context).apply {
            if (percent == null) {
                isIndeterminate = true
            } else {
                isIndeterminate = false
                max = 100
                progress = percent
            }
        }
        progressBar = bar
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(
                bar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setView(container)
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    private fun updateProgress(percent: Int) {
        progressBar?.isIndeterminate = false
        progressBar?.progress = percent
        dialog?.setMessage("$percent%")
    }

    private fun dismissProgress() {
        runCatching { dialog?.dismiss() }
        dialog = null
        progressBar = null
    }

    private fun toast(context: Context, message: String) {
        CustomToast.show(context, message)
    }
}
