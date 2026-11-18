package com.tv.ball.presentation.settings

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.tv.ball.databinding.FragmentSettingsBinding
import com.tv.ball.presentation.localvideo.LocalVideoActivity
import kotlinx.coroutines.launch
import java.io.File

class SettingsFragment : Fragment() {
    
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: SettingsViewModel by lazy {
        ViewModelProvider(this)[SettingsViewModel::class.java]
    }

    private val userPreferences by lazy {
        com.tv.ball.di.Dependencies.userPreferences
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
        setupClickListeners()
        observeViewModel()
        loadContinuePlayInfo()
        updateCodeCountdownDisplay()
    }
    
    override fun onResume() {
        super.onResume()
        loadContinuePlayInfo()
    }
    
    private fun loadContinuePlayInfo() {
        viewModel.loadRecentPlay()
    }
    
    private fun observeViewModel() {
        // 继续播放功能已禁用
        /*
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.recentPlay.collect { playInfo ->
                if (playInfo != null) {
                    binding.cardContinuePlay.visibility = View.VISIBLE
                    binding.tvContinuePlayTitle.text = "继续播放： ${playInfo.title}  ${playInfo.episode}"
                    binding.cardContinuePlay.setOnClickListener {
                        // 跳转到播放页面
                        val intent = Intent(requireContext(), com.tv.ball.presentation.player.VideoPlayerActivity::class.java).apply {
                            putExtra("video_id", playInfo.videoId?.toLongOrNull() ?: 0L)
                            putExtra("video_title", playInfo.title)
                            putExtra("video_url", "") // URL会从历史记录中恢复
                            putExtra("thumbnail_url", "")
                        }
                        startActivity(intent)
                    }
                } else {
                    binding.cardContinuePlay.visibility = View.GONE
                }
            }
        }
        */
    }
    
    private fun setupClickListeners() {
        // 通用
        binding.layoutNotification.setOnClickListener {
            com.tv.ball.util.CustomToast.show(requireContext(), "通知设置 - 开发中")
        }

        binding.layoutCareMode.setOnClickListener {
            com.tv.ball.util.CustomToast.show(requireContext(), "关怀模式 - 开发中")
        }

        binding.layoutDouyinLive.setOnClickListener {
            com.tv.ball.util.CustomToast.show(requireContext(), "抖音直播 - 开发中")
        }

        binding.layoutBilibiliBlueLink.setOnClickListener {
            com.tv.ball.util.CustomToast.show(requireContext(), "B站蓝链申请 - 开发中")
        }

        binding.layoutGroupRobot.setOnClickListener {
            com.tv.ball.util.CustomToast.show(requireContext(), "群机器人 - 开发中")
        }

        // 账号
        binding.layoutCodeCountdown.setOnClickListener {
            showCodeCountdownDialog()
        }
        binding.switchTouchSound.setOnCheckedChangeListener { _, isChecked ->
            com.tv.ball.util.CustomToast.show(requireContext(), if (isChecked) "触摸音效已开启" else "触摸音效已关闭")
        }

        binding.layoutWechat.setOnClickListener {
            com.tv.ball.util.CustomToast.show(requireContext(), "微信号 - 开发中")
        }

        binding.layoutAlipay.setOnClickListener {
            com.tv.ball.util.CustomToast.show(requireContext(), "支付宝 - 开发中")
        }

        binding.layoutFlagSettings.setOnClickListener {
            com.tv.ball.util.CustomToast.show(requireContext(), "标旗设置 - 开发中")
        }

        binding.layoutStoreSettings.setOnClickListener {
            com.tv.ball.util.CustomToast.show(requireContext(), "店铺设置 - 开发中")
        }

        binding.layoutAgentCommission.setOnClickListener {
            com.tv.ball.util.CustomToast.show(requireContext(), "新增代理自动分佣 - 开发中")
        }

        binding.layoutProductCommission.setOnClickListener {
            com.tv.ball.util.CustomToast.show(requireContext(), "新增产品自动分佣 - 开发中")
        }

        binding.layoutDeleteAccount.setOnClickListener {
            showDeleteAccountDialog()
        }

        // 关于
        binding.layoutPrivacyCollection.setOnClickListener {
            com.tv.ball.util.CustomToast.show(requireContext(), "个人信息收集清单 - 开发中")
        }

        binding.layoutThirdPartySharing.setOnClickListener {
            com.tv.ball.util.CustomToast.show(requireContext(), "第三方信息共享清单 - 开发中")
        }
    }

    private fun showDeleteAccountDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("注销账号")
            .setMessage("注销账号后，您的所有数据将被清除且无法恢复。\n\n确定要注销账号吗？")
            .setPositiveButton("确定") { _, _ ->
                com.tv.ball.util.CustomToast.show(requireContext(), "账号注销功能 - 开发中")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateCodeCountdownDisplay() {
        val countdown = userPreferences.getCodeCountdown()
        binding.tvCodeCountdown.text = "${countdown}秒"
    }

    private fun showCodeCountdownDialog() {
        val options = arrayOf("30秒", "60秒", "90秒", "120秒")
        val values = arrayOf(30, 60, 90, 120)
        val currentCountdown = userPreferences.getCodeCountdown()
        val currentIndex = values.indexOf(currentCountdown).takeIf { it >= 0 } ?: 1

        AlertDialog.Builder(requireContext())
            .setTitle("验证码倒计时设置")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                userPreferences.setCodeCountdown(values[which])
                updateCodeCountdownDisplay()
                com.tv.ball.util.CustomToast.show(requireContext(), "已设置为${options[which]}")
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
