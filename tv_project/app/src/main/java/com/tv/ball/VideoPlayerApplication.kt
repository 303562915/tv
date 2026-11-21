package com.tv.ball

import android.app.Application
import androidx.room.Room
import com.tv.ball.data.VideoSourceManager
import com.tv.ball.data.local.AppDatabase
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class VideoPlayerApplication : Application() {
    
    companion object {
        lateinit var instance: VideoPlayerApplication
            private set
    }
    
    // Cookie 存储
    private val cookieStore = mutableMapOf<String, List<Cookie>>()
    
    val okHttpClient: OkHttpClient by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }
        
        // 创建信任所有证书的 TrustManager
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        
        // 安装信任所有证书的 SSLContext
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        
        OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            // 极速超时配置 - 2秒内必须响应，否则立即换源
            .connectTimeout(1500, TimeUnit.MILLISECONDS)  // 连接超时1.5秒
            .readTimeout(2000, TimeUnit.MILLISECONDS)     // 读取超时2秒
            .writeTimeout(2000, TimeUnit.MILLISECONDS)    // 写入超时2秒
            .callTimeout(2000, TimeUnit.MILLISECONDS)     // 总调用超时2秒，超过立即失败
            // 连接池优化 - 复用连接
            .connectionPool(okhttp3.ConnectionPool(10, 5, TimeUnit.MINUTES))
            // 重试和重定向
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    cookieStore[url.host] = cookies
                }
                
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    return cookieStore[url.host] ?: emptyList()
                }
            })
            .build()
    }
    
    val database: AppDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "video_player_database"
        )
            .addMigrations(
                AppDatabase.MIGRATION_1_2, 
                AppDatabase.MIGRATION_2_3, 
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11
            )
            .fallbackToDestructiveMigration()
            .build()
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this

        // 初始化全局异常处理器
        com.tv.ball.util.GlobalExceptionHandler.init(this)

        // 尽早应用夜间模式设置
        applyNightModeFromPreferences()

        VideoSourceManager.init(this)
    }
    
    private fun applyNightModeFromPreferences() {
        try {
            val prefs = getSharedPreferences("video_player_prefs", MODE_PRIVATE)
            val nightMode = prefs.getInt("night_mode", 0) // 0 = 跟随系统
            
            android.util.Log.d("VideoPlayerApp", "应用夜间模式设置: $nightMode (0=跟随系统, 1=浅色, 2=深色)")
            
            com.tv.ball.utils.ThemeUtils.applyNightMode(nightMode)
        } catch (e: Exception) {
            e.printStackTrace()
            android.util.Log.e("VideoPlayerApp", "应用夜间模式失败: ${e.message}")
            // 如果出错，默认跟随系统
            com.tv.ball.utils.ThemeUtils.applyNightMode(0)
        }
    }
}
