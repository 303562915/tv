# TS 片段合并方案说明

## 当前实现

已使用 Android 原生 MediaMuxer 实现 TS 片段合并为 MP4。

### 优势

- **零额外体积**：使用 Android 系统自带的 MediaMuxer，不增加 APK 体积
- **APK 大小**：约 12MB（单架构）
- **兼容性好**：支持 H.264/AAC 编码（覆盖大部分视频）
- **速度快**：直接提取和重新封装，不重新编码

### 实现文件

- `VideoMerger.kt` - MediaMuxer 合并工具
- `VideoDownloader.kt` - 调用合并逻辑

### 合并策略（两层回退）

1. **优先使用 MediaMuxer**（Android 原生）
   - 支持 H.264/AAC
   - 生成标准 MP4
   - 零额外体积

2. **回退到简单拼接**（兜底方案）
   - 直接拼接 TS 文件
   - 可能无法拖动进度条

### 测试方法

1. **安装 APK**
   ```bash
   adb install app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
   ```

2. **下载 M3U8 视频**
   - 在下载页面添加 M3U8 视频任务
   - 等待下载完成
   - 查看日志确认使用了 MediaMuxer 合并

3. **验证输出文件**
   - 使用播放器打开合并后的 MP4 文件
   - 检查是否可以正常播放
   - 检查是否可以拖动进度条
   - 检查视频时长是否正确

### 日志输出

成功时会看到：
```
VideoMerger: === 开始使用 MediaMuxer 合并 TS 文件 ===
VideoMerger: 总分片数: 100
VideoMerger: 跳过分片数: 2
VideoMerger: === 合并完成 ===
VideoMerger: 输出文件大小: 150MB
```

失败时会回退：
```
VideoMerger: === MediaMuxer 合并失败 ===
VideoDownloader: MediaMuxer 合并失败，尝试使用简单拼接
```

### 注意事项

- MediaMuxer 仅支持 H.264/AAC 编码
- 如果视频使用其他编码（H.265, VP9 等），会回退到简单拼接
- 简单拼接的文件可能无法拖动进度条，但可以播放

### FFmpeg 方案（未实现）

如果需要支持更多编码格式，可以考虑集成 FFmpeg：
- 优势：支持所有视频/音频编码格式
- 劣势：增加约 8-10MB APK 体积（单架构）
- 依赖：需要添加 ffmpeg-kit 或 mobile-ffmpeg 库

## 构建信息

- 构建成功：✓
- APK 大小：12.33MB（arm64-v8a）
- 构建时间：约 2 分钟
- 已启用 ABI 分包：只包含 ARM 架构（覆盖 99% 设备）

