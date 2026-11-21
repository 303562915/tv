package com.tv.ball.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 下载 APK 到应用私有缓存，返回可直接用于安装的文件。 */
object ApkDownloader {

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun targetFile(context: Context, info: UpdateInfo): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val name = "TVPlayer-${info.versionName.ifBlank { "new" }}.apk"
        dir.listFiles()?.forEach { if (it.isFile && it.name != name) it.delete() }
        return File(dir, name)
    }

    /**
     * @param onProgress 0..100，未知总长时按 -1 回调
     */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val file = targetFile(context, info)
            val request = Request.Builder().url(info.apkUrl).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("空响应")
                val total = body.contentLength().takeIf { it > 0 } ?: info.size
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var sum = 0L
                        var lastPercent = -1
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            sum += read
                            if (total > 0) {
                                val percent = (sum * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    withContext(Dispatchers.Main) { onProgress(percent) }
                                }
                            }
                        }
                    }
                }
            }
            if (info.size > 0 && file.length() != info.size) {
                error("文件大小不符：期望 ${info.size}，实际 ${file.length()}")
            }
            if (info.sha256.isNotBlank()) {
                val actual = sha256(file)
                if (!actual.equals(info.sha256, ignoreCase = true)) {
                    error("SHA-256 校验失败")
                }
            }
            file
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}