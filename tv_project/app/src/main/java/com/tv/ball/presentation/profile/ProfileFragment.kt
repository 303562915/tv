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
        binding.updateProgress.visibility = View.VISIBLE
        binding.tvVersion.text = getString(R.string.checking_update)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder()
                        .url("https://api.github.com/repos/303562915/tv/releases/latest")
                        .header("Accept", "application/vnd.github+json")
                        .build()
                    http.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@runCatching null
                        val body = response.body?.string() ?: return@runCatching null
                        val json = JSONObject(body)
                        val tag = json.optString("tag_name")
                        val url = json.optString("html_url")
                        val apk = json.optJSONArray("assets")?.let { assets ->
                            (0 until assets.length())
                                .map { assets.getJSONObject(it) }
                                .firstOrNull { it.optString("name").endsWith(".apk") }
                                ?.optString("browser_download_url")
                        }
                        Triple(tag, url, apk)
                    }
                }.getOrNull()
            }

            binding.updateProgress.visibility = View.GONE
            binding.tvVersion.text = BuildConfig.VERSION_NAME

            if (result == null) {
                CustomToast.show(requireContext(), getString(R.string.update_failed))
                return@launch
            }

            val (tag, pageUrl, apkUrl) = result
            val latest = tag.removePrefix("v")
            if (latest.isBlank() || !isNewer(latest, BuildConfig.VERSION_NAME)) {
                CustomToast.show(requireContext(), getString(R.string.already_latest))
                return@launch
            }

            android.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.update_found, latest))
                .setPositiveButton(R.string.update_download) { _, _ ->
                    val target = if (!apkUrl.isNullOrBlank()) apkUrl else pageUrl
                    if (!target.isNullOrBlank()) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                    }
                }
                .setNegativeButton(R.string.later, null)
                .show()
        }
    }

    /** 简单版本比较：按分段数字比大小，latest 里带后缀（-debug）也能处理。 */
    private fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split(".", "-").mapNotNull { it.toIntOrNull() }
        val b = current.split(".", "-").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
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
