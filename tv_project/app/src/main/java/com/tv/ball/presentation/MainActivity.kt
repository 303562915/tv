package com.tv.ball.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.tv.ball.R
import com.tv.ball.databinding.ActivityMainBinding
import com.tv.ball.presentation.home.HomeFragment
import com.tv.ball.presentation.nav.FloatingNavDock
import com.tv.ball.presentation.profile.ProfileFragment
import com.tv.ball.presentation.videosource.VideoSourceFragment
import com.tv.ball.update.UpdateManager
import com.tv.ball.utils.StatusBarUtils

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private enum class Tab { HOME, SOURCE, PROFILE }

    private var currentTab: Tab = Tab.HOME

    /** 上一次在首页按返回键的时刻（elapsedRealtime），用于「再按一次退出」的两秒窗口。 */
    private var lastHomeBackAt = 0L

    companion object {
        private const val REQUEST_STORAGE_PERMISSION = 100
        private const val REQUEST_MANAGE_STORAGE = 101

        /** 「再按一次退出」的确认窗口：2 秒。 */
        private const val EXIT_CONFIRM_WINDOW_MS = 2000L
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

        // 权限改为按需申请（本地视频 / 下载时），启动时不再弹系统授权框跳出去

        // 加载云端视频源配置
        loadCloudVideoSources()

        setupBottomNav(savedInstanceState == null)
        setupBackPressHandler()

        // 前台自动检查更新：进前台立刻查一次，之后每 30 秒查一次，切后台就停。
        // 查到新版本才挂一条主题化轻提示，同一个版本只提示一次；
        // 已是最新或检查失败都静默，失败原因留给关于页的手动检查去说。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                UpdateManager.autoCheckLoop(this@MainActivity, this)
            }
        }

        showLastCrashIfAny()
    }

    /**
     * 上次进程崩过就把崩溃堆栈弹出来（可长按复制）。
     *
     * 播放器这类页面崩了就整个进程退出，用户既打不开文件管理器也不方便连电脑，
     * 与其让问题只能靠猜，不如进主界面就直接把堆栈摆出来，截一张图即可定位。
     */
    private fun showLastCrashIfAny() {
        Thread {
            val report = com.tv.ball.util.GlobalExceptionHandler.takeUnshownReport() ?: return@Thread
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val text = TextView(this).apply {
                    this.text = report.content
                    textSize = 10f
                    setPadding(32, 24, 32, 24)
                    setTextIsSelectable(true)
                }
                val scroll = ScrollView(this).apply {
                    addView(
                        text,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            (resources.displayMetrics.heightPixels * 0.55f).toInt()
                        )
                    )
                }
                AlertDialog.Builder(this)
                    .setTitle("上次崩溃：${report.fileName}")
                    .setView(scroll)
                    .setPositiveButton("知道了") { _, _ ->
                        com.tv.ball.util.GlobalExceptionHandler.markReportShown(report.fileName)
                    }
                    .setCancelable(false)
                    .show()
            }
        }.start()
    }

    /**
     * 悬浮胶囊底栏（移植 FlClash NavigationDock）：
     * 图标+文字常显；选中态是滑动的胶囊透镜，按下吸到该项并抬起，松手到位后落下。
     */
    private fun setupBottomNav(initial: Boolean) {
        binding.navDock.setDestinations(
            listOf(
                FloatingNavDock.Destination(R.drawable.ic_nav_home, getString(R.string.home)),
                FloatingNavDock.Destination(R.drawable.ic_nav_source, getString(R.string.source)),
                FloatingNavDock.Destination(R.drawable.ic_nav_profile, getString(R.string.profile))
            )
        )
        binding.navDock.onSelected = { index -> selectTab(Tab.entries[index], fromDock = true) }
        binding.navDock.post {
            binding.navDock.setSelected(if (initial) Tab.HOME.ordinal else currentTab.ordinal, animate = false)
        }
        if (initial) selectTab(Tab.HOME, fromDock = false)
    }

    private fun selectTab(tab: Tab, fromDock: Boolean) {
        val sameTab = tab == currentTab && supportFragmentManager.fragments.isNotEmpty()
        currentTab = tab
        if (!fromDock) binding.navDock.setSelected(tab.ordinal, animate = true)
        if (sameTab) return
        loadFragment(
            when (tab) {
                Tab.HOME -> HomeFragment()
                Tab.SOURCE -> VideoSourceFragment()
                Tab.PROFILE -> ProfileFragment()
            }
        )
    }
    
    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    /**
     * 返回键：非首页 Tab 先回首页；已经在首页时「再按一次退出」，2 秒内连按两次才真的退出。
     *
     * 走 [OnBackPressedDispatcher]（`onBackPressed()` 已废弃，别重写它）：
     * 回调挂在 Activity 的生命周期上，销毁时自动移除，不会重复注册。
     */
    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentTab != Tab.HOME) {
                    // 非首页：先回到首页 Tab，这一次不退出
                    selectTab(Tab.HOME, fromDock = false)
                    lastHomeBackAt = 0L
                    return
                }

                val now = SystemClock.elapsedRealtime()
                if (lastHomeBackAt != 0L && now - lastHomeBackAt <= EXIT_CONFIRM_WINDOW_MS) {
                    // 2 秒内第二次按：退出。先关掉自己，避免退出过程中又被这个回调拦一次
                    isEnabled = false
                    finish()
                } else {
                    lastHomeBackAt = now
                    com.tv.ball.util.CustomToast.show(this@MainActivity, "再按一次退出")
                }
            }
        })
    }

    /** 供首页「历史」图标调用：打开独立的观看历史页面。 */
    fun switchToHistory() {
        startActivity(Intent(this, com.tv.ball.presentation.history.HistoryActivity::class.java))
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




