package com.tv.ball.data.remote

import android.content.Context
import android.net.Network
import android.webkit.WebView
import android.webkit.WebViewClient
import com.tv.ball.domain.network.SmartNetworkSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

class NetworkDataSource(
    private val okHttpClient: OkHttpClient,
    private val context: Context? = null
) {
    
    private val smartNetworkSelector by lazy { 
        context?.let { SmartNetworkSelector(it) }
    }
    
    /**
     * 智能网络请求：自动选择可用网络（优先WiFi，不可用时切换移动数据）
     */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        useSmartNetwork: Boolean = true
    ): NetworkResult<String> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            // 如果启用智能网络且有多个网络可用
            val selector = smartNetworkSelector
            if (useSmartNetwork && selector != null && selector.hasBothNetworks()) {
                android.util.Log.d("NetworkDataSource", "检测到多个网络，启用智能切换")
                
                // 选择可用网络（优先WiFi）
                val availableNetwork = selector.selectAvailableNetwork()
                
                if (availableNetwork != null) {
                    val networkType = when {
                        selector.getWifiNetwork() == availableNetwork -> "WiFi"
                        selector.getMobileNetwork() == availableNetwork -> "移动数据"
                        else -> "未知"
                    }
                    android.util.Log.d("NetworkDataSource", "使用网络: $networkType")
                    
                    // 绑定到可用网络
                    selector.bindToNetwork(availableNetwork)
                    
                    try {
                        val result = performRequest(url, headers)
                        val elapsed = System.currentTimeMillis() - startTime
                        android.util.Log.d("NetworkDataSource", "请求完成 (${elapsed}ms)")
                        return@withContext result
                    } finally {
                        // 请求完成后解除绑定
                        selector.unbindNetwork()
                    }
                } else {
                    android.util.Log.w("NetworkDataSource", "没有可用网络，使用默认网络")
                }
            }
            
            // 使用默认网络
            val result = performRequest(url, headers)
            val elapsed = System.currentTimeMillis() - startTime
            android.util.Log.d("NetworkDataSource", "请求完成 (${elapsed}ms)")
            result
            
        } catch (e: IOException) {
            val elapsed = System.currentTimeMillis() - startTime
            android.util.Log.e("NetworkDataSource", "网络错误 (${elapsed}ms): ${e.message}")
            NetworkResult.Error("Network error: ${e.message}", e)
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            android.util.Log.e("NetworkDataSource", "未知错误 (${elapsed}ms): ${e.message}")
            NetworkResult.Error("Unexpected error: ${e.message}", e)
        }
    }
    
    /**
     * 执行实际的网络请求
     */
    private fun performRequest(url: String, headers: Map<String, String>): NetworkResult<String> {
        android.util.Log.d("NetworkDataSource", "========== 网络请求详情 ==========")
        android.util.Log.d("NetworkDataSource", "请求URL: $url")
        android.util.Log.d("NetworkDataSource", "请求头: $headers")
        
        try {
            val requestBuilder = Request.Builder().url(url)
            headers.forEach { (key, value) ->
                requestBuilder.addHeader(key, value)
            }
            
            android.util.Log.d("NetworkDataSource", "开始执行请求...")
            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            android.util.Log.d("NetworkDataSource", "响应状态码: ${response.code}")
            android.util.Log.d("NetworkDataSource", "响应消息: ${response.message}")
            android.util.Log.d("NetworkDataSource", "========================================")
            
            return handleResponse(response)
        } catch (e: Exception) {
            android.util.Log.e("NetworkDataSource", "========== 网络请求失败 ==========")
            android.util.Log.e("NetworkDataSource", "请求URL: $url")
            android.util.Log.e("NetworkDataSource", "错误类型: ${e.javaClass.simpleName}")
            android.util.Log.e("NetworkDataSource", "错误信息: ${e.message}")
            android.util.Log.e("NetworkDataSource", "堆栈跟踪:", e)
            android.util.Log.e("NetworkDataSource", "========================================")
            throw e
        }
    }
    
    suspend fun post(
        url: String,
        body: RequestBody,
        headers: Map<String, String> = emptyMap()
    ): NetworkResult<String> = withContext(Dispatchers.IO) {
        try {
            val requestBuilder = Request.Builder()
                .url(url)
                .post(body)
            
            headers.forEach { (key, value) ->
                requestBuilder.addHeader(key, value)
            }
            
            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            handleResponse(response)
        } catch (e: IOException) {
            NetworkResult.Error("Network error: ${e.message}", e)
        } catch (e: Exception) {
            NetworkResult.Error("Unexpected error: ${e.message}", e)
        }
    }
    
    private fun handleResponse(response: Response): NetworkResult<String> {
        return if (response.isSuccessful) {
            val body = response.body?.string()
            if (body != null) {
                NetworkResult.Success(body)
            } else {
                NetworkResult.Error("Empty response body")
            }
        } else {
            NetworkResult.Error("HTTP ${response.code}: ${response.message}")
        }
    }
    
    suspend fun fetchHtml(url: String): String = withContext(Dispatchers.IO) {
        val result = get(url)
        when (result) {
            is NetworkResult.Success -> result.data
            is NetworkResult.Error -> throw IOException(result.message)
        }
    }
    
    /**
     * 使用 WebView 加载页面并等待 JavaScript 执行完成后提取 HTML
     */
    suspend fun fetchHtmlWithWebView(url: String): NetworkResult<String> {
        if (context == null) {
            return NetworkResult.Error("Context is required for WebView")
        }
        
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<NetworkResult<String>> { continuation ->
                try {
                    val webView = WebView(context).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            blockNetworkImage = true // 不加载图片，加快速度
                            userAgentString = "Mozilla/5.0 (Linux; Android 13; SM-T837A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
                        }
                    }
                    
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            // 等待一小段时间确保 JavaScript 执行完成
                            view?.postDelayed({
                                view.evaluateJavascript("document.documentElement.outerHTML") { html ->
                                    // 移除 JavaScript 返回的引号
                                    val cleanHtml = html?.replace("\\u003C", "<")
                                        ?.replace("\\n", "")
                                        ?.replace("\\\"", "\"")
                                        ?.removeSurrounding("\"") ?: ""
                                    
                                    if (continuation.isActive) {
                                        continuation.resume(NetworkResult.Success(cleanHtml))
                                    }
                                    
                                    // 清理 WebView
                                    view.destroy()
                                }
                            }, 2000) // 等待 2 秒让 JavaScript 执行
                        }
                        
                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?
                        ) {
                            if (continuation.isActive) {
                                continuation.resume(NetworkResult.Error("WebView error: $description"))
                            }
                            view?.destroy()
                        }
                    }
                    
                    continuation.invokeOnCancellation {
                        webView.destroy()
                    }
                    
                    webView.loadUrl(url)
                } catch (e: Exception) {
                    if (continuation.isActive) {
                        continuation.resume(NetworkResult.Error("WebView exception: ${e.message}"))
                    }
                }
            }
        }
    }
}

sealed class NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>()
    data class Error(val message: String, val exception: Throwable? = null) : NetworkResult<Nothing>()
}
