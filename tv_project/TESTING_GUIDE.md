# FFmpeg 集成测试指南

## ✅ 安装成功

应用已成功编译并安装到你的设备上，包含了 FFmpeg 库。

## 📱 测试步骤

### 1. 启动应用并下载 M3U8 视频

1. 打开应用
2. 找到一个 M3U8 视频链接
3. 开始下载

### 2. 实时查看日志

在另一个终端窗口运行：

```bash
adb logcat | findstr /C:"VideoMerger" /C:"FFmpeg" /C:"VideoDownloader"
```

或者使用 PowerShell：

```powershell
adb logcat | Select-String "VideoMerger|FFmpeg|VideoDownloader"
```

### 3. 期望看到的日志

#### 成功场景 1：使用命令 1 转换

```
VideoDownloader: === 开始优化拼接方式合并（使用 FFmpeg）===
VideoDownloader: 步骤 1: 拼接 TS 分片到临时文件
VideoDownloader: 拼接进度: 25% (250/1000)
VideoDownloader: 拼接进度: 50% (500/1000)
VideoDownloader: === TS 拼接完成 ===
VideoDownloader: 临时 TS 文件验证通过: 150MB
VideoDownloader: 步骤 2: 使用 FFmpeg 转换 TS 为 MP4
VideoDownloader: 尝试 FFmpeg 命令 1/3
VideoDownloader: 命令: -i "xxx_temp.ts" -c copy -bsf:a aac_adtstoasc "xxx.mp4"
FFmpeg: [info] Input #0, mpegts, from 'xxx_temp.ts'
FFmpeg: [info] Output #0, mp4, to 'xxx.mp4'
VideoDownloader: FFmpeg 返回码: 0
VideoDownloader: === FFmpeg 转换成功（命令 1）===
VideoDownloader: 输出文件大小: 148MB
VideoDownloader: 已删除临时 TS 文件
```

#### 成功场景 2：使用命令 2 或 3 转换

```
VideoDownloader: 尝试 FFmpeg 命令 1/3
VideoDownloader: 命令 1 失败: 返回码 1
VideoDownloader: 尝试 FFmpeg 命令 2/3
VideoDownloader: === FFmpeg 转换成功（命令 2）===
```

#### 后备场景：直接使用 TS 文件

```
VideoDownloader: === 所有 FFmpeg 转换策略都失败 ===
VideoDownloader: 后备方案：直接使用 TS 文件作为输出
VideoDownloader: 已将 TS 文件重命名为 MP4（可能兼容性较差）
```

### 4. 验证输出文件

下载完成后：

1. **在应用中播放**
   - 点击下载完成的视频
   - 检查是否能正常播放

2. **使用其他播放器**
   - 在文件管理器中找到视频
   - 使用 VLC、MX Player 等播放器测试

3. **检查文件信息**
   ```bash
   # 导出文件到电脑
   adb pull /sdcard/Download/xxx.mp4 .
   
   # 使用 ffprobe 查看文件信息
   ffprobe xxx.mp4
   ```
   
   应该看到：
   ```
   Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'xxx.mp4':
   ```

## 🔍 故障排查

### 如果转换失败

1. **运行诊断脚本**
   ```cmd
   diagnose_ffmpeg.bat
   ```

2. **查看完整日志**
   ```bash
   adb logcat -d > logcat.txt
   ```
   
   然后搜索 "FFmpeg" 和 "VideoMerger"

3. **导出临时文件**
   ```bash
   # 查找临时文件
   adb shell "ls /sdcard/Android/data/com.tv.ball/cache/*_temp.ts"
   
   # 导出
   adb pull /sdcard/Android/data/com.tv.ball/cache/xxx_temp.ts .
   
   # 在电脑上测试
   ffmpeg -i xxx_temp.ts -c copy -bsf:a aac_adtstoasc test.mp4
   ```

### 如果播放失败

1. **检查文件格式**
   ```bash
   ffprobe xxx.mp4 2>&1 | findstr "Stream"
   ```

2. **尝试不同播放器**
   - VLC Player（兼容性最好）
   - MX Player
   - 系统默认播放器

3. **检查视频编码**
   - 某些播放器不支持 H.265/HEVC
   - 某些播放器不支持特定音频格式

## 📊 性能测试

测试不同大小视频的处理时间：

| 测试项 | 预期结果 |
|-------|---------|
| 100MB 视频 | 拼接 ~5s，转换 ~2s，总计 ~7s |
| 500MB 视频 | 拼接 ~20s，转换 ~8s，总计 ~28s |
| 1GB 视频 | 拼接 ~40s，转换 ~15s，总计 ~55s |

## ✅ 成功标志

- ✓ 日志显示 "FFmpeg 转换成功"
- ✓ 输出文件大小合理（接近临时 TS 文件大小）
- ✓ 文件可以在多个播放器中播放
- ✓ 使用 ffprobe 显示容器格式为 mp4

## ❌ 失败标志

- ✗ 所有 3 个 FFmpeg 命令都失败
- ✗ 输出文件为 0 字节或不存在
- ✗ 播放器无法识别文件格式

## 📝 反馈

测试后请反馈：

1. **使用了哪个 FFmpeg 命令？**（1、2、3 或后备方案）
2. **转换时间？**
3. **播放是否正常？**
4. **使用的播放器？**
5. **如果失败，完整的错误日志**

## 🔄 下一步

如果测试成功：
- ✅ FFmpeg 集成工作正常
- ✅ 可以正常使用应用下载和播放视频

如果测试失败：
- 📋 收集日志和诊断信息
- 🔧 根据错误信息调整 FFmpeg 命令
- 🔄 或者回退到输出 .ts 格式的方案
