package com.tv.ball.presentation.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.tv.ball.databinding.FragmentProfileBinding
import com.tv.ball.presentation.home.BannerAdapter
import com.tv.ball.presentation.home.BannerItem
import com.tv.ball.presentation.settings.SettingsFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private lateinit var bannerAdapter: BannerAdapter
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val userPreferences by lazy {
        com.tv.ball.di.Dependencies.userPreferences
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
        // setupBanner()
        setupClickListeners()
        // loadBannersFromApi()
        updateUserInfo()
    }

    override fun onResume() {
        super.onResume()
        // 每次回到页面时更新用户信息
        updateUserInfo()
    }

    private fun updateUserInfo() {
        if (userPreferences.isLoggedIn()) {
            // 已登录，显示用户信息
            val username = userPreferences.getUsername() ?: userPreferences.getEmail() ?: "用户"
            binding.tvUserId.text = username

            // 显示邀请码
            val inviteCode = userPreferences.getInviteCode()
            if (!inviteCode.isNullOrEmpty()) {
                binding.tvInviteCode.text = inviteCode
            } else {
                binding.tvInviteCode.text = "暂无"
            }
        } else {
            // 未登录，显示默认信息
            binding.tvUserId.text = "点击登录"
            binding.tvInviteCode.text = "暂无"
        }
    }

    private fun setupBanner() {
        // 初始化空适配器
        // bannerAdapter = BannerAdapter(emptyList())
        // binding.bannerViewPager.adapter = bannerAdapter
    }

    private fun loadBannersFromApi() {
        /*
        lifecycleScope.launch {
            try {
                val banners = withContext(Dispatchers.IO) {
                    fetchBannersFromApi()
                }

                if (banners.isNotEmpty()) {
                    bannerAdapter.updateData(banners)
                    // 设置初始位置到中间，实现无限循环效果
                    binding.bannerViewPager.setCurrentItem(Int.MAX_VALUE / 2, false)
                    Log.d("ProfileFragment", "加载了 ${banners.size} 个轮播图")
                } else {
                    Log.w("ProfileFragment", "没有获取到轮播图数据")
                }
            } catch (e: Exception) {
                Log.e("ProfileFragment", "加载轮播图失败: ${e.message}", e)
            }
        }
        */
    }

    private fun fetchBannersFromApi(): List<BannerItem> {
        val request = Request.Builder()
            .url("https://sk.nasxiaolin.top/app/api/profile_banners.php")
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.e("ProfileFragment", "API请求失败: ${response.code}")
            return emptyList()
        }

        val jsonString = response.body?.string() ?: return emptyList()
        Log.d("ProfileFragment", "API响应: $jsonString")

        val banners = mutableListOf<BannerItem>()
        val jsonArray = JSONArray(jsonString)

        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)
            banners.add(
                BannerItem(
                    id = item.optInt("id", 0),
                    imageUrl = item.optString("图片链接", ""),
                    linkUrl = item.optString("跳转链接", ""),
                    description = item.optString("描述", ""),
                    visible = item.optString("visble", "true") == "true"
                )
            )
        }

        return banners
    }

    private fun setupClickListeners() {
        // 顶部用户信息区域 - 已登录跳转设置，未登录跳转登录
        binding.layoutUserInfo.setOnClickListener {
            if (userPreferences.isLoggedIn()) {
                // 已登录，跳转到设置页面
                val settingsFragment = SettingsFragment()
                parentFragmentManager.beginTransaction()
                    .replace(android.R.id.content, settingsFragment)
                    .addToBackStack(null)
                    .commit()
            } else {
                // 未登录，跳转到登录页面
                startActivity(Intent(requireContext(), com.tv.ball.presentation.login.LoginActivity::class.java))
            }
        }

        // 推荐功能区域
        binding.layoutFavorite.setOnClickListener {
            startActivity(Intent(requireContext(), com.tv.ball.presentation.favorite.FavoriteActivity::class.java))
        }

        binding.layoutLocalVideo.setOnClickListener {
            startActivity(Intent(requireContext(), com.tv.ball.presentation.localvideo.LocalVideoActivity::class.java))
        }

        binding.layoutTheme.setOnClickListener {
            startActivity(Intent(requireContext(), com.tv.ball.presentation.settings.ThemeActivity::class.java))
        }

        binding.layoutClearCache.setOnClickListener {
            showClearCacheDialog()
        }

        // 长按清理缓存来重置视频源
        binding.layoutClearCache.setOnLongClickListener {
            showResetVideoSourceDialog()
            true
        }

        binding.layoutParser.setOnClickListener {
            startActivity(Intent(requireContext(), com.tv.ball.presentation.settings.ParserActivity::class.java))
        }

        // 登录/注册
        binding.layoutLogin.setOnClickListener {
            if (userPreferences.isLoggedIn()) {
                // 已登录，显示退出登录对话框
                showLogoutDialog()
            } else {
                // 未登录，跳转到登录页面
                startActivity(Intent(requireContext(), com.tv.ball.presentation.login.LoginActivity::class.java))
            }
        }

        // 个人中心
        binding.layoutPersonalCenter.setOnClickListener {
            com.tv.ball.util.CustomToast.show(requireContext(), "个人中心 - 开发中")
        }

        // 操作日志
        binding.layoutOperationLog.setOnClickListener {
            com.tv.ball.util.CustomToast.show(requireContext(), "操作日志 - 开发中")
        }

        // 设置 - 跳转到设置页面
        binding.layoutSettings.setOnClickListener {
            // 切换到设置Fragment
            val settingsFragment = SettingsFragment()
            parentFragmentManager.beginTransaction()
                .replace(android.R.id.content, settingsFragment)
                .addToBackStack(null)
                .commit()
        }

        // 在线客服
        binding.layoutCustomerService.setOnClickListener {
            com.tv.ball.util.CustomToast.show(requireContext(), "在线客服 - 开发中")
        }

        // 复制邀请码
        binding.btnCopyInviteCode.setOnClickListener {
            val inviteCode = userPreferences.getInviteCode()
            if (!inviteCode.isNullOrEmpty()) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("邀请码", inviteCode)
                clipboard.setPrimaryClip(clip)
                com.tv.ball.util.CustomToast.show(requireContext(), "邀请码已复制")
            } else {
                com.tv.ball.util.CustomToast.show(requireContext(), "暂无邀请码")
            }
        }

        // 申请推荐码
        binding.btnRequestInviteCode.setOnClickListener {
            com.tv.ball.util.CustomToast.show(requireContext(), "申请推荐码 - 开发中")
        }
    }

    private fun showClearCacheDialog() {
        val cacheSize = getCacheSize()
        val cacheSizeText = formatFileSize(cacheSize)

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("清理缓存")
            .setMessage("当前缓存大小：$cacheSizeText\n\n确定要清理缓存吗？")
            .setPositiveButton("确定") { _, _ ->
                clearCache()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLogoutDialog() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("退出登录")
            .setMessage("确定要退出登录吗？")
            .setPositiveButton("确定") { _, _ ->
                // 清除登录信息
                userPreferences.clearLoginInfo()
                // 更新UI
                updateUserInfo()
                com.tv.ball.util.CustomToast.show(requireContext(), "已退出登录")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showResetVideoSourceDialog() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("重置视频源")
            .setMessage("重置视频源失败记录，允许重新尝试所有视频源。\n\n确定要重置吗？")
            .setPositiveButton("确定") { _, _ ->
                resetVideoSource()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun resetVideoSource() {
        try {
            com.tv.ball.di.Dependencies.videoSourceAutoSwitcher.resetFailedSources()
            com.tv.ball.util.CustomToast.show(requireContext(), "视频源已重置")
        } catch (e: Exception) {
            e.printStackTrace()
            com.tv.ball.util.CustomToast.show(requireContext(), "重置失败: ${e.message}")
        }
    }

    private fun getCacheSize(): Long {
        var size: Long = 0
        try {
            val cacheDir = requireContext().cacheDir
            size = getFolderSize(cacheDir)

            val externalCacheDir = requireContext().externalCacheDir
            if (externalCacheDir != null) {
                size += getFolderSize(externalCacheDir)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return size
    }

    private fun getFolderSize(file: File): Long {
        var size: Long = 0
        try {
            val files = file.listFiles()
            if (files != null) {
                for (f in files) {
                    size += if (f.isDirectory) {
                        getFolderSize(f)
                    } else {
                        f.length()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return size
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> String.format("%.2f KB", size / 1024.0)
            size < 1024 * 1024 * 1024 -> String.format("%.2f MB", size / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
        }
    }

    private fun clearCache() {
        try {
            deleteDir(requireContext().cacheDir)
            requireContext().externalCacheDir?.let { deleteDir(it) }

            com.tv.ball.util.CustomToast.show(requireContext(), "缓存清理成功")
        } catch (e: Exception) {
            e.printStackTrace()
            com.tv.ball.util.CustomToast.show(requireContext(), "缓存清理失败")
        }
    }

    private fun deleteDir(dir: File?): Boolean {
        if (dir != null && dir.isDirectory) {
            val children = dir.list()
            if (children != null) {
                for (child in children) {
                    val success = deleteDir(File(dir, child))
                    if (!success) {
                        return false
                    }
                }
            }
            return dir.delete()
        } else if (dir != null && dir.isFile) {
            return dir.delete()
        }
        return false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
