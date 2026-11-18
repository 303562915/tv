package com.tv.ball.presentation.profile

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.tv.ball.databinding.FragmentProfileBinding
import com.tv.ball.presentation.localvideo.LocalVideoActivity
import com.tv.ball.presentation.settings.SettingsFragment
import com.tv.ball.presentation.settings.ThemeActivity
import java.io.File

/**
 * 「我的」页面
 *
 * 无需登录：只提供本地功能入口与缓存管理。
 */
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupHeader()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
    }

    private fun setupHeader() {
        binding.tvUserId.text = getString(com.tv.ball.R.string.app_name)
    }

    private fun setupClickListeners() {
        binding.layoutUserInfo.setOnClickListener {
            openSettings()
        }

        binding.layoutFavorite.setOnClickListener {
            startActivity(Intent(requireContext(), com.tv.ball.presentation.favorite.FavoriteActivity::class.java))
        }

        binding.layoutLocalVideo.setOnClickListener {
            startActivity(Intent(requireContext(), LocalVideoActivity::class.java))
        }

        binding.layoutTheme.setOnClickListener {
            startActivity(Intent(requireContext(), ThemeActivity::class.java))
        }

        binding.layoutClearCache.setOnClickListener {
            showClearCacheDialog()
        }

        binding.layoutSettings.setOnClickListener {
            openSettings()
        }
    }

    private fun openSettings() {
        parentFragmentManager.beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .addToBackStack(null)
            .commit()
    }

    private fun showClearCacheDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("清理缓存")
            .setMessage("当前缓存：${formatFileSize(getCacheSize())}\n\n确定要清理吗？")
            .setPositiveButton("确定") { _, _ ->
                deleteDir(requireContext().cacheDir)
                requireContext().externalCacheDir?.let { deleteDir(it) }
                com.tv.ball.util.CustomToast.show(requireContext(), "缓存已清理")
            }
            .setNegativeButton("取消", null)
            .show()
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
}
