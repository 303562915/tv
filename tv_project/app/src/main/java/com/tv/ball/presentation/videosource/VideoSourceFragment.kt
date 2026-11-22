package com.tv.ball.presentation.videosource

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tv.ball.R
import com.tv.ball.data.VideoSourceManager
import com.tv.ball.data.VideoSourceConfigs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private lateinit var btnRefresh: ImageButton

    private val videoSources = mutableListOf<VideoSource>()

    /** 正在跑的那轮测速；重新刷新时先取消，避免两轮的写回互相错位。 */
    private var refreshJob: Job? = null

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

        // 测速：图标抄 FlClash 的 bolt 闪电字形（drawable/ic_fl_bolt.xml），
        // 点一下重新拉源列表并重测延迟，闪一下表示这一轮在跑
        btnRefresh = view.findViewById(R.id.btnRefresh)
        btnRefresh.setOnClickListener { refreshSources() }

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

    /** 测速按钮：闪电闪一下 + 重拉列表、重测延迟。 */
    private fun refreshSources() {
        btnRefresh.animate().alpha(0.35f).setDuration(110L).withEndAction {
            btnRefresh.animate().alpha(1f).setDuration(220L).start()
        }.start()
        loadVideoSources()
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

        // 刷新时列表已经接上了 adapter，得让它重画一遍
        if (this::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
        }

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

    /** 单次探测的硬上限：8s。OkHttp 的 callTimeout 负责真的掐断连接，测速不会再无限转圈。 */
    private companion object {
        private const val PROBE_TIMEOUT_MS = 8_000L
        private const val REASON_TIMEOUT = "超时"
        private const val REASON_CONNECT_FAILED = "连接失败"
    }

    /** 一次探测的结果：成功只有响应时间，失败带一句可以直接显示的简短原因。 */
    private data class ProbeResult(val responseTime: Int?, val failureReason: String? = null)

    private fun testResponseTimes() {
        // 上一轮还没跑完就先掐掉：否则两轮的写回会按旧下标往新列表里塞
        refreshJob?.cancel()
        val snapshot = videoSources.toList()

        // 重测时先把每一项置回「测试中」，上一轮的结果不留在屏幕上
        videoSources.indices.forEach { index ->
            videoSources[index] = videoSources[index].copy(responseTime = null, failureReason = null)
        }
        if (this::adapter.isInitialized && videoSources.isNotEmpty()) {
            adapter.notifyItemRangeChanged(0, videoSources.size)
        }

        refreshJob = lifecycleScope.launch {
            val client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS) // 单次探测硬超时 8s
                .build()

            // 并发测试所有源；失败原因各自带回，不再一律是 null（那会让界面一直显示「测试中…」）
            snapshot.indices.map { i ->
                async(Dispatchers.IO) {
                    val source = snapshot[i]
                    val config = VideoSourceConfigs.getConfig(source.sourceKey)

                    val result = if (config == null) {
                        ProbeResult(null, REASON_CONNECT_FAILED)
                    } else {
                        // 提取基础URL进行测试
                        probe(client, extractBaseUrl(config.baseUrl))
                    }

                    // 返回索引和探测结果
                    i to result
                }
            }.awaitAll().forEach { (index, result) ->
                // 在主线程更新UI
                withContext(Dispatchers.Main) {
                    // 期间列表被刷新过就丢掉这一轮的结果
                    if (index >= videoSources.size ||
                        videoSources[index].sourceKey != snapshot[index].sourceKey
                    ) {
                        return@withContext
                    }
                    videoSources[index] = videoSources[index].copy(
                        responseTime = result.responseTime,
                        failureReason = result.failureReason
                    )
                    if (this@VideoSourceFragment::adapter.isInitialized) {
                        adapter.notifyItemChanged(index)
                    }
                }
            }
        }
    }

    /**
     * 带硬超时的一次探测：HEAD 优先（省流量），
     * 源站 / CDN 不认 HEAD（403 / 405 / 501）时退回只取首字节的 GET 再测。
     *
     * 超时由 OkHttp 的 callTimeout 掐断，异常映射成「超时」/「连接失败」/「HTTP xxx」，
     * 列表里不会再出现一直转圈的项目。
     */
    private fun probe(client: OkHttpClient, testUrl: String): ProbeResult {
        val startedAt = System.currentTimeMillis()
        return try {
            val request = Request.Builder()
                .url(testUrl)
                .head() // 使用HEAD请求减少数据传输
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful ->
                        ProbeResult((System.currentTimeMillis() - startedAt).toInt())
                    response.code == 403 || response.code == 405 || response.code == 501 ->
                        probeWithGet(client, testUrl)
                    else -> ProbeResult(null, "HTTP ${response.code}")
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            ProbeResult(null, REASON_TIMEOUT)
        } catch (e: java.io.InterruptedIOException) {
            // callTimeout 到点抛的就是它（连接 / 读 / 写超时都归到这一类）
            ProbeResult(null, REASON_TIMEOUT)
        } catch (e: java.io.IOException) {
            ProbeResult(null, REASON_CONNECT_FAILED)
        } catch (e: Exception) {
            ProbeResult(null, REASON_CONNECT_FAILED)
        }
    }

    /** HEAD 不被支持时的兜底探测：只取首字节，同样受 8s 硬超时约束。 */
    private fun probeWithGet(client: OkHttpClient, testUrl: String): ProbeResult {
        val startedAt = System.currentTimeMillis()
        return try {
            val request = Request.Builder()
                .url(testUrl)
                .header("Range", "bytes=0-0")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    ProbeResult((System.currentTimeMillis() - startedAt).toInt())
                } else {
                    ProbeResult(null, "HTTP ${response.code}")
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            ProbeResult(null, REASON_TIMEOUT)
        } catch (e: java.io.InterruptedIOException) {
            ProbeResult(null, REASON_TIMEOUT)
        } catch (e: java.io.IOException) {
            ProbeResult(null, REASON_CONNECT_FAILED)
        } catch (e: Exception) {
            ProbeResult(null, REASON_CONNECT_FAILED)
        }
    }

    private fun extractBaseUrl(fullUrl: String): String {
        // 从完整URL中提取基础URL用于测试
        val regex = Regex("(https?://[^/]+)")
        return regex.find(fullUrl)?.value ?: fullUrl
    }
}
