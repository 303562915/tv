package com.tv.ball.domain.downloader

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class VideoDownloader private constructor(private val context: Context) {

    companion object {
        private const val TAG = "VideoDownloader"
        private const val BUFFER_SIZE = 8192
        private const val MAX_CONCURRENT_DOWNLOADS = 16  // 提升到16个并发（类似IDM）
        private const val SEGMENTS_PER_THREAD = 10  // 每个线程分成10段
        private const val MIN_SIZE_FOR_MULTITHREAD = 10 * 1024 * 1024  // 10MB以上才使用多线程
        private const val SEGMENT_TIMEOUT_MS = 30000L  // 每个分段30秒超时
        private const val MAX_SEGMENT_RETRIES = 5  // 每个分段最多重试5次

        @Volatile
        private var instance: VideoDownloader? = null

        fun getInstance(context: Context): VideoDownloader {
            return instance ?: synchronized(this) {
                instance ?: VideoDownloader(context.applicationContext).also { instance = it }
            }
        }
    }

    private val downloadTasks = ConcurrentHashMap<Long, DownloadTask>()
    private val downloadJobs = ConcurrentHashMap<Long, Job>()
    
    // 临时存储当前任务的加密信息
    private val encryptionInfoMap = ConcurrentHashMap<Long, M3u8EncryptionInfo>()

    private val _tasksFlow = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasksFlow: StateFlow<List<DownloadTask>> = _tasksFlow

    @Volatile
    private var downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 数据库访问 - 使用依赖注入
    private val downloadTaskDao by lazy { com.tv.ball.di.Dependencies.database.downloadTaskDao() }
    
    /**
     * 确保 downloadScope 是活跃的
     */
    private fun ensureScopeActive() {
        if (!downloadScope.isActive) {
            Log.w(TAG, "downloadScope 不活跃，重新创建")
            downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }
    }

    init {
        // 配置SSL证书信任（解决HTTPS证书验证问题）
        trustAllCertificates()
        
        // 启动时从数据库加载任务
        downloadScope.launch {
            try {
                Log.d(TAG, "=== VideoDownloader 初始化开始 ===")
                loadTasksFromDatabase()
                Log.d(TAG, "=== VideoDownloader 初始化完成 ===")
            } catch (e: Exception) {
                Log.e(TAG, "加载数据库任务失败", e)
                e.printStackTrace()
            }
        }
        
        // 在单独的协程中启动定期保存任务，使用独立的Job
        downloadScope.launch {
            while (true) {
                try {
                    delay(10000) // 10秒
                    downloadTasks.values
                        .filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.MERGING }
                        .forEach { task ->
                            try {
                                updateTaskInDatabase(task)
                            } catch (e: Exception) {
                                Log.e(TAG, "保存任务失败: ${task.id}", e)
                            }
                        }
                } catch (e: CancellationException) {
                    Log.d(TAG, "定期保存任务被取消")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "定期保存任务出错", e)
                }
            }
        }
    }
    
    /**
     * 信任所有SSL证书（解决HTTPS证书验证问题）
     */
    private fun trustAllCertificates() {
        try {
            val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
                object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                }
            )
            
            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
            javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
            
            Log.d(TAG, "✓ 已配置信任所有SSL证书")
        } catch (e: Exception) {
            Log.e(TAG, "配置SSL失败: ${e.message}")
        }
    }
    
    private suspend fun loadTasksFromDatabase() {
        try {
            // 只加载一次，不使用Flow
            val entities = downloadTaskDao.getAllTasksOnce()
            entities.forEach { entity ->
                val task = entity.toDownloadTask()
                downloadTasks[task.id] = task
            }
            updateTasksFlow()
            Log.d(TAG, "从数据库加载了 ${entities.size} 个下载任务")
        } catch (e: Exception) {
            Log.e(TAG, "加载数据库任务失败", e)
        }
    }
    
    private suspend fun observeDatabaseChanges() {
        // 可选：监听数据库变化，实时同步
        // 这里暂时不实现，避免循环更新
    }
    
    
    private suspend fun saveTaskToDatabase(task: DownloadTask) {
        try {
            downloadTaskDao.insertTask(task.toEntity())
        } catch (e: Exception) {
            Log.e(TAG, "保存任务到数据库失败", e)
        }
    }
    
    private suspend fun updateTaskInDatabase(task: DownloadTask) {
        try {
            downloadTaskDao.updateTask(task.toEntity())
        } catch (e: Exception) {
            Log.e(TAG, "更新数据库任务失败", e)
        }
    }
    
    private suspend fun deleteTaskFromDatabase(taskId: Long) {
        try {
            downloadTaskDao.deleteTaskById(taskId)
        } catch (e: Exception) {
            Log.e(TAG, "从数据库删除任务失败", e)
        }
    }

    fun addDownloadTask(url: String, title: String, customHeaders: Map<String, String> = emptyMap(), threadCount: Int = 10): Long {
        // 清理URL中的转义字符
        val cleanUrl = url.replace("\\/", "/")
        
        val taskId = System.currentTimeMillis()
        val isM3u8 = cleanUrl.contains(".m3u8")
        // M3U8 输出为 MP4（TS 分片拼接后用 MediaMuxer 重封装）
        val fileName = if (isM3u8) "$title.mp4" else "$title.${getFileExtension(cleanUrl)}"
        val savePath = getDownloadPath(fileName)

        val task = DownloadTask(
            id = taskId,
            url = cleanUrl,
            title = title,
            fileName = fileName,
            savePath = savePath,
            isM3u8 = isM3u8,
            customHeaders = customHeaders,
            threadCount = if (isM3u8) 0 else threadCount // M3U8不使用多线程
        )

        downloadTasks[taskId] = task
        updateTasksFlow()
        
        Log.d(TAG, "=== 添加下载任务 ===")
        Log.d(TAG, "任务ID: $taskId")
        Log.d(TAG, "标题: $title")
        Log.d(TAG, "URL: $cleanUrl")
        Log.d(TAG, "文件名: $fileName")
        Log.d(TAG, "保存路径: $savePath")
        Log.d(TAG, "是否M3U8: $isM3u8")
        Log.d(TAG, "线程数: $threadCount")
        Log.d(TAG, "自定义请求头: $customHeaders")
        
        // 保存到数据库
        downloadScope.launch {
            saveTaskToDatabase(task)
            Log.d(TAG, "任务已保存到数据库")
        }

        return taskId
    }

    fun startDownload(taskId: Long) {
        Log.d(TAG, "=== startDownload 被调用 ===")
        Log.d(TAG, "任务ID: $taskId")
        
        // 确保 scope 是活跃的
        ensureScopeActive()
        
        val task = downloadTasks[taskId]
        
        if (task == null) {
            Log.e(TAG, "startDownload: 未找到任务 $taskId")
            Log.e(TAG, "当前任务列表: ${downloadTasks.keys}")
            return
        }

        Log.d(TAG, "任务状态: ${task.status}")
        
        if (task.status == DownloadStatus.DOWNLOADING) {
            Log.w(TAG, "startDownload: 任务 $taskId 已在下载中")
            return
        }
        
        // 检查是否已有Job在运行
        val existingJob = downloadJobs[taskId]
        if (existingJob != null && existingJob.isActive) {
            Log.w(TAG, "startDownload: 任务 $taskId 的Job已存在且活跃，取消旧Job")
            existingJob.cancel()
            downloadJobs.remove(taskId)
        }

        Log.d(TAG, "=== 准备启动下载任务 ===")
        Log.d(TAG, "任务ID: $taskId")
        Log.d(TAG, "标题: ${task.title}")
        Log.d(TAG, "URL: ${task.url}")
        Log.d(TAG, "当前状态: ${task.status}")
        Log.d(TAG, "downloadScope是否活跃: ${downloadScope.isActive}")
        Log.d(TAG, "downloadScope是否已取消: ${downloadScope.coroutineContext[Job]?.isCancelled}")
        
        // 启动后台下载服务
        try {
            com.tv.ball.service.DownloadService.start(context)
            Log.d(TAG, "后台下载服务已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动后台服务失败", e)
        }
        
        // 先更新状态为DOWNLOADING
        updateTaskStatus(taskId, DownloadStatus.DOWNLOADING)
        Log.d(TAG, "状态已更新为 DOWNLOADING")
        
        try {
            val job = downloadScope.launch(Dispatchers.IO) {
                try {
                    Log.d(TAG, "=== 协程开始执行 ===")
                    Log.d(TAG, "线程: ${Thread.currentThread().name}")

                    // 获取最新的任务数据（包含已下载的进度）
                    val currentTask = downloadTasks[taskId] ?: task
                    
                    Log.d(TAG, "开始下载任务: ${currentTask.title}, isM3u8=${currentTask.isM3u8}, 已下载: ${currentTask.downloadedSize}, url=${currentTask.url}")
                    
                    if (currentTask.isM3u8) {
                        Log.d(TAG, "检测到 M3U8 文件，使用 M3U8 下载模式")
                        downloadM3u8(taskId, currentTask)
                    } else {
                        Log.d(TAG, "普通视频文件，使用标准下载模式")
                        downloadNormalVideo(taskId, currentTask)
                    }

                    updateTaskStatus(taskId, DownloadStatus.COMPLETED)
                    Log.d(TAG, "下载完成: ${currentTask.title}")
                    
                    // 检查是否需要停止服务
                    checkAndStopService()
                } catch (e: CancellationException) {
                    Log.w(TAG, "下载被取消: ${task.title}", e)
                    updateTaskStatus(taskId, DownloadStatus.PAUSED)
                    checkAndStopService()
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Download failed: ${e.message}", e)
                    e.printStackTrace()
                    updateTaskStatus(taskId, DownloadStatus.FAILED, e.message)
                    checkAndStopService()
                }
            }

            downloadJobs[taskId] = job
            Log.d(TAG, "下载Job已创建并启动, Job是否活跃: ${job.isActive}")
            
            // 添加Job完成监听
            job.invokeOnCompletion { throwable ->
                if (throwable != null) {
                    Log.e(TAG, "Job完成时出现异常: ${throwable.message}", throwable)
                } else {
                    Log.d(TAG, "Job正常完成")
                }
                // 清理Job引用
                downloadJobs.remove(taskId)
            }
            
            Log.d(TAG, "=== startDownload 完成 ===")
        } catch (e: Exception) {
            Log.e(TAG, "创建下载Job失败", e)
            e.printStackTrace()
            updateTaskStatus(taskId, DownloadStatus.FAILED, "创建下载任务失败: ${e.message}")
        }
    }

    fun pauseDownload(taskId: Long) {
        Log.d(TAG, "暂停下载任务: $taskId")
        val job = downloadJobs[taskId]
        if (job != null) {
            job.cancel()
            downloadJobs.remove(taskId)
            Log.d(TAG, "已取消下载任务的Job")
        } else {
            Log.w(TAG, "未找到任务的Job，可能已经完成或未开始")
        }
        updateTaskStatus(taskId, DownloadStatus.PAUSED)
        
        // 如果没有下载任务了，停止后台服务
        checkAndStopService()
    }

    fun resumeDownload(taskId: Long) {
        Log.d(TAG, "=== resumeDownload 被调用 ===")
        Log.d(TAG, "任务ID: $taskId")
        
        val task = downloadTasks[taskId]
        if (task == null) {
            Log.e(TAG, "未找到任务: $taskId")
            Log.e(TAG, "当前任务列表: ${downloadTasks.keys}")
            return
        }
        
        Log.d(TAG, "任务信息:")
        Log.d(TAG, "  标题: ${task.title}")
        Log.d(TAG, "  状态: ${task.status}")
        Log.d(TAG, "  已下载: ${task.downloadedSize}")
        Log.d(TAG, "  总大小: ${task.totalSize}")
        Log.d(TAG, "  URL: ${task.url}")
        
        // 检查任务状态
        if (task.status == DownloadStatus.DOWNLOADING) {
            Log.w(TAG, "任务已在下载中")
            return
        }
        
        // 检查是否有活跃的Job
        val existingJob = downloadJobs[taskId]
        if (existingJob != null) {
            Log.d(TAG, "存在Job，是否活跃: ${existingJob.isActive}")
            if (existingJob.isActive) {
                Log.w(TAG, "Job仍在运行，取消旧Job")
                existingJob.cancel()
                downloadJobs.remove(taskId)
            }
        }
        
        Log.d(TAG, "调用 startDownload")
        startDownload(taskId)
        Log.d(TAG, "=== resumeDownload 完成 ===")
    }

    fun deleteTask(taskId: Long, deleteFile: Boolean = false) {
        pauseDownload(taskId)
        val task = downloadTasks[taskId]
        
        if (deleteFile) {
            task?.let {
                try {
                    // 删除主文件
                    val file = File(it.savePath)
                    if (file.exists()) {
                        file.delete()
                        Log.d(TAG, "已删除文件: ${it.savePath}")
                    }
                    
                    // 如果是多线程下载，删除临时文件夹
                    if (it.threadCount > 1 && file.parent != null) {
                        val tempDir = File(file.parent!!, ".${file.name}.parts")
                        if (tempDir.exists()) {
                            tempDir.deleteRecursively()
                            Log.d(TAG, "已删除临时文件夹: ${tempDir.absolutePath}")
                        }
                        Unit
                    }

                    // 如果是M3U8，删除临时分片文件夹
                    if (it.isM3u8) {
                        val m3u8TempDir = File(context.cacheDir, "m3u8_${it.id}")
                        if (m3u8TempDir.exists()) {
                            m3u8TempDir.deleteRecursively()
                            Log.d(TAG, "已删除M3U8临时文件夹: ${m3u8TempDir.absolutePath}")
                        } else {
                            // 文件夹不存在，无需删除
                        }
                        Unit
                    } else {
                        // 不是M3U8，无需删除临时文件夹
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "删除文件失败", e)
                }
            }
        }
        
        downloadTasks.remove(taskId)
        updateTasksFlow()
        
        // 从数据库删除
        downloadScope.launch {
            deleteTaskFromDatabase(taskId)
        }
        
        // 检查是否需要停止服务
        checkAndStopService()
    }
    
    /**
     * 检查是否有活跃的下载任务，如果没有则停止后台服务
     */
    private fun checkAndStopService() {
        val hasActiveDownloads = downloadTasks.values.any { 
            it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.MERGING 
        }
        
        if (!hasActiveDownloads) {
            try {
                com.tv.ball.service.DownloadService.stop(context)
                Log.d(TAG, "没有活跃下载，已停止后台服务")
            } catch (e: Exception) {
                Log.e(TAG, "停止后台服务失败", e)
            }
        }
    }
    
    /**
     * 检查并合并已下载完成的 M3U8 分片
     * @return 成功合并的任务数量
     */
    suspend fun checkAndMergeM3u8Tasks(): Int = withContext(Dispatchers.IO) {
        var mergedCount = 0
        
        downloadTasks.values.filter { task ->
            task.isM3u8 && 
            task.status == DownloadStatus.PAUSED && 
            task.m3u8Segments.isNotEmpty() &&
            task.downloadedSize == task.totalSize &&
            task.totalSize > 0
        }.forEach { task ->
            try {
                Log.d(TAG, "检查任务: ${task.title}, 已下载: ${task.downloadedSize}/${task.totalSize}")
                
                val outputFile = File(task.savePath)
                if (outputFile.exists() && outputFile.length() > 0) {
                    Log.d(TAG, "任务 ${task.title} 已经合并完成，跳过")
                    // 更新状态为已完成
                    updateTaskStatus(task.id, DownloadStatus.COMPLETED)
                    return@forEach
                }
                
                val tempDir = File(context.cacheDir, "m3u8_${task.id}")
                if (!tempDir.exists()) {
                    Log.w(TAG, "任务 ${task.title} 的临时目录不存在，跳过")
                    return@forEach
                }
                
                // 检查所有分片是否都存在
                var allSegmentsExist = true
                for (i in 0 until task.m3u8Segments.size) {
                    val segmentFile = File(tempDir, "segment_$i.ts")
                    if (!segmentFile.exists()) {
                        allSegmentsExist = false
                        Log.w(TAG, "任务 ${task.title} 缺少分片: segment_$i.ts")
                        break
                    }
                }
                
                if (allSegmentsExist) {
                    Log.d(TAG, "开始合并任务: ${task.title}")
                    updateTaskStatus(task.id, DownloadStatus.MERGING)
                    
                    mergeSegments(tempDir, outputFile, task.m3u8Segments.size)
                    
                    // 清理临时文件
                    tempDir.deleteRecursively()
                    
                    updateTaskStatus(task.id, DownloadStatus.COMPLETED)
                    mergedCount++
                    Log.d(TAG, "成功合并任务: ${task.title}")
                } else {
                    Log.w(TAG, "任务 ${task.title} 分片不完整，无法合并")
                }
            } catch (e: Exception) {
                Log.e(TAG, "合并任务失败: ${task.title}", e)
                updateTaskStatus(task.id, DownloadStatus.FAILED, "合并失败: ${e.message}")
            }
        }
        
        Log.d(TAG, "检查完成，成功合并 $mergedCount 个任务")
        mergedCount
    }
    
    /**
     * 手动合并指定的 M3U8 任务
     */
    suspend fun mergeM3u8Task(taskId: Long): Boolean = withContext(Dispatchers.IO) {
        val task = downloadTasks[taskId]
        if (task == null) {
            Log.e(TAG, "任务不存在: $taskId")
            return@withContext false
        }
        
        if (!task.isM3u8) {
            Log.e(TAG, "任务不是 M3U8 格式: ${task.title}")
            return@withContext false
        }
        
        if (task.m3u8Segments.isEmpty()) {
            Log.e(TAG, "任务没有分片信息: ${task.title}")
            return@withContext false
        }
        
        try {
            val outputFile = File(task.savePath)
            val tempDir = File(context.cacheDir, "m3u8_${task.id}")
            
            if (!tempDir.exists()) {
                Log.e(TAG, "临时目录不存在: ${task.title}")
                return@withContext false
            }
            
            // 检查所有分片是否都存在
            var existingSegments = 0
            for (i in 0 until task.m3u8Segments.size) {
                val segmentFile = File(tempDir, "segment_$i.ts")
                if (segmentFile.exists()) {
                    existingSegments++
                }
            }
            
            Log.d(TAG, "任务 ${task.title} 有 $existingSegments/${task.m3u8Segments.size} 个分片")
            
            if (existingSegments == 0) {
                Log.e(TAG, "没有找到任何分片")
                return@withContext false
            }
            
            Log.d(TAG, "开始合并任务: ${task.title}")
            updateTaskStatus(taskId, DownloadStatus.MERGING)
            
            mergeSegments(tempDir, outputFile, task.m3u8Segments.size)
            
            // 清理临时文件
            tempDir.deleteRecursively()
            
            updateTaskStatus(taskId, DownloadStatus.COMPLETED)
            Log.d(TAG, "成功合并任务: ${task.title}")
            
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "合并任务失败: ${task.title}", e)
            updateTaskStatus(taskId, DownloadStatus.FAILED, "合并失败: ${e.message}")
            return@withContext false
        }
    }

    private suspend fun downloadNormalVideo(taskId: Long, task: DownloadTask) {
        Log.d(TAG, "开始下载普通视频: ${task.url}")
        
        // 使用增强的连接，支持自定义 DNS 解析
        val connection = try {
            com.tv.ball.domain.network.EnhancedUrlConnection.openConnection(task.url, useDnsResolver = true)
        } catch (e: Exception) {
            Log.w(TAG, "增强连接失败，使用默认连接: ${e.message}")
            URL(task.url).openConnection() as HttpURLConnection
        }

        try {
            connection.requestMethod = "HEAD"  // 先用HEAD请求获取文件信息
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            
            // 应用自定义请求头（优先级最高）
            if (task.customHeaders.isNotEmpty()) {
                Log.d(TAG, "应用自定义请求头: ${task.customHeaders}")
                task.customHeaders.forEach { (key, value) ->
                    connection.setRequestProperty(key, value)
                }
            } else {
                // 使用默认请求头
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                connection.setRequestProperty("Accept", "*/*")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                connection.setRequestProperty("Connection", "keep-alive")
                
                // 设置Referer（从URL中提取域名）
                val referer = try {
                    val uri = java.net.URI(task.url)
                    "${uri.scheme}://${uri.host}"
                } catch (e: Exception) {
                    task.url.substringBefore("?")
                }
                connection.setRequestProperty("Referer", referer)
                
                // 设置Origin
                try {
                    val uri = java.net.URI(task.url)
                    connection.setRequestProperty("Origin", "${uri.scheme}://${uri.host}")
                } catch (e: Exception) {
                    // 忽略
                }
            }

            connection.instanceFollowRedirects = true
            connection.connect()

            val responseCode = connection.responseCode
            Log.d(TAG, "HTTP 响应码: $responseCode")
            
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP error code: $responseCode, URL: ${task.url}")
            }

            val totalSize = connection.contentLength.toLong()
            val acceptRanges = connection.getHeaderField("Accept-Ranges")
            val supportsRange = acceptRanges == "bytes"
            
            Log.d(TAG, "文件大小: ${totalSize / 1024 / 1024}MB, 支持断点续传: $supportsRange")
            
            connection.disconnect()
            
            if (totalSize == 0L || totalSize == -1L) {
                throw IOException("无法获取文件大小，可能链接无效")
            }
            
            // 更新任务的总大小（保存到数据库）
            updateTask(taskId, { it.copy(totalSize = totalSize) }, saveToDb = true)
            
            // 判断是否使用多线程下载
            val useThreadCount = if (task.threadCount > 1) task.threadCount else 10 // 默认10个线程
            if (supportsRange && totalSize > MIN_SIZE_FOR_MULTITHREAD && useThreadCount > 1) {
                Log.d(TAG, "尝试使用多线程分段下载 ($useThreadCount 线程，每线程 $SEGMENTS_PER_THREAD 段)")
                try {
                    downloadWithMultiThread(taskId, task, totalSize, useThreadCount)
                } catch (e: IOException) {
                    if (e.message?.contains("HTTP error: 400") == true || e.message?.contains("HTTP error: 403") == true) {
                        Log.w(TAG, "多线程下载失败，回退到单线程下载: ${e.message}")
                        downloadWithSingleThread(taskId, task, totalSize)
                    } else {
                        throw e
                    }
                }
            } else {
                Log.d(TAG, "使用单线程下载")
                downloadWithSingleThread(taskId, task, totalSize)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "下载失败: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * 单线程下载
     */
    private suspend fun downloadWithSingleThread(taskId: Long, task: DownloadTask, totalSize: Long) = withContext(Dispatchers.IO) {
        // 使用增强的连接，支持自定义 DNS 解析
        val connection = try {
            com.tv.ball.domain.network.EnhancedUrlConnection.openConnection(task.url, useDnsResolver = true)
        } catch (e: Exception) {
            Log.w(TAG, "增强连接失败，使用默认连接: ${e.message}")
            URL(task.url).openConnection() as HttpURLConnection
        }

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            
            // 应用请求头
            applyHeaders(connection, task)

            // 支持断点续传 - 检查文件是否已存在
            val file = File(task.savePath)
            val downloadedSize = if (file.exists()) {
                val size = file.length()
                Log.d(TAG, "文件已存在，大小: $size 字节，使用断点续传")
                size
            } else {
                0L
            }

            // 如果已有下载进度，使用Range请求
            if (downloadedSize > 0) {
                connection.setRequestProperty("Range", "bytes=$downloadedSize-")
                Log.d(TAG, "设置Range请求: bytes=$downloadedSize-")
            }

            connection.instanceFollowRedirects = true
            connection.connect()

            val responseCode = connection.responseCode
            Log.d(TAG, "HTTP 响应码: $responseCode")
            
            // 206是部分内容（断点续传），200是完整内容
            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("HTTP error code: $responseCode, URL: ${task.url}")
            }
            
            // 更新任务的已下载大小（不保存到数据库，由定期保存处理）
            updateTask(taskId, { it.copy(downloadedSize = downloadedSize) }, saveToDb = false)

            val inputStream = BufferedInputStream(connection.inputStream)
            // 如果是断点续传，使用追加模式；否则覆盖
            val outputStream = FileOutputStream(file, responseCode == HttpURLConnection.HTTP_PARTIAL)

            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            var currentSize = downloadedSize
            var lastUpdateTime = System.currentTimeMillis()
            var lastSize = downloadedSize

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                // 检查是否被取消
                yield()
                
                outputStream.write(buffer, 0, bytesRead)
                currentSize += bytesRead

                val currentTime = System.currentTimeMillis()
                if (currentTime - lastUpdateTime >= 500) {  // 每500ms更新一次
                    val speed = ((currentSize - lastSize) * 1000) / (currentTime - lastUpdateTime)
                    val progress = if (totalSize > 0) {
                        val calculated = ((currentSize * 100) / totalSize).toInt()
                        if (calculated > 100) 100 else calculated
                    } else 0

                    updateTask(taskId, {
                        it.copy(
                            downloadedSize = currentSize,
                            progress = progress,
                            speed = speed
                        )
                    }, saveToDb = false)

                    lastUpdateTime = currentTime
                    lastSize = currentSize
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()
            
            Log.d(TAG, "下载完成: ${task.title}, 最终大小: ${file.length() / 1024 / 1024}MB")

        } finally {
            connection.disconnect()
        }
    }
    
    /**
     * 多线程分段下载 - 改进版
     * 每个线程独立并发下载，互不影响
     * 支持超时跳过和容错
     */
    private suspend fun downloadWithMultiThread(taskId: Long, task: DownloadTask, totalSize: Long, threadCount: Int) = withContext(Dispatchers.IO) {
        val file = File(task.savePath)
        val tempDir = File(file.parent ?: "", ".${file.name}.parts")
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
        
        // 计算每个线程下载的大小
        val partSize = totalSize / threadCount
        
        Log.d(TAG, "=== 开始多线程并发下载 ===")
        Log.d(TAG, "总大小: ${totalSize / 1024 / 1024}MB")
        Log.d(TAG, "线程数: $threadCount, 每线程分段数: $SEGMENTS_PER_THREAD")
        Log.d(TAG, "每线程大小: ${partSize / 1024 / 1024}MB, 每段大小: ${partSize / SEGMENTS_PER_THREAD / 1024}KB")
        
        // 初始化线程数和分段进度
        updateTask(taskId, { 
            it.copy(
                threadCount = threadCount,
                partProgresses = List(threadCount) { 0 }
            ) 
        }, saveToDb = true)
        
        // 创建所有下载任务，使用 supervisorScope 确保一个失败不影响其他
        supervisorScope {
            val downloadJobs = (0 until threadCount).map { i ->
                val start = i * partSize
                val end = if (i == threadCount - 1) totalSize - 1 else (i + 1) * partSize - 1
                val partFile = File(tempDir, "part_$i")
                
                // 每个线程独立启动，互不等待
                async(Dispatchers.IO) {
                    try {
                        downloadPartWithSegments(task, start, end, partFile, i, taskId, totalSize, threadCount)
                        Log.d(TAG, "✓ 线程 $i 完成")
                        true
                    } catch (e: CancellationException) {
                        Log.w(TAG, "✗ 线程 $i 被取消")
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "✗ 线程 $i 失败: ${e.message}", e)
                        false // 返回false表示失败，但不抛出异常
                    }
                }
            }
            
            // 等待所有线程完成（失败的线程不会阻塞其他线程）
            val results = downloadJobs.map { 
                try {
                    it.await()
                } catch (e: Exception) {
                    Log.e(TAG, "等待线程完成时出错", e)
                    false
                }
            }
            
            val successCount = results.count { it }
            val failCount = results.count { !it }
            
            Log.d(TAG, "=== 下载完成统计 ===")
            Log.d(TAG, "成功: $successCount/$threadCount")
            Log.d(TAG, "失败: $failCount/$threadCount")
            
            // 即使有部分失败，也尝试合并已下载的部分
            if (successCount > 0) {
                Log.d(TAG, "开始合并已下载的分段")
                updateTaskStatus(taskId, DownloadStatus.MERGING)
                mergePartsWithProgress(taskId, file, tempDir, threadCount, totalSize)
                Log.d(TAG, "文件合并完成: ${file.length() / 1024 / 1024}MB")
            } else {
                throw IOException("所有线程都下载失败")
            }
        }
    }
    
    /**
     * 下载单个线程的分段 - 改进版
     * 每个分段独立下载，支持超时和重试
     */
    private suspend fun downloadPartWithSegments(
        task: DownloadTask,
        start: Long,
        end: Long,
        partFile: File,
        partIndex: Int,
        taskId: Long,
        totalSize: Long,
        threadCount: Int
    ): Unit = withContext(Dispatchers.IO) {
        val partSize = end - start + 1
        val segmentSize = partSize / SEGMENTS_PER_THREAD
        
        Log.d(TAG, "线程 $partIndex: 开始下载，范围 $start-$end (${partSize / 1024 / 1024}MB)")
        
        // 检查是否已有部分下载
        val existingSize = if (partFile.exists()) partFile.length() else 0L
        val startSegmentIndex = (existingSize / segmentSize).toInt()
        
        if (startSegmentIndex > 0) {
            Log.d(TAG, "线程 $partIndex: 从分段 $startSegmentIndex 继续下载")
        }
        
        // 打开文件用于追加
        val outputStream = FileOutputStream(partFile, existingSize > 0)
        
        try {
            // 下载每个小段
            for (segmentIndex in startSegmentIndex until SEGMENTS_PER_THREAD) {
                // 检查是否被取消
                yield()
                
                val segmentStart = start + (segmentIndex * segmentSize)
                val segmentEnd = if (segmentIndex == SEGMENTS_PER_THREAD - 1) {
                    end
                } else {
                    start + ((segmentIndex + 1) * segmentSize) - 1
                }
                
                // 尝试下载这个分段，支持超时和重试
                var retryCount = 0
                var segmentSuccess = false
                
                while (retryCount <= MAX_SEGMENT_RETRIES && !segmentSuccess) {
                    try {
                        // 使用 withTimeout 限制每个分段的下载时间
                        withTimeout(SEGMENT_TIMEOUT_MS) {
                            downloadSegmentRange(task, segmentStart, segmentEnd, outputStream)
                        }
                        segmentSuccess = true
                        Log.d(TAG, "线程 $partIndex 段 $segmentIndex: ✓ 下载成功")
                    } catch (e: TimeoutCancellationException) {
                        retryCount++
                        Log.w(TAG, "线程 $partIndex 段 $segmentIndex: ⏱ 超时 (重试 $retryCount/$MAX_SEGMENT_RETRIES)")
                        
                        if (retryCount > MAX_SEGMENT_RETRIES) {
                            Log.e(TAG, "线程 $partIndex 段 $segmentIndex: ✗ 超时次数过多，跳过此分段")
                            // 写入空数据占位，避免文件偏移错误
                            val placeholderSize = (segmentEnd - segmentStart + 1).toInt()
                            val placeholder = ByteArray(placeholderSize) { 0 }
                            outputStream.write(placeholder)
                            break
                        }
                        
                        delay(1000) // 重试前等待1秒
                    } catch (e: IOException) {
                        retryCount++
                        Log.w(TAG, "线程 $partIndex 段 $segmentIndex: ✗ IO错误 (重试 $retryCount/$MAX_SEGMENT_RETRIES): ${e.message}")
                        
                        if (retryCount > MAX_SEGMENT_RETRIES) {
                            Log.e(TAG, "线程 $partIndex 段 $segmentIndex: ✗ 重试次数过多，跳过此分段")
                            // 写入空数据占位
                            val placeholderSize = (segmentEnd - segmentStart + 1).toInt()
                            val placeholder = ByteArray(placeholderSize) { 0 }
                            outputStream.write(placeholder)
                            break
                        }
                        
                        delay(1000)
                    }
                }
                
                // 更新进度
                val currentPartProgress = ((segmentIndex + 1) * 100) / SEGMENTS_PER_THREAD
                updateThreadProgress(taskId, partIndex, currentPartProgress, totalSize, threadCount)
            }
            
            outputStream.flush()
            outputStream.close()
            
            Log.d(TAG, "线程 $partIndex 完成: ${partFile.length() / 1024 / 1024}MB")
            
        } catch (e: CancellationException) {
            outputStream.close()
            Log.d(TAG, "线程 $partIndex 被取消")
            throw e
        } catch (e: Exception) {
            outputStream.close()
            Log.e(TAG, "线程 $partIndex 失败: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * 下载指定范围的数据
     */
    private suspend fun downloadSegmentRange(
        task: DownloadTask,
        start: Long,
        end: Long,
        outputStream: FileOutputStream
    ): Unit = withContext(Dispatchers.IO) {
        val url = URL(task.url)
        val connection = url.openConnection() as HttpURLConnection
        
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            
            // 应用请求头
            applyHeaders(connection, task)
            
            // 设置Range请求
            connection.setRequestProperty("Range", "bytes=$start-$end")
            
            connection.instanceFollowRedirects = true
            connection.connect()
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_PARTIAL && responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP error: $responseCode")
            }
            
            val inputStream = BufferedInputStream(connection.inputStream)
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                yield()
                outputStream.write(buffer, 0, bytesRead)
            }
            
            inputStream.close()
            
        } finally {
            connection.disconnect()
        }
    }
    
    /**
     * 更新单个线程的进度
     */
    private fun updateThreadProgress(taskId: Long, threadIndex: Int, threadProgress: Int, totalSize: Long, threadCount: Int) {
        val task = downloadTasks[taskId] ?: return
        val file = File(task.savePath)
        val tempDir = File(file.parent ?: "", ".${file.name}.parts")
        
        // 计算所有分段的总大小
        var downloadedSize = 0L
        val partProgresses = task.partProgresses.toMutableList()
        
        // 更新当前线程的进度
        if (threadIndex < partProgresses.size) {
            partProgresses[threadIndex] = threadProgress
        }
        
        // 计算实际下载的字节数
        for (i in 0 until threadCount) {
            val partFile = File(tempDir, "part_$i")
            if (partFile.exists()) {
                downloadedSize += partFile.length()
            }
        }
        
        val progress = if (totalSize > 0) {
            val calculated = ((downloadedSize * 100) / totalSize).toInt()
            if (calculated > 100) 100 else calculated
        } else 0
        
        updateTask(taskId, {
            it.copy(
                downloadedSize = downloadedSize,
                progress = progress,
                partProgresses = partProgresses
            )
        }, saveToDb = false)
    }
    
    /**
     * 应用HTTP请求头
     */
    private fun applyHeaders(connection: HttpURLConnection, task: DownloadTask) {
        if (task.customHeaders.isNotEmpty()) {
            task.customHeaders.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }
        } else {
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            connection.setRequestProperty("Connection", "keep-alive")
            
            val referer = try {
                val uri = java.net.URI(task.url)
                "${uri.scheme}://${uri.host}"
            } catch (e: Exception) {
                task.url.substringBefore("?")
            }
            connection.setRequestProperty("Referer", referer)
            
            try {
                val uri = java.net.URI(task.url)
                connection.setRequestProperty("Origin", "${uri.scheme}://${uri.host}")
            } catch (e: Exception) {
                // 忽略
            }
        }
    }

    private suspend fun downloadM3u8(taskId: Long, task: DownloadTask) {
        Log.d(TAG, "=== 开始 M3U8 下载流程 ===")
        Log.d(TAG, "M3U8 URL: ${task.url}")
        
        // 保存加密信息到任务
        var encryptionKey: ByteArray? = null
        var encryptionIV: ByteArray? = null
        var encryptionMethod: String? = null
        
        try {
            // 1. 下载并解析 m3u8 文件（如果还没有解析过）
            val segments = if (task.m3u8Segments.isEmpty()) {
                Log.d(TAG, "步骤 1: 下载并解析 M3U8 文件")
                val parsedSegments = resolveM3u8Segments(task.url, taskId)
                Log.d(TAG, "最终解析到 ${parsedSegments.size} 个 TS 分片")

                if (parsedSegments.isEmpty()) {
                    throw IOException("M3U8 文件中没有找到视频分片")
                }

                updateTask(taskId, { it.copy(m3u8Segments = parsedSegments, totalSize = parsedSegments.size.toLong()) }, saveToDb = true)
                parsedSegments
            } else {
                Log.d(TAG, "使用已解析的 ${task.m3u8Segments.size} 个分片")
                task.m3u8Segments
            }
            
            // 从 map 中获取加密信息（如果有）
            val encryptionInfo = encryptionInfoMap[taskId]
            encryptionKey = encryptionInfo?.key
            encryptionIV = encryptionInfo?.iv
            encryptionMethod = encryptionInfo?.method
            
            if (encryptionKey != null) {
                Log.d(TAG, "✓ 检测到加密，将在合并时解密")
            }

            // 2. 创建临时目录
            val tempDir = File(context.cacheDir, "m3u8_$taskId")
            if (!tempDir.exists()) {
                tempDir.mkdirs()
            }
            Log.d(TAG, "临时目录: ${tempDir.absolutePath}")

            // 3. 并发下载分片（使用协程池，最多同时下载3个分片）
            val startIndex = task.downloadedSize.toInt()
            Log.d(TAG, "步骤 3: 从第 $startIndex 个分片开始下载（共 ${segments.size} 个）")
            
            var totalDownloadedBytes = 0L
            val skippedSegments = mutableSetOf<Int>()
            
            // 计算已下载分片的总大小
            for (i in 0 until startIndex) {
                val segmentFile = File(tempDir, "segment_$i.ts")
                if (segmentFile.exists()) {
                    totalDownloadedBytes += segmentFile.length()
                }
            }
            
            // 使用信号量限制并发数（提升到16个，类似IDM的多线程技术）
            val semaphore = kotlinx.coroutines.sync.Semaphore(MAX_CONCURRENT_DOWNLOADS)
            
            // 使用原子计数器统一管理进度，避免跳跃
            val completedCount = java.util.concurrent.atomic.AtomicInteger(startIndex) // 从已下载的分片数开始
            val lastReportedProgress = java.util.concurrent.atomic.AtomicInteger(0)
            
            // 速度计算
            var lastUpdateTime = System.currentTimeMillis()
            var lastCompletedCount = startIndex // 从已下载的分片数开始
            
            // 初始化分段进度（16个线程）
            val segmentProgresses = Array(MAX_CONCURRENT_DOWNLOADS) { java.util.concurrent.atomic.AtomicInteger(0) }
            val segmentsPerThread = (segments.size + MAX_CONCURRENT_DOWNLOADS - 1) / MAX_CONCURRENT_DOWNLOADS
            
            // 计算已下载分片的线程进度
            for (i in 0 until startIndex) {
                val threadIndex = i / segmentsPerThread
                if (threadIndex < MAX_CONCURRENT_DOWNLOADS) {
                    val threadProgress = ((i % segmentsPerThread + 1) * 100) / segmentsPerThread
                    segmentProgresses[threadIndex].set(threadProgress.coerceAtMost(100))
                }
            }
            
            updateTask(taskId, { 
                it.copy(
                    threadCount = MAX_CONCURRENT_DOWNLOADS,
                    partProgresses = segmentProgresses.map { it.get() },
                    downloadedSize = startIndex.toLong(),
                    progress = (startIndex * 100) / segments.size
                ) 
            }, saveToDb = false)
            
            supervisorScope {
                val downloadJobs = segments.mapIndexed { index, segmentUrl ->
                    async(Dispatchers.IO) {
                        // 跳过已下载的分片
                        val segmentFile = File(tempDir, "segment_$index.ts")
                        if (index < startIndex && segmentFile.exists()) {
                            Log.d(TAG, "跳过已下载的分片 $index")
                            return@async true
                        }
                        
                        // 获取信号量许可
                        semaphore.acquire()
                        
                        try {
                            // 尝试下载分片，支持超时和重试
                            var retryCount = 0
                            var success = false
                            
                            while (retryCount <= MAX_SEGMENT_RETRIES && !success) {
                                try {
                                    withTimeout(SEGMENT_TIMEOUT_MS) {
                                        downloadSegment(segmentUrl, segmentFile)
                                    }
                                    success = true
                                    
                                    // 使用原子计数器更新进度，避免跳跃
                                    val completed = completedCount.incrementAndGet()
                                    val progress = (completed * 100) / segments.size
                                    
                                    // 计算当前分片属于哪个线程组
                                    val threadIndex = index / segmentsPerThread
                                    if (threadIndex < MAX_CONCURRENT_DOWNLOADS) {
                                        val threadProgress = ((index % segmentsPerThread + 1) * 100) / segmentsPerThread
                                        segmentProgresses[threadIndex].set(threadProgress.coerceAtMost(100))
                                    }
                                    
                                    // 计算速度（每秒更新一次）
                                    val currentTime = System.currentTimeMillis()
                                    val timeDiff = currentTime - lastUpdateTime
                                    
                                    if (timeDiff >= 1000) { // 每秒更新一次速度
                                        val countDiff = completed - lastCompletedCount
                                        // 假设每个分片平均500KB
                                        val bytesDownloaded = countDiff * 500 * 1024L
                                        val speed = (bytesDownloaded * 1000) / timeDiff // 字节/秒
                                        
                                        lastUpdateTime = currentTime
                                        lastCompletedCount = completed
                                        
                                        // 收集所有线程的进度
                                        val allProgresses = segmentProgresses.map { it.get() }
                                        
                                        updateTask(taskId, {
                                            it.copy(
                                                downloadedSize = completed.toLong(),
                                                progress = progress,
                                                partProgresses = allProgresses,
                                                speed = speed
                                            )
                                        }, saveToDb = false)
                                    } else if (progress > lastReportedProgress.get()) {
                                        // 进度变化但不到1秒，只更新进度不更新速度
                                        lastReportedProgress.set(progress)
                                        
                                        val allProgresses = segmentProgresses.map { it.get() }
                                        
                                        updateTask(taskId, {
                                            it.copy(
                                                downloadedSize = completed.toLong(),
                                                progress = progress,
                                                partProgresses = allProgresses
                                            )
                                        }, saveToDb = false)
                                    }
                                    
                                    if (completed % 10 == 0 || completed == segments.size) {
                                        Log.d(TAG, "✓ 已下载 $completed/${segments.size} 个分片 ($progress%)")
                                    }
                                } catch (e: TimeoutCancellationException) {
                                    retryCount++
                                    Log.w(TAG, "分片 $index: ⏱ 超时 (重试 $retryCount/$MAX_SEGMENT_RETRIES)")
                                    
                                    if (retryCount > MAX_SEGMENT_RETRIES) {
                                        Log.e(TAG, "分片 $index: ✗ 超时次数过多，跳过此分片（可能是广告）")
                                        skippedSegments.add(index)
                                        break
                                    }
                                    delay(1000)
                                } catch (e: IOException) {
                                    retryCount++
                                    Log.w(TAG, "分片 $index: ✗ IO错误 (重试 $retryCount/$MAX_SEGMENT_RETRIES): ${e.message}")
                                    
                                    if (retryCount > MAX_SEGMENT_RETRIES) {
                                        Log.e(TAG, "分片 $index: ✗ 重试次数过多，跳过此分片")
                                        skippedSegments.add(index)
                                        break
                                    }
                                    delay(1000)
                                }
                            }
                            
                            success
                        } finally {
                            semaphore.release()
                        }
                    }
                }
                
                // 等待所有下载完成
                val results = downloadJobs.map { 
                    try {
                        it.await()
                    } catch (e: Exception) {
                        Log.e(TAG, "等待分片下载时出错", e)
                        false
                    }
                }
                
                val successCount = results.count { it }
                val failCount = results.count { !it }
                
                Log.d(TAG, "=== M3U8 下载统计 ===")
                Log.d(TAG, "成功: $successCount/${segments.size}")
                Log.d(TAG, "失败/跳过: $failCount/${segments.size}")
                if (skippedSegments.isNotEmpty()) {
                    Log.d(TAG, "跳过的分片索引: ${skippedSegments.sorted()}")
                }
            }

            // 4. 合并分片（跳过失败的分片）
            Log.d(TAG, "步骤 4: 合并分片")
            updateTaskStatus(taskId, DownloadStatus.MERGING)
            mergeSegmentsWithSkip(tempDir, File(task.savePath), segments.size, skippedSegments, encryptionKey, encryptionIV)
            Log.d(TAG, "分片合并完成")

            // 5. 清理临时文件
            Log.d(TAG, "步骤 5: 清理临时文件")
            tempDir.deleteRecursively()
            Log.d(TAG, "=== M3U8 下载流程完成 ===")
            
        } catch (e: Exception) {
            Log.e(TAG, "M3U8 下载失败", e)
            throw e
        }
    }

    private suspend fun resolveM3u8Segments(url: String, taskId: Long? = null): List<String> = withContext(Dispatchers.IO) {
        Log.d(TAG, "解析 M3U8: $url")
        val content = downloadM3u8File(url)
        
        // 检查是否是 Master Playlist（包含 #EXT-X-STREAM-INF）
        if (content.contains("#EXT-X-STREAM-INF")) {
            Log.d(TAG, "检测到 Master Playlist，需要递归解析")
            // 找到第一个子播放列表
            val lines = content.lines()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                    val subPlaylistUrl = when {
                        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> {
                            // 完整URL
                            trimmed
                        }
                        trimmed.startsWith("/") -> {
                            // 绝对路径，需要拼接域名
                            val uri = java.net.URI(url)
                            "${uri.scheme}://${uri.host}${trimmed}"
                        }
                        else -> {
                            // 相对路径
                            val baseUri = url.substringBeforeLast("/")
                            "$baseUri/$trimmed"
                        }
                    }
                    Log.d(TAG, "找到子播放列表: $subPlaylistUrl")
                    // 递归解析子播放列表
                    return@withContext resolveM3u8Segments(subPlaylistUrl, taskId)
                }
            }
            throw IOException("Master Playlist 中没有找到子播放列表")
        } else {
            // 这是真正的媒体播放列表，解析 TS 分片
            Log.d(TAG, "这是媒体播放列表，解析 TS 分片")
            val info = parseM3u8WithEncryption(content, url, taskId)
            return@withContext info.segments
        }
    }

    private suspend fun downloadM3u8File(url: String): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "下载 M3U8 文件: $url")
        
        // 使用增强的连接，支持自定义 DNS 解析
        val connection = try {
            com.tv.ball.domain.network.EnhancedUrlConnection.openConnection(url, useDnsResolver = true)
        } catch (e: Exception) {
            Log.w(TAG, "增强连接失败，使用默认连接: ${e.message}")
            URL(url).openConnection() as HttpURLConnection
        }
        
        try {
            connection.requestMethod = "GET"
            
            // 添加更完整的请求头
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            connection.setRequestProperty("Connection", "keep-alive")
            
            // 根据URL设置Referer
            val referer = try {
                val uri = java.net.URI(url)
                "${uri.scheme}://${uri.host}"
            } catch (e: Exception) {
                url.substringBefore("?")
            }
            connection.setRequestProperty("Referer", referer)
            
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true
            connection.connect()
            
            val responseCode = connection.responseCode
            Log.d(TAG, "M3U8 响应码: $responseCode")
            
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP error code: $responseCode, URL: $url")
            }
            
            val content = connection.inputStream.bufferedReader().use { it.readText() }
            Log.d(TAG, "M3U8 文件下载成功，内容长度: ${content.length}")
            content
        } catch (e: Exception) {
            Log.e(TAG, "下载M3U8文件失败: $url", e)
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private data class M3u8Info(
        val segments: List<String>,
        val encryptionKey: ByteArray? = null,
        val encryptionIV: ByteArray? = null,
        val encryptionMethod: String? = null
    )
    
    private data class M3u8EncryptionInfo(
        val key: ByteArray?,
        val iv: ByteArray?,
        val method: String?
    )
    
    private fun parseM3u8(content: String, baseUrl: String): List<String> {
        val info = parseM3u8WithEncryption(content, baseUrl, null)
        return info.segments
    }
    
    private fun parseM3u8WithEncryption(content: String, baseUrl: String, taskId: Long?): M3u8Info {
        val segments = mutableListOf<String>()
        val uri = try {
            java.net.URI(baseUrl)
        } catch (e: Exception) {
            null
        }
        val baseUri = baseUrl.substringBeforeLast("/")
        
        Log.d(TAG, "解析媒体播放列表，基础 URL: $baseUri")
        
        // 解析加密信息
        var encryptionMethod: String? = null
        var keyUri: String? = null
        var ivHex: String? = null
        
        content.lines().forEach { line ->
            val trimmedLine = line.trim()
            
            // 解析加密标签
            if (trimmedLine.startsWith("#EXT-X-KEY:")) {
                Log.d(TAG, "发现加密标签: $trimmedLine")
                
                // 提取 METHOD
                val methodMatch = Regex("""METHOD=([^,]+)""").find(trimmedLine)
                encryptionMethod = methodMatch?.groupValues?.get(1)
                
                // 提取 URI
                val uriMatch = Regex("""URI="([^"]+)"""").find(trimmedLine)
                val rawKeyUri = uriMatch?.groupValues?.get(1)
                
                // 转换为完整 URL
                keyUri = rawKeyUri?.let { uriPath ->
                    when {
                        uriPath.startsWith("http://") || uriPath.startsWith("https://") -> uriPath
                        uriPath.startsWith("/") -> {
                            // 提取baseUri的scheme和host
                            try {
                                val baseUrl = java.net.URL(baseUri)
                                "${baseUrl.protocol}://${baseUrl.host}$uriPath"
                            } catch (e: Exception) {
                                "$baseUri/$uriPath"
                            }
                        }
                        else -> "$baseUri/$uriPath"
                    }
                }
                
                // 提取 IV
                val ivMatch = Regex("""IV=0x([0-9a-fA-F]+)""").find(trimmedLine)
                ivHex = ivMatch?.groupValues?.get(1)
                
                Log.d(TAG, "加密方法: $encryptionMethod")
                Log.d(TAG, "密钥 URI: $keyUri")
                Log.d(TAG, "IV: $ivHex")
            }
            
            // 解析分片 URL
            if (trimmedLine.isNotBlank() && !trimmedLine.startsWith("#") && trimmedLine.endsWith(".ts")) {
                val segmentUrl = when {
                    trimmedLine.startsWith("http://") || trimmedLine.startsWith("https://") -> {
                        trimmedLine
                    }
                    trimmedLine.startsWith("/") -> {
                        // 绝对路径，拼接 scheme 和 host
                        try {
                            val baseUrl = java.net.URL(baseUri)
                            "${baseUrl.protocol}://${baseUrl.host}$trimmedLine"
                        } catch (e: Exception) {
                            // 如果 baseUri 解析失败，尝试从 uri 获取
                            if (uri != null) {
                                "${uri.scheme}://${uri.host}$trimmedLine"
                            } else {
                                "$baseUri$trimmedLine"
                            }
                        }
                    }
                    else -> {
                        // 相对路径
                        "$baseUri/$trimmedLine"
                    }
                }
                segments.add(segmentUrl)
            }
        }

        Log.d(TAG, "解析到 ${segments.size} 个 TS 分片")
        
        // 如果有加密，下载密钥
        var encryptionKey: ByteArray? = null
        var encryptionIV: ByteArray? = null
        
        val finalKeyUri = keyUri  // 创建不可变副本
        val finalIvHex = ivHex  // 创建不可变副本
        
        if (encryptionMethod != null && finalKeyUri != null) {
            Log.d(TAG, "检测到加密，尝试下载密钥...")
            try {
                encryptionKey = downloadEncryptionKey(finalKeyUri)
                Log.d(TAG, "✓ 密钥下载成功: ${encryptionKey.size} 字节")
                
                // 解析 IV
                encryptionIV = if (finalIvHex != null) {
                    hexStringToByteArray(finalIvHex)
                } else {
                    // 如果没有指定 IV，使用序列号作为 IV（HLS 标准）
                    ByteArray(16) { 0 }
                }
                
                Log.d(TAG, "✓ 将使用 AES-128 解密")
                
                // 保存加密信息到 map（如果提供了 taskId）
                if (taskId != null) {
                    encryptionInfoMap[taskId] = M3u8EncryptionInfo(encryptionKey, encryptionIV, encryptionMethod)
                    Log.d(TAG, "✓ 加密信息已保存到任务 $taskId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "✗ 密钥下载失败: ${e.message}", e)
            }
        }
        
        return M3u8Info(segments, encryptionKey, encryptionIV, encryptionMethod)
    }
    
    private fun downloadEncryptionKey(keyUrl: String): ByteArray {
        val connection = URL(keyUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.connect()
            
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("密钥下载失败，HTTP code: ${connection.responseCode}")
            }
            
            return connection.inputStream.readBytes()
        } finally {
            connection.disconnect()
        }
    }
    
    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private suspend fun downloadSegment(url: String, outputFile: File) = withContext(Dispatchers.IO) {
        // 使用增强的连接，支持自定义 DNS 解析
        val connection = try {
            com.tv.ball.domain.network.EnhancedUrlConnection.openConnection(url, useDnsResolver = true)
        } catch (e: Exception) {
            Log.w(TAG, "增强连接失败，使用默认连接: ${e.message}")
            URL(url).openConnection() as HttpURLConnection
        }
        
        try {
            connection.requestMethod = "GET"
            
            // 添加完整的请求头
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            connection.setRequestProperty("Connection", "keep-alive")
            
            // 根据URL设置Referer
            val referer = try {
                val uri = java.net.URI(url)
                "${uri.scheme}://${uri.host}"
            } catch (e: Exception) {
                url.substringBefore("?")
            }
            connection.setRequestProperty("Referer", referer)
            
            connection.connectTimeout = 60000  // 连接超时60秒
            connection.readTimeout = 60000     // 读取超时60秒
            connection.instanceFollowRedirects = true
            connection.connect()
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("分片下载失败，HTTP code: $responseCode, URL: $url")
            }
            
            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(outputFile)

            inputStream.copyTo(outputStream)

            outputStream.close()
            inputStream.close()
            
            // 不要每个分片都打印日志，太多了
            // Log.d(TAG, "分片下载成功: ${outputFile.name}, 大小: ${outputFile.length()} 字节")
        } catch (e: Exception) {
            Log.e(TAG, "分片下载失败: $url", e)
            throw e
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 合并多线程下载的分片（带进度）
     */
    private suspend fun mergePartsWithProgress(
        taskId: Long,
        outputFile: File,
        tempDir: File,
        partCount: Int,
        totalSize: Long
    ) = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== 开始合并 $partCount 个分片 ===")
        Log.d(TAG, "输出文件: ${outputFile.absolutePath}")
        
        // 检查所有分片是否存在
        var totalPartSize = 0L
        for (i in 0 until partCount) {
            val partFile = File(tempDir, "part_$i")
            if (partFile.exists()) {
                totalPartSize += partFile.length()
                Log.d(TAG, "分片 $i: ${partFile.length() / 1024 / 1024}MB")
            } else {
                Log.e(TAG, "分片 $i 不存在！")
            }
        }
        
        Log.d(TAG, "分片总大小: ${totalPartSize / 1024 / 1024}MB, 预期: ${totalSize / 1024 / 1024}MB")
        
        // 使用 BufferedOutputStream 提高写入性能
        val outputStream = BufferedOutputStream(FileOutputStream(outputFile), 1024 * 1024) // 1MB buffer
        var mergedBytes = 0L
        var lastProgressUpdate = System.currentTimeMillis()
        
        try {
            for (i in 0 until partCount) {
                // 检查是否被取消
                yield()
                
                val partFile = File(tempDir, "part_$i")
                if (!partFile.exists()) {
                    Log.e(TAG, "跳过不存在的分片: part_$i")
                    continue
                }
                
                Log.d(TAG, "正在合并分片 $i/${partCount - 1}")
                
                // 使用 BufferedInputStream 提高读取性能
                val inputStream = BufferedInputStream(FileInputStream(partFile), 1024 * 1024)
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    mergedBytes += bytesRead
                    
                    // 更新合并进度（每500ms更新一次）
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastProgressUpdate >= 500) {
                        val progress = if (totalPartSize > 0) {
                            ((mergedBytes * 100) / totalPartSize).toInt()
                        } else {
                            ((i * 100) / partCount)
                        }
                        
                        updateTask(taskId, {
                            it.copy(progress = progress)
                        }, saveToDb = false)
                        
                        Log.d(TAG, "合并进度: $progress% (${mergedBytes / 1024 / 1024}MB / ${totalPartSize / 1024 / 1024}MB)")
                        lastProgressUpdate = currentTime
                    }
                }
                
                inputStream.close()
                
                // 删除已合并的分片
                partFile.delete()
                Log.d(TAG, "分片 $i 已合并并删除")
            }
            
            outputStream.flush()
            outputStream.close()
            
            // 删除临时目录
            tempDir.delete()
            
            Log.d(TAG, "=== 合并完成 ===")
            Log.d(TAG, "最终文件大小: ${outputFile.length() / 1024 / 1024}MB")
            Log.d(TAG, "文件路径: ${outputFile.absolutePath}")
            
            // 验证文件
            if (outputFile.exists() && outputFile.length() > 0) {
                Log.d(TAG, "✓ 文件验证成功")
            } else {
                Log.e(TAG, "✗ 文件验证失败！")
                throw IOException("合并后的文件无效")
            }
            
        } catch (e: Exception) {
            outputStream.close()
            Log.e(TAG, "合并失败", e)
            throw e
        }
    }
    
    /**
     * 合并 M3U8 分片（带进度）
     */
    private suspend fun mergeSegments(tempDir: File, outputFile: File, segmentCount: Int) = withContext(Dispatchers.IO) {
        mergeSegmentsWithSkip(tempDir, outputFile, segmentCount, emptySet())
    }
    
    /**
     * 合并 M3U8 分片（支持跳过指定分片）- 使用优化的拼接方式
     */
    private suspend fun mergeSegmentsWithSkip(
        tempDir: File, 
        outputFile: File, 
        segmentCount: Int,
        skipSegments: Set<Int>,
        encryptionKey: ByteArray? = null,
        encryptionIV: ByteArray? = null
    ) = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== 开始合并 $segmentCount 个 M3U8 分片 ===")
        Log.d(TAG, "输出文件: ${outputFile.absolutePath}")
        
        if (skipSegments.isNotEmpty()) {
            Log.d(TAG, "将跳过 ${skipSegments.size} 个分片: ${skipSegments.sorted()}")
        }
        
        // 收集所有分片文件
        val segmentFiles = mutableListOf<File>()
        var missingSegments = 0
        
        for (i in 0 until segmentCount) {
            val segmentFile = File(tempDir, "segment_$i.ts")
            segmentFiles.add(segmentFile)
            
            if (!segmentFile.exists() && !skipSegments.contains(i)) {
                missingSegments++
                Log.w(TAG, "缺少分片: segment_$i.ts")
            }
        }
        
        if (missingSegments > 0) {
            Log.w(TAG, "警告: 缺少 $missingSegments 个分片")
        }

        // 使用优化的拼接方式合并 TS 分片
        Log.d(TAG, "使用优化拼接方式合并 TS 分片")
        mergeSegmentsOptimized(tempDir, outputFile, segmentCount, skipSegments, encryptionKey, encryptionIV)
        
        Log.d(TAG, "=== 合并完成 ===")
        if (skipSegments.isNotEmpty()) {
            Log.d(TAG, "跳过: ${skipSegments.size} 个分片（可能是广告或无法访问）")
        }
        Log.d(TAG, "最终文件大小: ${outputFile.length() / 1024 / 1024}MB")
        
        // 验证文件是否可读
        if (outputFile.exists() && outputFile.length() > 0) {
            Log.d(TAG, "✓ 文件验证成功")
        } else {
            Log.e(TAG, "✗ 文件验证失败！")
            throw IOException("合并后的文件无效")
        }
    }
    
    /**
     * 优化的拼接方式（使用更大的缓冲区，支持 AES-128 解密，重封装为 MP4）
     */
    private suspend fun mergeSegmentsOptimized(
        tempDir: File,
        outputFile: File,
        segmentCount: Int,
        skipSegments: Set<Int>,
        encryptionKey: ByteArray? = null,
        encryptionIV: ByteArray? = null
    ) = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== 开始优化拼接方式合并 ===")
        Log.d(TAG, "临时目录: ${tempDir.absolutePath}")
        Log.d(TAG, "输出文件: ${outputFile.absolutePath}")
        Log.d(TAG, "分片总数: $segmentCount")
        Log.d(TAG, "跳过分片: ${skipSegments.size}")
        
        if (encryptionKey != null) {
            Log.d(TAG, "✓ 将使用 AES-128 解密")
        }
        
        // 步骤 1: 先拼接所有 TS 分片为一个临时 TS 文件
        val tempTsFile = File(outputFile.parent, "${outputFile.nameWithoutExtension}_temp.ts")
        Log.d(TAG, "步骤 1: 拼接 TS 分片到临时文件: ${tempTsFile.absolutePath}")
        
        var outputStream: BufferedOutputStream? = null
        var mergedCount = 0
        var totalBytes = 0L

        try {
            // 确保输出目录存在
            outputFile.parentFile?.let { parent ->
                if (!parent.exists()) {
                    parent.mkdirs()
                    Log.d(TAG, "创建输出目录: ${parent.absolutePath}")
                }
            }
            
            outputStream = BufferedOutputStream(FileOutputStream(tempTsFile), 1024 * 1024)  // 1MB buffer
            Log.d(TAG, "输出流已创建")
            
            // 准备解密器（如果需要）
            val cipher = if (encryptionKey != null) {
                try {
                    val c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                    val keySpec = javax.crypto.spec.SecretKeySpec(encryptionKey, "AES")
                    c
                } catch (e: Exception) {
                    Log.e(TAG, "创建解密器失败: ${e.message}", e)
                    null
                }
            } else {
                null
            }
            
            for (i in 0 until segmentCount) {
                yield()
                
                if (skipSegments.contains(i)) {
                    Log.d(TAG, "跳过分片 $i (在跳过列表中)")
                    continue
                }
                
                val segmentFile = File(tempDir, "segment_$i.ts")
                
                if (!segmentFile.exists()) {
                    Log.w(TAG, "分片 $i 不存在: ${segmentFile.absolutePath}")
                    continue
                }
                
                val fileSize = segmentFile.length()
                if (fileSize < 1024) {
                    Log.w(TAG, "分片 $i 太小 ($fileSize 字节)，跳过")
                    continue
                }
                
                try {
                    val segmentData = segmentFile.readBytes()
                    
                    // 如果有加密，解密分片
                    val decryptedData = if (cipher != null && encryptionKey != null) {
                        try {
                            // 每个分片使用序列号作为 IV（如果没有指定 IV）
                            val iv = encryptionIV ?: run {
                                val ivBytes = ByteArray(16)
                                // 将序列号写入 IV 的最后4个字节（大端序）
                                ivBytes[12] = ((i shr 24) and 0xFF).toByte()
                                ivBytes[13] = ((i shr 16) and 0xFF).toByte()
                                ivBytes[14] = ((i shr 8) and 0xFF).toByte()
                                ivBytes[15] = (i and 0xFF).toByte()
                                ivBytes
                            }
                            
                            val ivSpec = javax.crypto.spec.IvParameterSpec(iv)
                            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(encryptionKey, "AES"), ivSpec)
                            val decrypted = cipher.doFinal(segmentData)
                            
                            // 验证解密是否成功（检查 TS 包头）
                            if (decrypted.isNotEmpty() && decrypted[0] == 0x47.toByte()) {
                                Log.d(TAG, "✓ 分片 $i 解密成功")
                                decrypted
                            } else {
                                Log.e(TAG, "✗ 分片 $i 解密后格式不正确，可能密钥或 IV 错误")
                                throw IOException("解密失败：分片 $i 解密后不是有效的 TS 格式")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "✗ 解密分片 $i 失败: ${e.message}", e)
                            throw IOException("解密失败：${e.message}")
                        }
                    } else {
                        // 没有加密，直接使用原始数据
                        // 但先验证是否是有效的 TS 格式
                        if (segmentData.isNotEmpty() && segmentData[0] == 0x47.toByte()) {
                            segmentData
                        } else {
                            Log.w(TAG, "⚠ 分片 $i 不是标准 TS 格式（可能是加密的但未提供密钥）")
                            segmentData  // 仍然尝试使用
                        }
                    }
                    
                    outputStream.write(decryptedData)
                    totalBytes += decryptedData.size
                    mergedCount++
                    
                    if ((i + 1) % 100 == 0 || i == segmentCount - 1) {
                        val progress = ((i + 1) * 50) / segmentCount  // 拼接阶段占 50%
                        Log.d(TAG, "拼接进度: $progress% (${i + 1}/$segmentCount), 已合并: $mergedCount 个, 总大小: ${totalBytes / 1024 / 1024}MB")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "处理分片 $i 失败: ${e.message}", e)
                    // 继续处理下一个分片
                }
            }

            outputStream.flush()
            outputStream.close()
            outputStream = null
            
            Log.d(TAG, "=== TS 拼接完成 ===")
            Log.d(TAG, "成功合并: $mergedCount 个分片")
            Log.d(TAG, "临时 TS 文件大小: ${tempTsFile.length() / 1024 / 1024}MB")
            
            if (mergedCount == 0) {
                tempTsFile.delete()
                throw IOException("没有成功合并任何分片")
            }
            
            // 步骤 2: 把拼接好的 TS 重封装为 MP4（系统 MediaMuxer，只搬流不重编码）
            Log.d(TAG, "步骤 2: TS 重封装为 MP4")

            if (!tempTsFile.exists()) {
                throw IOException("临时 TS 文件不存在: ${tempTsFile.absolutePath}")
            }
            if (tempTsFile.length() == 0L) {
                throw IOException("临时 TS 文件为空")
            }
            Log.d(TAG, "临时 TS 文件验证通过: ${tempTsFile.length() / 1024 / 1024}MB")

            if (outputFile.exists()) {
                outputFile.delete()
                Log.d(TAG, "删除旧的输出文件")
            }

            val remuxed = TsToMp4Remuxer.remux(tempTsFile, outputFile)
            if (remuxed) {
                Log.d(TAG, "=== MP4 重封装成功 ===")
                tempTsFile.delete()
            } else {
                // 系统 Muxer 不支持该编码组合时，回退为直接输出 TS
                Log.w(TAG, "MP4 重封装失败，回退为 TS 输出")
                try {
                    if (outputFile.exists()) outputFile.delete()
                    tempTsFile.renameTo(outputFile)
                } catch (e: Exception) {
                    Log.w(TAG, "重命名失败，改为复制", e)
                    tempTsFile.copyTo(outputFile, overwrite = true)
                    tempTsFile.delete()
                }
            }

            Log.d(TAG, "输出文件大小: ${outputFile.length() / 1024 / 1024}MB")

        } catch (e: Exception) {
            Log.e(TAG, "=== 合并失败 ===", e)
            Log.e(TAG, "错误类型: ${e.javaClass.simpleName}")
            Log.e(TAG, "错误信息: ${e.message}")
            e.printStackTrace()
            
            try {
                outputStream?.close()
            } catch (closeException: Exception) {
                Log.e(TAG, "关闭输出流失败", closeException)
            }
            
            throw e
        }
    }
    
    private fun updateTask(taskId: Long, update: (DownloadTask) -> DownloadTask, saveToDb: Boolean = false) {
        downloadTasks[taskId]?.let { task ->
            val updatedTask = update(task).copy(updateTime = System.currentTimeMillis())
            downloadTasks[taskId] = updatedTask
            updateTasksFlow()
            
            // 只在需要时更新数据库（状态变化、完成、失败等关键时刻）
            if (saveToDb) {
                downloadScope.launch {
                    updateTaskInDatabase(updatedTask)
                }
            }
        }
    }

    private fun updateTaskStatus(taskId: Long, status: DownloadStatus, errorMessage: String? = null) {
        updateTask(taskId, { it.copy(status = status, errorMessage = errorMessage) }, saveToDb = true)
        
        // 下载完成时，通知系统媒体扫描器扫描新文件
        if (status == DownloadStatus.COMPLETED) {
            val task = downloadTasks[taskId]
            task?.let {
                val file = File(it.savePath)
                if (file.exists()) {
                    scanMediaFile(file)
                    Log.d(TAG, "已通知系统扫描媒体文件: ${file.absolutePath}")
                }
            }
        }
    }
    
    /**
     * 通知系统媒体扫描器扫描文件
     */
    private fun scanMediaFile(file: File) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            intent.data = android.net.Uri.fromFile(file)
            context.sendBroadcast(intent)
            
            // 同时使用MediaScannerConnection（更可靠）
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("video/*")
            ) { path, uri ->
                Log.d(TAG, "媒体扫描完成: path=$path, uri=$uri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "媒体扫描失败: ${e.message}", e)
        }
    }

    private fun updateTasksFlow() {
        _tasksFlow.value = downloadTasks.values.sortedByDescending { it.createTime }
    }

    private fun getDownloadPath(fileName: String): String {
        val downloadDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "TVBall"
        )
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }
        return File(downloadDir, fileName).absolutePath
    }

    private fun getFileExtension(url: String): String {
        return url.substringAfterLast(".").substringBefore("?").ifEmpty { "mp4" }
    }

    fun getAllTasks(): List<DownloadTask> {
        return downloadTasks.values.sortedByDescending { it.createTime }
    }

    fun getTask(taskId: Long): DownloadTask? {
        return downloadTasks[taskId]
    }

    fun cleanup() {
        downloadJobs.values.forEach { it.cancel() }
        downloadJobs.clear()
        downloadScope.cancel()
    }
}
