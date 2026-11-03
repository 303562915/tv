# M3U8播放和下载问题修复

## 问题描述
某些M3U8链接无法播放或下载，但在IDM等专业下载工具中可以正常工作。

## 问题原因分析

### 1. 缺少必要的HTTP请求头
很多视频服务器会检查HTTP请求头，特别是：
- **User-Agent**: 识别客户端类型
- **Referer**: 防盗链检查
- **Origin**: 跨域请求验证
- **Accept**: 接受的内容类型
- **Connection**: 连接保持

### 2. 重定向处理
某些服务器会使用302重定向，需要正确处理：
- `instanceFollowRedirects = true`
- `setKeepPostFor302Redirects(true)`

### 3. 超时设置
网络不稳定时需要合理的超时时间：
- 连接超时：30秒
- 读取超时：30秒

## 修复内容

### 1. ExoPlayerManager.kt 改进

#### 添加的请求头：
```kotlin
val defaultRequestProperties = mapOf(
    "Accept" to "*/*",
    "Accept-Encoding" to "identity",
    "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
    "Connection" to "keep-alive",
    "Referer" to referer,
    "Origin" to referer  // 新增
)
```

#### 改进的HTTP配置：
```kotlin
val httpDataSourceFactory = DefaultHttpDataSource.Factory()
    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
    .setAllowCrossProtocolRedirects(true)
    .setConnectTimeoutMs(30000)
    .setReadTimeoutMs(30000)
    .setKeepPostFor302Redirects(true)  // 新增
```

#### 域名特定的Referer：
- `libvio` / `vbing` → `https://www.libvio.link`
- `maowushi` → `https://www.maowushi.com` (新增)
- 其他 → 自动从URL提取域名

### 2. VideoDownloader.kt 改进

#### downloadM3u8File 方法：
```kotlin
// 添加完整的请求头
connection.setRequestProperty("User-Agent", "Mozilla/5.0...")
connection.setRequestProperty("Accept", "*/*")
connection.setRequestProperty("Accept-Encoding", "identity")
connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
connection.setRequestProperty("Connection", "keep-alive")
connection.setRequestProperty("Referer", referer)

// 启用重定向
connection.instanceFollowRedirects = true
```

#### downloadSegment 方法：
同样添加了完整的HTTP请求头和重定向支持。

## 测试建议

### 1. 测试不同域名的M3U8
- maowushi.com
- libvio.link
- vbing.com
- 其他视频站点

### 2. 测试场景
- 直接播放M3U8
- 下载M3U8视频
- 检查日志输出

### 3. 查看日志
使用 `adb logcat` 查看详细日志：
```bash
adb logcat | grep -E "ExoPlayerManager|VideoDownloader"
```

关键日志信息：
- M3U8 响应码
- Referer 设置
- 分片下载状态
- 错误信息

## 常见问题排查

### 1. 仍然无法播放
检查日志中的HTTP响应码：
- **403**: 防盗链限制，需要正确的Referer
- **404**: URL不存在或已过期
- **500**: 服务器错误
- **超时**: 网络问题或服务器响应慢

### 2. 播放卡顿
可能原因：
- 网络速度慢
- 服务器限速
- 缓冲设置不合理

### 3. 下载失败
检查：
- 存储权限
- 磁盘空间
- 网络连接
- 分片URL是否正确

## 进一步优化建议

### 1. 添加重试机制
```kotlin
var retryCount = 0
while (retryCount < 3) {
    try {
        // 下载逻辑
        break
    } catch (e: Exception) {
        retryCount++
        delay(1000 * retryCount)
    }
}
```

### 2. 支持更多域名的特定配置
在代码中添加更多域名的Referer映射。

### 3. 添加代理支持
某些情况下可能需要通过代理访问。

### 4. HTTPS证书验证
如果遇到证书问题，可以考虑添加自定义的TrustManager（注意安全风险）。

## 更新日志
- 2024-XX-XX: 添加完整的HTTP请求头
- 2024-XX-XX: 支持maowushi域名
- 2024-XX-XX: 添加重定向支持
- 2024-XX-XX: 改进错误日志
