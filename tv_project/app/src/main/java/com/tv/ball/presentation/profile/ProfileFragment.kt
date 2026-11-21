package com.tv.ball.presentation.profile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.tv.ball.BuildConfig
import com.tv.ball.R
import com.tv.ball.databinding.FragmentProfileBinding
import com.tv.ball.presentation.favorite.FavoriteActivity
import com.tv.ball.presentation.history.HistoryActivity
import com.tv.ball.presentation.localvideo.LocalVideoActivity
import com.tv.ball.presentation.settings.SettingsFragment
import com.tv.ball.presentation.settings.ThemeActivity
import com.tv.ball.update.UpdateManager
import com.tv.ball.util.CustomToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 「我的」页面：只有功能入口与版本信息，无账号、无头像、无多余说明文案。
 */
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

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
        binding.tvVersion.text = BuildConfig.VERSION_NAME
        binding.tvCacheSize.text = formatSize(cacheSize())
        setupClickListeners()
    }

    private fun setupClickListeners() {
        binding.layoutLocalVideo.setOnClickListener {
            startActivity(Intent(requireContext(), LocalVideoActivity::class.java))
        }
        binding.layoutFavorite.setOnClickListener {
            startActivity(Intent(requireContext(), FavoriteActivity::class.java))
        }
        binding.layoutHistory.setOnClickListener {
            startActivity(Intent(requireContext(), HistoryActivity::class.java))
        }
        binding.layoutTheme.setOnClickListener {
            startActivity(Intent(requireContext(), ThemeActivity::class.java))
        }
        binding.layoutSettings.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .addToBackStack(null)
                .commit()
        }
        binding.layoutClearCache.setOnClickListener { clearCache() }
        binding.layoutCheckUpdate.setOnClickListener { checkUpdate() }
    }

    // ------------------------------------------------------------ 检查更新

    private fun checkUpdate() {
        val host = activity ?: return
        UpdateManager.start(host)
    }

    // ------------------------------------------------------------ 缓存

    private fun clearCache() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_cache_title)
            .setMessage(getString(R.string.clear_cache_message, formatSize(cacheSize())))
            .setPositiveButton(R.string.confirm) { _, _ ->
                deleteDir(requireContext().cacheDir)
                requireContext().externalCacheDir?.let { deleteDir(it) }
                binding.tvCacheSize.text = formatSize(cacheSize())
                CustomToast.show(requireContext(), getString(R.string.cache_cleared))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun cacheSize(): Long {
        var size = 0L
        runCatching { size += folderSize(requireContext().cacheDir) }
        requireContext().externalCacheDir?.let { dir -> runCatching { size += folderSize(dir) } }
        return size
    }

    private fun folderSize(file: File): Long {
        var size = 0L
        file.listFiles()?.forEach { f ->
            size += if (f.isDirectory) folderSize(f) else f.length()
        }
        return size
    }

    private fun formatSize(size: Long): String = when {
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
