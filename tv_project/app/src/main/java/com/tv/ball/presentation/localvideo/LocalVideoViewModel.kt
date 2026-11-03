package com.tv.ball.presentation.localvideo

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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

class LocalVideoViewModel(
    private val context: Context = Dependencies.context
) : ViewModel() {
    
    companion object {
        // 创建默认占位图（灰色）- 完全照搬 Lua 代码
        private val LOADING_IMAGE: Bitmap by lazy {
            Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply {
                val canvas = Canvas(this)
                val paint = Paint().apply {
                    color = 0xFFEEEEEE.toInt()
                }
                canvas.drawRect(0f, 0f, 320f, 180f, paint)
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
        viewModelScope.launch {
            _isLoading.value = true
            val folders = withContext(Dispatchers.IO) {
                scanVideoFolders()
            }
            // 先用占位图显示列表
            _videoFolders.value = folders
            _isLoading.value = false
            
            // 异步加载缩略图 - 完全照搬 Lua 逻辑
            loadQueue.clear()
            loadQueue.addAll(folders)
            startLoadingThumbnails()
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
        
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DISPLAY_NAME
        )
        
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val path = cursor.getString(dataColumn)
                val bucketName = cursor.getString(bucketColumn) ?: "未知文件夹"
                val parentDir = File(path).parent ?: continue
                
                if (!folderMap.containsKey(parentDir)) {
                    // 先用占位图 - 完全照搬 Lua
                    folderMap[parentDir] = VideoFolder(
                        folderName = bucketName,
                        folderPath = parentDir,
                        thumbnail = LOADING_IMAGE,  // 先用占位图
                        videoCount = 1,
                        videoId = id  // 保存 ID 用于后续加载缩略图
                    )
                } else {
                    folderMap[parentDir] = folderMap[parentDir]!!.copy(
                        videoCount = folderMap[parentDir]!!.videoCount + 1
                    )
                }
            }
        }
        
        // 额外扫描下载目录（即使MediaStore中没有）
        scanDownloadDirectory(folderMap)
        
        return folderMap.values.toList()
    }
    
    /**
     * 扫描下载目录，确保下载的视频能显示出来
     */
    private fun scanDownloadDirectory(folderMap: MutableMap<String, VideoFolder>) {
        try {
            val downloadDir = File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "TVBall"
            )
            
            if (!downloadDir.exists() || !downloadDir.isDirectory) {
                return
            }
            
            val videoExtensions = listOf(".mp4", ".mkv", ".avi", ".mov", ".rmvb", ".flv", ".wmv", ".m3u8")
            val videoFiles = downloadDir.listFiles()?.filter { file ->
                file.isFile && videoExtensions.any { ext -> file.name.lowercase().endsWith(ext) }
            } ?: emptyList()
            
            if (videoFiles.isNotEmpty()) {
                val folderPath = downloadDir.absolutePath
                
                if (!folderMap.containsKey(folderPath)) {
                    // 使用第一个视频文件的ID（如果在MediaStore中）
                    val firstVideoId = getVideoIdByPath(videoFiles[0].absolutePath) ?: 0L
                    
                    folderMap[folderPath] = VideoFolder(
                        folderName = "下载的视频",
                        folderPath = folderPath,
                        thumbnail = LOADING_IMAGE,
                        videoCount = videoFiles.size,
                        videoId = firstVideoId
                    )
                } else {
                    // 更新视频数量
                    folderMap[folderPath] = folderMap[folderPath]!!.copy(
                        videoCount = videoFiles.size
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("LocalVideoViewModel", "扫描下载目录失败: ${e.message}", e)
        }
    }
    
    /**
     * 根据文件路径获取MediaStore中的视频ID
     */
    private fun getVideoIdByPath(filePath: String): Long? {
        val projection = arrayOf(MediaStore.Video.Media._ID)
        val selection = "${MediaStore.Video.Media.DATA}=?"
        val selectionArgs = arrayOf(filePath)
        
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
            }
        }
        
        return null
    }
    
    // 完全照搬 Lua 的 getThumbnail 方法
    @Suppress("DEPRECATION")
    private fun getThumbnailFromMediaStore(videoId: Long): Bitmap? {
        return try {
            MediaStore.Video.Thumbnails.getThumbnail(
                context.contentResolver,
                videoId,
                MediaStore.Video.Thumbnails.MINI_KIND,
                null
            )
        } catch (e: Exception) {
            null
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        handler.removeCallbacksAndMessages(null)
    }
}

data class VideoFolder(
    val folderName: String,
    val folderPath: String,
    val thumbnail: Bitmap?,
    val videoCount: Int,
    val videoId: Long = 0  // 用于加载缩略图
)
