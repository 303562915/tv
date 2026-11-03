package com.tv.ball.presentation.localvideo

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tv.ball.databinding.ActivityLocalVideoFolderBinding
import com.tv.ball.presentation.player.VideoPlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LocalVideoFolderActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityLocalVideoFolderBinding
    private lateinit var adapter: VideoFileAdapter
    private var folderPath: String? = null
    private var isMultiSelectMode = false
    private val selectedVideos = mutableSetOf<String>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocalVideoFolderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // 设置透明沉浸式状态栏
        com.tv.ball.utils.StatusBarUtils.setupTransparentStatusBar(this)
        
        folderPath = intent.getStringExtra("FOLDER_PATH")
        val folderName = intent.getStringExtra("FOLDER_NAME")
        
        title = folderName ?: "本地视频"
        
        setupRecyclerView()
        setupDeleteButton()
        loadVideos()
    }
    
    private fun setupRecyclerView() {
        adapter = VideoFileAdapter(
            onVideoClick = { videoFile ->
                if (isMultiSelectMode) {
                    // 多选模式：切换选中状态
                    toggleSelection(videoFile.path)
                } else {
                    // 普通模式：播放视频
                    playVideo(videoFile)
                }
            },
            onVideoLongClick = { videoFile ->
                // 长按进入多选模式
                if (!isMultiSelectMode) {
                    enterMultiSelectMode()
                    toggleSelection(videoFile.path)
                }
                true
            },
            isItemSelected = { path -> selectedVideos.contains(path) },
            isMultiSelectMode = { isMultiSelectMode }
        )
        
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }
    
    private fun setupDeleteButton() {
        binding.deleteButton.setOnClickListener {
            if (selectedVideos.isEmpty()) {
                Toast.makeText(this, "请选择要删除的视频", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            AlertDialog.Builder(this)
                .setTitle("确认删除")
                .setMessage("确定要删除选中的 ${selectedVideos.size} 个视频吗？")
                .setPositiveButton("删除") { _, _ ->
                    deleteSelectedVideos()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }
    
    private fun playVideo(videoFile: VideoFile) {
        val intent = Intent(this, VideoPlayerActivity::class.java)
        intent.putExtra("VIDEO_ID", 0L)
        intent.putExtra("VIDEO_TITLE", videoFile.name)
        intent.putExtra("VIDEO_URL", videoFile.path)
        intent.putExtra("THUMBNAIL_URL", "")
        startActivity(intent)
    }
    
    private fun enterMultiSelectMode() {
        isMultiSelectMode = true
        binding.deleteButton.visibility = View.VISIBLE
        adapter.notifyDataSetChanged()
    }
    
    private fun exitMultiSelectMode() {
        isMultiSelectMode = false
        selectedVideos.clear()
        binding.deleteButton.visibility = View.GONE
        adapter.notifyDataSetChanged()
    }
    
    private fun toggleSelection(path: String) {
        if (selectedVideos.contains(path)) {
            selectedVideos.remove(path)
        } else {
            selectedVideos.add(path)
        }
        adapter.notifyDataSetChanged()
    }
    
    private fun deleteSelectedVideos() {
        var successCount = 0
        var failCount = 0
        
        selectedVideos.forEach { path ->
            val deleted = deleteVideoFromMediaStore(path) || deleteVideoFile(path)
            if (deleted) successCount++ else failCount++
        }
        
        Toast.makeText(
            this,
            "删除完成：成功 $successCount，失败 $failCount",
            Toast.LENGTH_SHORT
        ).show()
        
        exitMultiSelectMode()
        loadVideos()
    }
    
    private fun deleteVideoFromMediaStore(filePath: String): Boolean {
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.Video.Media.DATA}=?"
        val selectionArgs = arrayOf(filePath)
        
        val rows = contentResolver.delete(uri, selection, selectionArgs)
        return rows > 0
    }
    
    private fun deleteVideoFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            file.exists() && file.delete()
        } catch (e: Exception) {
            false
        }
    }
    
    private fun loadVideos() {
        folderPath?.let { path ->
            // 在后台线程扫描视频
            lifecycleScope.launch(Dispatchers.IO) {
                val videos = scanVideosInFolder(path)
                withContext(Dispatchers.Main) {
                    adapter.submitList(videos)
                    binding.emptyView.visibility = if (videos.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }
    
    private fun scanVideosInFolder(folderPath: String): List<VideoFile> {
        val videos = mutableListOf<VideoFile>()
        val dir = File(folderPath)
        
        if (!dir.isDirectory) return videos
        
        val videoExtensions = listOf(".mp4", ".mkv", ".avi", ".mov", ".rmvb", ".flv", ".wmv")
        
        dir.listFiles()?.forEach { file ->
            if (file.isFile) {
                val extension = file.extension.lowercase()
                if (videoExtensions.any { it.endsWith(extension) }) {
                    // 完全照搬 Lua 逻辑：获取 ID 并加载缩略图
                    val videoId = getVideoId(file.absolutePath)
                    val thumbnail = videoId?.let { getThumbnailFromMediaStore(it) }
                    
                    videos.add(
                        VideoFile(
                            name = file.name,
                            path = file.absolutePath,
                            size = file.length(),
                            thumbnail = thumbnail  // 使用真实缩略图或 null
                        )
                    )
                }
            }
        }
        
        return videos.sortedBy { it.name }
    }
    
    // 完全照搬 Lua 的 getThumbnail 方法
    @Suppress("DEPRECATION")
    private fun getThumbnailFromMediaStore(videoId: Long): Bitmap? {
        return try {
            MediaStore.Video.Thumbnails.getThumbnail(
                contentResolver,
                videoId,
                MediaStore.Video.Thumbnails.MINI_KIND,
                null
            )
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getVideoId(filePath: String): Long? {
        val projection = arrayOf(MediaStore.Video.Media._ID)
        val selection = "${MediaStore.Video.Media.DATA}=?"
        val selectionArgs = arrayOf(filePath)
        
        contentResolver.query(
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
    
    override fun onBackPressed() {
        if (isMultiSelectMode) {
            exitMultiSelectMode()
        } else {
            super.onBackPressed()
        }
    }
}
