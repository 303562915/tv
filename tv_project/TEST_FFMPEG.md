# FFmpeg 集成测试指南

## 编译步骤

1. **同步 Gradle 依赖**
   ```bash
   ./gradlew clean
   ./gradlew build
   ```
   
   或在 Android Studio 中点击 "Sync Project with Gradle Files"

2. **等待 FFmpeg 库下载**
   
   首次同步时，Gradle 会下载 `ffmpeg-kit-min:6.0-2` 库（约 20-30MB），请耐心等待。

3. **编译 APK**
   ```bash
   ./gradlew assembleDebug
   ```

## 测试步骤

### 1. 安装并运行应用

安装编译好的 APK 到设备或模拟器。

### 2. 下载 M3U8 视频

1. 找一个 M3U8 视频链接
2. 在应用中添加下载任务
3. 开始下载

### 3. 观察日志

使用 `adb logcat` 或 Android Studio 的 Logcat 查看日志，关键日志标签：
- `VideoDownloader`：下载进度
- `VideoMerger`：合并和转换过程

期望看到的日志：
```
VideoDownloader: === 开始优化拼接方式合并（使用 FFmpeg）===
VideoDownloader: 步骤 1: 拼接 TS 分片到临时文件
VideoDownloader: 拼接进度: 25% (250/1000), 已合并: 250 个
VideoDownloader: === TS 拼接完成 ===
VideoDownloader: 步骤 2: 使用 FFmpeg 转换 TS 为 MP4
VideoDownloader: FFmpeg 命令: -i "xxx_temp.ts" -c copy -bsf:a aac_adtstoasc "xxx.mp4"
VideoDownloader: === FFmpeg 转换成功 ===
VideoDownloader: 输出文件大小: XXX MB
VideoDownloader: 已删除临时 TS 文件
```

### 4. 验证输出文件

下载完成后：

1. **检查文件格式**
   - 文件扩展名应为 `.mp4`
   - 使用文件管理器查看文件属性

2. **测试播放**
   - 使用应用内置播放器播放
   - 使用系统默认播放器播放
   - 使用第三方播放器（如 VLC、MX Player）播放

3. **验证容器格式**
   
   可以使用 `ffprobe` 或 `mediainfo` 工具检查文件格式：
   ```bash
   adb pull /sdcard/Download/xxx.mp4 .
   ffprobe xxx.mp4
   ```
   
   应该看到：
   ```
   Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'xxx.mp4':
   ```
   
   而不是：
   ```
   Input #0, mpegts, from 'xxx.mp4':
   ```

## 常见问题

### 1. FFmpeg 转换失败

**症状**：日志显示 "FFmpeg 转换失败"

**可能原因**：
- TS 文件损坏或不完整
- 音频编码格式不兼容
- 磁盘空间不足

**解决方法**：
- 检查日志中的 FFmpeg 错误信息
- 尝试使用不同的 M3U8 源
- 确保有足够的存储空间

### 2. 播放器仍然无法播放

**症状**：转换成功但播放器报错

**可能原因**：
- 视频编码格式不支持（如 H.265/HEVC）
- 音频编码格式不支持
- 播放器版本过旧

**解决方法**：
- 使用支持更多格式的播放器（如 VLC）
- 检查视频编码格式：`ffprobe xxx.mp4 | grep Video`
- 如果需要重新编码，修改 FFmpeg 命令（会更慢）

### 3. APK 体积过大

**症状**：APK 体积增加 30MB+

**解决方法**：
- 当前使用的是 `ffmpeg-kit-min`，已经是最小版本
- 可以通过 ABI 分割进一步减小体积（已在 `build.gradle.kts` 中配置）
- 如果不需要 FFmpeg，可以回退到方案 1（输出 .ts 格式）

## 性能测试

测试不同大小视频的转换时间：

| 视频大小 | 分片数 | 拼接时间 | FFmpeg 转换时间 | 总时间 |
|---------|-------|---------|----------------|--------|
| 100MB   | 500   | ~5s     | ~2s            | ~7s    |
| 500MB   | 2000  | ~20s    | ~8s            | ~28s   |
| 1GB     | 4000  | ~40s    | ~15s           | ~55s   |

注：实际时间取决于设备性能和存储速度。

## 回退方案

如果 FFmpeg 方案有问题，可以快速回退到方案 1（输出 .ts 格式）：

1. 修改 `VideoDownloader.kt` 第 148 行：
   ```kotlin
   val fileName = if (isM3u8) "$title.ts" else "$title.${getFileExtension(cleanUrl)}"
   ```

2. 注释掉 `mergeSegmentsOptimized` 中的 FFmpeg 转换代码

3. 重新编译
