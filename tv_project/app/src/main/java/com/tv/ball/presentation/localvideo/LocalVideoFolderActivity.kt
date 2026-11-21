package com.tv.ball.presentation.localvideo

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
    private var folderBucketId: Long = LocalVideoMedia.NO_BUCKET_ID
    private var folderRelativePath: String? = null
    private var isMultiSelectMode = false
    private val selectedVideos = mutableSetOf<String>()
    /** 当前列表内容：删除/刷新时按 key 反查用 */
    private var currentVideos: List<VideoFile> = emptyList()
    /** 从系统设置页返回后是否需要重新加载 */
    private var reloadOnResume = false

    /** 详情页自己的权限申请：列表页拿到权限不代表这里能读，必须二次校验。 */
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            loadVideos()
        } else {
            Toast.makeText(this, "没有读取视频的权限，无法列出该文件夹", Toast.LENGTH_LONG).show()
            openAllFilesAccessSettings()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocalVideoFolderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // 设置透明沉浸式状态栏
        com.tv.ball.utils.StatusBarUtils.setupTransparentStatusBar(this)
        
        // FOLDER_PATH 可能是 "bucket:<id>" 这种占位 key（_data 缺失时），只认真实绝对路径
        folderPath = intent.getStringExtra("FOLDER_PATH")?.takeIf { it.startsWith("/") }
        folderBucketId = intent.getLongExtra("FOLDER_BUCKET_ID", LocalVideoMedia.NO_BUCKET_ID)
        folderRelativePath = intent.getStringExtra("FOLDER_RELATIVE_PATH")
        val folderName = intent.getStringExtra("FOLDER_NAME")
        
        title = folderName ?: "本地视频"
        
        setupRecyclerView()
        setupDeleteButton()
        loadVideos()
    }

    override fun onResume() {
        super.onResume()
        // 从「所有文件访问权限」设置页返回时重新加载一次
        if (reloadOnResume) {
            reloadOnResume = false
            loadVideos()
        }
    }
    
    private fun setupRecyclerView() {
        adapter = VideoFileAdapter(
            onVideoClick = { videoFile ->
                if (isMultiSelectMode) {
                    // 多选模式：切换选中状态
                    toggleSelection(videoFile.key)
                } else {
                    // 普通模式：播放视频
                    playVideo(videoFile)
                }
            },
            onVideoLongClick = { videoFile ->
                // 长按进入多选模式
                if (!isMultiSelectMode) {
                    enterMultiSelectMode()
                    toggleSelection(videoFile.key)
                }
                true
            },
            isItemSelected = { key -> selectedVideos.contains(key) },
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
        // 播放器（ExoPlayerManager）只把 "/" 与 "file://" 当本地文件，
        // content:// 会走网络数据源直接报错，所以这里仍然传真实文件路径。
        if (videoFile.path.isBlank()) {
            Toast.makeText(this, "读不到该视频的文件路径，无法播放", Toast.LENGTH_SHORT).show()
            return
        }
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
    
    /** key 见 VideoFile.key：有路径用路径，没有路径用 MediaStore ID。 */
    private fun toggleSelection(key: String) {
        if (selectedVideos.contains(key)) {
            selectedVideos.remove(key)
        } else {
            selectedVideos.add(key)
        }
        adapter.notifyDataSetChanged()
    }
    
    private fun deleteSelectedVideos() {
        if (selectedVideos.isEmpty()) return
        var successCount = 0
        var failCount = 0
        
        selectedVideos.forEach { key ->
            val video = currentVideos.firstOrNull { it.key == key }
            if (video == null) {
                // 列表已刷新但选中项还在，算失败
                failCount++
            } else {
                val deleted = deleteVideoFromMediaStore(video) ||
                    (video.path.isNotBlank() && deleteVideoFile(video.path))
                if (deleted) successCount++ else failCount++
            }
        }
        
        Toast.makeText(
            this,
            "删除完成：成功 $successCount，失败 $failCount",
            Toast.LENGTH_SHORT
        ).show()
        
        exitMultiSelectMode()
        loadVideos()
    }
    
    private fun deleteVideoFromMediaStore(video: VideoFile): Boolean {
        return try {
            val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val selection: String
            val selectionArgs: Array<String>
            if (video.videoId > 0L) {
                selection = "${MediaStore.Video.Media._ID} = ?"
                selectionArgs = arrayOf(video.videoId.toString())
            } else if (video.path.isNotBlank()) {
                selection = "${MediaStore.Video.Media.DATA} = ?"
                selectionArgs = arrayOf(video.path)
            } else {
                return false
            }

            contentResolver.delete(uri, selection, selectionArgs) > 0
        } catch (e: Throwable) {
            android.util.Log.e("LocalVideoFolder", "MediaStore 删除失败: ${e.message}", e)
            false
        }
    }
    
    private fun deleteVideoFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            file.exists() && file.delete()
        } catch (e: Exception) {
            false
        }
    }
    
    /** 详情页的四种结果：有视频 / 确实空 / 没权限 / 读取失败，空态必须分开说。 */
    private sealed class FolderLoadResult {
        data class Ok(val videos: List<VideoFile>) : FolderLoadResult()
        object Empty : FolderLoadResult()
        object Denied : FolderLoadResult()
        object NoFileAccess : FolderLoadResult()
        data class Failed(val reason: String) : FolderLoadResult()
    }

    private fun loadVideos() {
        // 二次校验权限：列表页授权过不代表详情页一定还能读
        if (!LocalVideoMedia.canReadLocalVideo(this)) {
            renderResult(FolderLoadResult.Denied)
            return
        }
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { loadVideosInternal() }
            } catch (e: Throwable) {
                android.util.Log.e("LocalVideoFolder", "读取文件夹失败: ${e.message}", e)
                FolderLoadResult.Failed("读取失败：${e.javaClass.simpleName}")
            }
            if (isMultiSelectMode) exitMultiSelectMode()
            renderResult(result)
        }
    }

    /**
     * 以 MediaStore 为准取该文件夹的视频——列表页同样是查 MediaStore，两边同源，
     * 不会再出现「列表显示有视频、点进来说没有视频」。
     * File.listFiles() 只作为兜底（需要「所有文件访问权限」）。
     */
    private fun loadVideosInternal(): FolderLoadResult {
        val mediaResult = runCatching { queryVideosFromMediaStore() }
        val mediaError = mediaResult.exceptionOrNull()
        val mediaVideos = mediaResult.getOrNull().orEmpty()
        if (mediaVideos.isNotEmpty()) {
            return FolderLoadResult.Ok(mediaVideos.sortedBy { it.name })
        }
        if (mediaError != null) {
            android.util.Log.e("LocalVideoFolder", "MediaStore 查询失败: ${mediaError.message}", mediaError)
        }

        // 兜底一：文件系统（仅当有文件访问权限时才可能列出内容）
        val listing = LocalVideoMedia.listDirectory(folderPath)
        if (listing != null) {
            // 同一套排除判定：只剩 .ts 的目录在这里就变成空列表，走 Empty 态而不是假装没这回事
            val excluded = LocalVideoMedia.excludedExtensions(this)
            val videos = listing
                .filter { it.isFile && LocalVideoMedia.hasVideoExtension(it.name, excluded) }
                .map { toVideoFile(it) }
                .sortedBy { it.name }
            return if (videos.isEmpty()) FolderLoadResult.Empty else FolderLoadResult.Ok(videos)
        }

        // 兜底二：都读不到，区分原因，不能一律说「文件夹没有视频」
        return when {
            !LocalVideoMedia.hasAllFilesAccess() && LocalVideoMedia.hasMediaReadPermission(this) ->
                FolderLoadResult.NoFileAccess
            folderPath.isNullOrBlank() -> FolderLoadResult.Failed("没有指定要打开的文件夹")
            !LocalVideoMedia.directoryExists(folderPath) -> FolderLoadResult.Failed("文件夹不存在或已被移动")
            mediaError != null -> FolderLoadResult.Failed("读取失败：${mediaError.javaClass.simpleName}")
            else -> FolderLoadResult.Failed("读取目录失败，可能没有访问权限")
        }
    }

    /** MediaStore 查询：优先 BUCKET_ID，其次路径前缀，最后相对路径（兼容只传 FOLDER_PATH 的旧入口）。 */
    private fun queryVideosFromMediaStore(): List<VideoFile> {
        var rows: List<LocalVideoMedia.MediaVideo> = emptyList()
        if (folderBucketId != LocalVideoMedia.NO_BUCKET_ID) {
            rows = LocalVideoMedia.queryInBucket(this, folderBucketId)
        }
        if (rows.isEmpty() && !folderPath.isNullOrBlank()) {
            rows = LocalVideoMedia.queryInDirectoryPath(this, folderPath)
        }
        if (rows.isEmpty() && !folderRelativePath.isNullOrBlank()) {
            rows = LocalVideoMedia.queryInRelativePath(this, folderRelativePath)
        }
        return rows.map { row ->
            VideoFile(
                name = row.name,
                path = row.path ?: "",
                size = row.size,
                thumbnail = LocalVideoMedia.loadThumbnail(this, row.id),
                videoId = row.id
            )
        }
    }

    /** 文件系统兜底扫描时把 File 转成列表项，顺带补 MediaStore ID 用于缩略图与删除。 */
    private fun toVideoFile(file: File): VideoFile {
        val path = file.absolutePath
        val videoId = LocalVideoMedia.queryIdByPath(this, path)
        return VideoFile(
            name = file.name,
            path = path,
            size = file.length(),
            thumbnail = LocalVideoMedia.loadThumbnail(this, videoId),
            videoId = videoId
        )
    }

    private fun renderResult(result: FolderLoadResult) {
        when (result) {
            is FolderLoadResult.Ok -> {
                currentVideos = result.videos
                adapter.submitList(result.videos)
                hideEmpty()
            }
            FolderLoadResult.Empty -> {
                currentVideos = emptyList()
                adapter.submitList(emptyList())
                showEmpty(emptyMessage(), null, null)
            }
            FolderLoadResult.Denied -> {
                currentVideos = emptyList()
                adapter.submitList(emptyList())
                showEmpty("没有读取视频的权限，无法列出该文件夹", "去授权") { requestReadPermission() }
            }
            FolderLoadResult.NoFileAccess -> {
                currentVideos = emptyList()
                adapter.submitList(emptyList())
                showEmpty(
                    "系统媒体库里找不到该文件夹的视频，应用也没有文件访问权限，读不到内容",
                    "去授权"
                ) { openAllFilesAccessSettings() }
            }
            is FolderLoadResult.Failed -> {
                currentVideos = emptyList()
                adapter.submitList(emptyList())
                showEmpty(result.reason, "重试") { loadVideos() }
            }
        }
    }

    /**
     * 空态文案：排除规则生效时要说明「不是没有文件，是被排除挡掉了」，
     * 否则只剩 .ts 分片的目录看起来像空白无提示。
     */
    private fun emptyMessage(): String {
        val excluded = LocalVideoMedia.excludedExtensions(this)
        if (excluded.isEmpty()) return "该文件夹没有视频"
        return "该文件夹没有可播放的视频（已排除 ${excluded.joinToString(", ") { ".$it" }}）"
    }

    private fun showEmpty(message: String, actionText: String?, action: (() -> Unit)?) {
        binding.emptyText.text = message
        if (actionText != null && action != null) {
            binding.emptyAction.visibility = View.VISIBLE
            binding.emptyAction.text = actionText
            binding.emptyAction.setOnClickListener { action.invoke() }
        } else {
            binding.emptyAction.visibility = View.GONE
            binding.emptyAction.setOnClickListener(null)
        }
        binding.emptyView.visibility = View.VISIBLE
    }

    private fun hideEmpty() {
        binding.emptyView.visibility = View.GONE
        binding.emptyAction.visibility = View.GONE
        binding.emptyAction.setOnClickListener(null)
    }

    private fun requestReadPermission() {
        try {
            requestPermissionLauncher.launch(LocalVideoMedia.requiredPermission())
        } catch (e: Throwable) {
            android.util.Log.e("LocalVideoFolder", "申请读取权限失败: ${e.message}", e)
            openAllFilesAccessSettings()
        }
    }

    /** 打开「所有文件访问权限」设置；返回时 onResume 会重新加载。 */
    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                reloadOnResume = true
                startActivity(intent)
                return
            } catch (e: Throwable) {
                android.util.Log.e("LocalVideoFolder", "打开所有文件访问权限失败: ${e.message}", e)
            }
        }
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            reloadOnResume = true
            startActivity(intent)
        } catch (e: Throwable) {
            android.util.Log.e("LocalVideoFolder", "打开应用设置失败: ${e.message}", e)
            Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onBackPressed() {
        if (isMultiSelectMode) {
            exitMultiSelectMode()
        } else {
            super.onBackPressed()
        }
    }
}
