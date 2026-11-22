package com.tv.ball.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.tv.ball.update.shizuku.ShizukuShell
import java.io.File

/**
 * Shizuku 安装通道的薄封装（兼容旧调用点）。
 *
 * 真正的机制在 [ShizukuShell] / [com.tv.ball.update.shizuku.ShellUserService]：
 * Shizuku 13.1.1 起 `Shizuku.newProcess` 已私有，必须走 UserService 才能执行 `pm install`。
 */
object ShizukuBridge {

    private const val UPDATE_CODE = 4211

    /** Shizuku 本体的包名：查「装没装」和跳转授权页都用它。 */
    const val SHIZUKU_PACKAGE = "rikka.shizuku"

    private val shizukuClass: Class<*>? by lazy {
        runCatching { Class.forName("rikka.shizuku.Shizuku") }.getOrNull()
    }

    fun isAvailable(): Boolean {
        val clazz = shizukuClass ?: return false
        return runCatching {
            clazz.getMethod("pingBinder").invoke(null) as? Boolean ?: false
        }.getOrDefault(false)
    }

    fun hasPermission(): Boolean {
        val clazz = shizukuClass ?: return false
        val granted = runCatching {
            clazz.getMethod("checkSelfPermission").invoke(null) as? Int
        }.getOrNull() ?: return false
        return granted == PackageManager.PERMISSION_GRANTED
    }

    fun requestPermission(requestCode: Int = UPDATE_CODE) {
        val clazz = shizukuClass ?: return
        runCatching {
            clazz.getMethod("requestPermission", Int::class.javaPrimitiveType)
                .invoke(null, requestCode)
        }
    }

    /**
     * 设备上装没装 Shizuku 本体。
     *
     * 与 [isAvailable] 不是一回事：[isAvailable] 问的是「服务活着且能用」，装了但没启动、
     * 没授权同样是 false。安装链路要把「没装」和「装了没授权」分开说，所以这里按包名单独查一次。
     */
    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess

    /**
     * 跳到 Shizuku 应用，让用户在那里允许本应用。
     *
     * 只发 `package:rikka.shizuku` 的 `ACTION_VIEW`；包被卸载、没有入口、系统拦了，
     * 全部静默忽略——提示文案已经说过该去哪儿点，跳不过去也不能崩。
     */
    fun openShizukuApp(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("package:$SHIZUKU_PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** 装 apk；成功返回 true。失败原因在 [ShizukuInstaller.install] 里。 */
    fun install(context: Context, apk: File): Boolean =
        ShizukuInstaller.install(context, apk) == null

    /** 兼容旧签名（无 Context），不建议用于安装。 */
    fun install(apk: File): Boolean = false
}