package com.tv.ball.update

import android.content.pm.PackageManager

/**
 * Shizuku 桥（用反射调用，避免不同 Shizuku 版本 API 差异导致编译/运行失败）。
 *
 * 需要：
 * - `dev.rikka.shizuku:api` + `dev.rikka.shizuku:provider` 依赖
 * - AndroidManifest 注册 `rikka.shizuku.ShizukuProvider`
 * - 用户装过 Shizuku 并授权本应用
 */
object ShizukuBridge {

    private const val CLASS_NAME = "rikka.shizuku.Shizuku"

    private val shizukuClass: Class<*>? by lazy {
        runCatching { Class.forName(CLASS_NAME) }.getOrNull()
    }

    /** Shizuku 服务是否在运行（取决于 Shizuku 自身是否启动）。 */
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

    fun requestPermission(requestCode: Int) {
        val clazz = shizukuClass ?: return
        runCatching {
            clazz.getMethod("requestPermission", Int::class.javaPrimitiveType)
                .invoke(null, requestCode)
        }
    }

    /**
     * 用 Shizuku 执行 `pm install -r -t <path>`，等价于 `adb install -r`。
     * 返回 true 表示输出里出现 Success。
     */
    fun install(apkPath: String): Boolean {
        val clazz = shizukuClass ?: return false
        val process = runCatching {
            clazz.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).invoke(null, arrayOf("pm", "install", "-r", "-t", apkPath), null, null) as? Process
        }.getOrNull() ?: return false

        return runCatching {
            val out = process.inputStream.bufferedReader().use { it.readText() }
            val err = process.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            process.waitFor()
            (out + err).contains("Success")
        }.getOrDefault(false)
    }
}

/** root 兜底：su -c pm install。没有 root 时不会抛异常。 */
object RootBridge {

    fun isAvailable(): Boolean = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val out = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        out.contains("uid=0")
    }.getOrDefault(false)

    fun install(apkPath: String): Boolean {
        if (!isAvailable()) return false
        return runCatching {
            val process = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "pm install -r -t \"$apkPath\"")
            )
            val out = process.inputStream.bufferedReader().use { it.readText() }
            val err = process.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            process.waitFor()
            (out + err).contains("Success")
        }.getOrDefault(false)
    }
}