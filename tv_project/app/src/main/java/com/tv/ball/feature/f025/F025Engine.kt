package com.tv.ball.feature.f025

import android.content.ContentResolver
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * F025 媒体信息解析的真实能力层。
 *
 * 两条系统 API 分工：
 * - [MediaExtractor] 负责「解复用」：轨道数、每轨 mime、分辨率、帧率、采样率、色域/传输特性；
 * - [MediaMetadataRetriever] 负责「总览」：码率、时长、旋转角、容器 MIME。
 *
 * 任何一边取不到的字段保持 null，由 UI 统一渲染成「未知」——不做猜测性兜底。
 * 对外只暴露挂起接口，内部切换 [Dispatchers.IO]，调用方拿到的结果直接上主线程。
 */
object F025Engine {

    private const val TAG = "F025Engine"

    /** 码率统一 bit/s、时长毫秒、帧率浮点，格式化交给本类的 format* 系列。 */
    data class MediaInfo(
        val displayName: String? = null,
        val fileSizeBytes: Long? = null,
        val container: String? = null,
        val mimeType: String? = null,
        val videoCodec: String? = null,
        val audioCodec: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val totalBitrate: Long? = null,
        val videoBitrate: Long? = null,
        val audioBitrate: Long? = null,
        val durationMs: Long? = null,
        val frameRate: Float? = null,
        val rotationDegrees: Int? = null,
        val audioChannels: Int? = null,
        val audioSampleRate: Int? = null,
        val trackCount: Int = 0,
        val videoTrackCount: Int = 0,
        val audioTrackCount: Int = 0,
        val hdr: HdrInfo = HdrInfo()
    )

    /** HDR / 色彩属性全部可能缺失：老系统、无元数据的文件都会给 null。 */
    data class HdrInfo(
        val colorInfoAvailable: Boolean = false,
        val transfer: String? = null,
        val primaries: String? = null,
        val bitDepth: Int? = null
    ) {
        /** 有明确的 HDR 传输特性（PQ/HLG），或位深到 10bit 及以上，才算 HDR。 */
        val isHdr: Boolean
            get() = transfer == "HDR10" || transfer == "HLG" || (bitDepth != null && bitDepth >= 10)
    }

    /** 解析结果。失败也返回对象而不是抛异常，UI 只负责展示。 */
    sealed class LoadResult {
        data class Success(val info: MediaInfo) : LoadResult()
        data class Failure(val reason: String) : LoadResult()
    }

    /**
     * 读取 content:// 或 file:// Uri 的媒体信息。
     *
     * 不是媒体文件、设备缺解复用组件、provider 断流都会走到 [LoadResult.Failure]，
     * 不让异常冒到 UI 线程。
     */
    suspend fun load(context: Context, uri: Uri): LoadResult = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val tracks = readTracks(extractor)
            val base = readMetadata(context, uri)

