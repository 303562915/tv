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
        // 顺序照参考项目 Canta：先走有特权的安装（Shizuku → root），
        // 装不动再把 APK 交给系统安装器让用户确认。
        // 之前先走 PackageInstaller 会话，被系统拦下来只会拿到 STATUS_FAILURE_BLOCKED，
        // 表现就是「提示没有安装权限」，所以把它降级到最后。

        // 1) Shizuku（adb 同款：先落到 /data/local/tmp 再 pm install）
        if (ShizukuBridge.isAvailable()) {
            if (!ShizukuBridge.hasPermission()) {
                ShizukuBridge.requestPermission(REQUEST_SHIZUKU)
                onMessage("请在 Shizuku 里允许本应用，然后再点一次更新")
                return Outcome.Failed("Shizuku 尚未授权")
            }
            onMessage("正在用 Shizuku 安装…")
            if (ShizukuBridge.install(apk)) return Outcome.InstalledByShizuku
            onMessage("Shizuku 安装失败，改用系统安装器")
        }

        // 2) root 兜底
        if (RootBridge.isAvailable()) {
            onMessage("正在用 root 安装…")
            if (RootBridge.install(apk)) return Outcome.InstalledByShizuku
        }

        // 3) 系统安装器：content:// 交给安装器读，不依赖任何路径权限
        if (installWithIntent(context, apk)) {
            onPending()
            return Outcome.Pending
        }

        // 4) 没有「安装未知应用」权限时，直接把设置页打开，别只报错
        if (!canRequestInstall(context)) {
            openUnknownSourceSettings(context)
            return Outcome.Failed("请在「安装未知应用」里允许本应用，然后再点一次更新")
        }

        return Outcome.Failed("系统安装器与 Shizuku 都没能接手这个 APK")
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