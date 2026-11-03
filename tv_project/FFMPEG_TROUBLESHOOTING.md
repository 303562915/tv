# FFmpeg 转换失败故障排查

## 问题：FFmpeg 返回码 1

返回码 1 通常表示 FFmpeg 遇到了输入文件或命令参数问题。

## 已实施的改进

### 1. 多策略容错机制

代码现在会尝试 3 种不同的 FFmpeg 命令：

**命令 1（标准）**：
```bash
-i input.ts -c copy -bsf:a aac_adtstoasc output.mp4
```
- 适用于标准 AAC 音频的 TS 文件
- 速度最快，无质量损失

**命令 2（无音频过滤器）**：
```bash
-i input.ts -c copy output.mp4
```
- 适用于不需要音频格式转换的视频
- 某些 TS 文件的音频已经是 MP4 兼容格式

**命令 3（重新编码音频）**：
```bash
-i input.ts -c:v copy -c:a aac -b:a 128k output.mp4
```
- 兼容性最好，适用于各种音频格式
- 会重新编码音频（较慢，但视频仍是复制）

### 2. 后备方案

如果所有 FFmpeg 命令都失败，会自动：
1. 将 TS 文件直接重命名为 MP4
2. 虽然容器格式不标准，但很多播放器仍能播放

## 查看详细日志

使用 `adb logcat` 查看详细错误信息：

```bash
adb logcat | grep -E "VideoDownloader|VideoMerger|FFmpeg"
```

关键日志：
- `尝试 FFmpeg 命令 1/3`：显示正在尝试哪个命令
- `FFmpeg 返回码`：显示返回码
- `FFmpeg 输出`：显示 FFmpeg 的详细输出
- `错误输出`：显示错误信息

## 常见原因和解决方法

### 原因 1：文件路径包含特殊字符

**症状**：所有命令都返回码 1

**解决方法**：
- 检查文件路径是否包含空格、中文或特殊字符
- 修改代码，使用更安全的路径处理

### 原因 2：TS 文件损坏或不完整

**症状**：
- 日志显示 "临时 TS 文件验证通过"
- 但 FFmpeg 仍然失败

**解决方法**：
- 检查下载的 TS 分片是否完整
- 查看是否有分片下载失败
- 尝试重新下载

### 原因 3：音频编码格式不支持

**症状**：
- 命令 1 和 2 失败
- 命令 3 可能成功（但较慢）

**解决方法**：
- 使用命令 3（已自动尝试）
- 或者修改命令，添加更多音频编码支持

### 原因 4：FFmpeg 库版本问题

**症状**：所有命令都失败，日志显示库加载错误

**解决方法**：
1. 检查 FFmpeg 库是否正确集成：
   ```bash
   ./gradlew app:dependencies | grep ffmpeg
   ```

2. 尝试使用不同版本的 FFmpeg Kit：
   ```kotlin
   // 在 build.gradle.kts 中
   implementation("com.arthenica:ffmpeg-kit-full:6.0-2")  // 完整版
   ```

3. 清理并重新构建：
   ```bash
   ./gradlew clean
   ./gradlew build
   ```

### 原因 5：存储权限问题

**症状**：
- 临时文件创建成功
- 但输出文件无法创建

**解决方法**：
- 检查应用是否有存储权限
- 检查输出目录是否可写
- 尝试使用应用私有目录

## 测试命令

### 手动测试 FFmpeg

如果想在设备上手动测试 FFmpeg：

1. 将 TS 文件拉到电脑：
   ```bash
   adb pull /sdcard/Android/data/com.tv.ball/cache/xxx_temp.ts .
   ```

2. 在电脑上测试 FFmpeg 命令：
   ```bash
   ffmpeg -i xxx_temp.ts -c copy -bsf:a aac_adtstoasc output.mp4
   ```

3. 查看详细错误信息

### 检查 TS 文件格式

```bash
ffprobe xxx_temp.ts
```

查看：
- 视频编码：应该是 H.264 或 H.265
- 音频编码：应该是 AAC、MP3 或其他
- 容器格式：应该是 mpegts

## 临时解决方案

如果 FFmpeg 持续失败，可以临时使用方案 1（输出 .ts 格式）：

### 修改 1：输出为 .ts 文件

在 `VideoDownloader.kt` 第 148 行：

```kotlin
// 修改前
val fileName = if (isM3u8) "$title.mp4" else "$title.${getFileExtension(cleanUrl)}"

// 修改后
val fileName = if (isM3u8) "$title.ts" else "$title.${getFileExtension(cleanUrl)}"
```

### 修改 2：跳过 FFmpeg 转换

在 `mergeSegmentsOptimized` 方法中，注释掉 FFmpeg 转换部分，直接使用拼接后的 TS 文件。

## 获取帮助

如果问题仍未解决，请提供以下信息：

1. **完整的 Logcat 日志**（包含 FFmpeg 输出）
2. **设备信息**（型号、Android 版本）
3. **视频源信息**（M3U8 链接或特征）
4. **TS 文件信息**（使用 ffprobe 检查）

## 性能优化建议

如果命令 3（重新编码音频）经常被使用：

1. **调整音频比特率**：
   ```kotlin
   // 降低比特率以提高速度
   "-i \"${tempTsFile.absolutePath}\" -c:v copy -c:a aac -b:a 64k \"${outputFile.absolutePath}\""
   ```

2. **使用硬件加速**（如果设备支持）：
   ```kotlin
   "-hwaccel auto -i \"${tempTsFile.absolutePath}\" -c:v copy -c:a aac \"${outputFile.absolutePath}\""
   ```

3. **考虑异步转换**：
   - 先保存 TS 文件
   - 后台慢慢转换为 MP4
   - 用户可以先播放 TS 文件
