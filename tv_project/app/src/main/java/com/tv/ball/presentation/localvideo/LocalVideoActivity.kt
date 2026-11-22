package com.tv.ball.presentation.localvideo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.tv.ball.R
import com.tv.ball.databinding.ActivityLocalVideoBinding
import com.tv.ball.presentation.player.VideoPlayerActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class LocalVideoActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityLocalVideoBinding
    private val viewModel: LocalVideoViewModel by lazy {
        // ViewModelProvider 走反射创建；万一工厂路径出问题（镜像 ROM / 类加载异常），
        // 退回直接构造，宁可少一份 ViewModelStore 托管也不能让入口闪退。
        try {
            ViewModelProvider(this)[LocalVideoViewModel::class.java]
        } catch (e: Throwable) {
            android.util.Log.e("LocalVideoActivity", "创建 LocalVideoViewModel 失败: ${e.message}", e)
            LocalVideoViewModel()
        }
    }
    private lateinit var adapter: LocalVideoAdapter

    /**
     * 「正在扫描…」提示行。
     *
     * 布局里只有居中的 ProgressBar 与空态视图，列表上方没有现成的提示行，
     * 这里在代码里补一行（不改 activity_local_video.xml），扫描完成即隐藏。
     */
    private var scanningLabel: TextView? = null
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.loadLocalVideos()
        } else {
            Toast.makeText(this, "需要存储权限才能访问本地视频", Toast.LENGTH_LONG).show()
            finish()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 页面自身初始化再兜一层：布局/状态栏/适配器任一环节抛异常时，
        // 就地显示异常摘要并保留已建好的内容，而不是让进程整个退出。
        try {
            binding = ActivityLocalVideoBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // 设置透明沉浸式状态栏
            com.tv.ball.utils.StatusBarUtils.setupTransparentStatusBar(this)

            setupRecyclerView()
            setupSortAndFilterEntries()
            observeViewModel()
            checkPermissionAndLoad()
        } catch (e: Throwable) {
            android.util.Log.e("LocalVideoActivity", "本地视频页初始化失败", e)
            showInitFailure(e)
        }
    }

    /** 初始化失败时的兜底提示：给出异常类型与消息，方便截图反馈。 */
    private fun showInitFailure(e: Throwable) {
        val detail = "${e.javaClass.name}\n\n${e.message ?: "无详情"}"
        try {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("本地视频页初始化失败")
                .setMessage(detail)
                .setPositiveButton("关闭") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        } catch (ignored: Throwable) {
            Toast.makeText(this, "本地视频页初始化失败：${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            finish()
        }
    }
    
    private fun setupRecyclerView() {
        adapter = LocalVideoAdapter { videoFolder ->
            val intent = Intent(this, LocalVideoFolderActivity::class.java)
            // 绝对路径可能失真（作用域存储下 _data 不一定可读），
            // 所以把 MediaStore 的 BUCKET_ID / RELATIVE_PATH 一起传过去，
            // 详情页优先按它们查，路径只作旧入口兼容与兜底。
            intent.putExtra("FOLDER_PATH", videoFolder.folderPath)
            intent.putExtra("FOLDER_NAME", videoFolder.folderName)
            intent.putExtra("FOLDER_BUCKET_ID", videoFolder.bucketId)
            intent.putExtra("FOLDER_RELATIVE_PATH", videoFolder.relativePath)
            startActivity(intent)
        }
        
        binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
        binding.recyclerView.adapter = adapter
        
        // 设置下拉刷新
        binding.swipeRefreshLayout.setOnRefreshListener {
            viewModel.loadLocalVideos()
        }
        binding.swipeRefreshLayout.setColorSchemeResources(
            R.color.theme_primary,
            R.color.theme_accent
        )

        setupScanningLabel()
    }

    /**
     * 顶部两个入口：排序 / 筛选。
     * 两者都只是把选择交给 ViewModel，由它在内存里对原始列表重排重筛，不重新查 MediaStore。
     */
    private fun setupSortAndFilterEntries() {
        binding.tvSortEntry.setOnClickListener { showSortDialog() }
        binding.tvFilterEntry.setOnClickListener { showFilterDialog() }
    }

    /** 排序弹窗：单选，选中即生效。 */
    private fun showSortDialog() {
        val options = LocalVideoSortOption.values()
        val labels = options.map { it.label }.toTypedArray()
        val checked = options.indexOf(viewModel.sortOption.value).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("排序方式")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                viewModel.setSortOption(options[which])
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 筛选弹窗：单选。
     * 前两项是固定项（全部 / 仅视频文件），后面把当前扫描到的目录逐个列出来，
     * 选中某个目录即「只看这个文件夹」。
     */
    private fun showFilterDialog() {
        val folders = viewModel.folderChoices()
        val options = buildList {
            add(LocalVideoFilterOption.All)
            add(LocalVideoFilterOption.VideoOnly)
            folders.forEach { folder ->
                add(LocalVideoFilterOption.Folder(folder.folderPath, folder.folderName))
            }
        }
        val labels = options.map { it.label }.toTypedArray()
        val current = viewModel.filterOption.value
        // 选中项可能已经被重新扫描淘汰（目录被删掉了），这时按「全部」高亮
        val checked = options.indexOfFirst { sameFilter(it, current) }.coerceAtLeast(0)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("筛选")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                viewModel.setFilterOption(options[which])
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 筛选项是否等价：object 比身份，Folder 比路径。 */
    private fun sameFilter(a: LocalVideoFilterOption, b: LocalVideoFilterOption): Boolean =
        when {
            a is LocalVideoFilterOption.Folder && b is LocalVideoFilterOption.Folder ->
                a.folderPath == b.folderPath
            else -> a === b
        }

    /** 在列表上方补一行「正在扫描…」，扫描结束（isLoading=false）时隐藏。 */
    private fun setupScanningLabel() {
        val root = binding.root as? ConstraintLayout ?: return
        val label = TextView(this).apply {
            id = View.generateViewId()
            text = "正在扫描…"
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@LocalVideoActivity, R.color.theme_text_secondary))
            setBackgroundColor(ContextCompat.getColor(this@LocalVideoActivity, R.color.surface_card))
            val padV = (6 * resources.displayMetrics.density).toInt()
            setPadding(0, padV, 0, padV)
            visibility = View.GONE
            elevation = 2f
        }
        val params = ConstraintLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            // 挂在排序/筛选行下面，别把入口盖住
            topToBottom = binding.sortBar.id
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        }
        root.addView(label, params)
        scanningLabel = label
    }
    
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.videoFolders.collect { folders ->
                        adapter.submitList(folders)
                        binding.emptyView.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE
                        binding.swipeRefreshLayout.isRefreshing = false
                    }
                }
                
                launch {
                    viewModel.isLoading.collect { isLoading ->
                        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                        if (!isLoading) {
                            binding.swipeRefreshLayout.isRefreshing = false
                        }
                    }
                }

                // 入口文案跟着状态走：排序/筛选一改，标题上就能看见当前口径
                launch {
                    viewModel.sortOption.collect { option ->
                        binding.tvSortEntry.text = "排序：${option.label}"
                    }
                }

                launch {
                    viewModel.filterOption.collect { option ->
                        binding.tvFilterEntry.text = "筛选：${option.label}"
                    }
                }
            }
        }
    }
    
    private fun checkPermissionAndLoad() {
        // 权限查询 / 拉起授权框在部分定制 ROM 上会抛异常（权限组被裁剪、Activity 已销毁），
        // 这里兜住并给出可读提示，不让入口闪退。
        try {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_VIDEO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

            when {
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                    viewModel.loadLocalVideos()
                }
                else -> {
                    requestPermissionLauncher.launch(permission)
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("LocalVideoActivity", "检查存储权限失败: ${e.message}", e)
            Toast.makeText(this, "无法读取本地视频：${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }
}
