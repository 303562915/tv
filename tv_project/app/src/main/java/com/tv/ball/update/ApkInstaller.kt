package com.tv.ball.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * 自动安装的通道顺序（与关于页「权限来源」卡片同一份定义，见 [PrivilegeSources]）：
 *
 * 1. **root**（面具 / KernelSU / APatch）—— `su -c pm install`，最优先；
 * 2. **Shizuku / Stellar** —— UserService 里的 `pm install`；装了但没授权就**当场申请授权**，
 *    用户点了「允许」立刻接着静默装完，不用再点一次更新；
 * 3. **Dhizuku** —— 设备所有者权限，服务器进程里跑 `pm install`；没授权就拉起它的授权页；
 * 4. **自带安装器**（系统安装器 + FileProvider）—— 最后的保底，弹一次系统确认；
 * 5. 连「安装未知应用」都没开时，直接打开授权页。
 *
 * 任何一条通道失败都只把小命交给下一条，绝不把整次更新卡死在某一条上。
 * 不再使用 PackageInstaller 会话：无权限时它只会返回 STATUS_FAILURE_BLOCKED，
 * 表现就是「提示没有安装权限」而卡死。
 */
object ApkInstaller {

    const val REQUEST_SHIZUKU = 4211

    /** 申请 Shizuku 授权后等用户点弹窗的上限；超时就继续往下走。 */
    private const val AUTH_TIMEOUT_MS = 15_000L

    /**
     * 通道不可用时必须说清是哪一种，「静默通道没走成」对用户没有信息量：
     * 没装 → 告诉他能装，装了没授权 → 已经替他去申请。
     */
    const val MSG_SHIZUKU_NOT_INSTALLED = "未检测到 Shizuku / Stellar，装上可免弹窗安装更新"
    const val MSG_SHIZUKU_NOT_AUTHORIZED = "Shizuku 已安装但未授权，正在申请授权…"
    const val MSG_SHIZUKU_DENIED = "Shizuku 授权没给，这次先用别的通道装"

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
        if (!apk.isFile || apk.length() <= 0) return Outcome.Failed("更新包不存在")

        // 1) root：最高优先级，su 里直接装
        if (RootBridge.isAvailable()) {
            onMessage("正在用 root 安装…")
            if (RootBridge.install(apk)) return Outcome.InstalledSilently
            onMessage("root 没装成功，换下一条通道…")
        }

        // 2) Shizuku / Stellar：同一个 binder，展示名按装的是哪个来
        val shell = shellLabel(context)
        if (ShizukuBridge.isAvailable() && ShizukuBridge.hasPermission()) {
            onMessage("正在用 $shell 安装…")
            val failure = ShizukuInstaller.install(context, apk)
            if (failure == null) return Outcome.InstalledSilently
            onMessage("$shell 安装失败：${failure.take(120)}")
        } else if (ShizukuBridge.isInstalled(context)) {
            // 装了但没授权：先申请，等到用户点完弹窗再接着装
            onMessage(MSG_SHIZUKU_NOT_AUTHORIZED)
            if (ShizukuBridge.requestPermissionBlocking(REQUEST_SHIZUKU, AUTH_TIMEOUT_MS)) {
                onMessage("已授权，正在用 $shell 安装…")
                val failure = ShizukuInstaller.install(context, apk)
                if (failure == null) return Outcome.InstalledSilently
                onMessage("$shell 安装失败：${failure.take(120)}")
            } else {
                // 拒绝了或者超时：不再强拉 Shizuku 前台（用户可能正看着安装弹窗），
                // 只留一句提示；想手动授权可以点关于页「权限来源」里那一行
                onMessage(MSG_SHIZUKU_DENIED)
            }
        } else {
            // 没装：提示一句就够了，继续往下走 Dhizuku / 系统安装器
            onMessage(MSG_SHIZUKU_NOT_INSTALLED)
        }

        // 3) Dhizuku：设备所有者权限
        if (DhizukuBridge.isInstalled(context)) {
            if (DhizukuBridge.isConnected(context) && DhizukuBridge.isPermissionGranted(context)) {
                onMessage("正在用 Dhizuku 安装…")
                val failure = DhizukuBridge.install(context, apk)
                if (failure == null) return Outcome.InstalledSilently
                onMessage("Dhizuku 安装失败：${failure.take(120)}")
            } else if (DhizukuBridge.isConnected(context)) {
                DhizukuBridge.requestPermission(context)
                onMessage("Dhizuku 未授权，已在 Dhizuku 里申请")
            }
        }

        // 4) 自带安装器（保底）
        if (installWithIntent(context, apk)) {
            onPending()
            return Outcome.Pending
        }

        // 5) 没有授权就带用户去打开
        if (!canRequestInstall(context)) {
            openUnknownSourceSettings(context)
            return Outcome.Failed("请在「安装未知应用」里允许本应用，然后再点一次更新")
        }

        return Outcome.Failed("没有能接手这个 APK 的安装器")
    }

    /** 现在提供 binder 的是谁：Shizuku 优先，其次 Stellar。 */
    private fun shellLabel(context: Context): String = when {
        ShizukuBridge.isPackageInstalled(context, ShizukuBridge.SHIZUKU_PACKAGE) -> "Shizuku"
        ShizukuBridge.isPackageInstalled(context, ShizukuBridge.STELLAR_PACKAGE) -> "Stellar"
        else -> "Shizuku"
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
