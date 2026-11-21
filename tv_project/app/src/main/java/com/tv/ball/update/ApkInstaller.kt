package com.tv.ball.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * 三级安装：
 * 1. 应用内 PackageInstaller（系统安装确认框）
 * 2. 系统安装器意图（FileProvider 共享 APK）
 * 3. Shizuku（相当于 adb shell pm install，可静默安装）
 * 4. root（su pm install）
 *
 * 任何一步失败都往下走，全失败返回 [Outcome.Failed]，由调用方给网页链接。
 */
object ApkInstaller {

    const val REQUEST_SHIZUKU = 4211

    sealed class Outcome {
        /** 交给系统安装器 / PackageInstaller，正在等待用户确认。 */
        object Pending : Outcome()

        /** 用 Shizuku 静默安装完成。 */
        object InstalledByShizuku : Outcome()

        /** Shizuku 不可用或安装失败。 */
        data class Failed(val reason: String) : Outcome()
    }

    // ---------------------------------------------------------------- 权限

    fun canRequestInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openUnknownSourceSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // ---------------------------------------------------------------- 入口

    /**
     * 按顺序尝试安装。
     *
     * @param onPending 前两级已经把安装请求交给系统时回调（此时不应再往下走）
     * @param onMessage 过程提示，用于界面展示（例如「正在用 Shizuku 安装…」）
     */
    fun install(
        context: Context,
        apk: File,
        onPending: () -> Unit,
        onMessage: (String) -> Unit
    ): Outcome {
        // 1) 应用内 PackageInstaller
        if (canRequestInstall(context) && installWithSession(context, apk)) {
            onPending()
            return Outcome.Pending
        }

        // 2) 系统安装器
        if (canRequestInstall(context) && installWithIntent(context, apk)) {
            onPending()
            return Outcome.Pending
        }

        if (!canRequestInstall(context)) {
            onMessage("应用没有「安装未知应用」权限，尝试 Shizuku")
        }

        // 3) Shizuku（adb 同款 pm install）
        if (ShizukuBridge.isAvailable()) {
            if (!ShizukuBridge.hasPermission()) {
                ShizukuBridge.requestPermission(REQUEST_SHIZUKU)
                return Outcome.Failed("需要先授予 Shizuku 权限，授权后再点一次更新")
            }
            onMessage("正在用 Shizuku 安装…")
            if (ShizukuBridge.install(apk.absolutePath)) {
                return Outcome.InstalledByShizuku
            }
        }

        // 4) root
        onMessage("正在用 root 安装…")
        if (RootBridge.install(apk.absolutePath)) {
            return Outcome.InstalledByShizuku
        }

        return Outcome.Failed("系统安装器与 Shizuku 都不可用")
    }

    // ---------------------------------------------------------------- 1. Session

    private fun installWithSession(context: Context, apk: File): Boolean = runCatching {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }
            val action = "${context.packageName}.INSTALL_RESULT"
            val intent = Intent(action).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
        true
    }.getOrDefault(false)

    // ---------------------------------------------------------------- 2. Intent

    private fun installWithIntent(context: Context, apk: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    // ---------------------------------------------------------------- 结果广播

    /** 监听 PackageInstaller 的结果，用于把「用户取消」也当作失败继续往下走。 */
    fun registerSessionResult(context: Context, onResult: (Boolean) -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val status = intent?.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) ?: -1
                val ok = status == PackageInstaller.STATUS_SUCCESS
                runCatching { context.unregisterReceiver(this) }
                onResult(ok)
            }
        }
        val filter = IntentFilter("${context.packageName}.INSTALL_RESULT")
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        return receiver
    }
}