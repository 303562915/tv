package com.tv.ball.domain.network

import okhttp3.OkHttpClient
import java.security.cert.X509Certificate
import javax.net.ssl.*

/**
 * 创建一个信任所有证书的OkHttpClient
 * 用于解决HTTPS证书验证问题
 */
object UnsafeOkHttpClient {
    
    fun getUnsafeOkHttpClient(): OkHttpClient {
        try {
            // 创建信任所有证书的TrustManager
            val trustAllCerts = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
            )

            // 安装信任所有证书的TrustManager
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            // 创建SSLSocketFactory
            val sslSocketFactory = sslContext.socketFactory

            val builder = OkHttpClient.Builder()
            builder.sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }

            return builder.build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
}
