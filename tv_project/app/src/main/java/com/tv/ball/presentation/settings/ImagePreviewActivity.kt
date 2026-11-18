package com.tv.ball.presentation.settings

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.tv.ball.databinding.ActivityImagePreviewBinding
import com.tv.ball.domain.downloader.VideoDownloader

/**
 * 图片预览Activity - 支持左右滑动查看
 */
class ImagePreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImagePreviewBinding
    private val videoDownloader by lazy { VideoDownloader.getInstance(this) }
    
    private var images: List<String> = emptyList()
    private var title: String = ""
    private var currentPosition: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImagePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 获取传入的数据
        images = intent.getStringArrayListExtra("images") ?: emptyList()
        title = intent.getStringExtra("title") ?: ""
        currentPosition = intent.getIntExtra("position", 0)

        if (images.isEmpty()) {
            Toast.makeText(this, "没有图片", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupViewPager()
        setupButtons()
    }

    private fun setupViewPager() {
        val adapter = ImagePreviewAdapter(images)
        binding.viewPager.adapter = adapter
        binding.viewPager.setCurrentItem(currentPosition, false)

        // 更新标题
        updateTitle(currentPosition)

        // 监听页面切换
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPosition = position
                updateTitle(position)
            }
        })
    }

    private fun setupButtons() {
        // 关闭按钮
        binding.btnClose.setOnClickListener {
            finish()
        }

        // 下载按钮
        binding.btnDownloadImage.setOnClickListener {
            downloadCurrentImage()
        }
    }

    private fun updateTitle(position: Int) {
        binding.tvImageCount.text = "${position + 1}/${images.size}"
    }

    private fun downloadCurrentImage() {
        if (currentPosition >= images.size) return
        
        val imageUrl = images[currentPosition]
        val fileName = "${title}_${currentPosition + 1}"
        
        try {
            val taskId = videoDownloader.addDownloadTask(imageUrl, fileName)
            videoDownloader.startDownload(taskId)
            Toast.makeText(this, "已添加到下载列表", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "添加下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
