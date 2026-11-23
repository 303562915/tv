package com.tv.ball.update

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import com.tv.ball.update.shizuku.ShizukuShell
import java.io.File
import kotlin.math.min

/**
 * Shizuku 安装通道（照参考项目 Canta 的 `installUpdate`）：
 *
 * 1. 直接 `pm install -r -d <缓存路径>`（有 root / shell 合并存储时可读）；
 * 2. 失败再 stage 到公共下载目录（MediaStore），用真实文件路径装；
 * 3. 还失败就把 APK 分块 base64 推到 `/data/local/tmp`，校验字节数后装。
 *
 * 关键点：**不用 `Shizuku.newProcess`**（13.1.1 起已私有），走 UserService 的 shell。
 */
object ShizukuInstaller {

    private const val CLASS_NAME = "rikka.shizuku.Shizuku"
    private const val STAGE_DIR = "/data/local/tmp"
    private const val STAGE_BASE = "$STAGE_DIR/.tvplayer-update"
    private const val CHUNK = 90_000
    private const val UPDATE_CODE = 4211

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

    fun requestPermission() {
        val clazz = shizukuClass ?: return
        runCatching {
            clazz.getMethod("requestPermission", Int::class.javaPrimitiveType)
                .invoke(null, UPDATE_CODE)
        }
    }

    /** 用 Shizuku 装 apk；成功返回 null，失败返回可展示的原因。 */
    fun install(context: Context, apk: File): String? {
        if (!isAvailable()) return "Shizuku 未运行"
        if (!hasPermission()) {
            requestPermission()
            return "请在 Shizuku 里允许本应用，然后再点一次更新"
        }
        if (!apk.isFile || apk.length() <= 0) return "APK 文件不存在"

        val shell = ShizukuShell(context)

        // 1) 直接路径
        pmInstall(shell, apk.absolutePath)?.let { first ->
            // 2) 公共下载目录
            stageInDownloads(context, apk)?.let { staged ->
                pmInstall(shell, staged)?.let {
                    // 3) /data/local/tmp 分块推送
                    return installViaTmp(shell, apk) ?: return null
                }
                return null
            }
            return installViaTmp(shell, apk) ?: return null
        }
        return null
    }

    private fun pmInstall(shell: ShizukuShell, where: String): String? {
        val result = shell.run("pm install -r -d '${where.replace("'", "'\\''")}'")
        return when {
            result.ok && !result.output.contains("Failure", ignoreCase = true) -> null
            result.output.isBlank() -> "pm install 没有输出（exit ${result.exitCode}）"
            else -> result.output.trim()
        }
    }

    /**
     * 复制到 Download/TVPlayer/ 并拿真实文件路径（Android 10+ 需要 MediaStore）。
     *
     * 不只是 Shizuku 通道在用：Dhizuku 的服务器进程跑在自己的 uid 下，读不到本应用的私有缓存，
     * 所以它也要先把 APK 暂存到公共下载目录，拿真实路径再去 `pm install`。
     */
    internal fun stageInDownloads(context: Context, apk: File): String? = runCatching {
        val resolver = context.contentResolver
        val collection = android.provider.MediaStore.Downloads
            .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val folder = "Download/TVPlayer/"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, apk.name)
            put(
                android.provider.MediaStore.MediaColumns.MIME_TYPE,
                "application/vnd.android.package-archive"
            )
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, folder)
        }
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri, "wt")?.use { out ->
            apk.inputStream().use { it.copyTo(out) }
        } ?: return null
        resolver.query(
            uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    /** 分块 base64 推到 /data/local/tmp，校验字节数后安装，最后清理。 */
    private fun installViaTmp(shell: ShizukuShell, apk: File): String? {
        shell.run("rm -f '$STAGE_BASE.b64' '$STAGE_BASE.apk'")
        val encoded = Base64.encodeToString(apk.readBytes(), Base64.NO_WRAP)
        var offset = 0
        while (offset < encoded.length) {
            val end = min(offset + CHUNK, encoded.length)
            val chunk = encoded.substring(offset, end).replace("'", "'\\''")
            val run = shell.run("printf %s '$chunk' >> '$STAGE_BASE.b64'")
            if (!run.ok) {
                shell.run("rm -f '$STAGE_BASE.b64'")
                return "推送到 /data/local/tmp 失败（偏移 $offset）：${run.output.trim().take(160)}"
            }
            offset = end
        }
        val verify = shell.run(
            "base64 -d '$STAGE_BASE.b64' > '$STAGE_BASE.apk' && rm -f '$STAGE_BASE.b64' && " +
                "test \"\$(wc -c < '$STAGE_BASE.apk')\" -eq ${apk.length()} && echo ok"
        )
        if (!verify.ok || !verify.output.contains("ok")) {
            shell.run("rm -f '$STAGE_BASE.b64' '$STAGE_BASE.apk'")
            return "暂存的 APK 校验失败：${verify.output.trim().take(160)}"
        }
        val failure = pmInstall(shell, "$STAGE_BASE.apk")
        shell.run("rm -f '$STAGE_BASE.apk'")
        return failure
    }
}