package com.tv.ball.presentation.download

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tv.ball.databinding.ActivityDownloadBinding
import com.tv.ball.domain.downloader.DownloadStatus
import com.tv.ball.domain.downloader.DownloadTask
import com.tv.ball.domain.downloader.VideoDownloader
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class DownloadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadBinding
    private lateinit var videoDownloader: VideoDownloader
    private lateinit var adapter: DownloadAdapter
    private var isFabMenuOpen = false
    private var isSelectionMode = false

    companion object {
        private const val TAG = "DownloadActivity"
        private const val REQUEST_STORAGE_PERMISSION = 100
        private const val REQUEST_MANAGE_STORAGE = 101
        private const val REQUEST_FILE_IMPORT = 102
        private const val REQUEST_FILE_PICKER = 103
        private const val REQUEST_NOTIFICATION_PERMISSION = 104

        /** 进行中任务的进度刷新节流窗口：500ms 攒一批，只更新进度条 / 速度 / 剩余时间。 */
        private const val PROGRESS_REFRESH_INTERVAL_MS = 500L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 状态栏跟随页面底色，与工具条同一层
        window.statusBarColor = androidx.core.content.ContextCompat.getColor(
            this, com.tv.ball.R.color.theme_background
        )

        videoDownloader = VideoDownloader.getInstance(this)

        // 初始化UI状态
        // binding.btnGlobal.visibility = View.GONE  // 默认隐藏浏览器按钮（按钮不存在）

        // 移除 DrawerLayout 的遮罩（scrim）
        binding.drawerLayout.setScrimColor(android.graphics.Color.TRANSPARENT)

        setupRecyclerView()
        setupTabs()
        setupButtons()
        setupDrawerButtons()  // 设置侧边栏按钮
        setupSaveDirInfo()
        observeDownloads()
        checkStoragePermission()
        ensureNotificationPermission()
        
        // 启动时自动检查并合并 M3U8 任务
        autoCheckAndMergeM3u8()
    }

    /** 回到页面时刷新保存目录：外部存储挂载状态 / 任务落盘目录可能已经变了。 */
    override fun onResume() {
        super.onResume()
        if (::videoDownloader.isInitialized) {
            updateSaveDirInfo(videoDownloader.tasksFlow.value)
        }
    }

    private val tabSelectedColor: Int by lazy {
        androidx.core.content.ContextCompat.getColor(this, com.tv.ball.R.color.theme_primary)
    }
    private val tabIdleColor: Int by lazy {
        androidx.core.content.ContextCompat.getColor(this, com.tv.ball.R.color.theme_text_secondary)
    }

    private var currentFilter = "all"

    /** 上一次真正提交给 adapter 的任务 id 序列，用来判断是「结构变了」还是「只有进度在动」。 */
    private var submittedTaskIds: List<Long> = emptyList()

    /** 节流窗口内攒下的进度快照：id -> 最新任务。 */
    private val pendingProgress = LinkedHashMap<Long, DownloadTask>()

    /** 上一次推给列表项的进度快照，避免同一批进度反复 notifyItemChanged。 */
    private val pushedProgress = HashMap<Long, DownloadTask>()

    private var progressFlushJob: Job? = null

    /** 上一次显示的保存目录文案，避免每次进度回调都重设文本。 */
    private var lastSaveDirText: String? = null

    /**
     * 保存目录行：文本「保存目录：<真实路径>」（界面上中间省略），
     * 点一下把完整路径 Toast 出来。完整路径存在 tag 里，不靠解析被省略的文本。
     */
    private fun setupSaveDirInfo() {
        binding.tvSaveDir.setOnClickListener {
            val fullPath = binding.tvSaveDir.tag as? String
            if (!fullPath.isNullOrBlank()) {
                Toast.makeText(this, fullPath, Toast.LENGTH_LONG).show()
            }
        }
        updateSaveDirInfo(videoDownloader.tasksFlow.value)
    }

    /**
     * 下载器默认落盘目录：外部存储 Downloads/TVBall，
     * 与 VideoDownloader.getDownloadPath 里的目录常量一致。
     */
    private fun defaultDownloadDir(): String = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "TVBall"
    ).absolutePath

    /**
     * 顶部「保存目录」行：优先取任一任务 savePath 的父目录（真实落盘位置），
     * 没有任务时用下载器的默认目录；两者都拿不到就整行 GONE，绝不留占位文案。
     */
    private fun updateSaveDirInfo(tasks: List<DownloadTask>) {
        val taskDir = tasks.asSequence()
            .mapNotNull { task ->
                task.savePath.takeIf { it.isNotBlank() }?.let { path -> File(path).parent }
            }
            .firstOrNull { it.isNotBlank() }

        val dir = taskDir ?: runCatching { defaultDownloadDir() }.getOrNull()

        if (dir.isNullOrBlank()) {
            lastSaveDirText = null
            binding.tvSaveDir.tag = null
            binding.tvSaveDir.visibility = View.GONE
            return
        }

        val text = "保存目录：$dir"
        if (text != lastSaveDirText) {
            lastSaveDirText = text
            binding.tvSaveDir.text = text
            binding.tvSaveDir.tag = dir
            binding.tvSaveDir.contentDescription = "下载保存目录：$dir，点按查看完整路径"
        }
        binding.tvSaveDir.visibility = View.VISIBLE
    }

    /** 按当前标签页过滤；筛选口径集中在这里，避免两处各写一份。 */
    private fun filteredTasks(allTasks: List<DownloadTask>): List<DownloadTask> {
        return when (currentFilter) {
            "downloading" -> allTasks.filter {
                it.status == DownloadStatus.DOWNLOADING ||
                    it.status == DownloadStatus.MERGING ||
                    it.status == DownloadStatus.PAUSED
            }
            "completed" -> allTasks.filter { it.status == DownloadStatus.COMPLETED }
            "error" -> allTasks.filter { it.status == DownloadStatus.FAILED }
            "scheduled" -> allTasks.filter { it.status == DownloadStatus.PENDING }
            else -> allTasks // "all"
        }
    }

    /**
     * 任务列表的统一入口。
     *
     * - 结构变了（增删任务、切标签、状态变化）→ 立刻 submitList，由 DiffUtil 只刷新变动行；
     * - 结构没变、只有进度/速度在跳 → 攒进 [pendingProgress]，每 500ms 批量
     *   [DownloadAdapter.updateProgress]，只更新进度条与速度/剩余时间文本。
     */
    private fun applyTasks(allTasks: List<DownloadTask>, forceSubmit: Boolean = false) {
        updateSaveDirInfo(allTasks)
        // 页面在前台也要发完成通知：服务侧可能因为「无活跃任务」先被停掉，
        // 与 DownloadCompletionNotifier 共享去重表，不会重复弹两条。
        DownloadCompletionNotifier.onTasks(this, allTasks)

        val filtered = filteredTasks(allTasks)

        val structureChanged = forceSubmit ||
            filtered.size != submittedTaskIds.size ||
            filtered.indices.any { submittedTaskIds[it] != filtered[it].id }

        val currentById = adapter.currentList.associateBy { it.id }
        val statusChanged = !structureChanged && filtered.any { task ->
            val old = currentById[task.id]
            old != null && old.status != task.status
        }

        if (structureChanged || statusChanged) {
            // 结构/状态变化：丢掉攒着的进度，走 DiffUtil 整批提交（不是整表刷新）
            pendingProgress.clear()
            progressFlushJob?.cancel()
            progressFlushJob = null
            pushedProgress.clear()
            submittedTaskIds = filtered.map { it.id }
            adapter.submitList(filtered)
            return
        }

        filtered.forEach { task ->
            val previous = pushedProgress[task.id] ?: currentById[task.id]
            if (previous == null) {
                pendingProgress[task.id] = task
                return@forEach
            }
            if (previous.progress != task.progress ||
                previous.speed != task.speed ||
                previous.downloadedSize != task.downloadedSize ||
                previous.totalSize != task.totalSize ||
                previous.partProgresses != task.partProgresses
            ) {
                pendingProgress[task.id] = task
            }
        }

        if (pendingProgress.isNotEmpty()) {
            scheduleProgressFlush()
        }
    }

    private fun scheduleProgressFlush() {
        if (progressFlushJob?.isActive == true) return
        progressFlushJob = lifecycleScope.launch {
            delay(PROGRESS_REFRESH_INTERVAL_MS)
            val batch = pendingProgress.values.toList()
            pendingProgress.clear()
            if (batch.isEmpty()) return@launch
            batch.forEach { pushedProgress[it.id] = it }
            adapter.updateProgress(batch)
        }
    }

    private fun setupTabs() {
        // 默认选中"全部"
        binding.tabAll.setTextColor(tabSelectedColor)
        
        binding.tabAll.setOnClickListener {
            selectTab(binding.tabAll)
            filterDownloads("all")
        }
        
        binding.tabDownloading.setOnClickListener {
            selectTab(binding.tabDownloading)
            filterDownloads("downloading")
        }
        
        binding.tabCompleted.setOnClickListener {
            selectTab(binding.tabCompleted)
            filterDownloads("completed")
        }
        
        binding.tabError.setOnClickListener {
            selectTab(binding.tabError)
            filterDownloads("error")
        }
        
        binding.tabScheduled.setOnClickListener {
            selectTab(binding.tabScheduled)
            filterDownloads("scheduled")
        }
    }
    
    private fun selectTab(selectedTab: android.widget.TextView) {
        // 重置所有标签颜色
        binding.tabAll.setTextColor(tabIdleColor)
        binding.tabDownloading.setTextColor(tabIdleColor)
        binding.tabCompleted.setTextColor(tabIdleColor)
        binding.tabError.setTextColor(tabIdleColor)
        binding.tabScheduled.setTextColor(tabIdleColor)
        
        // 设置选中标签为白色
        selectedTab.setTextColor(tabSelectedColor)
    }
    
    private fun filterDownloads(filter: String) {
        currentFilter = filter
        android.util.Log.d(TAG, "筛选下载任务: $filter")

        // 切标签是结构变化：直接整批提交（DiffUtil 会算出真正的增删）
        val allTasks = videoDownloader.tasksFlow.value
        applyTasks(allTasks, forceSubmit = true)
        android.util.Log.d(TAG, "筛选结果: ${adapter.itemCount} / ${allTasks.size} 个任务")
    }
    
    private fun setupButtons() {
        binding.btnMenu.setOnClickListener {
            if (isSelectionMode) {
                // 选择模式下，返回（退出选择模式）
                exitSelectionMode()
            } else {
                // 打开侧边栏
                binding.drawerLayout.openDrawer(android.view.Gravity.START)
            }
        }
        
        // Filter按钮 - 在选择模式下变成删除按钮
        binding.btnFilter.setOnClickListener {
            if (isSelectionMode) {
                // 删除选中的任务
                deleteSelectedTasks()
            }
        }
        
        // Global按钮 - 在选择模式下变成浏览器按钮（按钮不存在，已注释）
        // binding.btnGlobal.setOnClickListener {
        //     if (isSelectionMode) {
        //         // 在浏览器中打开选中的链接
        //         openSelectedInBrowser()
        //     } else {
        //         // 正常模式下的功能（如果有的话）
        //     }
        // }
        
        // 主FAB按钮
        binding.fabMain.setOnClickListener {
            toggleFabMenu()
        }
        
        // 遮罩层点击关闭菜单
        binding.fabOverlay.setOnClickListener {
            closeFabMenu()
        }
        
        // 从文本文件导入 - 整个LinearLayout可点击
        binding.fabImportFile.setOnClickListener {
            closeFabMenu()
            importFromFile()
        }
        
        // 从剪贴板 - 整个LinearLayout可点击
        binding.fabImportClipboard.setOnClickListener {
            closeFabMenu()
            importFromClipboard()
        }
        
        // 添加链接 - 整个LinearLayout可点击
        binding.fabAddLink.setOnClickListener {
            closeFabMenu()
            showAddDownloadDialog()
        }
    }
    
    /**
     * 设置侧边栏按钮
     */
    private fun setupDrawerButtons() {
        // 侧边栏在 DrawerLayout 中，需要通过 findViewById 获取
        val drawerView = binding.drawerLayout.getChildAt(1) // 第二个子视图是侧边栏
        
        // 检查并合并按钮
        val btnCheckAndMerge = drawerView?.findViewById<android.widget.Button>(com.tv.ball.R.id.btnCheckAndMerge)
        btnCheckAndMerge?.setOnClickListener {
            android.util.Log.d(TAG, "点击了检查并合并按钮")
            checkAndMergeM3u8Tasks()
        }
        
        // 同时下载数滑动条
        val tvConcurrentDownloads = drawerView?.findViewById<android.widget.TextView>(com.tv.ball.R.id.tvConcurrentDownloads)
        val seekBarConcurrent = drawerView?.findViewById<android.widget.SeekBar>(com.tv.ball.R.id.seekBarConcurrentDownloads)
        
        seekBarConcurrent?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val count = if (progress < 1) 1 else progress
                tvConcurrentDownloads?.text = "同时下载：$count"
                // TODO: 保存设置
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        
        // 多线程数滑动条
        val tvThreadCount = drawerView?.findViewById<android.widget.TextView>(com.tv.ball.R.id.tvThreadCount)
        val seekBarThreadCount = drawerView?.findViewById<android.widget.SeekBar>(com.tv.ball.R.id.seekBarThreadCount)
        
        // 从 SharedPreferences 读取保存的线程数
        val prefs = getSharedPreferences("download_settings", MODE_PRIVATE)
        val savedThreadCount = prefs.getInt("thread_count", 10)  // 默认10个线程
        seekBarThreadCount?.progress = savedThreadCount
        tvThreadCount?.text = "多线程数：$savedThreadCount"
        
        seekBarThreadCount?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val count = if (progress < 1) 1 else progress
                tvThreadCount?.text = "多线程数：$count"
                
                // 保存到 SharedPreferences
                if (fromUser) {
                    prefs.edit().putInt("thread_count", count).apply()
                    android.util.Log.d(TAG, "保存线程数设置: $count")
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                val count = seekBar?.progress ?: 4
                Toast.makeText(this@DownloadActivity, "多线程数已设置为 $count", Toast.LENGTH_SHORT).show()
            }
        })
        
        if (btnCheckAndMerge == null) {
            android.util.Log.e(TAG, "未找到检查并合并按钮")
        } else {
            android.util.Log.d(TAG, "成功绑定侧边栏控件")
        }
    }
    
    private fun enterSelectionMode() {
        isSelectionMode = true
        adapter.enterSelectionMode()
        updateSelectionModeUI(0)
    }
    
    private fun exitSelectionMode() {
        isSelectionMode = false
        adapter.clearSelection()
        updateSelectionModeUI(0)
    }
    
    private fun updateSelectionModeUI(selectedCount: Int) {
        if (isSelectionMode) {
            // 保持标签页显示
            binding.tabAll.visibility = View.VISIBLE
            binding.tabDownloading.visibility = View.VISIBLE
            binding.tabCompleted.visibility = View.VISIBLE
            binding.tabError.visibility = View.VISIBLE
            binding.tabScheduled.visibility = View.VISIBLE
            
            // 更改菜单图标为返回箭头
            binding.btnMenu.setImageResource(android.R.drawable.ic_menu_revert)
            
            // 更改Filter图标为删除图标
            binding.btnFilter.setImageResource(android.R.drawable.ic_menu_delete)
            binding.btnFilter.visibility = View.VISIBLE
            
            // Global按钮显示为浏览器图标（按钮不存在，已注释）
            // binding.btnGlobal.setImageResource(com.tv.ball.R.drawable.ic_webpage)
            // binding.btnGlobal.clearColorFilter() // 清除tint，使用图标原色
            // binding.btnGlobal.visibility = View.VISIBLE
            
            // 隐藏U文字和更多按钮
            binding.btnMore.visibility = View.GONE
            
        } else {
            // 恢复标签页
            binding.tabAll.visibility = View.VISIBLE
            binding.tabDownloading.visibility = View.VISIBLE
            binding.tabCompleted.visibility = View.VISIBLE
            binding.tabError.visibility = View.VISIBLE
            binding.tabScheduled.visibility = View.VISIBLE
            
            // 恢复菜单图标
            binding.btnMenu.setImageResource(com.tv.ball.R.drawable.menu)
            
            // 恢复Filter图标
            binding.btnFilter.setImageResource(com.tv.ball.R.drawable.filter)
            
            // 隐藏Global按钮（按钮不存在，已注释）
            // binding.btnGlobal.visibility = View.GONE
            
            // 显示更多按钮
            binding.btnMore.visibility = View.VISIBLE
        }
    }
    
    private fun deleteSelectedTasks() {
        val selectedTasks = adapter.getSelectedTasks()
        if (selectedTasks.isEmpty()) {
            Toast.makeText(this, "请先选择要删除的任务", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 显示确认对话框，带"同时删除本地文件"选项
        val checkBox = android.widget.CheckBox(this).apply {
            text = "同时删除本地文件"
            isChecked = false
            setPadding(50, 30, 50, 30)
        }
        
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
            
            val messageText = android.widget.TextView(this@DownloadActivity).apply {
                text = "确定要从 IDM+ 列表中删除选择的下载?"
                textSize = 16f
                setTextColor(androidx.core.content.ContextCompat.getColor(this@DownloadActivity, com.tv.ball.R.color.theme_text_primary))
                setPadding(0, 0, 0, 20)
            }
            
            addView(messageText)
            addView(checkBox)
        }
        
        AlertDialog.Builder(this)
            .setTitle("确认")
            .setView(container)
            .setPositiveButton("删除") { _, _ ->
                val deleteFile = checkBox.isChecked
                selectedTasks.forEach { task ->
                    videoDownloader.deleteTask(task.id, deleteFile)
                }
                
                val message = if (deleteFile) {
                    "已删除 ${selectedTasks.size} 个任务及文件"
                } else {
                    "已删除 ${selectedTasks.size} 个任务"
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                exitSelectionMode()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun openSelectedInBrowser() {
        val selectedTasks = adapter.getSelectedTasks()
        if (selectedTasks.isEmpty()) {
            Toast.makeText(this, "请先选择任务", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 打开第一个选中任务的链接
        val task = selectedTasks.first()
        openInBrowser(task.url.replace("\\/", "/"))
    }
    
    private fun toggleFabMenu() {
        if (isFabMenuOpen) {
            closeFabMenu()
        } else {
            openFabMenu()
        }
    }
    
    private fun openFabMenu() {
        isFabMenuOpen = true
        
        // 显示遮罩层
        binding.fabOverlay.visibility = View.VISIBLE
        binding.fabOverlay.alpha = 0f
        binding.fabOverlay.animate()
            .alpha(1f)
            .setDuration(200)
            .start()
        
        // 旋转主按钮
        binding.fabMain.animate()
            .rotation(45f)
            .setDuration(200)
            .start()
        
        // 显示并动画子按钮
        animateFabIn(binding.fabAddLink, 80)
        animateFabIn(binding.fabImportClipboard, 150)
        animateFabIn(binding.fabImportFile, 220)
    }
    
    private fun closeFabMenu() {
        isFabMenuOpen = false
        
        // 隐藏遮罩层
        binding.fabOverlay.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                binding.fabOverlay.visibility = View.GONE
            }
            .start()
        
        // 旋转主按钮回原位
        binding.fabMain.animate()
            .rotation(0f)
            .setDuration(200)
            .start()
        
        // 隐藏子按钮
        animateFabOut(binding.fabAddLink)
        animateFabOut(binding.fabImportClipboard)
        animateFabOut(binding.fabImportFile)
    }
    
    private fun animateFabIn(view: View, translationY: Int) {
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.translationY = 20f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200)
            .start()
    }
    
    private fun animateFabOut(view: View) {
        view.animate()
            .alpha(0f)
            .translationY(20f)
            .setDuration(200)
            .withEndAction {
                view.visibility = View.GONE
            }
            .start()
    }
    
    private fun importFromFile() {
        // 打开自定义文件选择器
        val intent = Intent(this, FilePickerActivity::class.java)
        startActivityForResult(intent, REQUEST_FILE_PICKER)
    }
    
    private fun importFromClipboard() {
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clipData = clipboard.primaryClip
        
        if (clipData != null && clipData.itemCount > 0) {
            val text = clipData.getItemAt(0).text?.toString()
            if (!text.isNullOrEmpty()) {
                // 检查是否是有效的URL
                if (text.startsWith("http://") || text.startsWith("https://")) {
                    showAddDownloadDialog(text)
                } else {
                    Toast.makeText(this, "剪贴板中没有有效的链接", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showAddDownloadDialog() {
        showAddDownloadDialog(null)
    }
    
    private fun showAddDownloadDialog(prefilledUrl: String? = null) {
        android.util.Log.d(TAG, "显示下载配置对话框，预填URL: $prefilledUrl")
        
        val dialog = if (prefilledUrl != null) {
            DownloadConfigDialog.newInstance(prefilledUrl, "视频")
        } else {
            DownloadConfigDialog.newInstance("", "视频")
        }
        
        dialog.setOnStartDownloadListener { url, title, wifiOnly, customHeaders, threadCount ->
            android.util.Log.d(TAG, "=== 开始添加下载任务 ===")
            android.util.Log.d(TAG, "URL: $url")
            android.util.Log.d(TAG, "标题: $title")
            android.util.Log.d(TAG, "线程数: $threadCount")
            android.util.Log.d(TAG, "自定义请求头: $customHeaders")
            
            // 创建并开始下载任务
            val taskId = videoDownloader.addDownloadTask(url, title, customHeaders, threadCount)
            android.util.Log.d(TAG, "任务ID: $taskId")
            
            videoDownloader.startDownload(taskId)
            android.util.Log.d(TAG, "已调用 startDownload")
            
            Toast.makeText(this, "开始下载: $title", Toast.LENGTH_SHORT).show()
        }
        
        dialog.show(supportFragmentManager, "DownloadConfigDialog")
    }

    private fun setupRecyclerView() {
        adapter = DownloadAdapter(
            onPauseClick = { task ->
                android.util.Log.d(TAG, "暂停下载: ${task.title}")
                videoDownloader.pauseDownload(task.id)
            },
            onResumeClick = { task ->
                android.util.Log.d(TAG, "=== DownloadActivity.onResumeClick ===")
                android.util.Log.d(TAG, "任务: ${task.title}")
                android.util.Log.d(TAG, "任务ID: ${task.id}")
                android.util.Log.d(TAG, "状态: ${task.status}")
                
                try {
                    videoDownloader.resumeDownload(task.id)
                    android.util.Log.d(TAG, "resumeDownload 已调用")
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "resumeDownload 失败", e)
                    Toast.makeText(this, "恢复下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            },
            onDeleteClick = { task ->
                showDeleteConfirmDialog(task)
            },
            onPlayClick = { task ->
                // 播放已下载的视频，传递标题
                playDownloadedVideo(task.savePath, task.title)
            },
            onCopyLinkClick = { task ->
                // 复制下载链接
                copyLinkToClipboard(task.url)
            },
            onLongClick = { task ->
                // 长按进入选择模式
                enterSelectionMode()
            },
            onMergeClick = { task ->
                // 合并 M3U8 分片
                mergeM3u8Task(task)
            }
        )
        
        // 设置选择变化回调
        adapter.onSelectionChanged = { count ->
            updateSelectionModeUI(count)
        }

        binding.rvDownloads.apply {
            layoutManager = LinearLayoutManager(this@DownloadActivity)
            adapter = this@DownloadActivity.adapter
        }
    }

    private fun observeDownloads() {
        lifecycleScope.launch {
            videoDownloader.tasksFlow.collect { allTasks ->
                // 结构变化走 DiffUtil 提交；只有进度在动就进 500ms 节流窗口
                applyTasks(allTasks)
            }
        }
    }

    /**
     * Android 13+ 的首发通知需要运行时权限；Manifest 已声明 POST_NOTIFICATIONS，
     * 这里只做一次申请，用户拒了也不影响下载，只是完成通知不发。
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) return

        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATION_PERMISSION
        )
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11 及以上，检查所有文件访问权限
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("需要存储权限")
                    .setMessage("下载功能需要访问所有文件权限")
                    .setPositiveButton("去设置") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.data = Uri.parse("package:$packageName")
                            startActivityForResult(intent, REQUEST_MANAGE_STORAGE)
                        } catch (e: Exception) {
                            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            startActivityForResult(intent, REQUEST_MANAGE_STORAGE)
                        }
                    }
                    .setNegativeButton("取消") { _, _ ->
                        Toast.makeText(this, "没有存储权限将无法下载视频", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
        } else {
            // Android 10 及以下，检查传统存储权限
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_STORAGE_PERMISSION
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "需要存储权限才能下载视频", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playDownloadedVideo(filePath: String, title: String) {
        val intent = android.content.Intent(this, com.tv.ball.presentation.player.VideoPlayerActivity::class.java)
        intent.putExtra("VIDEO_URL", "file://$filePath")
        intent.putExtra("VIDEO_TITLE", title)  // 使用实际的视频标题
        startActivity(intent)
    }

    private fun copyLinkToClipboard(url: String) {
        // 清理转义字符
        val cleanUrl = url.replace("\\/", "/")
        
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("视频链接", cleanUrl)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "链接已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun showDeleteConfirmDialog(task: com.tv.ball.domain.downloader.DownloadTask) {
        // 显示确认对话框，带"同时删除本地文件"选项
        val checkBox = android.widget.CheckBox(this).apply {
            text = "同时删除本地文件"
            isChecked = false
            setPadding(50, 30, 50, 30)
        }
        
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
            
            val messageText = android.widget.TextView(this@DownloadActivity).apply {
                text = "确定要从 IDM+ 列表中删除 ${task.title} 吗?"
                textSize = 16f
                setTextColor(androidx.core.content.ContextCompat.getColor(this@DownloadActivity, com.tv.ball.R.color.theme_text_primary))
                setPadding(0, 0, 0, 20)
            }
            
            addView(messageText)
            addView(checkBox)
        }
        
        AlertDialog.Builder(this)
            .setTitle("确认")
            .setView(container)
            .setPositiveButton("删除") { _, _ ->
                val deleteFile = checkBox.isChecked
                videoDownloader.deleteTask(task.id, deleteFile)
                
                val message = if (deleteFile) {
                    "已删除任务及文件"
                } else {
                    "已删除任务"
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showTaskOptionsDialog(task: com.tv.ball.domain.downloader.DownloadTask) {
        val cleanUrl = task.url.replace("\\/", "/")
        
        val options = mutableListOf(
            "复制链接",
            "浏览器打开",
            "分享链接"
        )
        
        // 如果有错误信息，添加"查看错误详情"选项
        if (task.status == com.tv.ball.domain.downloader.DownloadStatus.FAILED && !task.errorMessage.isNullOrEmpty()) {
            options.add(0, "查看错误详情")
        }
        
        AlertDialog.Builder(this)
            .setTitle(task.title)
            .setItems(options.toTypedArray()) { _, which ->
                val adjustedIndex = if (task.status == com.tv.ball.domain.downloader.DownloadStatus.FAILED && !task.errorMessage.isNullOrEmpty()) {
                    when (which) {
                        0 -> {
                            showErrorDetailsDialog(task)
                            return@setItems
                        }
                        else -> which - 1
                    }
                } else {
                    which
                }
                
                when (adjustedIndex) {
                    0 -> copyLinkToClipboard(task.url)
                    1 -> openInBrowser(cleanUrl)
                    2 -> shareLink(task.title, cleanUrl)
                }
            }
            .show()
    }
    
    private fun showErrorDetailsDialog(task: com.tv.ball.domain.downloader.DownloadTask) {
        val errorDetails = buildString {
            append("文件名: ${task.fileName}\n\n")
            append("URL: ${task.url}\n\n")
            append("错误信息:\n${task.errorMessage}\n\n")
            append("进度: ${task.progress}%\n")
            append("已下载: ${task.downloadedSize} / ${task.totalSize}\n")
            append("状态: ${task.status}\n")
            
            if (task.customHeaders.isNotEmpty()) {
                append("\n自定义请求头:\n")
                task.customHeaders.forEach { (key, value) ->
                    append("$key: $value\n")
                }
            }
        }
        
        AlertDialog.Builder(this)
            .setTitle("错误详情")
            .setMessage(errorDetails)
            .setPositiveButton("复制错误信息") { _, _ ->
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("错误信息", errorDetails)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "错误信息已复制", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun openInBrowser(url: String) {
        try {
            val intent = Intent(this, com.tv.ball.presentation.webview.WebViewActivity::class.java)
            intent.putExtra("url", url)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开页面", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareLink(title: String, url: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_SUBJECT, title)
            intent.putExtra(Intent.EXTRA_TEXT, "$title\n\n$url")
            startActivity(Intent.createChooser(intent, "分享链接"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 不要清理下载器，让下载在后台继续
        // videoDownloader.cleanup()
    }
    
    override fun onBackPressed() {
        if (isFabMenuOpen) {
            closeFabMenu()
        } else if (isSelectionMode) {
            exitSelectionMode()
        } else {
            super.onBackPressed()
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_FILE_PICKER && resultCode == RESULT_OK) {
            val filePath = data?.getStringExtra(FilePickerActivity.EXTRA_FILE_PATH)
            if (filePath != null) {
                processTextFile(filePath)
            }
        } else if (requestCode == REQUEST_FILE_IMPORT && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    val content = inputStream?.bufferedReader()?.use { it.readText() }
                    inputStream?.close()
                    
                    if (!content.isNullOrEmpty()) {
                        processTextContent(content)
                    } else {
                        Toast.makeText(this, "文件为空", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "读取文件失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * 启动时自动检查并合并 M3U8 任务（静默执行）
     */
    private fun autoCheckAndMergeM3u8() {
        lifecycleScope.launch {
            try {
                // 延迟1秒后执行，避免影响启动速度
                kotlinx.coroutines.delay(1000)
                
                val mergedCount = videoDownloader.checkAndMergeM3u8Tasks()
                
                if (mergedCount > 0) {
                    // 只在成功合并时显示提示
                    Toast.makeText(
                        this@DownloadActivity,
                        "自动合并了 $mergedCount 个 M3U8 任务",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "自动检查合并失败", e)
                // 静默失败，不打扰用户
            }
        }
    }
    
    /**
     * 检查并合并已下载完成的 M3U8 任务
     */
    private fun checkAndMergeM3u8Tasks() {
        // 显示进度对话框
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("检查 M3U8 任务")
            .setMessage("正在检查已下载的 M3U8 分片...")
            .setCancelable(false)
            .create()
        progressDialog.show()
        
        lifecycleScope.launch {
            try {
                val mergedCount = videoDownloader.checkAndMergeM3u8Tasks()
                
                progressDialog.dismiss()
                
                if (mergedCount > 0) {
                    AlertDialog.Builder(this@DownloadActivity)
                        .setTitle("合并完成")
                        .setMessage("成功合并了 $mergedCount 个 M3U8 任务")
                        .setPositiveButton("确定", null)
                        .show()
                } else {
                    Toast.makeText(
                        this@DownloadActivity,
                        "没有找到需要合并的 M3U8 任务",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                
                // 关闭侧边栏
                binding.drawerLayout.closeDrawer(android.view.Gravity.START)
                
            } catch (e: Exception) {
                progressDialog.dismiss()
                android.util.Log.e(TAG, "检查合并失败", e)
                Toast.makeText(
                    this@DownloadActivity,
                    "检查失败: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    /**
     * 合并单个 M3U8 任务
     */
    private fun mergeM3u8Task(task: DownloadTask) {
        AlertDialog.Builder(this)
            .setTitle("合并 M3U8")
            .setMessage("确定要合并 ${task.title} 的分片吗？")
            .setPositiveButton("确定") { _, _ ->
                val progressDialog = AlertDialog.Builder(this)
                    .setTitle("合并中")
                    .setMessage("正在合并 M3U8 分片，请稍候...")
                    .setCancelable(false)
                    .create()
                progressDialog.show()
                
                lifecycleScope.launch {
                    try {
                        val success = videoDownloader.mergeM3u8Task(task.id)
                        
                        progressDialog.dismiss()
                        
                        if (success) {
                            Toast.makeText(
                                this@DownloadActivity,
                                "合并成功！",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                this@DownloadActivity,
                                "合并失败，请检查分片是否完整",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } catch (e: Exception) {
                        progressDialog.dismiss()
                        android.util.Log.e(TAG, "合并失败", e)
                        Toast.makeText(
                            this@DownloadActivity,
                            "合并失败: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun processTextFile(filePath: String) {
        try {
            val file = java.io.File(filePath)
            val content = file.readText()
            processTextContent(content)
        } catch (e: Exception) {
            Toast.makeText(this, "读取文件失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun processTextContent(content: String) {
        // 解析文件内容，每行一个URL
        val urls = content.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && (it.startsWith("http://") || it.startsWith("https://")) }
        
        if (urls.isNotEmpty()) {
            // 批量添加下载任务
            urls.forEach { url ->
                val fileName = url.substringAfterLast("/").substringBefore("?")
                val taskId = videoDownloader.addDownloadTask(url, fileName, emptyMap())
                videoDownloader.startDownload(taskId)
            }
            Toast.makeText(this, "已添加 ${urls.size} 个下载任务", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "文件中没有找到有效的URL", Toast.LENGTH_SHORT).show()
        }
    }
}

