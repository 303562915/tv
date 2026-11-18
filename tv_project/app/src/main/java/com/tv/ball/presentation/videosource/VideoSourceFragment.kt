package com.tv.ball.presentation.videosource

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.R
import com.tv.ball.data.VideoSourceManager
import com.tv.ball.data.VideoSourceConfigs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class VideoSourceFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: VideoSourceAdapter

    private val videoSources = mutableListOf<VideoSource>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_video_source, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // 从云端配置加载视频源列表
        loadVideoSources()

        adapter = VideoSourceAdapter(videoSources) { source ->
            // 切换视频源
            VideoSourceManager.setCurrentSource(source.sourceKey)

            // 更新选中状态
            updateSelectedState()
            adapter.notifyDataSetChanged()

            com.tv.ball.util.CustomToast.showLong(
                requireContext(),
                "已切换到: ${source.name}\n请返回主页刷新查看"
            )
        }

        recyclerView.adapter = adapter
    }

    private fun loadVideoSources() {
        // 从 VideoSourceConfigs 加载所有视频源
        val configs = VideoSourceConfigs.sources

        videoSources.clear()
        for (config in configs) {
            videoSources.add(
                VideoSource(
                    name = config.name,
                    iconUrl = "", // 可以根据需要添加图标URL
                    sourceKey = config.key,
                    isSelected = false,
                    responseTime = null
                )
            )
        }

        // 更新选中状态
        updateSelectedState()

        // 测试响应时间
        testResponseTimes()
    }

    private fun updateSelectedState() {
        val currentSource = VideoSourceManager.getCurrentSource()
        for (i in videoSources.indices) {
            val source = videoSources[i]
            videoSources[i] = source.copy(isSelected = source.sourceKey == currentSource)
        }
    }

    private fun testResponseTimes() {
        lifecycleScope.launch {
            val client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .callTimeout(3, TimeUnit.SECONDS) // 总超时限制
                .build()

            // 并发测试所有源
            videoSources.indices.map { i ->
                async(Dispatchers.IO) {
                    val source = videoSources[i]
                    val config = VideoSourceConfigs.getConfig(source.sourceKey)
                    
                    val responseTime = if (config != null) {
                        try {
                            // 提取基础URL进行测试
                            val testUrl = extractBaseUrl(config.baseUrl)
                            val startTime = System.currentTimeMillis()
                            
                            val request = Request.Builder()
                                .url(testUrl)
                                .head() // 使用HEAD请求减少数据传输
                                .build()
                            
                            val response = client.newCall(request).execute()
                            response.close()
                            val endTime = System.currentTimeMillis()
                            
                            if (response.isSuccessful) {
                                (endTime - startTime).toInt()
                            } else {
                                null
                            }
                        } catch (e: Exception) {
                            // 超时或网络错误
                            null
                        }
                    } else {
                        null
                    }
                    
                    // 返回索引和响应时间
                    i to responseTime
                }
            }.awaitAll().forEach { (index, responseTime) ->
                // 在主线程更新UI
                withContext(Dispatchers.Main) {
                    videoSources[index] = videoSources[index].copy(responseTime = responseTime)
                    adapter.notifyItemChanged(index)
                }
            }
        }
    }

    private fun extractBaseUrl(fullUrl: String): String {
        // 从完整URL中提取基础URL用于测试
        val regex = Regex("(https?://[^/]+)")
        return regex.find(fullUrl)?.value ?: fullUrl
    }
}
