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
import com.tv.ball.R
import com.tv.ball.databinding.ActivityMainBinding
import com.tv.ball.presentation.history.HistoryFragment
import com.tv.ball.presentation.home.HomeFragment
import com.tv.ball.presentation.localvideo.LocalVideoActivity
import com.tv.ball.presentation.profile.ProfileFragment
import com.tv.ball.presentation.videosource.VideoSourceFragment
import com.tv.ball.utils.StatusBarUtils

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private enum class Tab { HOME, HISTORY, SOURCE, PROFILE }

    private var currentTab: Tab = Tab.HOME

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

        binding.fabOpenLocalVideo.setOnClickListener {
            startActivity(Intent(this, LocalVideoActivity::class.java))
        }

        setupBottomNav(savedInstanceState == null)
    }

    /**
     * 悬浮胶囊底栏（仿 FlClash NavigationDock）：
     * 图标 + 文字常显，选中态是一颗在两项目间滑动的胶囊“透镜”，按下时透镜放大凸出胶囊边缘。
     */
    private fun setupBottomNav(initial: Boolean) {
        val items = navItems()
        items.forEachIndexed { index, (container, _, _) ->
            container.setOnClickListener { selectTab(Tab.entries[index]) }
            container.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        liftLens(true)
                        view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        liftLens(false)
                        view.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                    }
                }
                false
            }
        }

        // 首次布局完成后把透镜摆到初始项上
        binding.navCapsule.post {
            if (initial) selectTab(Tab.HOME) else renderTab(currentTab, animate = false)
        }
    }

    private fun navItems(): List<Triple<View, android.widget.ImageView, android.widget.TextView>> = listOf(
        Triple(binding.navHome, binding.iconHome, binding.labelHome),
        Triple(binding.navHistory, binding.iconHistory, binding.labelHistory),
        Triple(binding.navSource, binding.iconSource, binding.labelSource),
        Triple(binding.navProfile, binding.iconProfile, binding.labelProfile)
    )

    private fun selectTab(tab: Tab) {
        val sameTab = tab == currentTab && supportFragmentManager.fragments.isNotEmpty()
        currentTab = tab
        renderTab(tab, animate = true)
        if (sameTab) return
        loadFragment(
            when (tab) {
                Tab.HOME -> HomeFragment()
                Tab.HISTORY -> HistoryFragment()
                Tab.SOURCE -> VideoSourceFragment()
                Tab.PROFILE -> ProfileFragment()
            }
        )
    }

    private fun renderTab(tab: Tab, animate: Boolean) {
        val items = navItems()
        val selectedIndex = tab.ordinal
        val activeColor = androidx.core.content.ContextCompat.getColor(this, R.color.theme_primary)
        val idleColor = androidx.core.content.ContextCompat.getColor(this, R.color.nav_item_idle)

        items.forEachIndexed { index, (container, icon, label) ->
            val selected = index == selectedIndex
            container.isSelected = selected
            icon.setColorFilter(if (selected) activeColor else idleColor)
            label.setTextColor(if (selected) activeColor else idleColor)
            label.alpha = if (selected) 1f else 0.85f
        }

        moveLensTo(items[selectedIndex].first, animate)
    }

    /** 把选中透镜滑到指定项：宽度 = 项宽 + 8dp，居中于该项。 */
    private fun moveLensTo(item: View, animate: Boolean) {
        if (item.width == 0) return
        val lens = binding.navLens
        val extra = (8 * resources.displayMetrics.density).toInt()
        val lensWidth = item.width + extra
        val lensHeight = ((62 - 8) * resources.displayMetrics.density).toInt()
        if (lens.width != lensWidth || lens.height != lensHeight) {
            lens.layoutParams = lens.layoutParams.apply {
                width = lensWidth
                height = lensHeight
            }
            lens.requestLayout()
        }
        val targetX = item.left + (item.width - lensWidth) / 2f
        val targetY = (binding.navCapsule.height - lensHeight) / 2f
        if (animate) {
            lens.animate()
                .translationX(targetX)
                .translationY(targetY)
                .setDuration(280)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.1f))
                .start()
        } else {
            lens.translationX = targetX
            lens.translationY = targetY
        }
    }

    /** 按下时透镜放大，凸出胶囊上下边缘（FlClash 的 lens lift）。 */
    private fun liftLens(lifted: Boolean) {
        binding.navLens.animate()
            .scaleX(if (lifted) 1.12f else 1f)
            .scaleY(if (lifted) 1.22f else 1f)
            .setDuration(if (lifted) 120 else 220)
            .start()
    }
    
    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    /** 供首页「历史」图标调用，切换到历史标签页。 */
    fun switchToHistory() {
        selectTab(Tab.HISTORY)
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
