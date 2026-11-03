# FFmpeg 集成说明

## 问题描述

之前的实现中，M3U8 视频下载后的合并过程只是简单地将 TS 分片文件拼接在一起，然后输出为 `.mp4` 扩展名。但实际上文件内容仍然是 MPEG-TS 容器格式，导致很多播放器无法识别和播放。

## 解决方案

集成 FFmpeg 库，在拼接完 TS 分片后，使用 FFmpeg 将 TS 容器格式转换为标准的 MP4 容器格式。

## 修改内容

### 1. 添加 FFmpeg 依赖 (`app/build.gradle.kts`)

```kotlin
// FFmpeg for video conversion
implementation("com.arthenica:ffmpeg-kit-min:6.0-2")
```

使用 `ffmpeg-kit-min` 版本，这是最小化的 FFmpeg 库，只包含基本的编解码器，APK 体积增加约 20-30MB。

### 2. 修改 VideoMerger.kt

- 添加 `Context` 参数到 `mergeTsToMp4` 方法
- 实现两步合并流程：
  1. **拼接 TS 分片**：将所有 TS 分片拼接为一个临时 `.ts` 文件
  2. **FFmpeg 转换**：使用 FFmpeg 将临时 TS 文件转换为 MP4 格式

FFmpeg 命令：
```bash
ffmpeg -i input.ts -c copy -bsf:a aac_adtstoasc output.mp4
```

参数说明：
- `-i input.ts`：输入文件
- `-c copy`：不重新编码，只重新封装容器（速度快，无质量损失）
- `-bsf:a aac_adtstoasc`：转换 AAC 音频格式以兼容 MP4 容器
- `output.mp4`：输出文件

### 3. 修改 VideoDownloader.kt

在 `mergeSegmentsOptimized` 方法中：
- 先拼接所有 TS 分片到临时文件
- 使用 FFmpeg 将临时 TS 文件转换为 MP4
- 转换成功后删除临时文件
- 更新进度：拼接占 50%，转换占剩余 50%

## 优势

1. **兼容性好**：生成标准的 MP4 容器，所有播放器都能识别
2. **速度快**：使用 `-c copy` 不重新编码，只是重新封装容器
3. **无质量损失**：不重新编码，保持原始视频质量
4. **支持加密**：仍然支持 AES-128 解密

## APK 体积影响

- `ffmpeg-kit-min`：约 20-30MB（仅包含基本编解码器）
- 如果需要更多编解码器支持，可以使用：
  - `ffmpeg-kit-full`：约 80-100MB（包含所有编解码器）
  - `ffmpeg-kit-video`：约 40-50MB（包含常用视频编解码器）

## 使用方法

同步 Gradle 依赖后，重新编译项目即可。下载 M3U8 视频时会自动使用 FFmpeg 转换。

## 注意事项

1. 首次使用 FFmpeg 时可能需要初始化，会有短暂延迟
2. 转换过程会占用一定 CPU 资源，但因为使用 `-c copy`，速度很快
3. 临时 TS 文件会在转换成功后自动删除
4. 如果 FFmpeg 转换失败，会抛出异常并清理临时文件

## 测试建议

1. 下载一个 M3U8 视频
2. 等待下载和合并完成
3. 检查输出文件是否为标准 MP4 格式
4. 使用不同播放器测试播放兼容性
