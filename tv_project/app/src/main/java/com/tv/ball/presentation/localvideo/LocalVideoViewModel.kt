package com.tv.ball.presentation.localvideo

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tv.ball.di.Dependencies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LocalVideoViewModel : ViewModel() {

    /**
     * ViewModel 由 ViewModelProvider 反射创建（走无参构造），
     * 上下文按需取 Application，不通过构造参数注入，避免反射创建时踩初始化顺序。
     */
    private val context: Context
        get() = Dependencies.context

    companion object {
        /**
         * 占位图（灰色）- 完全照搬 Lua 代码。
         * 低内存盒子上 320x180 ARGB_8888 的分配仍可能失败，
         * 失败退化 1x1 空图，不能把整页带崩。
         */
        private val LOADING_IMAGE: Bitmap by lazy {
            try {
                Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply {
                    val canvas = Canvas(this)
                    val paint = Paint().apply {
                        color = androidx.core.content.ContextCompat.getColor(
                            Dependencies.context,
                            com.tv.ball.R.color.surface_inset
                        )
                    }
                    canvas.drawRect(0f, 0f, 320f, 180f, paint)
                }
            } catch (e: Throwable) {
                android.util.Log.e("LocalVideoViewModel", "创建占位图失败: ${e.message}", e)
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }
        }
    }
    
    private val _videoFolders = MutableStateFlow<List<VideoFolder>>(emptyList())
    val videoFolders: StateFlow<List<VideoFolder>> = _videoFolders.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val handler = Handler(Looper.getMainLooper())
    private val loadQueue = mutableListOf<VideoFolder>()
    
    fun loadLocalVideos() {
        if (_isLoading.value) return
        // MediaStore 查询 + 下载目录遍历全部在 IO 线程跑，主线程只负责收结果、开 loading 状态
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { _isLoading.value = true }

            // 定制 ROM 的 MediaProvider 可能被裁剪 / 中途死亡（DeadObjectException），
            // 扫描失败只降级成空列表，不能把协程异常抛成闪退。
            val folders = try {
                scanVideoFolders()
            } catch (e: Throwable) {
                android.util.Log.e("LocalVideoViewModel", "扫描本地视频失败: ${e.message}", e)
                emptyList()
            }

            // 结果回主线程：先出列表（占位图），再异步补缩略图
            withContext(Dispatchers.Main) {
                _videoFolders.value = folders
                _isLoading.value = false

                // 异步加载缩略图 - 完全照搬 Lua 逻辑
                loadQueue.clear()
                loadQueue.addAll(folders)
                startLoadingThumbnails()
            }
        }
    }
    
    // 完全照搬 Lua 的异步加载逻辑
    private fun startLoadingThumbnails() {
        lateinit var loadRunnable: Runnable
        loadRunnable = Runnable {
            if (loadQueue.isEmpty()) return@Runnable
            
            val folder = loadQueue.removeAt(0)
            
            // 在后台线程加载缩略图
            viewModelScope.launch(Dispatchers.IO) {
                val thumbnail = getThumbnailFromMediaStore(folder.videoId)
                
                // 更新列表
                withContext(Dispatchers.Main) {
                    val updatedList = _videoFolders.value.map {
                        if (it.folderPath == folder.folderPath) {
                            it.copy(thumbnail = thumbnail ?: LOADING_IMAGE)
                        } else {
                            it
                        }
                    }
                    _videoFolders.value = updatedList
                    
                    // 延迟 50ms 加载下一个
                    handler.postDelayed(loadRunnable, 50)
                }
            }
        }
        handler.post(loadRunnable)
    }
    
    private fun scanVideoFolders(): List<VideoFolder> {
        val folderMap = mutableMapOf<String, VideoFolder>()
        // 每次扫描前读一次排除设置（内存读）：设置页改完、重新进入本地视频页就按新规则统计
        val excluded = LocalVideoMedia.excludedExtensions(context)

        try {
            // 多查 BUCKET_ID / RELATIVE_PATH：详情页按它们查同一批视频，
            // 不再依赖 File.listFiles()（作用域存储下它经常返回 null）。
            val columns = mutableListOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.BUCKET_ID,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Video.Media.DISPLAY_NAME
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                columns.add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
            val projection = columns.toTypedArray()

            val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndex(MediaStore.Video.Media._ID)
                val dataColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                val bucketIdColumn = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_ID)
                val bucketColumn = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val nameColumn = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val relativeColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                } else {
                    -1
                }

                while (cursor.moveToNext()) {
                    val id = if (idColumn >= 0) cursor.getLong(idColumn) else 0L
                    val path = if (dataColumn >= 0) cursor.getString(dataColumn) else null
                    // 与详情页、下载目录扫描同一个排除判定：被排除的后缀（默认 .ts）不计入 videoCount
                    val displayName = if (nameColumn >= 0) cursor.getString(nameColumn) else null
                    if (LocalVideoMedia.isExcludedName(displayName, path, excluded)) continue
                    val bucketId = if (bucketIdColumn >= 0) {
                        cursor.getLong(bucketIdColumn)
                    } else {
                        LocalVideoMedia.NO_BUCKET_ID
                    }
                    val relativePath = if (relativeColumn >= 0) cursor.getString(relativeColumn) else null
                    val bucketName = (if (bucketColumn >= 0) cursor.getString(bucketColumn) else null)
                        ?.takeIf { it.isNotBlank() }
                        ?: folderNameOf(path, relativePath)
                    val parentDir = path?.let { File(it).parent }
                    // _data 拿不到时退回 BUCKET_ID 当 key，别把整条视频丢掉
                    val bucketKey = bucketId
                        .takeIf { it != LocalVideoMedia.NO_BUCKET_ID }
                        ?.let { "bucket:$it" }
                    val folderKey: String? = parentDir ?: bucketKey
                    if (folderKey == null) continue

                    if (!folderMap.containsKey(folderKey)) {
                        // 先用占位图 - 完全照搬 Lua
                        folderMap[folderKey] = VideoFolder(
                            folderName = bucketName,
                            folderPath = folderKey,
                            thumbnail = LOADING_IMAGE,  // 先用占位图
                            videoCount = 1,
                            videoId = id,  // 保存 ID 用于后续加载缩略图
                            bucketId = bucketId,
                            relativePath = relativePath
                        )
                    } else {
                        folderMap[folderKey] = folderMap[folderKey]!!.copy(
                            videoCount = folderMap[folderKey]!!.videoCount + 1
                        )
                    }
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("LocalVideoViewModel", "MediaStore 查询失败: ${e.message}", e)
        }

        // 额外扫描下载目录（即使MediaStore中没有）
        scanDownloadDirectory(folderMap, excluded)

        return folderMap.values.toList()
    }

    /** 目录名兜底：优先取相对路径的最后一段，其次父目录名。 */
    private fun folderNameOf(path: String?, relativePath: String?): String {
        val fromRelative = relativePath?.trim()?.trim('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        if (fromRelative != null) return fromRelative
        val fromPath = path?.substringBeforeLast('/', "")?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        return fromPath ?: "未知文件夹"
    }
    
    /**
     * 扫描下载目录，确保下载的视频能显示出来
     */
    private fun scanDownloadDirectory(folderMap: MutableMap<String, VideoFolder>, excluded: Set<String>) {
        try {
            val downloadDir = File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "TVBall"
            )
            
            if (!downloadDir.exists() || !downloadDir.isDirectory) {
                return
            }
            
            val videoFiles = downloadDir.listFiles()?.filter { file ->
                file.isFile && LocalVideoMedia.hasVideoExtension(file.name, excluded)
            }?.sortedBy { it.name } ?: emptyList()

            if (videoFiles.isNotEmpty()) {
                val folderPath = downloadDir.absolutePath

                if (!folderMap.containsKey(folderPath)) {
                    // 使用第一个视频文件的ID（如果在MediaStore中）
                    val firstVideoId = LocalVideoMedia.queryIdByPath(context, videoFiles[0].absolutePath)

                    folderMap[folderPath] = VideoFolder(
                        folderName = "下载的视频",
                        folderPath = folderPath,
                        thumbnail = LOADING_IMAGE,
                        videoCount = videoFiles.size,
                        videoId = firstVideoId,
                        bucketId = LocalVideoMedia.NO_BUCKET_ID,
                        relativePath = "Download/TVBall/"
                    )
                } else {
                    // 更新视频数量：只增不减，避免文件系统少看到几个时把 MediaStore 的统计改小
                    val existing = folderMap[folderPath]!!
                    folderMap[folderPath] = existing.copy(
                        videoCount = maxOf(existing.videoCount, videoFiles.size)
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("LocalVideoViewModel", "扫描下载目录失败: ${e.message}", e)
        }
    }
    
    // 缩略图：实现挪到 LocalVideoMedia，列表页/详情页共用一套
    private fun getThumbnailFromMediaStore(videoId: Long): Bitmap? =
        LocalVideoMedia.loadThumbnail(context, videoId)
    
    override fun onCleared() {
        super.onCleared()
        handler.removeCallbacksAndMessages(null)
    }
}

data class VideoFolder(
    val folderName: String,
    /** 目录绝对路径；_data 缺失时会退化成 "bucket:<id>"，详情页按前缀 "/" 判断是否是真路径。 */
    val folderPath: String,
    val thumbnail: Bitmap?,
    val videoCount: Int,
    val videoId: Long = 0,  // 用于加载缩略图
    /**
     * MediaStore 的 BUCKET_ID：详情页按它查同一批视频，避免依赖 File.listFiles()。
     * 默认值即 LocalVideoMedia.NO_BUCKET_ID（Long.MIN_VALUE）——BUCKET_ID 是路径 hashCode，
     * 可能是负数，所以不能用 0 或 -1 表达「没有」。
     */
    val bucketId: Long = Long.MIN_VALUE,
    /** MediaStore 的 RELATIVE_PATH（API 29+），形如 Movies/MyFolder/。 */
    val relativePath: String? = null
)

