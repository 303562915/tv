package com.tv.ball.presentation.localvideo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.tv.ball.R
import com.tv.ball.databinding.ActivityLocalVideoBinding
import com.tv.ball.presentation.player.VideoPlayerActivity
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
