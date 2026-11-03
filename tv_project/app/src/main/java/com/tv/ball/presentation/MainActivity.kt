package com.tv.ball.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.tv.ball.R
import com.tv.ball.databinding.ActivityMainBinding
import com.tv.ball.presentation.history.HistoryFragment
import com.tv.ball.presentation.home.HomeFragment
import com.tv.ball.presentation.profile.ProfileFragment
import com.tv.ball.presentation.videosource.VideoSourceFragment
import com.tv.ball.utils.StatusBarUtils

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    companion object {
        private const val REQUEST_STORAGE_PERMISSION = 100
        private const val REQUEST_MANAGE_STORAGE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 应用夜间模式设置（必须在 super.onCreate 之前）
        val nightMode = com.tv.ball.di.Dependencies.configRepository.getNightMode()
        com.tv.ball.utils.ThemeUtils.applyNightMode(nightMode)
        
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 设置透明沉浸式状态栏
        StatusBarUtils.setupTransparentStatusBar(this)

        // 请求存储权限
        requestStoragePermission()

        // 加载云端视频源配置
        loadCloudVideoSources()

        if (savedInstanceState == null) {
            loadFragment(HomeFragment())
        }
        
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_home -> {
                    loadFragment(HomeFragment())
                    true
                }
                R.id.navigation_history -> {
                    loadFragment(HistoryFragment())
                    true
                }
                R.id.navigation_local -> {
                    loadFragment(VideoSourceFragment())
                    true
                }
                R.id.navigation_profile -> {
                    loadFragment(ProfileFragment())
                    true
                }
                else -> false
            }
        }
    }
    
    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    private fun loadCloudVideoSources() {
        Thread {
            kotlinx.coroutines.runBlocking {
                com.tv.ball.data.VideoSourceConfigs.loadFromCloud()
            }
        }.start()
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11 及以上，请求所有文件访问权限
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("需要存储权限")
                    .setMessage("应用需要访问所有文件以保存下载的视频")
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
                    .setNegativeButton("取消", null)
                    .show()
            }
        } else {
            // Android 10 及以下，请求传统存储权限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ),
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
                AlertDialog.Builder(this)
                    .setTitle("权限被拒绝")
                    .setMessage("没有存储权限将无法下载视频")
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }
}
