package com.tv.ball.presentation.settings

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.tv.ball.databinding.FragmentSettingsBinding
import java.io.File

/**
 * 设置页面
 *
 * 只包含播放器自身需要的配置项，所有数据保存在本地，无需登录。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val prefs by lazy {
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTitleBar()
        setupPlayback()
        setupAppearance()
        setupStorage()
        setupAbout()
    }

    private fun setupTitleBar() {
        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    private fun setupPlayback() {
        // 默认倍速
        updateSpeedLabel()
        binding.layoutPlaybackSpeed.setOnClickListener {
            showSpeedDialog()
        }

        // 自动连播
        binding.switchAutoNext.isChecked = prefs.getBoolean(KEY_AUTO_NEXT, true)
        binding.switchAutoNext.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_NEXT, isChecked).apply()
        }

        // 跳过片头片尾
        binding.switchSkipIntro.isChecked = prefs.getBoolean(KEY_SKIP_INTRO, false)
        binding.switchSkipIntro.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SKIP_INTRO, isChecked).apply()
        }
    }

    private fun updateSpeedLabel() {
        val speed = prefs.getFloat(KEY_PLAYBACK_SPEED, 1.0f)
        binding.tvPlaybackSpeed.text = String.format(java.util.Locale.US, "%.2fx", speed)
    }

    private fun showSpeedDialog() {
        val labels = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
        val values = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val current = prefs.getFloat(KEY_PLAYBACK_SPEED, 1.0f)
        val currentIndex = values.indexOfFirst { it == current }.takeIf { it >= 0 } ?: 2

        AlertDialog.Builder(requireContext())
            .setTitle("默认倍速")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                prefs.edit().putFloat(KEY_PLAYBACK_SPEED, values[which]).apply()
                updateSpeedLabel()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setupAppearance() {
        binding.layoutTheme.setOnClickListener {
            startActivity(Intent(requireContext(), ThemeActivity::class.java))
        }
    }

    private fun setupStorage() {
        updateCacheSize()
        binding.layoutClearCache.setOnClickListener {
            showClearCacheDialog()
        }
    }

    private fun setupAbout() {
        binding.tvVersion.text = appVersionName()
        binding.layoutOpenSourceLicense.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("开源许可")
                .setMessage(
                    "本应用基于 MIT License 开源。\n\n" +
                        "第三方组件：\n" +
                        "· ExoPlayer（Apache-2.0）\n" +
                        "· OkHttp（Apache-2.0）\n" +
                        "· Glide（Apache-2.0）\n" +
                        "· Room（Apache-2.0）\n" +
                        "· Gson（Apache-2.0）"
                )
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun appVersionName(): String {
        return try {
            requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    private fun updateCacheSize() {
        binding.tvCacheSize.text = formatFileSize(getCacheSize())
    }

    private fun getCacheSize(): Long {
        var size = 0L
        try {
            size += getFolderSize(requireContext().cacheDir)
            requireContext().externalCacheDir?.let { size += getFolderSize(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return size
    }

    private fun getFolderSize(file: File): Long {
        var size = 0L
        try {
            file.listFiles()?.forEach { f ->
                size += if (f.isDirectory) getFolderSize(f) else f.length()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return size
    }

    private fun formatFileSize(size: Long): String = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format("%.2f KB", size / 1024.0)
        size < 1024 * 1024 * 1024 -> String.format("%.2f MB", size / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
    }

    private fun showClearCacheDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("清理缓存")
            .setMessage("当前缓存：${formatFileSize(getCacheSize())}\n\n确定要清理吗？")
            .setPositiveButton("确定") { _, _ ->
                deleteDir(requireContext().cacheDir)
                requireContext().externalCacheDir?.let { deleteDir(it) }
                updateCacheSize()
                com.tv.ball.util.CustomToast.show(requireContext(), "缓存已清理")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteDir(dir: File?): Boolean {
        if (dir == null) return false
        if (dir.isDirectory) {
            dir.list()?.forEach { deleteDir(File(dir, it)) }
            return dir.delete()
        }
        return if (dir.isFile) dir.delete() else false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val PREFS_NAME = "player_settings"
        const val KEY_PLAYBACK_SPEED = "playback_speed"
        const val KEY_AUTO_NEXT = "auto_next"
        const val KEY_SKIP_INTRO = "skip_intro"
    }
}
