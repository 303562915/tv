package com.tv.ball.presentation.settings

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tv.ball.R
import com.tv.ball.util.ImageLoader
import com.tv.ball.databinding.ActivityParserBinding
import com.tv.ball.domain.parser.aggregate.AggregateParserManager
import com.tv.ball.domain.parser.aggregate.ParsedVideo
import com.tv.ball.domain.parser.aggregate.Platform
import com.tv.ball.domain.downloader.VideoDownloader
import kotlinx.coroutines.launch

class ParserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityParserBinding
    private val parserManager = AggregateParserManager()
    private var parsedVideo: ParsedVideo? = null
    private val imageAdapter = ImageGridAdapter()
    private val videoDownloader by lazy { VideoDownloader.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 应用主题色到按钮
        val themeColor = com.tv.ball.di.Dependencies.configRepository.getThemeColor()
        binding.btnParse.setBackgroundColor(themeColor)
        binding.btnPlay.setBackgroundColor(themeColor)
        binding.btnDownload.setBackgroundColor(themeColor)

        // 设置图集网格
        binding.rvImageGrid.layoutManager = GridLayoutManager(this, 2)
        binding.rvImageGrid.adapter = imageAdapter

        setupListeners()
        checkClipboard()
    }

    private fun setupListeners() {
        // 输入框文本变化监听
        binding.etVideoUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                detectPlatform()
            }
        }

        // 解析按钮
        binding.btnParse.setOnClickListener {
            parseVideo()
        }

        // 播放按钮
        binding.btnPlay.setOnClickListener {
            playVideo()
        }

        // 下载按钮
        binding.btnDownload.setOnClickListener {
            downloadMedia()
        }

        // 复制链接按钮
        binding.btnCopyUrl.setOnClickListener {
            copyVideoUrl()
        }
    }

    /**
     * 检查剪贴板
     */
    private fun checkClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val text = clipData.getItemAt(0).text?.toString() ?: ""
            if (text.isNotEmpty() && isVideoUrl(text)) {
                binding.etVideoUrl.setText(text)
                detectPlatform()
            }
        }
    }

    /**
     * 判断是否为视频链接
     */
    private fun isVideoUrl(url: String): Boolean {
        return url.contains("douyin.com") ||
                url.contains("kuaishou.com") ||
                url.contains("bilibili.com") ||
                url.contains("weibo.com") ||
                url.contains("xiaohongshu.com")
    }

    /**
     * 识别平台
     */
    private fun detectPlatform() {
        val url = binding.etVideoUrl.text.toString().trim()
        if (url.isEmpty()) {
            binding.layoutPlatformInfo.visibility = View.GONE
            return
        }

        val platform = parserManager.detectPlatform(url)
        if (platform != null) {
            binding.layoutPlatformInfo.visibility = View.VISIBLE
            binding.tvPlatformName.text = platform.platformName
        } else {
            binding.layoutPlatformInfo.visibility = View.GONE
        }
    }

    /**
     * 解析视频
     */
    private fun parseVideo() {
        val url = binding.etVideoUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "请输入视频链接", Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.cardResult.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val result = parserManager.parse(url)
                if (result != null) {
                    parsedVideo = result
                    showResult(result)
                } else {
                    Toast.makeText(this@ParserActivity, "解析失败，暂不支持该平台或链接格式", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@ParserActivity, "解析出错: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    /**
     * 显示解析结果
     */
    private fun showResult(video: ParsedVideo) {
        binding.cardResult.visibility = View.VISIBLE

        if (video.isVideo) {
            // 显示视频封面
            binding.ivResultCover.visibility = View.VISIBLE
            binding.rvImageGrid.visibility = View.GONE
            
            ImageLoader.load(
                context = this,
                url = video.coverUrl,
                imageView = binding.ivResultCover,
                placeholder = R.drawable.ic_play
            )
        } else {
            // 显示图集
            binding.ivResultCover.visibility = View.GONE
            binding.rvImageGrid.visibility = View.VISIBLE
            
            video.images?.let { images ->
                imageAdapter.setImages(images)
            }
        }

        // 显示信息
        binding.tvResultTitle.text = video.title
        binding.tvResultAuthor.text = "作者: ${video.author}"

        Toast.makeText(this, "解析成功！", Toast.LENGTH_SHORT).show()
    }

    /**
     * 播放视频
     */
    private fun playVideo() {
        val video = parsedVideo
        if (video == null) {
            Toast.makeText(this, "请先解析视频", Toast.LENGTH_SHORT).show()
            return
        }

        if (!video.isVideo) {
            Toast.makeText(this, "这是图集，不是视频", Toast.LENGTH_SHORT).show()
            return
        }

        if (video.videoUrl.isNullOrEmpty()) {
            Toast.makeText(this, "视频链接无效", Toast.LENGTH_SHORT).show()
            return
        }

        // 跳转到播放器
        val intent = Intent(this, com.tv.ball.presentation.player.VideoPlayerActivity::class.java).apply {
            putExtra("VIDEO_URL", video.videoUrl)
            putExtra("VIDEO_TITLE", video.title)
        }
        startActivity(intent)
    }

    /**
     * 下载媒体（视频或图片）
     */
    private fun downloadMedia() {
        val video = parsedVideo
        if (video == null) {
            Toast.makeText(this, "请先解析视频", Toast.LENGTH_SHORT).show()
            return
        }

        if (video.isVideo && !video.videoUrl.isNullOrEmpty()) {
            // 下载视频
            downloadVideo(video.videoUrl, video.title)
        } else if (!video.images.isNullOrEmpty()) {
            // 显示图片下载选项
            showImageDownloadOptions(video.images, video.title)
        } else {
            Toast.makeText(this, "没有可下载的内容", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 下载视频
     */
    private fun downloadVideo(url: String, title: String) {
        try {
            val taskId = videoDownloader.addDownloadTask(url, title)
            videoDownloader.startDownload(taskId)
            Toast.makeText(this, "已添加到下载列表", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "添加下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 显示图片下载选项
     */
    private fun showImageDownloadOptions(images: List<String>, title: String) {
        val options = arrayOf("下载全部图片", "选择单张下载")
        AlertDialog.Builder(this)
            .setTitle("下载图片")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> downloadAllImages(images, title)
                    1 -> showImageSelectionDialog(images, title)
                }
            }
            .show()
    }

    /**
     * 下载全部图片
     */
    private fun downloadAllImages(images: List<String>, title: String) {
        lifecycleScope.launch {
            try {
                images.forEachIndexed { index, url ->
                    val fileName = "${title}_${index + 1}"
                    val taskId = videoDownloader.addDownloadTask(url, fileName)
                    videoDownloader.startDownload(taskId)
                }
                Toast.makeText(this@ParserActivity, "已添加 ${images.size} 张图片到下载列表", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@ParserActivity, "添加下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 显示图片选择对话框
     */
    private fun showImageSelectionDialog(images: List<String>, title: String) {
        val items = images.mapIndexed { index, _ -> "图片 ${index + 1}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择要下载的图片")
            .setItems(items) { _, which ->
                val url = images[which]
                val fileName = "${title}_${which + 1}"
                val taskId = videoDownloader.addDownloadTask(url, fileName)
                videoDownloader.startDownload(taskId)
                Toast.makeText(this, "已添加到下载列表", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * 复制视频链接
     */
    private fun copyVideoUrl() {
        val video = parsedVideo
        if (video == null) {
            Toast.makeText(this, "请先解析视频", Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        
        if (video.isVideo && !video.videoUrl.isNullOrEmpty()) {
            // 复制视频链接
            val clip = ClipData.newPlainText("video_url", video.videoUrl)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "视频链接已复制到剪贴板", Toast.LENGTH_SHORT).show()
        } else if (!video.images.isNullOrEmpty()) {
            // 复制所有图片链接
            val links = video.images.joinToString("\n")
            val clip = ClipData.newPlainText("image_urls", links)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "已复制 ${video.images.size} 张图片链接", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "没有可复制的链接", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 图集适配器
     */
    inner class ImageGridAdapter : RecyclerView.Adapter<ImageGridAdapter.ImageViewHolder>() {
        
        private val images = mutableListOf<String>()

        fun setImages(newImages: List<String>) {
            images.clear()
            images.addAll(newImages)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
            val imageView = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    400
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(8, 8, 8, 8)
            }
            return ImageViewHolder(imageView)
        }

        override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
            val imageUrl = images[position]
            ImageLoader.load(
                context = holder.imageView.context,
                url = imageUrl,
                imageView = holder.imageView,
                placeholder = R.drawable.ic_play
            )
            
            holder.imageView.setOnClickListener {
                // 显示大图和下载选项
                showImagePreviewDialog(position)
            }
        }

        override fun getItemCount() = images.size

        inner class ImageViewHolder(val imageView: ImageView) : RecyclerView.ViewHolder(imageView)
    }

    /**
     * 显示图片预览对话框
     */
    private fun showImagePreviewDialog(position: Int) {
        val video = parsedVideo ?: return
        val images = video.images ?: return
        
        if (position >= images.size) return
        
        // 启动图片预览Activity
        val intent = Intent(this, ImagePreviewActivity::class.java).apply {
            putStringArrayListExtra("images", ArrayList(images))
            putExtra("title", video.title)
            putExtra("position", position)
        }
        startActivity(intent)
    }
}
