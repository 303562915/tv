package com.tv.ball.update

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 下载 APK 到应用私有缓存，返回可直接用于安装的文件。 */
object ApkDownloader {

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // ---------------------------------------------------------------- 进度

    /**
     * 下载进度：字节级的「已下载 / 总大小」。
     *
     * 原来只回调一个 0..100 的整数，界面上写不出「1.2 MB / 12.4 MB」这种人在意的数字；
     * 现在把两个字节数都带出去，百分比由 UI 自己按 [percent] 取，不用再猜。
     */
    data class Progress(val downloaded: Long, val total: Long) {

        /** 0..100；服务器没给总大小（[total] <= 0）时是 -1。 */
        val percent: Int
            get() = if (total > 0L) {
                ((downloaded * 100L) / total).coerceIn(0L, 100L).toInt()
            } else {
                -1
            }

        /** 「1.2 MB / 12.4 MB」；总量未知时只写已下载部分。 */
        fun sizeText(): String = if (total > 0L) {
            "${ApkDownloader.formatBytes(downloaded)} / ${ApkDownloader.formatBytes(total)}"
        } else {
            ApkDownloader.formatBytes(downloaded)
        }
    }

    /** 失败类型：界面只按这几类给一句人话，不把状态码和堆栈丢给用户。 */
    enum class FailureKind {
        /** 连不上、HTTP 非 2xx、响应体空 */
        NETWORK,

        /** 体积不符 / SHA-256 对不上 */
        VERIFY,

        /** 写不进缓存目录（空间、权限） */
        STORAGE,

        /** 其它 */
        UNKNOWN
    }

    /** 带类型的下载异常：[reasonOf] 靠它把失败翻成「网络失败 / 校验失败」这类短句。 */
    class DownloadException(
        val kind: FailureKind,
        message: String,
        cause: Throwable? = null
    ) : IOException(message, cause)

    /** 失败原因的一句话（网络失败 / 校验失败 / 写入缓存目录失败 / 未知错误）。 */
    fun reasonOf(error: Throwable?): String = when {
        error is DownloadException -> when (error.kind) {
            FailureKind.NETWORK -> "网络失败"
            FailureKind.VERIFY -> "校验失败"
            FailureKind.STORAGE -> "写入缓存目录失败"
            FailureKind.UNKNOWN -> error.message?.take(80) ?: "未知错误"
        }

        error is IOException -> "网络失败"
        else -> error?.message?.take(80) ?: "未知错误"
    }

    /** 字节数 → 「12.4 MB」；进度行和缓存占用共用同一套口径。 */
    fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    // ---------------------------------------------------------------- 入口

    /**
     * 老接口：只要百分比。
     *
     * 「我的」页与提示条在用它，签名不动；内部转调 [downloadDetailed]，
     * 行为（只在百分比变化时回调、回到主线程）与之前完全一致。
     */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit
    ): Result<File> = downloadDetailed(context, info) { progress ->
        onProgress(progress.percent)
    }

    /**
     * 新接口：把「已下载 / 总大小」原样带出去，供关于页与设置页的进度条使用。
     *
     * 回调固定在主线程（下载本身在 [Dispatchers.IO]）；失败一律是 [DownloadException]，
     * 调用方用 [reasonOf] 取短原因，不用解析 HTTP 状态码。
     */
    suspend fun downloadDetailed(
        context: Context,
        info: UpdateInfo,
        onProgress: (Progress) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            Result.success(downloadBlocking(context, info, onProgress))
        } catch (e: CancellationException) {
            throw e
        } catch (e: DownloadException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(DownloadException(FailureKind.NETWORK, e.message ?: "网络失败", e))
        } catch (e: Exception) {
            Result.failure(DownloadException(FailureKind.UNKNOWN, e.message ?: "未知错误", e))
        }
    }

    private suspend fun downloadBlocking(
        context: Context,
        info: UpdateInfo,
        onProgress: (Progress) -> Unit
    ): File {
        val file = targetFile(context, info)
        val request = Request.Builder().url(info.apkUrl).build()

        // 连接与读响应头：失败就是网络问题
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw DownloadException(FailureKind.NETWORK, e.message ?: "连接失败", e)
        }

        response.use { result ->
            if (!result.isSuccessful) {
                throw DownloadException(FailureKind.NETWORK, "HTTP ${result.code}")
            }
            val body = result.body ?: throw DownloadException(FailureKind.NETWORK, "服务器没有返回内容")
            val total = body.contentLength().takeIf { it > 0 } ?: info.size

            // 落盘这一段单独归类：空间不够、目录不可写都不该报成网络失败
            try {
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var sum = 0L
                        var lastPercent = -1
                        var lastReportedBytes = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            sum += read
                            val progress = Progress(sum, total)
                            // 有总大小就按百分比节流；不知道总大小时每 256 KB 报一次，
                            // 免得几百字节就切一次主线程
                            val due = if (total > 0L) {
                                progress.percent != lastPercent
                            } else {
                                sum - lastReportedBytes >= 256L * 1024L
                            }
                            if (due) {
                                lastPercent = progress.percent
                                lastReportedBytes = sum
                                withContext(Dispatchers.Main) { onProgress(progress) }
                            }
                        }
                        // 收尾补一发：最后一段往往不到 1%，不给的话进度条停在 99%
                        if (sum != lastReportedBytes) {
                            withContext(Dispatchers.Main) { onProgress(Progress(sum, total)) }
                        }
                    }
                }
            } catch (e: IOException) {
                throw DownloadException(FailureKind.STORAGE, e.message ?: "写入失败", e)
            }
        }

        if (info.size > 0 && file.length() != info.size) {
            throw DownloadException(
                FailureKind.VERIFY,
                "文件大小不符：期望 ${info.size}，实际 ${file.length()}"
            )
        }
        if (info.sha256.isNotBlank()) {
            // 读校验失败按「写盘/读盘」算，不冒充网络失败
            val actual = try {
                sha256(file)
            } catch (e: IOException) {
                throw DownloadException(FailureKind.STORAGE, e.message ?: "读取文件失败", e)
            }
            if (!actual.equals(info.sha256, ignoreCase = true)) {
                throw DownloadException(FailureKind.VERIFY, "SHA-256 校验失败")
            }
        }
        return file
    }

    private fun targetFile(context: Context, info: UpdateInfo): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val name = "TVPlayer-${info.versionName.ifBlank { "new" }}.apk"
        dir.listFiles()?.forEach { if (it.isFile && it.name != name) it.delete() }
        return File(dir, name)
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