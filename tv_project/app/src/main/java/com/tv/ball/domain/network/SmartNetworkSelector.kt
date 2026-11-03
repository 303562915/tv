package com.tv.ball.domain.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * 智能网络选择器
 * 自动在WiFi和移动数据之间切换
 * WiFi不可用时切换到移动数据，移动数据不可用时切换到WiFi
 */
class SmartNetworkSelector(private val context: Context) {
    
    companion object {
        private const val TAG = "SmartNetworkSelector"
        private const val TEST_TIMEOUT = 1000L // 1秒超时，快速判断网络可用性
        private const val TEST_URL = "https://www.baidu.com" // 测试连接的URL
    }
    
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    /**
     * 测试结果
     */
    data class NetworkTestResult(
        val network: Network,
        val type: NetworkType,
        val isAvailable: Boolean,
        val responseTime: Long // 响应时间（毫秒）
    )
    
    enum class NetworkType {
        WIFI,
        MOBILE,
        UNKNOWN
    }
    
    /**
     * 快速选择可用网络
     * 优先使用WiFi，WiFi不可用时使用移动数据
     */
    suspend fun selectAvailableNetwork(): Network? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "=== 开始选择可用网络 ===")
            
            // 获取所有可用网络 - 使用 registerNetworkCallback 替代已弃用的 allNetworks
            val networkRequest = android.net.NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            
            val allNetworks = mutableListOf<Network>()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    allNetworks.add(network)
                }
            }
            
            // 临时注册回调获取网络列表
            connectivityManager.registerNetworkCallback(networkRequest, callback)
            delay(50) // 等待回调
            connectivityManager.unregisterNetworkCallback(callback)
            
            if (allNetworks.isEmpty()) {
                Log.e(TAG, "没有可用网络")
                return@withContext null
            }
            
            Log.d(TAG, "找到 ${allNetworks.size} 个网络")
            
            // 分类网络
            var wifiNetwork: Network? = null
            var mobileNetwork: Network? = null
            
            for (network in allNetworks) {
                when (getNetworkType(network)) {
                    NetworkType.WIFI -> wifiNetwork = network
                    NetworkType.MOBILE -> mobileNetwork = network
                    else -> {}
                }
            }
            
            // 优先测试WiFi
            if (wifiNetwork != null) {
                Log.d(TAG, "测试WiFi网络...")
                val wifiResult = testNetwork(wifiNetwork)
                if (wifiResult.isAvailable) {
                    Log.d(TAG, "✓ WiFi可用，响应时间: ${wifiResult.responseTime}ms")
                    return@withContext wifiNetwork
                } else {
                    Log.w(TAG, "✗ WiFi不可用")
                }
            }
            
            // WiFi不可用，尝试移动数据
            if (mobileNetwork != null) {
                Log.d(TAG, "测试移动数据网络...")
                val mobileResult = testNetwork(mobileNetwork)
                if (mobileResult.isAvailable) {
                    Log.d(TAG, "✓ 移动数据可用，响应时间: ${mobileResult.responseTime}ms")
                    return@withContext mobileNetwork
                } else {
                    Log.w(TAG, "✗ 移动数据不可用")
                }
            }
            
            Log.e(TAG, "所有网络都不可用")
            null
            
        } catch (e: Exception) {
            Log.e(TAG, "网络选择失败: ${e.message}", e)
            null
        }
    }
    
    /**
     * 测试单个网络的可用性
     */
    private suspend fun testNetwork(network: Network): NetworkTestResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val networkType = getNetworkType(network)
        
        try {
            // 使用指定网络创建连接
            val url = URL(TEST_URL)
            val connection = network.openConnection(url) as HttpURLConnection
            
            // 设置极短超时 - 快速判断
            connection.connectTimeout = TEST_TIMEOUT.toInt()
            connection.readTimeout = TEST_TIMEOUT.toInt()
            
            // 尝试连接
            withTimeout(TEST_TIMEOUT) {
                connection.connect()
                val responseCode = connection.responseCode
                connection.disconnect()
                
                val responseTime = System.currentTimeMillis() - startTime
                val isAvailable = responseCode in 200..399
                
                NetworkTestResult(
                    network = network,
                    type = networkType,
                    isAvailable = isAvailable,
                    responseTime = responseTime
                )
            }
            
        } catch (e: Exception) {
            val responseTime = System.currentTimeMillis() - startTime
            
            NetworkTestResult(
                network = network,
                type = networkType,
                isAvailable = false,
                responseTime = responseTime
            )
        }
    }
    
    /**
     * 获取网络类型
     */
    private fun getNetworkType(network: Network): NetworkType {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkType.UNKNOWN
        
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.MOBILE
            else -> NetworkType.UNKNOWN
        }
    }
    
    /**
     * 获取当前活动网络类型
     */
    fun getCurrentNetworkType(): NetworkType {
        val network = connectivityManager.activeNetwork ?: return NetworkType.UNKNOWN
        return getNetworkType(network)
    }
    
    /**
     * 检查是否有WiFi和移动数据同时可用
     */
    fun hasBothNetworks(): Boolean {
        val wifiNetwork = getWifiNetwork()
        val mobileNetwork = getMobileNetwork()
        return wifiNetwork != null && mobileNetwork != null
    }
    
    /**
     * 强制使用指定网络
     */
    fun bindToNetwork(network: Network): Boolean {
        return try {
            connectivityManager.bindProcessToNetwork(network)
            Log.d(TAG, "已绑定到网络: ${getNetworkType(network)}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "绑定网络失败: ${e.message}", e)
            false
        }
    }
    
    /**
     * 解除网络绑定
     */
    fun unbindNetwork() {
        connectivityManager.bindProcessToNetwork(null)
        Log.d(TAG, "已解除网络绑定")
    }
    
    /**
     * 获取WiFi网络
     */
    fun getWifiNetwork(): Network? {
        val activeNetwork = connectivityManager.activeNetwork
        if (activeNetwork != null && getNetworkType(activeNetwork) == NetworkType.WIFI) {
            return activeNetwork
        }
        return null
    }
    
    /**
     * 获取移动数据网络
     */
    fun getMobileNetwork(): Network? {
        val activeNetwork = connectivityManager.activeNetwork
        if (activeNetwork != null && getNetworkType(activeNetwork) == NetworkType.MOBILE) {
            return activeNetwork
        }
        return null
    }
}