            val merged = base.copy(
                container = base.container ?: tracks.containerHint,
                mimeType = base.mimeType ?: tracks.mime,
                videoCodec = tracks.videoCodec,
                audioCodec = tracks.audioCodec,
                width = tracks.width ?: base.width,
                height = tracks.height ?: base.height,
                videoBitrate = tracks.videoBitrate,
                audioBitrate = tracks.audioBitrate,
                durationMs = base.durationMs ?: tracks.durationMs,
                // 轨道自带的 frame-rate 才是真实容器帧率，retriever 的 CAPTURE_FRAMERATE 只作兜底
                frameRate = tracks.frameRate ?: base.frameRate,
                rotationDegrees = base.rotationDegrees ?: tracks.rotation,
                audioChannels = tracks.audioChannels,
                audioSampleRate = tracks.audioSampleRate,
                trackCount = tracks.total,
                videoTrackCount = tracks.video,
                audioTrackCount = tracks.audio,
                hdr = tracks.hdr
            )
            // 总码率：优先 retriever 给的值，其次各轨码率之和，最后用「体积 ÷ 时长」推算
            val summed = listOfNotNull(tracks.videoBitrate, tracks.audioBitrate).sum().takeIf { it > 0 }
            val derived = deriveBitrate(merged.fileSizeBytes, merged.durationMs)
            LoadResult.Success(merged.copy(totalBitrate = base.totalBitrate ?: summed ?: derived))
        } catch (e: Exception) {
            Log.w(TAG, "解析媒体信息失败: $uri", e)
            LoadResult.Failure(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun deriveBitrate(sizeBytes: Long?, durationMs: Long?): Long? {
        if (sizeBytes == null || sizeBytes <= 0 || durationMs == null || durationMs <= 0) return null
        return sizeBytes * 8L * 1000L / durationMs
    }

    // region 解复用：轨道级信息

    private class TrackInfo(
        val total: Int,
        val video: Int,
        val audio: Int,
        val videoCodec: String?,
        val audioCodec: String?,
        val width: Int?,
        val height: Int?,
        val videoBitrate: Long?,
        val audioBitrate: Long?,
        val audioChannels: Int?,
        val audioSampleRate: Int?,
        val frameRate: Float?,
        val rotation: Int?,
        val durationMs: Long?,
        val hdr: HdrInfo,
        val mime: String?,
        val containerHint: String?
    )

    private fun readTracks(extractor: MediaExtractor): TrackInfo {
        var videoCodec: String? = null
        var audioCodec: String? = null
        var width: Int? = null
        var height: Int? = null
        var videoBitrate: Long? = null
        var audioBitrate: Long? = null
        var audioChannels: Int? = null
        var audioSampleRate: Int? = null
        var frameRate: Float? = null
        var rotation: Int? = null
        var durationMs: Long? = null
        var hdr = HdrInfo()
        var videoMime: String? = null
        var videoCount = 0
        var audioCount = 0
        var total = 0

        // 多轨混装（双音轨、外挂音轨）时只取第一轨作代表值，其余只计入轨道数
        for (index in 0 until extractor.trackCount) {
            val format = runCatching { extractor.getTrackFormat(index) }.getOrNull() ?: continue
            total++
            val trackDurationUs = longOrNull(format, KEY_DURATION)
            if (trackDurationUs != null && trackDurationUs > 0) {
                val ms = trackDurationUs / 1000
                if (durationMs == null || ms > durationMs) durationMs = ms
            }
            val mime = format.getString(KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                videoCount++
                if (videoCount > 1) continue
                videoMime = mime
                videoCodec = codecName(mime, format)
                width = intOrNull(format, KEY_WIDTH, KEY_CROP_WIDTH)
                height = intOrNull(format, KEY_HEIGHT, KEY_CROP_HEIGHT)
                videoBitrate = longOrNull(format, KEY_BIT_RATE)
                frameRate = floatOrNull(format, KEY_FRAME_RATE, KEY_FRAME_RATE_FLOAT)
                rotation = intOrNull(format, KEY_ROTATION)
                hdr = readHdr(format)
            } else if (mime.startsWith("audio/")) {
                audioCount++
                if (audioCount > 1) continue
                audioCodec = codecName(mime, format)
                audioBitrate = longOrNull(format, KEY_BIT_RATE)
                audioChannels = intOrNull(format, KEY_CHANNEL_COUNT)
                audioSampleRate = intOrNull(format, KEY_SAMPLE_RATE)
            }
        }

        return TrackInfo(
            total = total,
            video = videoCount,
            audio = audioCount,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            width = width,
            height = height,
            videoBitrate = videoBitrate,
            audioBitrate = audioBitrate,
            audioChannels = audioChannels,
            audioSampleRate = audioSampleRate,
            frameRate = frameRate,
            rotation = rotation,
            durationMs = durationMs,
            hdr = hdr,
            mime = videoMime,
            containerHint = containerFromMime(videoMime)
        )
    }

    /**
     * 读色彩信息。
     *
     * KEY_COLOR_STANDARD / KEY_COLOR_TRANSFER 自 API 24 起可用；"bits-per-sample" 不是
     * MediaFormat 的公开常量，按字符串 key 探测，取不到就不显示。
     */
    private fun readHdr(format: MediaFormat): HdrInfo {
        val transferValue = intOrNull(format, MediaFormat.KEY_COLOR_TRANSFER)
        val standardValue = intOrNull(format, MediaFormat.KEY_COLOR_STANDARD)
        return HdrInfo(
            colorInfoAvailable = transferValue != null || standardValue != null,
            transfer = transferName(transferValue),
            primaries = colorStandardName(standardValue),
            bitDepth = intOrNull(format, KEY_BITS_PER_SAMPLE)
        )
    }

    /** 编码名按 mime 后缀细分成可读名字；profile 能读到就一并带上。 */
    private fun codecName(mime: String, format: MediaFormat): String {
        val profile = intOrNull(format, KEY_PROFILE)
        return when (val suffix = mime.substringAfter('/', mime)) {
            "avc" -> if (profile != null) "H.264 (AVC, profile $profile)" else "H.264 (AVC)"
            "hevc" -> if (profile != null) "H.265 (HEVC, profile $profile)" else "H.265 (HEVC)"
            "av01" -> "AV1"
            "vp9" -> "VP9"
            "vp8" -> "VP8"
            "mp4v-es" -> "MPEG-4 Part 2"
            "mpeg2" -> "MPEG-2"
            "h263" -> "H.263"
            "dolby-vision" -> "Dolby Vision"
            "mp4a-latm" -> "AAC"
            "ac3" -> "AC-3"
            "eac3" -> "E-AC-3"
            "dts" -> "DTS"
            "dts-hd" -> "DTS-HD"
            "truehd" -> "Dolby TrueHD"
            "opus" -> "Opus"
            "vorbis" -> "Vorbis"
            "flac" -> "FLAC"
            "mpeg" -> "MP3"
            "raw" -> "PCM"
            "amr-nb" -> "AMR-NB"
            "amr-wb" -> "AMR-WB"
            else -> suffix
        }
    }

    private fun transferName(value: Int?): String? = when (value) {
        null -> null
        MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> "SDR"
        MediaFormat.COLOR_TRANSFER_ST2084 -> "HDR10"
        MediaFormat.COLOR_TRANSFER_HLG -> "HLG"
        else -> "其他（value=$value）"
    }

    private fun colorStandardName(value: Int?): String? = when (value) {
        null -> null
        MediaFormat.COLOR_STANDARD_BT709 -> "BT.709"
        MediaFormat.COLOR_STANDARD_BT601_PAL -> "BT.601 PAL"
        MediaFormat.COLOR_STANDARD_BT601_NTSC -> "BT.601 NTSC"
        MediaFormat.COLOR_STANDARD_BT2020 -> "BT.2020"
        else -> "其他（value=$value）"
    }

    // endregion

    // region 总览：文件级信息

    private fun readMetadata(context: Context, uri: Uri): MediaInfo {
        var info = readFileMeta(context, uri)
        val retriever = MediaMetadataRetriever()
        var attached = false
        try {
            retriever.setDataSource(context, uri)
            attached = true
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
            val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()
                ?.takeIf { it > 0f }
            info = info.copy(
                durationMs = duration,
                totalBitrate = bitrate,
                width = width,
                height = height,
                rotationDegrees = rotation,
                frameRate = fps,
                container = info.container ?: containerFromMime(mimeOf(retriever))
            )
        } catch (e: Exception) {
            // 部分 provider（例如网络流式 DocumentsProvider）不支持随机读取，retriever 会直接抛
            Log.w(TAG, "MediaMetadataRetriever 读取失败: $uri", e)
        } finally {
            if (attached) runCatching { retriever.release() }
        }
        return info
    }

    private fun mimeOf(retriever: MediaMetadataRetriever): String? = runCatching {
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
    }.getOrNull()

    /**
     * 文件名 / 体积 / MIME 只能从 ContentResolver 拿。
     * 某些 DocumentsProvider 不给 DISPLAY_NAME 或 SIZE，缺就留 null。
     */
    private fun readFileMeta(context: Context, uri: Uri): MediaInfo {
        val resolver: ContentResolver = context.contentResolver
        var name: String? = null
        var size: Long? = null
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        if (name.isNullOrBlank()) name = uri.lastPathSegment
        return MediaInfo(
            displayName = name,
            fileSizeBytes = size?.takeIf { it > 0 },
            mimeType = mime,
            container = containerFromMime(mime) ?: containerFromName(name)
        )
    }

    /**
     * 系统没有「容器格式」这一属性，只能从 MIME 或文件名后缀推断。
     * 两条路都推不出来就返回 null，交给 UI 显示「未知」。
     */
    private fun containerFromMime(mime: String?): String? = when (mime?.lowercase(Locale.US)) {
        "video/mp4", "video/x-m4v" -> "MP4"
        "video/x-matroska" -> "Matroska (MKV)"
        "video/webm" -> "WebM"
        "video/3gpp" -> "3GPP"
        "video/3gpp2" -> "3GPP2"
        "video/quicktime" -> "QuickTime (MOV)"
        "video/mp2t" -> "MPEG-TS"
        "video/mpeg" -> "MPEG-PS"
        "video/x-msvideo" -> "AVI"
        "video/x-flv" -> "FLV"
        "video/ogg" -> "Ogg"
        else -> null
    }

    private fun containerFromName(name: String?): String? {
        val ext = name?.substringAfterLast('.', "")?.lowercase(Locale.US) ?: return null
        return when (ext) {
            "mp4", "m4v" -> "MP4"
            "mkv" -> "Matroska (MKV)"
            "webm" -> "WebM"
            "mov" -> "QuickTime (MOV)"
            "avi" -> "AVI"
            "flv" -> "FLV"
            "ts", "m2ts", "mts" -> "MPEG-TS"
            "mpg", "mpeg" -> "MPEG-PS"
            "3gp" -> "3GPP"
            "rmvb", "rm" -> "RealMedia"
            "wmv", "asf" -> "ASF"
            "ogv", "ogg" -> "Ogg"
            else -> null
        }
    }

    // endregion

    // region 格式化：UI 直接复用，「缺失即未知」只有这一处实现

    fun formatDuration(ms: Long?): String? {
        if (ms == null || ms <= 0) return null
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun formatBitrate(bitsPerSecond: Long?): String? {
        if (bitsPerSecond == null || bitsPerSecond <= 0) return null
        val mbps = bitsPerSecond / 1_000_000.0
        return if (mbps >= 1.0) {
            String.format(Locale.US, "%.2f Mbps", mbps)
        } else {
            String.format(Locale.US, "%.0f kbps", bitsPerSecond / 1000.0)
        }
    }

    fun formatFrameRate(fps: Float?): String? {
        if (fps == null || fps <= 0f) return null
        // 29.97 / 23.976 这类值保留两位小数就够
        return String.format(Locale.US, "%.2f", fps)
    }

    fun formatSize(bytes: Long?): String? {
        if (bytes == null || bytes <= 0) return null
        val mb = bytes / (1024.0 * 1024.0)
        return when {
            mb >= 1024.0 -> String.format(Locale.US, "%.2f GB", mb / 1024.0)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        }
    }

    /** 声道数转可读布局；不认识的声道数返回 null，UI 只显示数字。 */
    fun channelLayout(channels: Int?): String? = when (channels) {
        1 -> "mono"
        2 -> "stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> null
    }

    // endregion

    // region 属性读取小工具

    private const val KEY_MIME = "mime"
    private const val KEY_BIT_RATE = "bitrate"
    private const val KEY_DURATION = "durationUs"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"
    private const val KEY_CROP_WIDTH = "crop-width"
    private const val KEY_CROP_HEIGHT = "crop-height"
    private const val KEY_FRAME_RATE = "frame-rate"
    private const val KEY_FRAME_RATE_FLOAT = "frame-rate-float"
    private const val KEY_ROTATION = "rotation-degrees"
    private const val KEY_CHANNEL_COUNT = "channel-count"
    private const val KEY_SAMPLE_RATE = "sample-rate"
    private const val KEY_PROFILE = "profile"
    private const val KEY_BITS_PER_SAMPLE = "bits-per-sample"

    private fun intOrNull(format: MediaFormat, vararg keys: String): Int? {
        for (key in keys) {
            if (!format.containsKey(key)) continue
            runCatching { return format.getInteger(key) }
        }
        return null
    }

    private fun longOrNull(format: MediaFormat, vararg keys: String): Long? {
        for (key in keys) {
            if (!format.containsKey(key)) continue
            runCatching { return format.getLong(key) }
        }
        return null
    }

    private fun floatOrNull(format: MediaFormat, vararg keys: String): Float? {
        for (key in keys) {
            if (!format.containsKey(key)) continue
            // MediaFormat.getFloat 是 API 29 才有的，24~28 只能按整数帧率退回
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { return format.getFloat(key) }
            } else {
                runCatching { return format.getInteger(key).toFloat() }
            }
        }
        return null
    }

    // endregion
}