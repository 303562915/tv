package com.tv.ball.presentation.localvideo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.tv.ball.data.local.UserPreferences
import java.io.File

/**
 * 本地视频的统一规则。
 *
 * 之前列表页（LocalVideoViewModel）走 MediaStore、详情页（LocalVideoFolderActivity）走
 * File.listFiles()，两套扩展名集合也不一致；targetSdk 36 的作用域存储下，没有
 * 「所有文件访问权限」时 listFiles() 直接返回 null，于是出现
 * 「列表显示有视频，点进文件夹却说没有视频」。
 *
 * 现在两页共用这里的权限判断、扩展名判断、MediaStore 查询，保证同源。
 */
internal object LocalVideoMedia {

    private const val TAG = "LocalVideoMedia"

    /**
     * 线程约束：这个对象里的所有 MediaStore 查询、缩略图读取、目录遍历都是阻塞调用，
     * 必须在 `Dispatchers.IO` 上执行，主线程只接结果。
     *
     * 列表页 / 详情页统一用 [onIo] 进入 IO 线程；真被主线程调用时打一条警告，
     * 方便下次又在主线程上扫盘时立刻定位（低配盒子上这就是滑不动的原因）。
     */
    suspend fun <T> onIo(block: () -> T): T = kotlinx.coroutines.withContext(
        kotlinx.coroutines.Dispatchers.IO
    ) { block() }

