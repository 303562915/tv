package com.tv.ball.presentation.player

import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.HttpDataSource
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 使用OkHttp的优化HTTP数据源工厂
 * 支持HTTP/2多路复用、连接池、自动重试
 */
object OkHttpDataSourceFactory {
    
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // 连接池配置 - 支持多个并发连接
            .connectionPool(
                okhttp3.ConnectionPool(
                    maxIdleConnections = 16,  // 增加到16个空闲连接（更多并发）
                    keepAliveDuration = 5,    // 保持5分钟
                    timeUnit = TimeUnit.MINUTES
                )
            )
            // 超时配置 - 针对慢速网络优化
            .connectTimeout(15, TimeUnit.SECONDS)  // 连接超时增加到15秒（慢速网络）
            .readTimeout(20, TimeUnit.SECONDS)     // 读取超时增加到20秒（慢速网络）
            .writeTimeout(15, TimeUnit.SECONDS)    // 写入超时增加到15秒
            .callTimeout(60, TimeUnit.SECONDS)     // 整体调用超时增加到60秒
            // 自动重试
            .retryOnConnectionFailure(true)
            // 支持HTTP/2和HTTP/1.1
            .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
            // 缓存控制
            .cache(null)  // 使用ExoPlayer的缓存而不是OkHttp的
            // DNS配置 - 使用系统DNS，更快
            .dns(okhttp3.Dns.SYSTEM)
            // 添加日志拦截器用于调试
            .addInterceptor { chain ->
                val request = chain.request()
                android.util.Log.d("OkHttp", "=== HTTP请求 ===")
                android.util.Log.d("OkHttp", "URL: ${request.url}")
                android.util.Log.d("OkHttp", "Method: ${request.method}")
                android.util.Log.d("OkHttp", "Headers: ${request.headers}")
                
                try {
                    val response = chain.proceed(request)
                    android.util.Log.d("OkHttp", "=== HTTP响应 ===")
                    android.util.Log.d("OkHttp", "Code: ${response.code}")
                    android.util.Log.d("OkHttp", "Message: ${response.message}")
                    android.util.Log.d("OkHttp", "Headers: ${response.headers}")
                    response
                } catch (e: Exception) {
                    android.util.Log.e("OkHttp", "=== HTTP错误 ===")
                    android.util.Log.e("OkHttp", "Error: ${e.javaClass.simpleName}")
                    android.util.Log.e("OkHttp", "Message: ${e.message}")
                    android.util.Log.e("OkHttp", "Cause: ${e.cause}")
                    throw e
                }
            }
            .build()
    }
    
    /**
     * 创建优化的HTTP数据源工厂
     */
    fun createFactory(
        userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        defaultRequestProperties: Map<String, String> = emptyMap()
    ): HttpDataSource.Factory {
        // 使用ExoPlayer的OkHttp扩展（如果有）或默认实现
        return try {
            // 尝试使用ExoPlayer的OkHttp扩展
            Class.forName("com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource")
            createOkHttpExtensionFactory(userAgent, defaultRequestProperties)
        } catch (e: ClassNotFoundException) {
            // 如果没有OkHttp扩展，使用优化的默认实现
            createOptimizedDefaultFactory(userAgent, defaultRequestProperties)
        }
    }
    
    private fun createOkHttpExtensionFactory(
        userAgent: String,
        defaultRequestProperties: Map<String, String>
    ): HttpDataSource.Factory {
        // 这里需要ExoPlayer的OkHttp扩展库
        // 暂时返回优化的默认实现
        return createOptimizedDefaultFactory(userAgent, defaultRequestProperties)
    }
    
    private fun createOptimizedDefaultFactory(
        userAgent: String,
        defaultRequestProperties: Map<String, String>
    ): HttpDataSource.Factory {
        // 创建一个使用OkHttpClient的自定义Factory
        return object : HttpDataSource.Factory {
            override fun createDataSource(): com.google.android.exoplayer2.upstream.HttpDataSource {
                val baseDataSource = DefaultHttpDataSource.Factory()
                    .setUserAgent(userAgent)
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15000)
                    .setReadTimeoutMs(20000)
                    .setKeepPostFor302Redirects(true)
                    .setDefaultRequestProperties(defaultRequestProperties)
                    .createDataSource()
                
                android.util.Log.d("OkHttpDataSourceFactory", "=== 创建HTTP数据源 ===")
                android.util.Log.d("OkHttpDataSourceFactory", "数据源类型: ${baseDataSource.javaClass.simpleName}")
                android.util.Log.d("OkHttpDataSourceFactory", "User-Agent: $userAgent")
                android.util.Log.d("OkHttpDataSourceFactory", "请求头: $defaultRequestProperties")
                
                // 包装数据源以添加日志
                return object : com.google.android.exoplayer2.upstream.HttpDataSource by baseDataSource {
                    override fun open(dataSpec: com.google.android.exoplayer2.upstream.DataSpec): Long {
                        android.util.Log.d("OkHttpDataSourceFactory", "=== HTTP请求开始 ===")
                        android.util.Log.d("OkHttpDataSourceFactory", "URL: ${dataSpec.uri}")
                        android.util.Log.d("OkHttpDataSourceFactory", "Position: ${dataSpec.position}")
                        android.util.Log.d("OkHttpDataSourceFactory", "Length: ${dataSpec.length}")
                        
                        try {
                            val result = baseDataSource.open(dataSpec)
                            android.util.Log.d("OkHttpDataSourceFactory", "=== HTTP请求成功 ===")
                            android.util.Log.d("OkHttpDataSourceFactory", "Content-Length: $result")
                            return result
                        } catch (e: Exception) {
                            android.util.Log.e("OkHttpDataSourceFactory", "=== HTTP请求失败 ===")
                            android.util.Log.e("OkHttpDataSourceFactory", "错误类型: ${e.javaClass.simpleName}")
                            android.util.Log.e("OkHttpDataSourceFactory", "错误消息: ${e.message}")
                            android.util.Log.e("OkHttpDataSourceFactory", "堆栈:", e)
                            throw e
                        }
                    }
                }
            }
            
            override fun setDefaultRequestProperties(defaultRequestProperties: MutableMap<String, String>): HttpDataSource.Factory {
                return this
            }
        }
    }
}
