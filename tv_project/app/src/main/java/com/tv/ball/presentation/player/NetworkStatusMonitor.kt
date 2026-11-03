package com.tv.ball.presentation.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.tv.ball.domain.network.SmartNetworkSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 网络状态监控器
 * 监控WiFi和移动数据的可用性
 * WiFi不可用时自动切换到移动数据，移动数据不可用时自动切换到WiFi
 */
class NetworkStatusMonitor(
    private val context: Context,
    private val onNetworkChanged: (NetworkType, String) -> Unit
) {
    
    companion object {
        private const val TAG = "NetworkStatusMonitor"
    }
    
    enum class NetworkType {
        WIFI,
        MOBILE,
        NONE
    }
    
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val smartNetworkSelector = SmartNetworkSelector(context)
    private val scope = CoroutineScope(Dispatchers.Main)
    
    private var currentNetworkType = NetworkType.NONE
    private var isMonitoring = false
    
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "网络可用: $network")
            checkAndSwitchNetwork()
        }
        
        override fun onLost(network: Network) {
            Log.d(TAG, "网络丢失: $network")
            checkAndSwitchNetwork()
        }
        
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            Log.d(TAG, "网络能力变化: $network")
            checkAndSwitchNetwork()
        }
    }
    
    /**
     * 开始监控网络状态
     */
    fun startMonitoring() {
        if (isMonitoring) return
        
        Log.d(TAG, "开始监控网络状态")
        isMonitoring = true
        
        // 注册网络回调
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        
        // 立即检查当前网络
        checkAndSwitchNetwork()
    }
    
    /**
     * 停止监控
     */
    fun stopMonitoring() {
        if (!isMonitoring) return
        
        Log.d(TAG, "停止监控网络状态")
        isMonitoring = false
        
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "取消网络回调失败: ${e.message}")
        }
    }
    
    /**
     * 检查并切换到可用网络
     * 优先使用WiFi，WiFi不可用时切换到移动数据
     */
    private fun checkAndSwitchNetwork() {
        scope.launch {
            try {
                val availableNetwork = smartNetworkSelector.selectAvailableNetwork()
                
                if (availableNetwork != null) {
                    val networkType = getNetworkType(availableNetwork)
                    
                    // 如果网络类型发生变化
                    if (networkType != currentNetworkType) {
                        val oldType = currentNetworkType
                        currentNetworkType = networkType
                        
                        val message = when {
                            oldType == NetworkType.NONE && networkType == NetworkType.WIFI -> 
                                "已连接WiFi网络"
                            oldType == NetworkType.NONE && networkType == NetworkType.MOBILE -> 
                                "已连接移动数据网络"
                            oldType == NetworkType.WIFI && networkType == NetworkType.MOBILE -> 
                                "WiFi不可用，已切换到移动数据"
                            oldType == NetworkType.MOBILE && networkType == NetworkType.WIFI -> 
                                "移动数据不可用，已切换到WiFi"
                            else -> "网络已切换"
                        }
                        
                        Log.d(TAG, message)
                        onNetworkChanged(networkType, message)
                        
                        // 绑定到可用网络
                        smartNetworkSelector.bindToNetwork(availableNetwork)
                    }
                } else {
                    if (currentNetworkType != NetworkType.NONE) {
                        currentNetworkType = NetworkType.NONE
                        Log.w(TAG, "没有可用网络")
                        onNetworkChanged(NetworkType.NONE, "网络连接已断开")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "检查网络失败: ${e.message}", e)
            }
        }
    }
    
    /**
     * 获取网络类型
     */
    private fun getNetworkType(network: Network): NetworkType {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkType.NONE
        
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.MOBILE
            else -> NetworkType.NONE
        }
    }
    
    /**
     * 获取当前网络类型
     */
    fun getCurrentNetworkType(): NetworkType {
        return currentNetworkType
    }
}
