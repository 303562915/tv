# DNS 解析问题修复说明

## 问题描述

错误信息：`Unable to resolve host "play.ly166.com": No address associated with hostname`

这是一个 **DNS 解析失败**的问题。手机浏览器可以播放，但应用内无法下载/播放。

## 原因分析

1. **DNS 服务器差异**：应用使用的 DNS 服务器与浏览器不同
2. **网络接口问题**：应用可能绑定了错误的网络接口（WiFi/移动数据）
3. **域名解析限制**：某些域名需要特定的 DNS 服务器才能解析

## 解决方案

### 已实现的修复

我已经为你的应用添加了以下功能：

#### 1. 自定义 DNS 解析器 (`CustomDnsResolver.kt`)

- 支持多个公共 DNS 服务器：
  - Google DNS (8.8.8.8, 8.8.4.4)
  - Cloudflare DNS (1.1.1.1, 1.0.0.1)
  - 114 DNS (114.114.114.114)
  - 阿里 DNS (223.5.5.5, 223.6.6.6)

- 使用 DNS over HTTPS (DoH) 技术
- 自动尝试多个 DNS 服务器，直到成功

#### 2. 增强的 URL 连接工具 (`EnhancedUrlConnection.kt`)

- 自动使用自定义 DNS 解析
- 支持 HTTPS 的 SNI (Server Name Indication)
- 正确设置 Host 头
- 失败时自动回退到系统默认

#### 3. 网络诊断工具 (`NetworkDiagnostics.kt`)

- 诊断 DNS 解析问题
- 测试网络连接
- 提供修复建议

#### 4. 修改的文件

- `VideoDownloader.kt`：下载器现在使用增强的连接
  - `downloadM3u8File()` - M3U8 文件下载
  - `downloadSegment()` - M3U8 分片下载
  - `downloadNormalVideo()` - 普通视频下载
  - `downloadWithSingleThread()` - 单线程下载

## 使用方法

### 自动使用（推荐）

修改后的代码会自动使用自定义 DNS 解析，无需额外配置。

### 手动诊断

如果仍然有问题，可以使用诊断工具：

```kotlin
// 在你的 Activity 或 ViewModel 中
import com.tv.ball.domain.network.NetworkDiagnostics

// 诊断 URL
lifecycleScope.launch {
    val result = NetworkDiagnostics.diagnose(
        context = applicationContext,
        urlString = "https://play.ly166.com:65/videos/20251216/694174af80eee9b95522bf39/acafef/index.m3u8"
    )
    
    // 打印诊断报告
    NetworkDiagnostics.printReport(result)
    
    // 或者在 UI 中显示
    if (!result.connectionSuccess) {
        Toast.makeText(
            this@YourActivity,
            "连接失败: ${result.errorMessage}\n建议: ${result.suggestions.joinToString("\n")}",
            Toast.LENGTH_LONG
        ).show()
    }
}
```

### 测试连接

```kotlin
import com.tv.ball.domain.network.EnhancedUrlConnection

lifecycleScope.launch {
    val success = EnhancedUrlConnection.testConnection(
        "https://play.ly166.com:65/videos/20251216/694174af80eee9b95522bf39/acafef/index.m3u8"
    )
    
    if (success) {
        Log.d("Test", "连接成功！")
    } else {
        Log.e("Test", "连接失败！")
    }
}
```

## 播放器修复

如果播放器也无法播放，需要修改播放器的 URL 加载方式。

### ExoPlayer 示例

```kotlin
import com.tv.ball.domain.network.CustomDnsResolver

// 在播放前解析域名
lifecycleScope.launch {
    val originalUrl = "https://play.ly166.com:65/videos/xxx/index.m3u8"
    val url = URL(originalUrl)
    val hostname = url.host
    
    // 解析 IP
    val resolvedIp = CustomDnsResolver.resolve(hostname)
    
    if (resolvedIp != null) {
        // 替换域名为 IP
        val newUrl = originalUrl.replace(hostname, resolvedIp)
        
        // 创建 MediaItem，设置 Host 头
        val mediaItem = MediaItem.Builder()
            .setUri(newUrl)
            .setHttpRequestHeaders(mapOf("Host" to hostname))
            .build()
        
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
    } else {
        // DNS 解析失败，显示错误
        Toast.makeText(this@Activity, "无法解析域名", Toast.LENGTH_SHORT).show()
    }
}
```

## 其他建议

### 1. 添加网络权限（已有）

确保 `AndroidManifest.xml` 中有以下权限：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

### 2. 允许明文流量（已有）

```xml
<application
    android:usesCleartextTraffic="true"
    ...>
```

### 3. 网络安全配置（可选）

如果需要更精细的控制，可以创建 `res/xml/network_security_config.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />
        </trust-anchors>
    </base-config>
    
    <!-- 允许特定域名 -->
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">play.ly166.com</domain>
    </domain-config>
</network-security-config>
```

然后在 `AndroidManifest.xml` 中引用：

```xml
<application
    android:networkSecurityConfig="@xml/network_security_config"
    ...>
```

## 测试步骤

1. **编译并安装应用**
2. **尝试下载或播放视频**
3. **查看 Logcat 日志**，搜索以下标签：
   - `CustomDnsResolver`
   - `EnhancedUrlConnection`
   - `VideoDownloader`
   - `NetworkDiagnostics`

4. **如果仍然失败**，运行诊断工具并查看建议

## 常见问题

### Q: 为什么浏览器可以播放，应用不行？

A: 浏览器通常使用系统的 DNS 设置，并且可能有自己的 DNS 缓存。应用使用的是 Java 的网络库，DNS 解析方式不同。

### Q: 自定义 DNS 会影响性能吗？

A: 首次解析会稍慢（尝试多个 DNS 服务器），但解析成功后会缓存结果。

### Q: 可以禁用自定义 DNS 吗？

A: 可以，在调用 `EnhancedUrlConnection.openConnection()` 时设置 `useDnsResolver = false`。

## 日志示例

成功的日志应该类似：

```
D/CustomDnsResolver: 开始解析域名: play.ly166.com
D/CustomDnsResolver: ✓ 使用 DNS 8.8.8.8 解析成功: play.ly166.com -> 123.45.67.89
D/EnhancedUrlConnection: ✓ DNS 解析成功: play.ly166.com -> 123.45.67.89
D/VideoDownloader: M3U8 响应码: 200
D/VideoDownloader: M3U8 文件下载成功，内容长度: 1234
```

失败的日志会显示：

```
W/CustomDnsResolver: ✗ 系统 DNS 解析失败: play.ly166.com
W/CustomDnsResolver: ✗ DNS 8.8.8.8 解析失败: ...
E/CustomDnsResolver: 所有 DNS 服务器都无法解析: play.ly166.com
```

## 联系支持

如果问题仍然存在，请提供：
1. 完整的 Logcat 日志
2. 网络类型（WiFi/移动数据）
3. 设备型号和 Android 版本
4. 具体的视频 URL
