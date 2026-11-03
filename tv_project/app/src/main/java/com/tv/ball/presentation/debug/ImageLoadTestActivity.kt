package com.tv.ball.presentation.debug

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tv.ball.databinding.ActivityImageLoadTestBinding
import com.tv.ball.util.ImageLoadMonitor
import com.tv.ball.util.ImageLoader

/**
 * 图片加载性能测试页面
 * 用于测试和验证图片加载优化效果
 */
class ImageLoadTestActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityImageLoadTestBinding
    
    // 测试图片URL列表
    private val testImageUrls = listOf(
        "https://picsum.photos/400/600?random=1",
        "https://picsum.photos/400/600?random=2",
        "https://picsum.photos/400/600?random=3",
        "https://picsum.photos/400/600?random=4",
        "https://picsum.photos/400/600?random=5",
        "https://picsum.photos/400/600?random=6",
        "https://picsum.photos/400/600?random=7",
        "https://picsum.photos/400/600?random=8",
        "https://picsum.photos/400/600?random=9",
        "https://picsum.photos/400/600?random=10",
        "https://picsum.photos/400/600?random=11",
        "https://picsum.photos/400/600?random=12",
        "https://picsum.photos/400/600?random=13",
        "https://picsum.photos/400/600?random=14",
        "https://picsum.photos/400/600?random=15",
        "https://picsum.photos/400/600?random=16",
        "https://picsum.photos/400/600?random=17",
        "https://picsum.photos/400/600?random=18",
        "https://picsum.photos/400/600?random=19",
        "https://picsum.photos/400/600?random=20"
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageLoadTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupButtons()
        updateStats()
    }
    
    private fun setupButtons() {
        // 测试预加载
        binding.btnTestPreload.setOnClickListener {
            testPreload()
        }
        
        // 测试单张加载
        binding.btnTestSingle.setOnClickListener {
            testSingleLoad()
        }
        
        // 查看统计
        binding.btnViewStats.setOnClickListener {
            viewStats()
        }
        
        // 重置统计
        binding.btnResetStats.setOnClickListener {
            resetStats()
        }
        
        // 检查性能
        binding.btnCheckPerformance.setOnClickListener {
            checkPerformance()
        }
    }
    
    /**
     * 测试预加载
     */
    private fun testPreload() {
        binding.tvStatus.text = "开始预加载测试..."
        
        val startTime = System.currentTimeMillis()
        ImageLoader.preloadImages(
            context = this,
            urls = testImageUrls,
            maxConcurrent = 6
        ) { current, total ->
            val progress = (current * 100 / total)
            binding.tvStatus.text = "预加载进度: $current/$total ($progress%)"
            
            if (current == total) {
                val duration = System.currentTimeMillis() - startTime
                binding.tvStatus.text = "预加载完成！耗时: ${duration}ms"
                updateStats()
            }
        }
    }
    
    /**
     * 测试单张加载
     */
    private fun testSingleLoad() {
        val randomUrl = testImageUrls.random()
        binding.tvStatus.text = "加载单张图片..."
        
        ImageLoader.load(
            context = this,
            url = randomUrl,
            imageView = binding.ivTest,
            placeholder = android.R.drawable.ic_menu_gallery
        )
        
        binding.tvStatus.text = "已加载: $randomUrl"
        updateStats()
    }
    
    /**
     * 查看统计
     */
    private fun viewStats() {
        val report = ImageLoadMonitor.getReport()
        binding.tvStats.text = report
        ImageLoadMonitor.printReport()
    }
    
    /**
     * 重置统计
     */
    private fun resetStats() {
        ImageLoadMonitor.reset()
        ImageLoader.clearPreloadCache()
        updateStats()
        Toast.makeText(this, "统计数据已重置", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 检查性能
     */
    private fun checkPerformance() {
        val status = ImageLoadMonitor.checkPerformance()
        val message = when (status) {
            ImageLoadMonitor.PerformanceStatus.GOOD -> "✅ 性能良好"
            ImageLoadMonitor.PerformanceStatus.MODERATE -> "⚠️ 性能一般"
            ImageLoadMonitor.PerformanceStatus.SLOW -> "⚠️ 加载较慢"
            ImageLoadMonitor.PerformanceStatus.POOR -> "❌ 性能较差"
        }
        
        binding.tvPerformance.text = "性能状态: $message"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 更新统计显示
     */
    private fun updateStats() {
        binding.tvStats.text = ImageLoadMonitor.getReport()
    }
}