    private fun warnIfOnMainThread(what: String) {
        try {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                android.util.Log.w(TAG, "$what 在主线程执行：调用方应放到 Dispatchers.IO")
            }
        } catch (e: Throwable) {
            // 线程判断失败不影响查询本身
        }
    }

    /**
     * 「没有 BUCKET_ID」的哨兵值。
     * 注意 BUCKET_ID 实际是父目录路径的 hashCode，**可以是负数**，
     * 所以既不能用 0 也不能用 -1 当「无效」，必须用这个显式哨兵。
     */
    const val NO_BUCKET_ID: Long = Long.MIN_VALUE

    /** 内置支持的视频扩展名（小写、带点）。两页共用，新增格式只改这里。 */
    private val VIDEO_EXTENSIONS = setOf(
        ".mp4", ".mkv", ".avi", ".mov", ".rmvb", ".rm", ".flv", ".wmv",
        ".m3u8", ".ts", ".3gp", ".3g2", ".webm", ".m4v", ".mpg", ".mpeg",
        ".m2ts", ".mts", ".vob", ".f4v", ".asf", ".ogv"
    )

    /** 设置页「排除的后缀」多选候选（不带点，落库也是裸后缀）。 */
    val EXTENSION_CANDIDATES: List<String> = listOf("ts", "tmp", "part", "m3u8", "html")

    /** 默认排除的后缀：只排 .ts 分片（不臆造第二项）。 */
    const val DEFAULT_EXCLUDED_EXTENSION = "ts"

    /**
     * 「内置支持集合 − 用户排除集合」。
     *
     * 用户排除集合来自设置页（[UserPreferences] 的 `local_video_exclude_ts` /
     * `local_video_exclude_ext`）。这里是唯一读设置的地方，列表页的 videoCount、
     * 文件夹详情、下载目录扫描全部经过下面的 [isExcludedName] / [hasVideoExtension]，
     * 不允许有第二套白名单逻辑。
     *
     * 每次都新读一次（内存读，设置页写入用 apply() 已回写到内存）：
     * 用户从设置页返回、重新进入本地视频页即生效，不需要广播或监听。
     */
    fun excludedExtensions(context: Context): Set<String> = try {
        val prefs = UserPreferences(context)
        if (prefs.isLocalVideoExcludeTs()) parseExcludedExtensions(prefs.getLocalVideoExcludeExt()) else emptySet()
    } catch (e: Throwable) {
        // 读设置失败不能让列表整页崩：退回出厂口径「只排 ts」
        android.util.Log.e(TAG, "读取排除后缀失败: ${e.message}", e)
        setOf(DEFAULT_EXCLUDED_EXTENSION)
    }

    /** 把逗号分隔的后缀串解析成集合：去空白、去前导点、转小写、丢空项。 */
    fun parseExcludedExtensions(raw: String?): Set<String> =
        raw.orEmpty()
            .split(',')
            .map { it.trim().trimStart('.').lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * 排除判定：文件名（拿不到就用路径的文件名部分）是否命中用户排除的后缀。
     * 这是「排除」的唯一判据，三处扫描都调它。
     */
    fun isExcludedName(name: String?, path: String?, excluded: Set<String>): Boolean {
        if (excluded.isEmpty()) return false
        val subject = name?.takeIf { it.isNotBlank() }
            ?: path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: return false
        val lower = subject.lowercase()
        return excluded.any { lower.endsWith(".$it") }
    }

    /**
     * 文件名是否属于「可播放的本地视频」：命中内置集合，且不在排除集合里。
     * 扩展名集合新增格式只改上面的 [VIDEO_EXTENSIONS]。
     */
    fun hasVideoExtension(name: String, excluded: Set<String>): Boolean {
        if (name.isEmpty()) return false
        if (isExcludedName(name, null, excluded)) return false
        val lower = name.lowercase()
        return VIDEO_EXTENSIONS.any { lower.endsWith(it) }
    }

    /** 读取本地视频需要的运行时权限。 */
    fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    /** 运行时读媒体权限（MediaStore 能查、能读缩略图的前提）。 */
    fun hasMediaReadPermission(context: Context): Boolean = try {
        ContextCompat.checkSelfPermission(context, requiredPermission()) == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        android.util.Log.e(TAG, "检查媒体权限失败: ${e.message}", e)
        false
    }

    /** 「所有文件访问权限」：只有拿到它，File.listFiles() 和按路径播放才可用。 */
    fun hasAllFilesAccess(): Boolean = try {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
    } catch (e: Throwable) {
        android.util.Log.e(TAG, "检查所有文件访问权限失败: ${e.message}", e)
        false
    }

    /**
     * 是否具备读取本地视频的能力：MediaStore 权限或所有文件访问权限，有其一即可。
     * MediaStore 权限只够查询和读缩略图，不够按路径播放，这一点由调用方提示。
     */
    fun canReadLocalVideo(context: Context): Boolean =
        hasMediaReadPermission(context) || hasAllFilesAccess()

    /** MediaStore 查询出来的一条视频。 */
    data class MediaVideo(
        val id: Long,
        val name: String,
        /** _data 列；个别 ROM 会返回 null，此时只能靠 id 定位。 */
        val path: String?,
        val size: Long,
        val bucketId: Long,
        /** API 29+ 才有，形如 Movies/MyFolder/。 */
        val relativePath: String?,
        val mimeType: String?
    )

    /**
     * 按 BUCKET_ID 查该目录的视频。
     * 列表页与 MediaStore 同源，详情页用它就不会再出现「列表有、详情空」。
     */
    fun queryInBucket(context: Context, bucketId: Long): List<MediaVideo> {
        if (bucketId == NO_BUCKET_ID) return emptyList()
        return runQuery(context, "${MediaStore.Video.Media.BUCKET_ID} = ?", arrayOf(bucketId.toString()))
    }

    /**
     * 按绝对目录路径查视频（兼容只传 FOLDER_PATH 的旧入口）。
     * 用 _data 前缀匹配；_data 为 null 的行不会被匹配到，属于可接受的兼容降级。
     */
    @Suppress("DEPRECATION")
    fun queryInDirectoryPath(context: Context, folderPath: String?): List<MediaVideo> {
        val normalized = folderPath?.let { normalizePath(it) }
        if (normalized.isNullOrEmpty()) return emptyList()
        return runQuery(
            context,
            "${MediaStore.Video.Media.DATA} LIKE ?",
            arrayOf("$normalized/%")
        )
    }

    /** 按 RELATIVE_PATH 查视频（API 29+；更精确的目录匹配）。 */
    fun queryInRelativePath(context: Context, relativePath: String?): List<MediaVideo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val normalized = relativePath?.trim()?.trim('/')?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return runQuery(
            context,
            "(${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?)",
            arrayOf("$normalized/", normalized)
        )
    }

    /** 查单条视频的 MediaStore ID（文件系统兜底扫描时用）。 */
    @Suppress("DEPRECATION")
    fun queryIdByPath(context: Context, filePath: String?): Long {
        val normalized = filePath?.let { normalizePath(it) }
        if (normalized.isNullOrEmpty()) return 0L
        return try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.DATA} = ?",
                arrayOf(normalized),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            } ?: 0L
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "按路径查询视频 ID 失败: ${e.message}", e)
            0L
        }
    }

    /**
     * 用 MediaStore 生成缩略图。
     * 拿不到就返回 null，由界面退占位图，不影响列表。
     */
    @Suppress("DEPRECATION")
    fun loadThumbnail(context: Context, videoId: Long): Bitmap? {
        if (videoId <= 0L) return null
        return try {
            MediaStore.Video.Thumbnails.getThumbnail(
                context.contentResolver,
                videoId,
                MediaStore.Video.Thumbnails.MINI_KIND,
                null
            )
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "读取缩略图失败: ${e.message}", e)
            null
        }
    }

    /**
     * 列目录内容。
     * 返回 null 表示「读不到」（无权限 / 目录不存在），返回空列表表示「目录真的是空的」——
     * 这两种情况在详情页必须给出不同提示，不能一律说「没有视频」。
     */
    fun listDirectory(folderPath: String?): List<File>? {
        if (folderPath.isNullOrBlank()) return null
        warnIfOnMainThread("列目录 $folderPath")
        return try {
            val dir = File(folderPath)
            if (!dir.isDirectory) return null
            // 作用域存储下没有所有文件访问权限时，这里就是 null，不是空目录
            dir.listFiles()?.toList()
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "读取目录失败: ${e.message}", e)
            null
        }
    }

    /** 目录是否存在（区分「目录没了」和「目录读不到」）。 */
    fun directoryExists(folderPath: String?): Boolean {
        if (folderPath.isNullOrBlank()) return false
        return try {
            File(folderPath).exists()
        } catch (e: Throwable) {
            false
        }
    }

    /** 去掉尾部斜杠，避免拼出 // 前缀。 */
    private fun normalizePath(path: String): String {
        var p = path.trim()
        while (p.length > 1 && p.endsWith("/")) p = p.dropLast(1)
        return p
    }

    /** 查询主体：任何异常都向上抛，由调用方区分「读取失败」与「确实没有视频」。 */
    private fun runQuery(context: Context, selection: String, selectionArgs: Array<String>): List<MediaVideo> {
        warnIfOnMainThread("MediaStore 查询 $selection")
        val result = mutableListOf<MediaVideo>()
        val projection = buildProjection()
        // 按 BUCKET_ID / 路径 / 相对路径的三种查询都走这里，排除规则必须在这里拦一次，
        // 否则详情页会绕过列表页的过滤，把 .ts 分片又列出来。
        val excluded = excludedExtensions(context)
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(MediaStore.Video.Media._ID)
            val nameIndex = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
            val dataIndex = cursor.getColumnIndexOrNull("_data")
            val sizeIndex = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
            val bucketIndex = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_ID)
            val mimeIndex = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
            val relativeIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            } else {
                -1
            }

            while (cursor.moveToNext()) {
                val id = if (idIndex >= 0) cursor.getLong(idIndex) else 0L
                val path = if (dataIndex >= 0) cursor.getString(dataIndex) else null
                val rawName = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                // 被用户排除的后缀（默认 .ts）直接跳过，不进入结果
                if (isExcludedName(rawName, path, excluded)) continue
                val name = rawName
                    ?.takeIf { it.isNotBlank() }
                    ?: path?.substringAfterLast('/')
                    ?: "未知视频"
                result.add(
                    MediaVideo(
                        id = id,
                        name = name,
                        path = path,
                        size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L,
                        bucketId = if (bucketIndex >= 0) cursor.getLong(bucketIndex) else NO_BUCKET_ID,
                        relativePath = if (relativeIndex >= 0) cursor.getString(relativeIndex) else null,
                        mimeType = if (mimeIndex >= 0) cursor.getString(mimeIndex) else null
                    )
                )
            }
        }
        return result
    }

    /** 投影里带上 API 29 才有的 relative_path，低版本加了会让查询报错。 */
    private fun buildProjection(): Array<String> {
        val columns = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            "_data",
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.MIME_TYPE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            columns.add(MediaStore.MediaColumns.RELATIVE_PATH)
        }
        return columns.toTypedArray()
    }

    /** 列名不存在时返回 -1，不让整次查询崩掉。 */
    @Suppress("DEPRECATION")
    private fun android.database.Cursor.getColumnIndexOrNull(column: String): Int =
        try {
            getColumnIndex(column)
        } catch (e: Throwable) {
            -1
        }
}
