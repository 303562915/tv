# FFmpeg 转换失败修复总结

## 问题
FFmpeg 转换返回码 1，导致 M3U8 视频合并失败。

## 已实施的修复

### 1. 多策略容错机制

代码现在会自动尝试 3 种不同的 FFmpeg 命令：

| 策略 | 命令 | 适用场景 | 速度 |
|-----|------|---------|------|
| 1 | `-c copy -bsf:a aac_adtstoasc` | 标准 AAC 音频 | 最快 |
| 2 | `-c copy` | 无需音频转换 | 最快 |
| 3 | `-c:v copy -c:a aac -b:a 128k` | 需要重新编码音频 | 较慢 |

### 2. 后备方案

如果所有 FFmpeg 命令都失败，会自动将 TS 文件重命名为 MP4，虽然容器格式不标准，但大多数播放器仍能播放。

### 3. 详细日志

- 启用了 FFmpeg 详细日志输出
- 每个命令的执行结果都会记录
- 包含完整的错误信息

### 4. 文件验证

- 转换前验证临时 TS 文件是否存在且非空
- 转换后验证输出 MP4 文件是否有效

## 使用方法

### 重新编译

```bash
./gradlew clean
./gradlew assembleDebug
```

### 安装并测试

1. 安装新的 APK
2. 下载一个 M3U8 视频
3. 查看日志确认转换过程

### 查看日志

**Windows:**
```cmd
adb logcat | findstr /C:"VideoMerger" /C:"FFmpeg"
```

**Linux/Mac:**
```bash
adb logcat | grep -E "VideoMerger|FFmpeg"
```

### 运行诊断

**Windows:**
```cmd
diagnose_ffmpeg.bat
```

**Linux/Mac:**
```bash
./diagnose_ffmpeg.sh
```

## 预期日志输出

### 成功的情况

```
VideoDownloader: === 开始优化拼接方式合并（使用 FFmpeg）===
VideoDownloader: 步骤 1: 拼接 TS 分片到临时文件
VideoDownloader: 拼接进度: 50% (500/1000)
VideoDownloader: === TS 拼接完成 ===
VideoDownloader: 临时 TS 文件验证通过: 150MB
VideoDownloader: 步骤 2: 使用 FFmpeg 转换 TS 为 MP4
VideoDownloader: 尝试 FFmpeg 命令 1/3
FFmpeg: [info] Input #0, mpegts, from 'xxx_temp.ts'
FFmpeg: [info] Output #0, mp4, to 'xxx.mp4'
VideoDownloader: FFmpeg 返回码: 0
VideoDownloader: === FFmpeg 转换成功（命令 1）===
VideoDownloader: 输出文件大小: 148MB
VideoDownloader: 已删除临时 TS 文件
```

### 使用后备方案的情况

```
VideoDownloader: 尝试 FFmpeg 命令 1/3
VideoDownloader: 命令 1 失败: 返回码 1
VideoDownloader: 尝试 FFmpeg 命令 2/3
VideoDownloader: 命令 2 失败: 返回码 1
VideoDownloader: 尝试 FFmpeg 命令 3/3
VideoDownloader: === FFmpeg 转换成功（命令 3）===
```

或

```
VideoDownloader: === 所有 FFmpeg 转换策略都失败 ===
VideoDownloader: 后备方案：直接使用 TS 文件作为输出
VideoDownloader: 已将 TS 文件重命名为 MP4（可能兼容性较差）
```

## 如果仍然失败

### 1. 收集信息

运行诊断脚本并保存输出：

**Windows:**
```cmd
diagnose_ffmpeg.bat > diagnosis.txt
```

**Linux/Mac:**
```bash
./diagnose_ffmpeg.sh > diagnosis.txt
```

### 2. 导出临时文件

```bash
# 查找临时文件
adb shell "ls /sdcard/Android/data/com.tv.ball/cache/*_temp.ts"

# 导出到电脑
adb pull /sdcard/Android/data/com.tv.ball/cache/xxx_temp.ts .
```

### 3. 本地测试

在电脑上测试 FFmpeg 命令：

```bash
# 测试命令 1
ffmpeg -i xxx_temp.ts -c copy -bsf:a aac_adtstoasc output1.mp4

# 测试命令 2
ffmpeg -i xxx_temp.ts -c copy output2.mp4

# 测试命令 3
ffmpeg -i xxx_temp.ts -c:v copy -c:a aac -b:a 128k output3.mp4

# 查看文件信息
ffprobe xxx_temp.ts
```

### 4. 检查文件格式

```bash
ffprobe xxx_temp.ts 2>&1 | grep -E "Stream|Duration|Video|Audio"
```

关注：
- 视频编码：H.264, H.265, 等
- 音频编码：AAC, MP3, AC3, 等
- 容器格式：mpegts

## 临时解决方案

如果 FFmpeg 持续失败，可以临时改为输出 .ts 格式：

### 修改文件名

在 `VideoDownloader.kt` 第 148 行：

```kotlin
// 改为输出 .ts 格式
val fileName = if (isM3u8) "$title.ts" else "$title.${getFileExtension(cleanUrl)}"
```

### 跳过 FFmpeg 转换

在 `mergeSegmentsOptimized` 方法中，注释掉 FFmpeg 转换部分。

## 常见问题

### Q: 为什么需要 3 种不同的命令？

A: 不同的视频源使用不同的编码格式，单一命令无法覆盖所有情况。

### Q: 后备方案的 MP4 文件能播放吗？

A: 大多数现代播放器（VLC、MX Player、ExoPlayer）都能播放，但某些老旧播放器可能不支持。

### Q: 命令 3 为什么这么慢？

A: 因为需要重新编码音频。如果经常使用命令 3，建议降低音频比特率（如 64k）以提高速度。

### Q: 可以完全禁用 FFmpeg 吗？

A: 可以，参考"临时解决方案"部分。

## 性能对比

| 方案 | 速度 | 兼容性 | 文件大小 |
|-----|------|--------|---------|
| 命令 1/2 | 最快（秒级） | 好 | 不变 |
| 命令 3 | 较慢（分钟级） | 最好 | 略小 |
| 后备方案 | 最快（秒级） | 中等 | 不变 |
| 输出 .ts | 最快（秒级） | 中等 | 不变 |

## 下一步

1. 重新编译并测试
2. 查看日志确认使用了哪个策略
3. 如果仍有问题，运行诊断脚本并提供输出
