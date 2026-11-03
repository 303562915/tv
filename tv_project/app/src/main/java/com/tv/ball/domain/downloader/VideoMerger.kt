package com.tv.ball.domain.downloader

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.ReturnCode
import java.io.*

/**
 * 视频合并工具
 * 使用 FFmpeg 合并 TS 分片并转换为 MP4
 */
object VideoMerger {
    
    private const val TAG = "VideoMerger"
    private const val BUFFER_SIZE = 1024 * 1024  // 1MB buffer
    
    init {
        // 启用 FFmpeg 详细日志
        FFmpegKitConfig.setLogLevel(Level.AV_LOG_INFO)
        FFmpegKitConfig.enableLogCallback { log ->
            Log.d("FFmpeg", "[${log.level}] ${log.message}")
        }
    }
    
    /**
     * 合并 TS 分片为 MP4（使用 FFmpeg 转换）
     * @param context Android Context
     * @param segmentFiles TS 分片文件列表（按顺序）
     * @param outputFile 输出的 MP4 文件
     * @param skipSegments 要跳过的分片索引
     * @param onProgress 进度回调
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
            Log.d(TAG, "=== 开始合并 TS 文件（使用 FFmpeg）===")
            Log.d(TAG, "总分片数: ${segmentFiles.size}")
            Log.d(TAG, "跳过分片数: ${skipSegments.size}")
            Log.d(TAG, "输出文件: ${outputFile.absolutePath}")
            
            // 确保输出目录存在
            outputFile.parentFile?.let { parent ->
                if (!parent.exists()) {
                    parent.mkdirs()
                    Log.d(TAG, "创建输出目录: ${parent.absolutePath}")
                }
            }
            
            // 步骤 1: 先拼接所有 TS 分片为一个临时 TS 文件
            val tempTsFile = File(outputFile.parent, "${outputFile.nameWithoutExtension}_temp.ts")
            Log.d(TAG, "步骤 1: 拼接 TS 分片到临时文件: ${tempTsFile.absolutePath}")
            
            var outputStream: BufferedOutputStream? = null
            var mergedCount = 0
            var totalBytes = 0L
            
            try {
                outputStream = BufferedOutputStream(FileOutputStream(tempTsFile), BUFFER_SIZE)
                Log.d(TAG, "输出流已创建")
                
                val outStream = outputStream  // Create local reference for smart cast
                
                segmentFiles.forEachIndexed { index, segmentFile ->
                    if (skipSegments.contains(index)) {
                        Log.d(TAG, "跳过分片 $index")
                        return@forEachIndexed
                    }
                    
                    if (!segmentFile.exists()) {
                        Log.w(TAG, "分片 $index 不存在: ${segmentFile.name}")
                        return@forEachIndexed
                    }
                    
                    val fileSize = segmentFile.length()
                    // 检查文件大小，太小的可能是广告或损坏的
                    if (fileSize < 1024) {
                        Log.w(TAG, "分片 $index 太小 ($fileSize 字节)，跳过")
                        return@forEachIndexed
                    }
                    
                    try {
                        // 读取并写入分片
                        val inputStream = BufferedInputStream(FileInputStream(segmentFile), BUFFER_SIZE)
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        var segmentBytes = 0L
                        
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outStream.write(buffer, 0, bytesRead)
                            segmentBytes += bytesRead
                            totalBytes += bytesRead
                        }
                        
                        inputStream.close()
                        mergedCount++
                        
                        // 更新进度（拼接阶段占 50%）
                        val progress = ((index + 1) * 50) / segmentFiles.size
                        onProgress?.invoke(progress)
                        
                        if ((index + 1) % 100 == 0 || index == segmentFiles.size - 1) {
                            Log.d(TAG, "拼接进度: $progress% (${index + 1}/${segmentFiles.size}), 已合并: $mergedCount 个")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "读取分片 $index 失败: ${e.message}", e)
                        // 继续处理下一个分片
                    }
                }
                
                outputStream.flush()
                outputStream.close()
                outputStream = null
                
                Log.d(TAG, "=== TS 拼接完成 ===")
                Log.d(TAG, "成功合并: $mergedCount 个分片")
                Log.d(TAG, "临时 TS 文件大小: ${tempTsFile.length() / 1024 / 1024}MB")
                
                if (mergedCount == 0) {
                    Log.e(TAG, "没有成功合并任何分片")
                    tempTsFile.delete()
                    return false
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "=== TS 拼接失败 ===", e)
                Log.e(TAG, "错误类型: ${e.javaClass.simpleName}")
                Log.e(TAG, "错误信息: ${e.message}")
                e.printStackTrace()
                
                try {
                    outputStream?.close()
                } catch (closeException: Exception) {
                    Log.e(TAG, "关闭输出流失败", closeException)
                }
                
                tempTsFile.delete()
                return false
            }
            
            // 步骤 2: 使用 FFmpeg 将 TS 转换为 MP4
            Log.d(TAG, "步骤 2: 使用 FFmpeg 转换 TS 为 MP4")
            onProgress?.invoke(60)
            
            // 验证临时 TS 文件
            if (!tempTsFile.exists()) {
                Log.e(TAG, "临时 TS 文件不存在: ${tempTsFile.absolutePath}")
                return false
            }
            if (tempTsFile.length() == 0L) {
                Log.e(TAG, "临时 TS 文件为空")
                tempTsFile.delete()
                return false
            }
            Log.d(TAG, "临时 TS 文件验证通过: ${tempTsFile.length() / 1024 / 1024}MB")
            
            // 删除旧的输出文件（如果存在）
            if (outputFile.exists()) {
                outputFile.delete()
                Log.d(TAG, "删除旧的输出文件")
            }
            
            // 尝试多种 FFmpeg 命令策略
            var conversionSuccess = false
            var lastError = ""
            
            val commands = listOf(
                // 命令 1: 标准转换，带 AAC 音频过滤器
                "-i \"${tempTsFile.absolutePath}\" -c copy -bsf:a aac_adtstoasc \"${outputFile.absolutePath}\"",
                // 命令 2: 不使用音频过滤器
                "-i \"${tempTsFile.absolutePath}\" -c copy \"${outputFile.absolutePath}\"",
                // 命令 3: 强制重新编码音频
                "-i \"${tempTsFile.absolutePath}\" -c:v copy -c:a aac -b:a 128k \"${outputFile.absolutePath}\""
            )
            
            for ((index, command) in commands.withIndex()) {
                Log.d(TAG, "尝试 FFmpeg 命令 ${index + 1}/${commands.size}")
                Log.d(TAG, "命令: $command")
                
                onProgress?.invoke(60 + index * 10)
                
                try {
                    val session = FFmpegKit.execute(command)
                    val returnCode = session.returnCode
                    
                    Log.d(TAG, "FFmpeg 返回码: $returnCode")
                    Log.d(TAG, "FFmpeg 输出: ${session.output}")
                    
                    if (ReturnCode.isSuccess(returnCode)) {
                        if (outputFile.exists() && outputFile.length() > 0) {
                            Log.d(TAG, "=== FFmpeg 转换成功（命令 ${index + 1}）===")
                            Log.d(TAG, "输出文件大小: ${outputFile.length() / 1024 / 1024}MB")
                            conversionSuccess = true
                            break
                        } else {
                            Log.w(TAG, "FFmpeg 返回成功但输出文件无效")
                            lastError = "输出文件无效"
                        }
                    } else {
                        Log.w(TAG, "命令 ${index + 1} 失败: 返回码 $returnCode")
                        Log.w(TAG, "错误输出: ${session.allLogsAsString}")
                        lastError = "返回码: $returnCode"
                        
                        // 清理失败的输出文件
                        if (outputFile.exists()) {
                            outputFile.delete()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "执行 FFmpeg 命令 ${index + 1} 时出错: ${e.message}", e)
                    lastError = e.message ?: "未知错误"
                }
            }
            
            if (conversionSuccess) {
                // 清理临时文件
                tempTsFile.delete()
                Log.d(TAG, "已删除临时 TS 文件")
                onProgress?.invoke(100)
                return true
            } else {
                Log.e(TAG, "=== 所有 FFmpeg 转换策略都失败 ===")
                Log.e(TAG, "最后错误: $lastError")
                
                // 作为后备方案，直接重命名 TS 文件为 MP4
                Log.w(TAG, "后备方案：直接使用 TS 文件作为输出")
                try {
                    tempTsFile.renameTo(outputFile)
                    Log.w(TAG, "已将 TS 文件重命名为 MP4（可能兼容性较差）")
                    onProgress?.invoke(100)
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "重命名失败，尝试复制", e)
                    try {
                        tempTsFile.copyTo(outputFile, overwrite = true)
                        tempTsFile.delete()
                        Log.w(TAG, "已复制 TS 文件为 MP4（可能兼容性较差）")
                        onProgress?.invoke(100)
                        return true
                    } catch (e2: Exception) {
                        Log.e(TAG, "复制也失败", e2)
                        tempTsFile.delete()
                        return false
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "=== 合并失败 ===", e)
            Log.e(TAG, "错误类型: ${e.javaClass.simpleName}")
            Log.e(TAG, "错误信息: ${e.message}")
            e.printStackTrace()
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
        Log.e(TAG, "错误：调用了不带 Context 的旧版本方法，无法使用 FFmpeg")
        Log.e(TAG, "请更新调用代码，传入 Context 参数")
        return false
    }
}
