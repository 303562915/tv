package com.tv.ball.presentation.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager

class SystemInfoMonitor(private val context: Context) {
    
    private var batteryReceiver: BroadcastReceiver? = null
    private var onBatteryChanged: ((level: Int, isCharging: Boolean) -> Unit)? = null
    
    fun startMonitoring(
        onBatteryChanged: (level: Int, isCharging: Boolean) -> Unit
    ) {
        this.onBatteryChanged = onBatteryChanged
        
        // 监听电池状态
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let {
                    val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                    val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL
                    
                    onBatteryChanged(level, isCharging)
                }
            }
        }
        
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(batteryReceiver, filter)
    }
    
    fun stopMonitoring() {
        batteryReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        batteryReceiver = null
    }
    
    fun getNetworkType(): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return "无网络"
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "无网络"
        
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "数据"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            else -> "未知"
        }
    }
    
    fun getBatteryLevel(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
    
    fun isCharging(): Boolean {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }
    
    fun getBatteryIconResource(): Int {
        val level = getBatteryLevel()
        val isCharging = isCharging()
        
        return if (isCharging) {
            // 充电状态
            when {
                level >= 90 -> com.tv.ball.R.drawable.ic_battery_charging_full_black_24dp
                level >= 80 -> com.tv.ball.R.drawable.ic_battery_charging_90_black_24dp
                level >= 60 -> com.tv.ball.R.drawable.ic_battery_charging_80_black_24dp
                level >= 50 -> com.tv.ball.R.drawable.ic_battery_charging_60_black_24dp
                level >= 30 -> com.tv.ball.R.drawable.ic_battery_charging_50_black_24dp
                level >= 20 -> com.tv.ball.R.drawable.ic_battery_charging_30_black_24dp
                level >= 10 -> com.tv.ball.R.drawable.ic_battery_charging_20_black_24dp
                else -> com.tv.ball.R.drawable.ic_battery_alert_black_24dp
            }
        } else {
            // 非充电状态
            when {
                level >= 90 -> com.tv.ball.R.drawable.ic_battery_full_black_24dp
                level >= 80 -> com.tv.ball.R.drawable.ic_battery_90_black_24dp
                level >= 60 -> com.tv.ball.R.drawable.ic_battery_80_black_24dp
                level >= 50 -> com.tv.ball.R.drawable.ic_battery_60_black_24dp
                level >= 30 -> com.tv.ball.R.drawable.ic_battery_50_black_24dp
                level >= 20 -> com.tv.ball.R.drawable.ic_battery_30_black_24dp
                level >= 10 -> com.tv.ball.R.drawable.ic_battery_20_black_24dp
                else -> com.tv.ball.R.drawable.ic_battery_alert_black_24dp
            }
        }
    }
}
