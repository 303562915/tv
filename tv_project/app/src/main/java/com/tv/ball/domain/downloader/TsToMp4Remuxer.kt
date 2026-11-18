package com.tv.ball.domain.downloader

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * TS → MP4 重封装（Remux）
 *
 * 使用 Android 系统自带的 MediaExtractor + MediaMuxer 完成容器转换，
 * 只搬流不重新编码，因此速度接近磁盘拷贝、画质无损，也不需要任何第三方原生库。
 *
 * 适用范围：TS 内的 H.264/H.265 视频 + AAC 音频（M3U8 分片最常见的情况）。
 * 遇到系统 MediaMuxer 不支持的编码时返回 false，由调用方回退到 TS 输出。
 */
object TsToMp4Remuxer {

    private const val TAG = "TsToMp4Remuxer"
    private const val BUFFER_SIZE = 1024 * 1024

    /**
     * 把 TS 文件重封装为 MP4
     *
     * @param tsFile 输入 TS 文件
     * @param mp4File 输出 MP4 文件
     * @return 是否成功；失败时输出文件会被清理
     */
    fun remux(tsFile: File, mp4File: File): Boolean {
        if (!tsFile.exists() || tsFile.length() == 0L) {
            Log.e(TAG, "输入 TS 文件无效: ${tsFile.absolutePath}")
            return false
        }

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        return try {
            mp4File.parentFile?.let { if (!it.exists()) it.mkdirs() }
            if (mp4File.exists()) mp4File.delete()

            extractor = MediaExtractor().apply {
                setDataSource(tsFile.absolutePath)
            }

            muxer = MediaMuxer(
                mp4File.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            // 选择音视频轨道并登记到 muxer
            val trackMap = mutableMapOf<Int, Int>()
            var hasVideo = false

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                val accept = when {
                    mime.startsWith("video/") -> {
                        hasVideo = true
                        true
                    }
                    mime.startsWith("audio/") -> true
                    else -> false
                }
                if (!accept) continue

                try {
                    extractor.selectTrack(i)
                    val muxerTrack = muxer.addTrack(format)
                    trackMap[i] = muxerTrack
                    Log.d(TAG, "轨道 $i ($mime) -> muxer 轨道 $muxerTrack")
                } catch (e: Exception) {
                    Log.w(TAG, "跳过无法封装的轨道 $i ($mime): ${e.message}")
                    try {
                        extractor.unselectTrack(i)
                    } catch (ignored: Exception) {
                        // 忽略
                    }
                }
            }

            if (trackMap.isEmpty() || !hasVideo) {
                Log.e(TAG, "没有可封装的音视频轨道，放弃 MP4 输出")
                muxer.release()
                muxer = null
                if (mp4File.exists()) mp4File.delete()
                return false
            }

            muxer.start()

            val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()
            var sampleCount = 0L

            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break

                val sampleTrack = extractor.sampleTrackIndex
                val muxerTrack = trackMap[sampleTrack]

                if (muxerTrack != null) {
                    bufferInfo.offset = 0
                    bufferInfo.size = size
                    bufferInfo.presentationTimeUs = extractor.sampleTime
                    // MediaExtractor.SAMPLE_FLAG_SYNC(1) 与 MediaCodec.BUFFER_FLAG_KEY_FRAME(1) 对齐
                    bufferInfo.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                    muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                    sampleCount++
                }

                if (!extractor.advance()) break
            }

            muxer.stop()
            muxer.release()
            muxer = null

            extractor.release()
            extractor = null

            if (sampleCount == 0L || !mp4File.exists() || mp4File.length() == 0L) {
                Log.e(TAG, "重封装没有写入任何数据")
                if (mp4File.exists()) mp4File.delete()
                return false
            }

            Log.d(TAG, "重封装成功: 采样 $sampleCount 个, 输出 ${mp4File.length() / 1024 / 1024}MB")
            true

        } catch (e: Exception) {
            Log.e(TAG, "重封装失败: ${e.message}", e)
            try {
                muxer?.release()
            } catch (ignored: Exception) {
                // 忽略
            }
            try {
                extractor?.release()
            } catch (ignored: Exception) {
                // 忽略
            }
            if (mp4File.exists()) mp4File.delete()
            false
        }
    }
}
