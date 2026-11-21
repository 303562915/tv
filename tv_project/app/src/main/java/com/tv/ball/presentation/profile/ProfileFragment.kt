package com.tv.ball.presentation.profile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.tv.ball.BuildConfig
import com.tv.ball.R
import com.tv.ball.databinding.FragmentProfileBinding
import com.tv.ball.feature.f086.F086Activity
import com.tv.ball.presentation.favorite.FavoriteActivity
import com.tv.ball.presentation.history.HistoryActivity
import com.tv.ball.presentation.localvideo.LocalVideoActivity
import com.tv.ball.presentation.settings.SettingsFragment
import com.tv.ball.presentation.settings.ThemeActivity
import com.tv.ball.util.CustomToast
import kotlin.math.roundToInt

/**
 * 「我的」页 = unicanta SettingsScreen 的 View 版。
 *
 * 结构：用户卡（字母头像 + 名字 + 改名提示 + 铅笔）→「内容」组（本地视频/收藏/历史）→
 * 「设置」组（主题/播放设置/清理缓存）→ 单行「关于本应用」（描述行显示版本）。
 *
 * 这一页本身不做检查更新：更新、渠道、自动安装、外链全在 [F086Activity]（关于页），
 * 本页只留一个入口，旧版那行「检查更新」和底部说明文字已从布局里清掉。
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
        // 关于行的描述行就是版本号（对照 unicanta：SettingsItem(about_us, description = VERSION_NAME)）
        binding.profileVersionValue.text = BuildConfig.VERSION_NAME
        binding.profileCacheSize.text = formatSize(cacheSize())
        renderUser()
        setupClickListeners()
    }

    private fun setupClickListeners() {
        binding.profileUserCard.setOnClickListener { showRenameDialog() }
        binding.rowLocalVideo.setOnClickListener {
            startActivity(Intent(requireContext(), LocalVideoActivity::class.java))
        }
        binding.rowFavorite.setOnClickListener {
            startActivity(Intent(requireContext(), FavoriteActivity::class.java))
        }
        binding.rowHistory.setOnClickListener {
            startActivity(Intent(requireContext(), HistoryActivity::class.java))
        }
        binding.rowTheme.setOnClickListener {
            startActivity(Intent(requireContext(), ThemeActivity::class.java))
        }
        binding.rowSettings.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .addToBackStack(null)
                .commit()
        }
        binding.rowClearCache.setOnClickListener { clearCache() }
        binding.rowAbout.setOnClickListener {
            startActivity(Intent(requireContext(), F086Activity::class.java))
        }
    }

    // ------------------------------------------------------------ 用户卡

    /** 名字、字母头像、未命名的暖色提示，三者都由同一个 [localName] 决定。 */
    private fun renderUser() {
        val name = localName()
        val unnamed = isUnnamed(name)
        val shown = if (unnamed) getString(R.string.profile_user_default) else name

        binding.profileUserName.text = shown
        binding.profileAvatarLetter.text = shown.trim().take(1).uppercase()
        binding.profileUserBadge.visibility = if (unnamed) View.VISIBLE else View.GONE
        binding.profileUserHint.setText(
            if (unnamed) R.string.profile_unnamed_hint else R.string.profile_edit_name
        )
        binding.profileUserName.setTextColor(
            color(if (unnamed) COLOR_UNNAMED_TEXT else R.color.theme_text_primary)
        )
    }

    /**
     * 名字是否还等于「没起名字」：trim 后为空，或仍然是默认名。
     * 只有用户真的改过名字才算有名字——「本机用户」不是名字，是占位。
     */
    private fun isUnnamed(name: String): Boolean {
        val trimmed = name.trim()
        return trimmed.isEmpty() || trimmed == getString(R.string.profile_user_default)
    }

    private fun localName(): String =
        prefs().getString(KEY_NICKNAME, null) ?: getString(R.string.profile_user_default)

    /** 改名对话框：上限 24 字符，空名字存空串（下次进来继续提示起名字）。 */
    private fun showRenameDialog() {
        val context = context ?: return
        val input = EditText(context).apply {
            setText(prefs().getString(KEY_NICKNAME, null).orEmpty())
            hint = getString(R.string.profile_name_hint)
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(MAX_NAME_LENGTH))
            setSelection(text.length)
        }
        val container = FrameLayout(context).apply {
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.profile_name_title)
            .setView(container)
            .setPositiveButton(R.string.confirm) { _, _ ->
                prefs().edit().putString(KEY_NICKNAME, input.text.toString().trim()).apply()
                renderUser()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun prefs() = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------ 缓存

    private fun clearCache() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_cache_title)
            .setMessage(getString(R.string.clear_cache_message, formatSize(cacheSize())))
            .setPositiveButton(R.string.confirm) { _, _ ->
                deleteDir(requireContext().cacheDir)
                requireContext().externalCacheDir?.let { dir -> deleteDir(dir) }
                binding.profileCacheSize.text = formatSize(cacheSize())
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

    // ------------------------------------------------------------ 小工具

    private fun color(resId: Int): Int = ContextCompat.getColor(requireContext(), resId)

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).roundToInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /** 昵称只属于这台机器，不进登录用的 UserPreferences。 */
        const val PREFS = "profile_local"
        const val KEY_NICKNAME = "nickname"

        /** 对照 unicanta MAX_NAME_LENGTH：名字再长也不会把头像行挤变形。 */
        const val MAX_NAME_LENGTH = 24

        /** 未起名字时的暖色文字（对照 unicanta glowText #8A6D00）。 */
        const val COLOR_UNNAMED_TEXT = 0xFF8A6D00.toInt()
    }
}