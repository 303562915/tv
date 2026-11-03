package com.tv.ball.domain.downloader

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.*
import java.security.cert.X509Certificate

/**
 * 增强的M3U8下载器
 * 支持高并发、连接复用、AES-128解密
 * 类似IDM的多线程下载技术
 */
class EnhancedM3u8Downloader(
    private val maxConcurrent: Int = 16,
    private val maxRetries: Int = 5,
    private val timeoutMs: Long = 30000L
) {
    private val TAG = "EnhancedM3u8Downloader"
    
    init {
        // 信任所有SSL证书（解决证书验证问题）
        trustAllCertificates()
    }
    
    /**
     * 信任所有SSL证书
     */
    private fun trustAllCertificates() {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
            
            Log.d(TAG, "已配置信任所有SSL证书")
        } catch (e: Exception) {
            Log.e(TAG, "配置SSL失败: ${e.message}")
        }
    }
    
    data class SegmentInfo(
        val url: String,
        val index: Int,
        val duration: Float = 0f
    )
    
    data class EncryptionInfo(
        val method: String,
        val keyUrl: String?,
        val iv: ByteArray?
    )
    
    /**
     * 下载M3U8并解析分片
     */
    suspend fun parseM3u8(url: String): Pair<List<SegmentInfo>, EncryptionInfo?> = withContext(Dispatchers.IO) {
        Log.d(TAG, "解析M3U8: $url")
        val content = downloadContent(url)
        
        // 检查是否是Master Playlist
        if (content.contains("#EXT-X-STREAM-INF")) {
            Log.d(TAG, "检测到Master Playlist，解析子播放列表")
            val subUrl = extractFirstSubPlaylist(content, url)
            return@withContext parseM3u8(subUrl)
        }
        
        // 解析媒体播放列表
        val segments = mutableListOf<SegmentInfo>()
        var encryptionInfo: EncryptionInfo? = null
        var currentDuration = 0f
        val baseUrl = url.substringBeforeLast("/")
        
        content.lines().forEach { line ->
            val trimmed = line.trim()
            
            when {
                trimmed.startsWith("#EXTINF:") -> {
                    // 提取时长
                    val durationStr = trimmed.substringAfter("#EXTINF:").substringBefore(",")
                    currentDuration = durationStr.toFloatOrNull() ?: 0f
                }
                
                trimmed.startsWith("#EXT-X-KEY:") -> {
                    // 解析加密信息
                    encryptionInfo = parseEncryption(trimmed, baseUrl)
                    Log.d(TAG, "检测到加密: ${encryptionInfo?.method}")
                }
                
                trimmed.isNotBlank() && !trimmed.startsWith("#") -> {
                    // 这是分片URL
                    val segmentUrl = resolveUrl(trimmed, baseUrl, url)
                    segments.add(SegmentInfo(segmentUrl, segments.size, currentDuration))
                }
            }
        }
        
        Log.d(TAG, "解析完成: ${segments.size} 个分片")
        Pair(segments, encryptionInfo)
    }
    
    /**
     * 高并发下载所有分片
     */
    suspend fun downloadSegments(
        segments: List<SegmentInfo>,
        outputDir: File,
        encryptionInfo: EncryptionInfo?,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<File> = withContext(Dispatchers.IO) {
        
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        
        // 预先下载解密密钥
        val decryptionKey = if (encryptionInfo?.method == "AES-128" && encryptionInfo.keyUrl != null) {
            try {
                downloadKey(encryptionInfo.keyUrl)
            } catch (e: Exception) {
                Log.e(TAG, "下载密钥失败: ${e.message}")
                null
            }
        } else null
        
        val semaphore = Semaphore(maxConcurrent)
        val downloadedFiles = mutableListOf<File?>()
        
        // 初始化结果列表
        repeat(segments.size) { downloadedFiles.add(null) }
        
        supervisorScope {
            val jobs = segments.map { segment ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        val outputFile = File(outputDir, "segment_${segment.index}.ts")
                        
                        // 如果文件已存在且大小合理，跳过
                        if (outputFile.exists() && outputFile.length() > 1024) {
                            Log.d(TAG, "分片 ${segment.index} 已存在，跳过")
                            downloadedFiles[segment.index] = outputFile
                            onProgress(segment.index + 1, segments.size)
                            return@async true
                        }
                        
                        // 下载分片（带重试）
                        var success = false
                        var retryCount = 0
                        
                        while (!success && retryCount <= maxRetries) {
                            try {
                                withTimeout(timeoutMs) {
                                    downloadSegment(segment.url, outputFile, decryptionKey, encryptionInfo?.iv)
                                }
                                success = true
                                downloadedFiles[segment.index] = outputFile
                                onProgress(segment.index + 1, segments.size)
                                
                                if ((segment.index + 1) % 10 == 0) {
                                    Log.d(TAG, "✓ 已下载 ${segment.index + 1}/${segments.size}")
                                }
                            } catch (e: TimeoutCancellationException) {
                                retryCount++
                                Log.w(TAG, "分片 ${segment.index} 超时 (重试 $retryCount/$maxRetries)")
                                delay(1000)
                            } catch (e: Exception) {
                                retryCount++
                                Log.w(TAG, "分片 ${segment.index} 失败: ${e.message} (重试 $retryCount/$maxRetries)")
                                delay(1000)
                            }
                        }
                        
                        if (!success) {
                            Log.e(TAG, "分片 ${segment.index} 下载失败，已达最大重试次数")
                        }
                        
                        success
                    } finally {
                        semaphore.release()
                    }
                }
            }
            
            // 等待所有下载完成
            val results = jobs.map { it.await() }
            val successCount = results.count { it }
            
            Log.d(TAG, "下载完成: $successCount/${segments.size} 成功")
        }
        
        // 返回成功下载的文件（保持顺序）
        downloadedFiles.filterNotNull()
    }
    
    /**
     * 下载单个分片（支持解密）
     */
    private suspend fun downloadSegment(
        url: String,
        outputFile: File,
        key: ByteArray?,
        iv: ByteArray?
    ) = withContext(Dispatchers.IO) {
        
        val connection = openConnection(url)
        
        try {
            connection.connect()
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $responseCode")
            }
            
            val inputStream = BufferedInputStream(connection.inputStream)
            val data = inputStream.readBytes()
            inputStream.close()
            
            // 如果需要解密
            val finalData = if (key != null) {
                try {
                    decryptAES128(data, key, iv)
                } catch (e: Exception) {
                    Log.w(TAG, "解密失败，使用原始数据: ${e.message}")
                    data
                }
            } else {
                data
            }
            
            // 写入文件
            FileOutputStream(outputFile).use { it.write(finalData) }
            
        } finally {
            connection.disconnect()
        }
    }
    
    /**
     * 下载解密密钥
     */
    private suspend fun downloadKey(keyUrl: String): ByteArray = withContext(Dispatchers.IO) {
        Log.d(TAG, "下载密钥: $keyUrl")
        val connection = openConnection(keyUrl)
        
        try {
            connection.connect()
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("下载密钥失败: HTTP $responseCode")
            }
            
            val key = connection.inputStream.readBytes()
            Log.d(TAG, "密钥下载成功: ${key.size} 字节")
            key
        } finally {
            connection.disconnect()
        }
    }
    
    /**
     * AES-128 解密
     */
    private fun decryptAES128(data: ByteArray, key: ByteArray, iv: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKey = SecretKeySpec(key, "AES")
        
        // 如果没有提供IV，使用默认的16字节零IV
        val ivSpec = if (iv != null) {
            IvParameterSpec(iv)
        } else {
            IvParameterSpec(ByteArray(16))
        }
        
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        return cipher.doFinal(data)
    }
    
    /**
     * 下载文本内容
     */
    private suspend fun downloadContent(url: String): String = withContext(Dispatchers.IO) {
        val connection = openConnection(url)
        
        try {
            connection.connect()
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $responseCode")
            }
            
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
    
    /**
     * 打开HTTP连接（带完整请求头，支持HTTPS）
     */
    private fun openConnection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        
        // 如果是HTTPS连接，禁用证书验证
        if (connection is HttpsURLConnection) {
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        
        connection.requestMethod = "GET"
        connection.connectTimeout = timeoutMs.toInt()
        connection.readTimeout = timeoutMs.toInt()
        connection.instanceFollowRedirects = true
        
        // 设置完整的请求头（模拟浏览器）
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        connection.setRequestProperty("Accept", "*/*")
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("Connection", "keep-alive")
        
        // 设置Referer和Origin
        try {
            val uri = java.net.URI(url)
            val origin = "${uri.scheme}://${uri.host}"
            connection.setRequestProperty("Referer", origin)
            connection.setRequestProperty("Origin", origin)
        } catch (e: Exception) {
            // 忽略
        }
        
        return connection
    }
    
    /**
     * 解析加密信息
     */
    private fun parseEncryption(line: String, baseUrl: String): EncryptionInfo? {
        val methodMatch = Regex("""METHOD=([^,]+)""").find(line)
        val method = methodMatch?.groupValues?.get(1)?.trim() ?: return null
        
        if (method == "NONE") return null
        
        val uriMatch = Regex("""URI="([^"]+)"""").find(line)
        val keyUrl = uriMatch?.groupValues?.get(1)?.let { resolveUrl(it, baseUrl, baseUrl) }
        
        val ivMatch = Regex("""IV=0x([0-9A-Fa-f]+)""").find(line)
        val iv = ivMatch?.groupValues?.get(1)?.let { hexStringToByteArray(it) }
        
        return EncryptionInfo(method, keyUrl, iv)
    }
    
    /**
     * 提取第一个子播放列表URL
     */
    private fun extractFirstSubPlaylist(content: String, baseUrl: String): String {
        content.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                return resolveUrl(trimmed, baseUrl.substringBeforeLast("/"), baseUrl)
            }
        }
        throw IOException("未找到子播放列表")
    }
    
    /**
     * 解析相对URL为绝对URL
     */
    private fun resolveUrl(url: String, baseUrl: String, originalUrl: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("/") -> {
                val uri = java.net.URI(originalUrl)
                "${uri.scheme}://${uri.host}${url}"
            }
            else -> "$baseUrl/$url"
        }
    }
    
    /**
     * 十六进制字符串转字节数组
     */
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
}
