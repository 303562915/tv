package com.tv.ball.domain.downloader

import android.content.Context
import android.util.Log
import java.io.*

/**
 * 视频合并工具
 *
 * M3U8 分片（TS）按顺序做字节级拼接，然后用系统 MediaMuxer 重封装为 MP4。
 *
 * 说明：早期版本依赖 FFmpegKit 做 TS→MP4 转封装，但 FFmpegKit 已停止维护、
 * 制品也从 Maven Central 下架，因此改用 Android 自带的 MediaExtractor + MediaMuxer
 * （只搬流不重编码，速度与画质都无损）。系统不支持该编码组合时回退为 TS 输出。
 */
object VideoMerger {

    private const val TAG = "VideoMerger"
    private const val BUFFER_SIZE = 1024 * 1024  // 1MB buffer

    /**
     * 合并 TS 分片
     *
     * @param context Android Context（保留以兼容既有调用方）
     * @param segmentFiles TS 分片文件列表（按顺序）
     * @param outputFile 输出文件（建议以 .ts 结尾）
     * @param skipSegments 要跳过的分片索引
     * @param onProgress 进度回调（0~100）
     * @return 是否成功
     */
    fun mergeTsToMp4(
        context: Context,
        segmentFiles: List<File>,
        outputFile: File,
        skipSegments: Set<Int> = emptySet(),
        onProgress: ((Int) -> Unit)? = null
    ): Boolean {
        try {
            Log.d(TAG, "=== 开始合并 TS 分片 ===")
            Log.d(TAG, "总分片数: ${segmentFiles.size}")
            Log.d(TAG, "跳过分片数: ${skipSegments.size}")
            Log.d(TAG, "输出文件: ${outputFile.absolutePath}")

            outputFile.parentFile?.let { parent ->
                if (!parent.exists()) parent.mkdirs()
            }

            val validSegments = segmentFiles.filterIndexed { index, file ->
                index !in skipSegments && file.exists() && file.length() > 0
            }

            if (validSegments.isEmpty()) {
                Log.e(TAG, "没有可用的 TS 分片")
                return false
            }

            if (outputFile.exists()) {
                outputFile.delete()
            }

            // 先拼接到临时 TS，再重封装为 MP4
            val tempTsFile = File(outputFile.parent, "${outputFile.nameWithoutExtension}_temp.ts")
            if (tempTsFile.exists()) tempTsFile.delete()

            var written = 0L
            FileOutputStream(tempTsFile, /* append = */ true).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                validSegments.forEachIndexed { index, segment ->
                    FileInputStream(segment).use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                        }
                    }
                    // 拼接阶段占 0~80%，重封装占 80~100%
                    onProgress?.invoke(((index + 1) * 80 / validSegments.size).coerceIn(0, 79))
                }
                output.flush()
            }

            if (!tempTsFile.exists() || tempTsFile.length() == 0L) {
                Log.e(TAG, "临时 TS 文件无效")
                tempTsFile.delete()
                return false
            }

            // 用系统 MediaMuxer 重封装为 MP4；不支持时回退为 TS 输出
            val remuxed = TsToMp4Remuxer.remux(tempTsFile, outputFile)
            if (remuxed) {
                tempTsFile.delete()
            } else {
                Log.w(TAG, "MP4 重封装失败，回退为 TS 输出")
                if (outputFile.exists()) outputFile.delete()
                try {
                    tempTsFile.renameTo(outputFile)
                } catch (e: Exception) {
                    tempTsFile.copyTo(outputFile, overwrite = true)
                    tempTsFile.delete()
                }
            }

            if (!outputFile.exists() || outputFile.length() == 0L) {
                Log.e(TAG, "输出文件无效")
                return false
            }

            Log.d(TAG, "=== 合并完成 ===")
            Log.d(TAG, "写入字节: $written")
            Log.d(TAG, "输出大小: ${outputFile.length() / 1024 / 1024}MB")
            onProgress?.invoke(100)
            return true

        } catch (e: Exception) {
            Log.e(TAG, "=== 合并失败 ===", e)
            Log.e(TAG, "错误类型: ${e.javaClass.simpleName}")
            Log.e(TAG, "错误信息: ${e.message}")
            return false
        }
    }

    /**
     * 兼容旧版本的方法签名（不带 Context）
     * @deprecated 请使用带 Context 参数的版本
     */
    @Deprecated("Use mergeTsToMp4 with Context parameter")
    fun mergeTsToMp4(
        segmentFiles: List<File>,
        outputFile: File,
        skipSegments: Set<Int> = emptySet(),
        onProgress: ((Int) -> Unit)? = null
    ): Boolean {
        Log.e(TAG, "请使用带 Context 参数的 mergeTsToMp4")
        return false
    }
}
