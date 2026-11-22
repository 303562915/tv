package com.tv.ball.presentation.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tv.ball.R
import com.tv.ball.databinding.BottomSheetDlnaCastBinding
import com.tv.ball.databinding.ItemDlnaDeviceBinding
import com.tv.ball.domain.dlna.DLNADevice
import com.tv.ball.domain.dlna.DLNAManager
import com.tv.ball.domain.webserver.LocalWebServer
import com.tv.ball.domain.lan.LanNetworkGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DLNA投屏设备选择底部弹窗
 */
class DLNACastBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetDlnaCastBinding? = null
    private val binding get() = _binding!!

    private lateinit var dlnaManager: DLNAManager
    private lateinit var deviceAdapter: DLNADeviceAdapter
    private var webServer: LocalWebServer? = null

    private var videoUrl: String = ""
    private var videoTitle: String = ""
    private var videoReferer: String = ""
    private var videoUserAgent: String = ""
    private var episodes: List<LocalWebServer.Episode> = emptyList()
    private var currentEpisodeIndex: Int = 0
    private var onDeviceSelected: ((DLNADevice) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetDlnaCastBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dlnaManager = DLNAManager(requireContext())
        setupViews()
        startDiscovery()
    }

    private fun setupViews() {
        // 设置 DLNA 设备列表
        deviceAdapter = DLNADeviceAdapter { device ->
            onDeviceSelected?.invoke(device)
            dismiss()
        }
        binding.deviceRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = deviceAdapter
        }

        // 刷新按钮
        binding.refreshButton.setOnClickListener {
            startDiscovery()
        }

        // 复制视频地址
        binding.copyUrlButton.setOnClickListener {
            copyVideoUrl()
        }

        // 电脑浏览器播放
        binding.browserPlayButton.setOnClickListener {
            startBrowserPlay()
        }

        // 使用系统播放器
        binding.systemPlayerButton.setOnClickListener {
            openSystemPlayer()
        }

        // 取消按钮
        binding.cancelButton.setOnClickListener {
            dismiss()
        }
    }

    private fun startDiscovery() {
        val current = _binding ?: return
        deviceAdapter.submitList(emptyList())

        // 不在 WiFi / 以太网上时，SSDP 组播只会发到移动数据接口，永远收不到设备
        if (LanNetworkGate.findLanAddress(requireContext()) == null) {
            current.subtitleText.text = "未连接 WiFi"
            current.emptyStateTitle.text = "当前未连接 WiFi"
            current.emptyStateHint.text = "DLNA 投屏要求手机与电视 / 盒子处在同一个局域网，请先连上 WiFi"
            current.emptyStateLayout.visibility = View.VISIBLE
            current.deviceRecyclerView.visibility = View.GONE
            return
        }

        current.subtitleText.text = "正在搜索设备..."
        current.emptyStateTitle.text = "未发现 DLNA 设备"
        current.emptyStateHint.text = "请确保设备与手机在同一WiFi网络"
        current.emptyStateLayout.visibility = View.GONE
        current.deviceRecyclerView.visibility = View.VISIBLE

        dlnaManager.startDiscovery { devices ->
            val view = _binding ?: return@startDiscovery
            if (devices.isEmpty()) {
                view.subtitleText.text = "未发现 DLNA 设备"
                view.emptyStateLayout.visibility = View.VISIBLE
                view.deviceRecyclerView.visibility = View.GONE
            } else {
                view.subtitleText.text = "发现 ${devices.size} 个 DLNA 设备"
                deviceAdapter.submitList(devices)
            }
        }
    }

    /**
     * 生成远程播放链接（使用远程服务器的 index.html）
     */
    private fun generateRemotePlayUrl(): String {
        val remoteProxy = LocalWebServer.REMOTE_PROXY_URL
        if (remoteProxy.isEmpty()) {
            return "未配置远程代理"
        }

        // 获取 index.html 的地址（与 video-proxy.php 同目录）
        val baseUrl = remoteProxy.substringBeforeLast("/")
        val indexUrl = "$baseUrl/index.html"

        // 构建 URL 参数
        val params = mutableListOf<String>()
        params.add("url=${java.net.URLEncoder.encode(videoUrl, "UTF-8")}")
        params.add("title=${java.net.URLEncoder.encode(videoTitle, "UTF-8")}")

        if (videoReferer.isNotEmpty()) {
            params.add("referer=${java.net.URLEncoder.encode(videoReferer, "UTF-8")}")
        }
        if (videoUserAgent.isNotEmpty()) {
            params.add("ua=${java.net.URLEncoder.encode(videoUserAgent, "UTF-8")}")
        }

        // 如果有剧集列表，添加剧集参数
        if (episodes.isNotEmpty()) {
            val episodesJson = episodes.joinToString(",", "[", "]") { ep ->
                "{\"title\":\"${ep.title}\",\"url\":\"${ep.url}\"}"
            }
            params.add("episodes=${java.net.URLEncoder.encode(episodesJson, "UTF-8")}")
            params.add("index=$currentEpisodeIndex")
        }

        return "$indexUrl?${params.joinToString("&")}"
    }

    private fun copyVideoUrl() {
        if (videoUrl.isEmpty()) {
            Toast.makeText(requireContext(), "视频地址为空", Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("视频地址", videoUrl)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(requireContext(), "视频地址已复制到剪贴板", Toast.LENGTH_SHORT).show()
        dismiss()
    }

    private fun openSystemPlayer() {
        if (videoUrl.isEmpty()) {
            Toast.makeText(requireContext(), "视频地址为空", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(Uri.parse(videoUrl), "video/*")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            dismiss()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "无法打开系统播放器", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 启动浏览器播放
     */
    private fun startBrowserPlay() {
        if (videoUrl.isEmpty()) {
            Toast.makeText(requireContext(), "视频地址为空", Toast.LENGTH_SHORT).show()
            return
        }

        // 显示加载提示
        val loadingToast = Toast.makeText(requireContext(), "⏳ 正在启动服务器...", Toast.LENGTH_SHORT)
        loadingToast.show()

        lifecycleScope.launch {
            try {
                // 在后台线程启动服务器
                val success = withContext(Dispatchers.IO) {
                    webServer = LocalWebServer.getInstance(requireContext())
                    // 如果有剧集列表，使用带剧集的方法
                    if (episodes.isNotEmpty()) {
                        webServer?.setVideoInfoWithEpisodes(
                            videoUrl, videoTitle, episodes, currentEpisodeIndex, videoReferer, videoUserAgent
                        )
                    } else {
                        webServer?.setVideoInfo(videoUrl, videoTitle, videoReferer, videoUserAgent)
                    }
                    webServer?.startServer() ?: false
                }

                if (success) {
                    val accessUrl = webServer?.getAccessUrl() ?: ""
                    Toast.makeText(requireContext(), "✅ 服务器已启动，可以访问了！", Toast.LENGTH_SHORT).show()
                    showBrowserPlayDialog(accessUrl)
                } else {
                    Toast.makeText(requireContext(), "❌ 启动服务器失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "❌ 启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 显示浏览器播放对话框
     */
    private fun showBrowserPlayDialog(url: String) {
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle("✅ 服务器已就绪")
            .setMessage("📱 服务器已启动成功！\n\n🌐 请在电脑浏览器中打开：\n\n$url\n\n⏱️ 立即可访问，无需等待\n\n💡 提示：\n• 确保手机和电脑在同一WiFi\n• 支持选集和自动下一集\n• 按F12可查看调试日志")
            .setPositiveButton("复制地址") { _, _ ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("播放地址", url)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), "✅ 地址已复制，可以粘贴到浏览器了", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .create()
        
        dialog.show()
    }

    fun setVideoInfo(url: String, title: String, referer: String = "", userAgent: String = "") {
        this.videoUrl = url
        this.videoTitle = title
        this.videoReferer = referer
        this.videoUserAgent = userAgent
    }

    fun setVideoInfoWithEpisodes(
        url: String,
        title: String,
        episodes: List<LocalWebServer.Episode>,
        currentIndex: Int = 0,
        referer: String = "",
        userAgent: String = ""
    ) {
        this.videoUrl = url
        this.videoTitle = title
        this.episodes = episodes
        this.currentEpisodeIndex = currentIndex
        this.videoReferer = referer
        this.videoUserAgent = userAgent
    }

    fun setOnDeviceSelectedListener(listener: (DLNADevice) -> Unit) {
        this.onDeviceSelected = listener
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dlnaManager.stopDiscovery()
        // 注意：不在这里停止服务器，因为用户可能还在使用
        _binding = null
    }

    companion object {
        const val TAG = "DLNACastBottomSheet"

        fun newInstance(videoUrl: String, videoTitle: String, referer: String = "", userAgent: String = ""): DLNACastBottomSheet {
            return DLNACastBottomSheet().apply {
                setVideoInfo(videoUrl, videoTitle, referer, userAgent)
            }
        }

        fun newInstanceWithEpisodes(
            videoUrl: String,
            videoTitle: String,
            episodes: List<LocalWebServer.Episode>,
            currentIndex: Int = 0,
            referer: String = "",
            userAgent: String = ""
        ): DLNACastBottomSheet {
            return DLNACastBottomSheet().apply {
                setVideoInfoWithEpisodes(videoUrl, videoTitle, episodes, currentIndex, referer, userAgent)
            }
        }
    }
}

/**
 * DLNA设备列表适配器
 */
class DLNADeviceAdapter(
    private val onDeviceClick: (DLNADevice) -> Unit
) : RecyclerView.Adapter<DLNADeviceAdapter.DeviceViewHolder>() {

    private val devices = mutableListOf<DLNADevice>()

    fun submitList(newDevices: List<DLNADevice>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemDlnaDeviceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    inner class DeviceViewHolder(
        private val binding: ItemDlnaDeviceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(device: DLNADevice) {
            binding.deviceName.text = device.name
            binding.deviceIp.text = device.ipAddress

            // 根据设备名称设置图标
            val iconRes = when {
                device.name.contains("电视", ignoreCase = true) -> R.drawable.ic_tv
                device.name.contains("Android", ignoreCase = true) -> R.drawable.ic_phone
                else -> R.drawable.ic_cast
            }
            binding.deviceIcon.setImageResource(iconRes)

            binding.root.setOnClickListener {
                onDeviceClick(device)
            }
        }
    }
}

