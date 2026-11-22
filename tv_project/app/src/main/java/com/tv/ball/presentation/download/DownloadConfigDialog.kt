package com.tv.ball.presentation.download

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.tv.ball.databinding.DialogDownloadConfigBinding
import com.tv.ball.domain.downloader.DownloadDirKind
import com.tv.ball.domain.downloader.DownloadStatus
import com.tv.ball.domain.downloader.VideoDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class DownloadConfigDialog : DialogFragment() {

    private lateinit var binding: DialogDownloadConfigBinding
    private var videoUrl: String = ""
    private var videoTitle: String = ""
    private var referrerUrl: String = ""  // 引用页面链接
    private var onStartDownload: ((url: String, title: String, wifiOnly: Boolean, customHeaders: Map<String, String>, threadCount: Int) -> Unit)? = null
    
    private val userAgents = listOf(
        "默认 UA" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
        "Chrome" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Firefox" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/121.0",
        "Safari" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
        "Edge" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0",
        "Android" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36",
        "iPhone" to "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    )
    
    private val fileTypes = listOf("其它", "视频", "音频", "文档", "压缩包", "应用程序")

    companion object {
        fun newInstance(url: String, title: String, referrer: String = ""): DownloadConfigDialog {
            return DownloadConfigDialog().apply {
                arguments = Bundle().apply {
                    putString("url", url)
                    putString("title", title)
                    putString("referrer", referrer)
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = DialogDownloadConfigBinding.inflate(LayoutInflater.from(requireContext()))

        videoUrl = arguments?.getString("url") ?: ""
        videoTitle = arguments?.getString("title") ?: "视频"
        referrerUrl = arguments?.getString("referrer") ?: ""

        setupViews()
        loadFileInfo()

        return AlertDialog.Builder(requireContext())
            .setView(binding.root)
            .create()
    }

    private fun setupViews() {
        // 清理转义字符
        val cleanUrl = videoUrl.replace("\\/", "/")
        
        binding.tvUrl.text = cleanUrl
        binding.etFileName.setText(videoTitle)
        
        // 从 SharedPreferences 读取默认线程数
        val prefs = requireContext().getSharedPreferences("download_settings", android.content.Context.MODE_PRIVATE)
        val defaultThreadCount = prefs.getInt("thread_count", 10)  // 默认10个线程
        binding.seekBarThreads.progress = defaultThreadCount - 1 // SeekBar 从 0 开始
        binding.tvThreadCount.text = defaultThreadCount.toString()
        
        // 设置引用页面链接
        if (referrerUrl.isNotEmpty()) {
            binding.etReferrerUrl.setText(referrerUrl)
        } else {
            // 如果没有提供引用页面，尝试从URL提取域名
            try {
                val uri = java.net.URI(cleanUrl)
                val defaultReferrer = "${uri.scheme}://${uri.host}/"
                binding.etReferrerUrl.setText(defaultReferrer)
            } catch (e: Exception) {
                // 忽略错误
            }
        }
        
        // 监听引用页面链接变化，自动更新请求头
        binding.etReferrerUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                updateHeadersWithReferrer()
            }
        }

        // 显示存储信息（先按当前「保存位置」解析一遍目录，路径与可用空间都来自它）
        refreshSaveLocation()
        
        // 自动设置防盗链请求头
        autoSetHeaders(cleanUrl)

        // 复制链接
        binding.btnCopyUrl.setOnClickListener {
            copyToClipboard(cleanUrl)
        }

        // 选择保存位置：三档单选，写 download_settings / download_dir，只对之后的任务生效
        binding.layoutSavePath.setOnClickListener {
            showSaveLocationPicker()
        }
        binding.btnChangePath.setOnClickListener {
            showSaveLocationPicker()
        }
        
        // 高级设置展开/收起
        binding.cbAdvancedSettings.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutAdvancedSettings.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
        }
        
        // 设置文件类型Spinner
        val fileTypeAdapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, fileTypes)
        fileTypeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerFileType.adapter = fileTypeAdapter
        
        // 设置User-Agent Spinner
        val userAgentNames = userAgents.map { it.first }
        val userAgentAdapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, userAgentNames)
        userAgentAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerUserAgent.adapter = userAgentAdapter
        
        // 多线程数滑块
        binding.seekBarThreads.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvThreadCount.text = (progress + 1).toString()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        
        // 速度限制滑块
        binding.seekBarSpeedLimit.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvSpeedLimit.text = if (progress == 100) "最大" else "${progress * 10}KB/s"
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // 开始下载（连接）
        binding.btnStart.setOnClickListener {
            startDownload()
        }

        // 添加到队列
        binding.btnAddToQueue.setOnClickListener {
            addToQueue()
        }

        // 取消
        binding.btnCancel.setOnClickListener {
            dismiss()
        }
    }
    
    /**
     * 自动设置防盗链请求头
     */
    private fun autoSetHeaders(url: String) {
        try {
            val uri = java.net.URI(url)
            val origin = "${uri.scheme}://${uri.host}"
            
            // 获取用户输入的引用页面链接
            val referrer = binding.etReferrerUrl.text.toString().trim().ifEmpty {
                "${uri.scheme}://${uri.host}/"
            }
            
            // 自动填充请求头
            val headers = StringBuilder()
            headers.append("Referer: $referrer\n")
            headers.append("Origin: $origin\n")
            headers.append("Accept: */*\n")
            headers.append("Accept-Language: zh-CN,zh;q=0.9,en;q=0.8\n")
            headers.append("Connection: keep-alive")
            
            binding.etCustomHeaders.setText(headers.toString())
            
            // 默认展开高级设置
            binding.cbAdvancedSettings.isChecked = true
            
        } catch (e: Exception) {
            // 忽略错误
        }
    }
    
    /**
     * 当引用页面链接改变时，更新请求头中的Referer
     */
    private fun updateHeadersWithReferrer() {
        try {
            val referrer = binding.etReferrerUrl.text.toString().trim()
            if (referrer.isEmpty()) return
            
            val currentHeaders = binding.etCustomHeaders.text.toString()
            val lines = currentHeaders.lines().toMutableList()
            
            // 查找并替换Referer行
            var refererFound = false
            for (i in lines.indices) {
                if (lines[i].trim().startsWith("Referer:", ignoreCase = true)) {
                    lines[i] = "Referer: $referrer"
                    refererFound = true
                    break
                }
            }
            
            // 如果没有找到Referer行，添加到开头
            if (!refererFound) {
                lines.add(0, "Referer: $referrer")
            }
            
            binding.etCustomHeaders.setText(lines.joinToString("\n"))
            
        } catch (e: Exception) {
            // 忽略错误
        }
    }

    private fun loadFileInfo() {
        val cleanUrl = videoUrl.replace("\\/", "/")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val connection = URL(cleanUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 10000
                connection.connect()

                val contentLength = connection.contentLength.toLong()

                withContext(Dispatchers.Main) {
                    // 不显示额外的文件信息提示
                }

                connection.disconnect()
            } catch (e: Exception) {
                // 忽略错误
            }
        }
    }

    /**
     * 「保存位置」一行：显示当前档位解析出来的**真实路径**，并刷新该分区的可用空间。
     *
     * 路径口径只有一处 —— [VideoDownloader.currentDownloadDir]（下载器真正落盘也读它），
     * 这里不再自己拼 `Downloads/...`，免得界面写的目录和文件实际去的地方不是同一个。
     */
    private fun refreshSaveLocation() {
        val context = requireContext()
        val dir = try {
            VideoDownloader.currentDownloadDir(context)
        } catch (e: Exception) {
            null
        }

        if (dir == null) {
            binding.tvSavePath.text = "—"
            binding.tvStorageInfo.text = "无法获取存储信息"
            return
        }

        binding.tvSavePath.text = dir.absolutePath
        binding.tvStorageInfo.text = try {
            val stat = StatFs(dir.absolutePath)
            val availableBytes = stat.availableBytes
            val totalBytes = stat.totalBytes
            val usedBytes = totalBytes - availableBytes
            val usedPercent = if (totalBytes > 0) usedBytes * 100.0 / totalBytes else 0.0
            String.format(
                "存储：%s/%s, %.1f%% 可用",
                formatFileSize(availableBytes),
                formatFileSize(totalBytes),
                100 - usedPercent
            )
        } catch (e: Exception) {
            "无法获取存储信息"
        }
    }

    /**
     * 保存位置三档单选。
     *
     * 选完只写偏好：落盘目录是在「新建任务」时才解析的（`addDownloadTask` → `getDownloadPath`），
     * 所以已经在跑 / 已暂停的任务照旧在原来的目录里收尾，这里不暂停、不取消、不改写任何任务。
     */
    private fun showSaveLocationPicker() {
        val context = requireContext()
        val kinds = arrayOf(
            DownloadDirKind.DEFAULT,
            DownloadDirKind.EXTERNAL_FILES,
            DownloadDirKind.INTERNAL_CACHE
        )
        val labels = arrayOf(
            "默认（Downloads/TVBall）",
            "应用外部私有目录（Android/data/…/Movies）",
            "应用内部缓存目录"
        )
        val checked = kinds
            .indexOfFirst { it == VideoDownloader.downloadDirKind(context) }
            .coerceAtLeast(0)

        AlertDialog.Builder(context)
            .setTitle("保存位置")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                applySaveLocation(kinds[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 落库 + 刷界面 + 提示。有进行中的任务时明确告知「新目录仅对之后的任务生效」。 */
    private fun applySaveLocation(kind: DownloadDirKind) {
        val context = requireContext()
        VideoDownloader.setDownloadDirKind(context, kind)
        refreshSaveLocation()

        val hasActiveTask = try {
            VideoDownloader.getInstance(context).getAllTasks().any {
                it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.MERGING
            }
        } catch (e: Exception) {
            false
        }

        Toast.makeText(
            context,
            if (hasActiveTask) "新目录仅对之后的任务生效" else "保存位置已切换",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun startDownload() {
        val fileName = binding.etFileName.text.toString().trim()
        if (fileName.isEmpty()) {
            Toast.makeText(requireContext(), "请输入文件名", Toast.LENGTH_SHORT).show()
            return
        }

        val wifiOnly = binding.cbWifiOnly.isChecked
        
        // 获取自定义请求头
        val customHeaders = getCustomHeaders()

        // 检查网络状态
        if (wifiOnly && !isWifiConnected()) {
            AlertDialog.Builder(requireContext())
                .setTitle("仅限WiFi")
                .setMessage("仅限WiFi已启用，但WiFi未连接，是否使用移动数据下载文件？")
                .setPositiveButton("确定") { _, _ ->
                    performDownload(fileName, false, customHeaders)
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            performDownload(fileName, wifiOnly, customHeaders)
        }
    }

    private fun addToQueue() {
        val fileName = binding.etFileName.text.toString().trim()
        if (fileName.isEmpty()) {
            Toast.makeText(requireContext(), "请输入文件名", Toast.LENGTH_SHORT).show()
            return
        }

        val customHeaders = getCustomHeaders()
        performDownload(fileName, binding.cbWifiOnly.isChecked, customHeaders)
        Toast.makeText(requireContext(), "已添加到下载队列", Toast.LENGTH_SHORT).show()
    }

    private fun performDownload(fileName: String, wifiOnly: Boolean, customHeaders: Map<String, String>) {
        val cleanUrl = videoUrl.replace("\\/", "/")
        val threadCount = binding.seekBarThreads.progress + 1 // 1-8个线程
        onStartDownload?.invoke(cleanUrl, fileName, wifiOnly, customHeaders, threadCount)
        dismiss()
    }
    
    private fun getCustomHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        
        // 添加选择的User-Agent
        if (binding.cbAdvancedSettings.isChecked) {
            val selectedPosition = binding.spinnerUserAgent.selectedItemPosition
            if (selectedPosition >= 0 && selectedPosition < userAgents.size) {
                headers["User-Agent"] = userAgents[selectedPosition].second
            }
            
            // 解析自定义请求头
            val customHeadersText = binding.etCustomHeaders.text.toString().trim()
            if (customHeadersText.isNotEmpty()) {
                customHeadersText.lines().forEach { line ->
                    val parts = line.split(":", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val value = parts[1].trim()
                        if (key.isNotEmpty() && value.isNotEmpty()) {
                            headers[key] = value
                        }
                    }
                }
            }
        }
        
        return headers
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("视频链接", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "链接已复制", Toast.LENGTH_SHORT).show()
    }

    private fun openInBrowser(url: String) {
        try {
            val intent = Intent(requireContext(), com.tv.ball.presentation.webview.WebViewActivity::class.java)
            intent.putExtra("url", url)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "无法打开页面", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isWifiConnected(): Boolean {
        val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> String.format("%.2fKB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.2fMB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2fGB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    fun setOnStartDownloadListener(listener: (url: String, title: String, wifiOnly: Boolean, customHeaders: Map<String, String>, threadCount: Int) -> Unit) {
        onStartDownload = listener
    }
    
    // 用于从Activity设置URL
    fun setUrl(url: String) {
        videoUrl = url
        if (::binding.isInitialized) {
            binding.tvUrl.text = url.replace("\\/", "/")
        }
    }
    
    // 用于从Activity设置标题
    fun setTitle(title: String) {
        videoTitle = title
        if (::binding.isInitialized) {
            binding.etFileName.setText(title)
        }
    }
}
