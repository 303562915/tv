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
import com.tv.ball.util.CustomToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「检查更新 → 下载 → 安装」的完整编排，界面用纯代码弹窗，不依赖布局文件。
 *
 * 安装顺序：PackageInstaller / 系统安装器 → Shizuku → root → 网页。
 */
object UpdateManager {

    private const val DOWNLOAD_PAGE = "https://303562915.github.io/tv/"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var dialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var sessionReceiver: BroadcastReceiver? = null

    // ---------------------------------------------------------------- 入口

    fun start(activity: Activity, onDone: () -> Unit = {}) {
        showProgress(activity, "检查更新", "正在读取最新版本…")

        scope.launch {
            val result = UpdateChecker.check()
            dismissProgress()

            val info = result.getOrNull()
            if (info == null) {
                toast(activity, "检查更新失败，请稍后再试")
                onDone()
                return@launch
            }
            if (!UpdateChecker.isNewer(info)) {
                toast(activity, "已经是最新版本（${BuildConfig.VERSION_NAME}）")
                onDone()
                return@launch
            }
            askDownload(activity, info, onDone)
        }
    }

    /** 本地已有 APK 时可直接调用。 */
    fun install(activity: Activity, apk: File, onDone: () -> Unit = {}) {
        clearReceiver(activity)

        val outcome = ApkInstaller.install(
            context = activity,
            apk = apk,
            onPending = {
                sessionReceiver = ApkInstaller.registerSessionResult(activity) { ok ->
                    sessionReceiver = null
                    if (ok) {
                        onDone()
                    } else {
                        toast(activity, "系统安装未完成，改用 Shizuku 安装")
                        fallbackToShizuku(activity, apk, onDone)
                    }
                }
            },
            onMessage = { toast(activity, it) }
        )

        when (outcome) {
            is ApkInstaller.Outcome.Pending -> Unit
            is ApkInstaller.Outcome.InstalledByShizuku -> {
                toast(activity, "已安装新版本，请重新打开应用")
                onDone()
            }
            is ApkInstaller.Outcome.Failed -> {
                toast(activity, outcome.reason)
                askOpenPage(activity, onDone)
            }
        }
    }

    // ---------------------------------------------------------------- 流程

    private fun askDownload(activity: Activity, info: UpdateInfo, onDone: () -> Unit) {
        val size = if (info.size > 0) " · %.1f MB".format(info.size / 1048576.0) else ""
        AlertDialog.Builder(activity)
            .setTitle("发现新版本 ${info.versionName}$size")
            .setMessage(info.notes.ifBlank { "是否下载并安装？" }.take(800))
            .setPositiveButton("下载并安装") { _, _ -> downloadAndInstall(activity, info, onDone) }
            .setNeutralButton("打开下载页") { _, _ ->
                openUrl(activity, if (info.pageUrl.isNotBlank()) info.pageUrl else DOWNLOAD_PAGE)
                onDone()
            }
            .setNegativeButton("以后再说") { _, _ -> onDone() }
            .show()
    }

    private fun downloadAndInstall(activity: Activity, info: UpdateInfo, onDone: () -> Unit) {
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

    private fun fallbackToShizuku(activity: Activity, apk: File, onDone: () -> Unit) {
        if (ShizukuBridge.isAvailable() && !ShizukuBridge.hasPermission()) {
            ShizukuBridge.requestPermission(ApkInstaller.REQUEST_SHIZUKU)
            toast(activity, "请在 Shizuku 里授权本应用，然后再点一次检查更新")
            onDone()
            return
        }

        showProgress(activity, "安装更新", "正在通过 Shizuku / root 安装…")
        scope.launch {
            val installed = withContext(Dispatchers.IO) {
                (ShizukuBridge.isAvailable() && ShizukuBridge.install(apk.absolutePath)) ||
                    RootBridge.install(apk.absolutePath)
            }
            dismissProgress()
            if (installed) {
                toast(activity, "已通过 Shizuku / root 安装，请重新打开应用")
                onDone()
            } else {
                toast(activity, "自动安装失败，请手动安装")
                askOpenPage(activity, onDone)
            }
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