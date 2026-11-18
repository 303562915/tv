package com.tv.ball.presentation.debug

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tv.ball.databinding.ActivityHtmlDebugBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * HTML调试工具 - 用于查看网页源码
 */
class HtmlDebugActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityHtmlDebugBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHtmlDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // 默认URL
        binding.etUrl.setText("https://103.194.185.51:51120/show/1--------1---.html")
        
        binding.btnFetch.setOnClickListener {
            fetchHtml()
        }
        
        binding.btnCopy.setOnClickListener {
            copyToClipboard()
        }
        
        binding.btnSavePattern.setOnClickListener {
            savePattern()
        }
    }
    
    private fun fetchHtml() {
        val url = binding.etUrl.text.toString()
        if (url.isEmpty()) {
            Toast.makeText(this, "请输入URL", Toast.LENGTH_SHORT).show()
            return
        }
        
        binding.tvStatus.text = "正在获取..."
        binding.tvHtml.text = ""
        
        lifecycleScope.launch {
            try {
                val html = withContext(Dispatchers.IO) {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build()
                    
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                        .build()
                    
                    val response = client.newCall(request).execute()
                    response.body?.string() ?: ""
                }
                
                binding.tvStatus.text = "获取成功 (${html.length} 字符)"
                binding.tvHtml.text = html
                
                // 分析HTML结构
                analyzeHtml(html)
                
            } catch (e: Exception) {
                binding.tvStatus.text = "获取失败: ${e.message}"
                Toast.makeText(this@HtmlDebugActivity, "错误: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun analyzeHtml(html: String) {
        val analysis = StringBuilder()
        analysis.append("=== HTML结构分析 ===\n\n")
        
        // 查找视频列表容器
        val containers = listOf(
            "stui-vodlist",
            "module-items",
            "video-list",
            "vodlist",
            "movie-list"
        )
        
        for (container in containers) {
            val count = html.split(container).size - 1
            if (count > 0) {
                analysis.append("✓ 找到 '$container': $count 次\n")
            }
        }
        
        analysis.append("\n=== 视频项结构 ===\n\n")
        
        // 查找视频项
        val itemPatterns = listOf(
            "stui-vodlist__box",
            "module-item",
            "video-item",
            "vodlist-item"
        )
        
        for (pattern in itemPatterns) {
            val count = html.split(pattern).size - 1
            if (count > 0) {
                analysis.append("✓ 找到 '$pattern': $count 次\n")
            }
        }
        
        analysis.append("\n=== 链接结构 ===\n\n")
        
        // 提取示例链接
        val linkPattern = """href="(/detail/[^"]+)"""".toRegex()
        val links = linkPattern.findAll(html).take(3).toList()
        if (links.isNotEmpty()) {
            analysis.append("示例详情链接:\n")
            links.forEach { match ->
                analysis.append("  ${match.groupValues[1]}\n")
            }
        }
        
        analysis.append("\n=== 图片结构 ===\n\n")
        
        // 查找图片属性
        val imgAttrs = listOf("data-original", "data-src", "src", "lazy-src")
        for (attr in imgAttrs) {
            val count = html.split(attr).size - 1
            if (count > 0) {
                analysis.append("✓ 找到 '$attr': $count 次\n")
            }
        }
        
        // 提取示例图片URL
        val imgPattern = """data-original="([^"]+)"""".toRegex()
        val imgs = imgPattern.findAll(html).take(3).toList()
        if (imgs.isNotEmpty()) {
            analysis.append("\n示例图片URL:\n")
            imgs.forEach { match ->
                analysis.append("  ${match.groupValues[1]}\n")
            }
        }
        
        analysis.append("\n=== 标题结构 ===\n\n")
        
        // 查找标题
        val titlePattern = """<h4[^>]*class="[^"]*title[^"]*"[^>]*>.*?<a[^>]*>([^<]+)</a>""".toRegex()
        val titles = titlePattern.findAll(html).take(5).toList()
        if (titles.isNotEmpty()) {
            analysis.append("示例标题:\n")
            titles.forEach { match ->
                analysis.append("  ${match.groupValues[1]}\n")
            }
        }
        
        binding.tvAnalysis.text = analysis.toString()
    }
    
    private fun copyToClipboard() {
        val html = binding.tvHtml.text.toString()
        if (html.isEmpty()) {
            Toast.makeText(this, "没有内容可复制", Toast.LENGTH_SHORT).show()
            return
        }
        
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("HTML", html)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }
    
    private fun savePattern() {
        val analysis = binding.tvAnalysis.text.toString()
        if (analysis.isEmpty()) {
            Toast.makeText(this, "请先获取HTML", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 保存分析结果
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("分析结果", analysis)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "分析结果已复制", Toast.LENGTH_SHORT).show()
    }
}
