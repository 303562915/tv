package com.tv.ball.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.tv.ball.databinding.ActivityMainBinding
import com.tv.ball.presentation.favorite.FavoriteFragment
import com.tv.ball.presentation.history.HistoryFragment
import com.tv.ball.presentation.localvideo.LocalVideoActivity
import com.tv.ball.presentation.profile.ProfileFragment
import com.tv.ball.utils.StatusBarUtils

/**
 * 主界面：历史 / 收藏 / 我的
 *
 * 无需登录，所有数据保存在本机。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private enum class Tab { HISTORY, FAVORITE, PROFILE }

    private var currentTab: Tab = Tab.HISTORY

    companion object {
        private const val REQUEST_STORAGE_PERMISSION = 100
        private const val REQUEST_MANAGE_STORAGE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val nightMode = com.tv.ball.di.Dependencies.configRepository.getNightMode()
        com.tv.ball.utils.ThemeUtils.applyNightMode(nightMode)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        StatusBarUtils.setupTransparentStatusBar(this)

        requestStoragePermission()

        binding.fabOpenLocalVideo.setOnClickListener {
            startActivity(Intent(this, LocalVideoActivity::class.java))
        }

        setupBottomNav(savedInstanceState == null)
    }

    /**
     * 悬浮胶囊底栏：选中项展开为浅色胶囊（图标 + 文字），其余只显示图标。
     */
    private fun setupBottomNav(initial: Boolean) {
        binding.navHistory.setOnClickListener { selectTab(Tab.HISTORY) }
        binding.navFavorite.setOnClickListener { selectTab(Tab.FAVORITE) }
        binding.navProfile.setOnClickListener { selectTab(Tab.PROFILE) }

        if (initial) {
            selectTab(Tab.HISTORY)
        } else {
            renderTab(currentTab, animate = false)
        }
    }

    private fun selectTab(tab: Tab) {
        if (tab == currentTab && supportFragmentManager.fragments.isNotEmpty()) {
            renderTab(tab, animate = true)
            return
        }
        currentTab = tab
        renderTab(tab, animate = true)
        loadFragment(
            when (tab) {
                Tab.HISTORY -> HistoryFragment()
                Tab.FAVORITE -> FavoriteFragment()
                Tab.PROFILE -> ProfileFragment()
            }
        )
    }

    private fun renderTab(tab: Tab, animate: Boolean) {
        val items = listOf(
            Triple(binding.navHistory, binding.iconHistory, binding.labelHistory),
            Triple(binding.navFavorite, binding.iconFavorite, binding.labelFavorite),
            Triple(binding.navProfile, binding.iconProfile, binding.labelProfile)
        )
        val selectedIndex = when (tab) {
            Tab.HISTORY -> 0
            Tab.FAVORITE -> 1
            Tab.PROFILE -> 2
        }
        items.forEachIndexed { index, (container, icon, label) ->
            val selected = index == selectedIndex
            container.isSelected = selected
            icon.isSelected = selected
            if (selected) {
                label.alpha = 0f
                label.visibility = View.VISIBLE
                val end = if (animate) 1f else 1f
                label.animate().alpha(end).setDuration(if (animate) 160 else 0).start()
            } else {
                label.animate().cancel()
                label.alpha = 0f
                label.visibility = View.GONE
            }
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_MEDIA_VIDEO),
                    REQUEST_STORAGE_PERMISSION
                )
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
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
                    .setTitle("需要读取视频权限")
                    .setMessage("没有该权限将无法浏览设备上的本地视频，你可以稍后在系统设置中开启。")
                    .setPositiveButton("去设置") { _, _ ->
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        })
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }
}

