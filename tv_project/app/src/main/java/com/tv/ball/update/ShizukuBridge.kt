package com.tv.ball.update

import android.content.Context
import android.content.pm.PackageManager
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

    /** 装 apk；成功返回 true。失败原因在 [ShizukuInstaller.install] 里。 */
    fun install(context: Context, apk: File): Boolean =
        ShizukuInstaller.install(context, apk) == null

    /** 兼容旧签名（无 Context），不建议用于安装。 */
    fun install(apk: File): Boolean = false
}