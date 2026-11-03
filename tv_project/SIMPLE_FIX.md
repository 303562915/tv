# 简单修复方案

## 问题分析

从日志看到：
1. FFmpeg 报错：`Invalid data found when processing input`
2. 所有 3 个 FFmpeg 命令都失败
3. 使用后备方案后文件只有 400MB，说明拼接时跳过了很多分片

## 根本原因

代码中有这行：
```kotlin
if (fileSize < 1024) {
    Log.w(TAG, "分片 $i 太小 ($fileSize 字节)，跳过")
    continue
}
```

这个阈值太严格了！很多有效的小分片（比如 500 字节）被跳过了，导致：
- 拼接后的 TS 文件不完整
- FFmpeg 无法识别损坏的 TS 文件
- 最终文件只有 400MB 而不是完整大小

## 解决方案

### 方案 1：直接输出 TS 格式（推荐）

修改两个地方：

**1. 修改文件扩展名（第 148 行）**
```kotlin
// 修改前
val fileName = if (isM3u8) "$title.mp4" else "$title.${getFileExtension(cleanUrl)}"

// 修改后
val fileName = if (isM3u8) "$title.ts" else "$title.${getFileExtension(cleanUrl)}"
```

**2. 降低分片大小阈值（约第 1770 行）**
```kotlin
// 修改前
if (fileSize < 1024) {
    Log.w(TAG, "分片 $i 太小 ($fileSize 字节)，跳过")
    continue
}

// 修改后
if (fileSize < 188) {  // TS 包的标准大小是 188 字节
    Log.w(TAG, "分片 $i 太小 ($fileSize 字节)，跳过")
    continue
}
```

**3. 移除 FFmpeg 转换部分**

删除或注释掉 "步骤 2: 使用 FFmpeg 转换 TS 为 MP4" 的所有代码。

### 方案 2：保留 FFmpeg 但修复拼接

如果你坚持要 MP4 格式，只需修改阈值：

```kotlin
if (fileSize < 188) {  // 从 1024 改为 188
    Log.w(TAG, "分片 $i 太小 ($fileSize 字节)，跳过")
    continue
}
```

这样拼接后的 TS 文件会完整，FFmpeg 就能正常转换了。

## 快速修复步骤

1. 打开 `VideoDownloader.kt`
2. 找到第 148 行，改为 `.ts`
3. 找到约第 1770 行，把 `1024` 改为 `188`
4. 重新编译安装
5. 重新下载测试

## 为什么 TS 格式更好

- ✅ 不需要转换，速度快
- ✅ 不会因为转换失败导致问题
- ✅ 大多数播放器都支持 TS 格式
- ✅ VLC、MX Player、ExoPlayer 都能播放
- ✅ 文件大小和质量完全一样

## 测试

修改后重新下载同一个视频，应该看到：
- 文件大小正常（不是 400MB）
- 文件扩展名为 `.ts`
- 可以正常播放
