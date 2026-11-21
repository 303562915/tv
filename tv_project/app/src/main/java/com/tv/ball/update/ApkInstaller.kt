package com.tv.ball.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * 三级安装（顺序照参考项目 Canta：先特权、后对话框）：
 *
 * 1. **Shizuku**（UserService 里的 `pm install`，可静默）—— 默认优先；
 * 2. **root**（su pm install）；
 * 3. **系统安装器**（FileProvider + ACTION_VIEW），用户确认一次；
 * 4. 都没有「安装未知应用」权限时，直接打开授权页。
 *
 * 不再使用 PackageInstaller 会话：无权限时它只会返回 STATUS_FAILURE_BLOCKED，
 * 表现就是「提示没有安装权限」而卡死。
 */
object ApkInstaller {

    const val REQUEST_SHIZUKU = 4211

    sealed class Outcome {
        object Pending : Outcome()
        object InstalledSilently : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    fun canRequestInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun openUnknownSourceSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun install(
        context: Context,
        apk: File,
        onPending: () -> Unit,
        onMessage: (String) -> Unit
    ): Outcome {
        // 1) Shizuku：默认优先，静默装
        if (ShizukuBridge.isAvailable()) {
            if (ShizukuBridge.hasPermission()) {
                onMessage("正在用 Shizuku 安装…")
                val failure = ShizukuInstaller.install(context, apk)
                if (failure == null) return Outcome.InstalledSilently
                onMessage("Shizuku 安装失败：${failure.take(120)}")
            } else {
                ShizukuBridge.requestPermission(REQUEST_SHIZUKU)
                onMessage("请在 Shizuku 里允许本应用（已发起授权）")
            }
        }

        // 2) root 兜底
        if (RootBridge.isAvailable()) {
            onMessage("正在用 root 安装…")
            if (RootBridge.install(apk)) return Outcome.InstalledSilently
        }

        // 3) 系统安装器
        if (installWithIntent(context, apk)) {
            onPending()
            return Outcome.Pending
        }

        // 4) 没有授权就带用户去打开
        if (!canRequestInstall(context)) {
            openUnknownSourceSettings(context)
            return Outcome.Failed("请在「安装未知应用」里允许本应用，然后再点一次更新")
        }

        return Outcome.Failed("没有能接手这个 APK 的安装器")
    }

    private fun installWithIntent(context: Context, apk: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}