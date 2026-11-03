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
        ViewModelProvider(this)[LocalVideoViewModel::class.java]
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
        binding = ActivityLocalVideoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // 设置透明沉浸式状态栏
        com.tv.ball.utils.StatusBarUtils.setupTransparentStatusBar(this)
        
        setupRecyclerView()
        observeViewModel()
        checkPermissionAndLoad()
    }
    
    private fun setupRecyclerView() {
        adapter = LocalVideoAdapter { videoFolder ->
            val intent = Intent(this, LocalVideoFolderActivity::class.java)
            intent.putExtra("FOLDER_PATH", videoFolder.folderPath)
            intent.putExtra("FOLDER_NAME", videoFolder.folderName)
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
    }
}
