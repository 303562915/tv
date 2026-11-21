package com.tv.ball.update

import android.content.pm.PackageManager
import java.io.File

/**
 * Shizuku 桥（反射调用，避免不同 Shizuku 版本 API 差异导致编译/运行失败）。
 *
 * 安装方式照参考项目 Canta 的做法：**先把 APK 用 shell 写到 shell 能读的地方**
 * （`/data/local/tmp`），再 `pm install`。直接把应用私有缓存路径丢给 `pm install`
 * 一定失败——shell 身份读不到 `/data/data/<pkg>/cache/`。
 */
object ShizukuBridge {

    private const val CLASS_NAME = "rikka.shizuku.Shizuku"
    private const val STAGE_DIR = "/data/local/tmp"

    private val shizukuClass: Class<*>? by lazy {
        runCatching { Class.forName(CLASS_NAME) }.getOrNull()
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

    fun requestPermission(requestCode: Int) {
        val clazz = shizukuClass ?: return
        runCatching {
            clazz.getMethod("requestPermission", Int::class.javaPrimitiveType)
                .invoke(null, requestCode)
        }
    }

    /** 用 Shizuku 装一个本地 APK：先落到 /data/local/tmp，再 pm install。 */
    fun install(apk: File): Boolean {
        if (!isAvailable() || !hasPermission()) return false
        if (!apk.isFile || apk.length() <= 0) return false

        val remote = "$STAGE_DIR/tv_update_${System.currentTimeMillis()}.apk"
        if (!stage(apk, remote)) return false
        val output = shell("pm install -r -t '$remote'")
        shell("rm -f '$remote'")
        return output.contains("Success")
    }

    /** 兼容旧签名：按路径装（内部会先 stage 到 shell 可读路径）。 */
    fun install(apkPath: String): Boolean = install(File(apkPath))

    // ---------------------------------------------------------------- shell

    private fun stage(file: File, remote: String): Boolean {
        val process = newProcess(arrayOf("sh", "-c", "cat > '$remote'")) ?: return false
        return runCatching {
            process.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
            process.waitFor() == 0
        }.getOrDefault(false)
    }

    private fun shell(command: String): String {
        val process = newProcess(arrayOf("sh", "-c", command)) ?: return ""
        return runCatching {
            val out = process.inputStream.bufferedReader().use { it.readText() }
            val err = process.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            process.waitFor()
            out + err
        }.getOrDefault("")
    }

    /** `Shizuku.newProcess(cmd, env, dir)`，反射调用以兼容各版本。 */
    private fun newProcess(command: Array<String>): Process? {
        val clazz = shizukuClass ?: return null
        return runCatching {
            clazz.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).invoke(null, command, null, null) as? Process
        }.getOrNull()
    }
}

/** root 兜底：同样先落到 /data/local/tmp，再 `su -c pm install`。 */
object RootBridge {

    private const val STAGE_DIR = "/data/local/tmp"

    fun isAvailable(): Boolean = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val out = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        out.contains("uid=0")
    }.getOrDefault(false)

    fun install(apk: File): Boolean {
        if (!isAvailable() || !apk.isFile) return false
        val remote = "$STAGE_DIR/tv_update_${System.currentTimeMillis()}.apk"
        val staged = runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat > '$remote'"))
            process.outputStream.use { out -> apk.inputStream().use { it.copyTo(out) } }
            process.waitFor() == 0
        }.getOrDefault(false)
        if (!staged) return false

        val ok = runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "pm install -r -t '$remote'"))
            val out = process.inputStream.bufferedReader().use { it.readText() }
            val err = process.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            process.waitFor()
            (out + err).contains("Success")
        }.getOrDefault(false)

        runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", "rm -f '$remote'")).waitFor() }
        return ok
    }

    /** 兼容旧签名。 */
    fun install(apkPath: String): Boolean = install(File(apkPath))
}