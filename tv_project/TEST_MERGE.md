# 测试视频合并功能

## 测试步骤

### 1. 构建项目
```bash
./gradlew clean assembleDebug
```

### 2. 安装到设备
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. 查看日志
```bash
adb logcat | grep -E "VideoDownloader|VideoMerger|FFmpeg"
```

## 预期日志输出

### FFmpeg 合并成功
```
D/VideoDownloader: === 开始合并 XXX 个 M3U8 分片 ===
D/VideoDownloader: 使用 FFmpeg 合并 TS 分片
D/VideoMerger: === 开始使用 FFmpeg 合并 TS 文件 ===
D/VideoMerger: 总分片数: XXX
D/VideoMerger: 创建文件列表: /path/to/filelist.txt, 包含 XXX 个分片
D/VideoMerger: FFmpeg 命令: -f concat -safe 0 -i ...
D/VideoMerger: FFmpeg 返回码: 0
D/VideoMerger: ✓ FFmpeg 合并成功
D/VideoMerger: 输出文件大小: XXX MB
D/VideoDownloader: === 合并完成 ===
D/VideoDownloader: ✓ 文件验证成功
```

### FFmpeg 失败，回退到简单拼接
```
D/VideoMerger: ✗ FFmpeg 合并失败
W/VideoMerger: 回退到简单拼接方式
D/VideoMerger: === 使用简单拼接方式合并 ===
D/VideoMerger: 简单拼接完成: XXX 个分片
```

## 测试场景

### 场景 1: 正常 M3U8 视频
1. 下载一个完整的 M3U8 视频
2. 等待所有分片下载完成
3. 观察合并过程日志
4. 验证输出的 MP4 文件可以正常播放

### 场景 2: 部分分片失败
1. 下载一个 M3U8 视频，模拟部分分片下载失败
2. 观察是否跳过失败的分片
3. 验证合并后的视频是否可以播放（可能有跳帧）

### 场景 3: 手动触发合并
1. 暂停一个已下载部分分片的任务
2. 长按任务，选择"合并 M3U8 分片"
3. 观察合并过程
4. 验证输出文件

## 性能指标

### 合并速度
- FFmpeg `-c copy` 模式：约 100-200 MB/s（取决于设备性能）
- 简单拼接模式：约 50-100 MB/s

### 内存占用
- FFmpeg 合并：约 50-100 MB
- 简单拼接：约 20-50 MB

### CPU 占用
- FFmpeg 合并：中等（主要是 I/O 操作）
- 简单拼接：低

## 常见问题

### Q: FFmpeg 合并失败怎么办？
A: 系统会自动回退到简单拼接方式，不影响使用。

### Q: 合并后的视频无法播放？
A: 检查日志，可能是：
- 分片损坏或不完整
- 视频源本身有问题
- 播放器不支持该格式

### Q: 合并速度很慢？
A: 可能原因：
- 设备性能较低
- 存储空间不足
- 分片数量过多（>1000）

### Q: APK 体积增加了多少？
A: FFmpeg-Kit min 版本约增加 20-30 MB，已配置只打包 ARM 架构以减小体积。
