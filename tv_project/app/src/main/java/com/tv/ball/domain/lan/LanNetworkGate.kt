package com.tv.ball.domain.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 局域网门禁：判断本机此刻是不是真的接在 WiFi / 以太网上。
 *
 * 为什么必须有这一层：
 * 移动数据、VPN、CLAT 接口同样会分到一个 RFC1918 私网地址（运营商 CGNAT 常见 10.x.x.x）。
 * 拿这种地址去扫 `xxx.0/24`，扫的其实是**运营商网络**，一屏陌生 IP 全被当成"局域网电脑"。
 * 所以只有 wlan* / eth* 接口上存在私网 IPv4 时才认为在局域网，其余一律拒绝扫描。
 */
object LanNetworkGate {

    /** 只有这些接口算局域网 */
    private val LAN_PREFIXES = listOf("wlan", "eth")

    /** 移动数据 / VPN / 隧道 / 厂商私有接口，出现即排除 */
    private val BLOCKED_PREFIXES = listOf(
        "rmnet", "ccmni", "pdp", "clat", "v4-", "tun", "ppp", "radio", "dummy", "sit", "ip6tnl"
    )

    data class LanAddress(val ip: String, val prefixLength: Int)

    /**
     * 取本机在局域网里的 IPv4；返回 null 表示当前不在任何局域网（比如只开了移动数据）。
     */
    fun findLanAddress(context: Context? = null): LanAddress? {
        fromInterfaces()?.let { return it }
        return context?.let { fromConnectivity(it) }
    }

    private fun fromInterfaces(): LanAddress? = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        var ethFallback: LanAddress? = null
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            val ifName = nif.name.lowercase()
            if (BLOCKED_PREFIXES.any { ifName.startsWith(it) }) continue
            if (LAN_PREFIXES.none { ifName.startsWith(it) }) continue

            for (interfaceAddress in nif.interfaceAddresses) {
                val address = interfaceAddress.address
                val ip = address?.hostAddress ?: continue
                if (address !is Inet4Address || address.isLoopbackAddress) continue
                if (!isPrivateIpv4(ip)) continue

                val prefix = interfaceAddress.networkPrefixLength.toInt()
                    .let { if (it in 1..32) it else 24 }
                val candidate = LanAddress(ip, prefix)
                if (ifName.startsWith("wlan")) return@runCatching candidate
                if (ethFallback == null) ethFallback = candidate
            }
        }
        ethFallback
    }.getOrNull()

    private fun fromConnectivity(context: Context): LanAddress? {
        return runCatching {
            val manager = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching null
            val active = manager.activeNetwork ?: return@runCatching null
            val capabilities = manager.getNetworkCapabilities(active) ?: return@runCatching null
            val isLan = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!isLan) return@runCatching null

            val linkAddress = manager.getLinkProperties(active)
                ?.linkAddresses
                ?.firstOrNull { candidate ->
                    val address = candidate.address
                    address is Inet4Address &&
                        !address.isLoopbackAddress &&
                        isPrivateIpv4(address.hostAddress ?: "")
                }
                ?: return@runCatching null

            val ip = linkAddress.address.hostAddress ?: return@runCatching null
            val prefix = linkAddress.prefixLength.let { if (it in 1..32) it else 24 }
            LanAddress(ip, prefix)
        }.getOrNull()
    }

    fun isPrivateIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return when {
            octets[0] == 10 -> true
            octets[0] == 172 -> octets[1] in 16..31
            octets[0] == 192 && octets[1] == 168 -> true
            else -> false
        }
    }
}