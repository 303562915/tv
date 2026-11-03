# 视频播放器快速启动指南

## 🚀 快速开始

### 1. 构建项目
```bash
./gradlew assembleDebug
```

### 2. 安装到设备
```bash
./gradlew installDebug
```

### 3. 启动播放器

#### 方式一：从代码启动
```kotlin
val intent = Intent(context, VideoPlayerActivity::class.java).apply {
    putExtra("VIDEO_ID", videoId)
    putExtra("VIDEO_TITLE", "视频标题")
    putExtra("VIDEO_URL", "http://example.com/video.mp4")
    putExtra("THUMBNAIL_URL", "http://example.com/thumb.jpg") // 可选
}
startActivity(intent)
```

#### 方式二：带播放列表启动
```kotlin
// 构建播放列表 JSON
val episodes = listOf(
    mapOf("name" to "第1集", "url" to "http://example.com/ep1.mp4"),
    mapOf("name" to "第2集", "url" to "http://example.com/ep2.mp4"),
    mapOf("name" to "第3集", "url" to "http://example.com/ep3.mp4")
)
val episodesJson = JSONArray(episodes).toString()

val intent = Intent(context, VideoPlayerActivity::class.java).apply {
    putExtra("VIDEO_ID", videoId)
    putExtra("VIDEO_TITLE", "电视剧名称")
    putExtra("EPISODES_JSON", episodesJson)
    putExtra("START_INDEX", 0) // 从第几集开始播放
}
startActivity(intent)
```

## 🎮 功能使用指南

### 手势操作

| 手势 | 功能 | 说明 |
|------|------|------|
| 左右滑动 | 调整进度 | 在屏幕任意位置左右滑动 |
| 左侧上下滑动 | 调整亮度 | 在屏幕左侧1/3区域上下滑动 |
| 右侧上下滑动 | 调整音量 | 在屏幕右侧1/3区域上下滑动 |
| 单击 | 显示/隐藏控制栏 | 点击屏幕任意位置 |
| 双击 | 播放/暂停 | 快速双击屏幕 |
| 长按 | 2倍速快进 | 长按屏幕，松开恢复正常速度 |

### 控制栏功能

#### 顶部工具栏
- **返回按钮**：退出播放器
- **标题**：显示当前播放的视频标题
- **分享按钮**：分享视频链接
- **投屏按钮**：投屏到电视或其他设备
- **菜单按钮**：打开侧滑菜单

#### 底部控制栏
- **播放/暂停按钮**：控制播放状态
- **下一集按钮**：播放下一集（有播放列表时显示）
- **截屏按钮**：截取当前画面
- **进度条**：显示和调整播放进度
- **时间显示**：当前时间 / 总时长
- **清晰度按钮**：切换视频清晰度（开发中）
- **选集按钮**：选择播放集数
- **倍速按钮**：快速切换播放速度
- **全屏按钮**：切换横竖屏

#### 左侧控制栏
- **锁定按钮**：锁定屏幕，防止误触

### 侧滑菜单

点击顶部菜单按钮打开侧滑菜单，包含以下功能：

1. **画面比例**
   - 自适应（默认）
   - 16:9
   - 4:3
   - 铺满屏幕
   - 放大裁剪

2. **播放倍速**
   - 滑动条精确调节（0.5x - 2.0x）
   - 快速按钮（0.5x, 0.75x, 1.0x, 1.25x, 1.5x, 2.0x）

3. **片头片尾跳过**
   - 自动跳过片头开关
   - 自动跳过片尾开关

4. **投屏**
   - 一键投屏到电视

## 🔧 配置选项

### 片头片尾时长配置

默认配置：
- 片头时长：90秒
- 片尾时长：60秒

可通过 `SkipIntroManager` 修改：
```kotlin
val skipIntroManager = SkipIntroManager(context)
skipIntroManager.setIntroDuration(120000L) // 120秒
skipIntroManager.setOutroDuration(90000L)  // 90秒
```

### 画面比例配置

默认使用自适应模式，可通过 `AspectRatioManager` 修改：
```kotlin
val aspectRatioManager = AspectRatioManager(context)
aspectRatioManager.setMode(AspectRatioManager.AspectRatioMode.FIXED_WIDTH) // 16:9
```

## 📱 竖屏模式

竖屏模式下，播放器会显示底部内容区域，包含：

1. **视频信息**
   - 视频标题
   - 视频详情

2. **功能按钮**
   - 日志：查看播放日志
   - 投屏：投屏功能
   - 快源：快速切换视频源
   - 外控：外部控制
   - 离线：离线下载

3. **视频源选择器**
   - 切换不同的视频源

4. **选集列表**
   - 网格布局显示所有集数
   - 点击切换播放

## 🎨 UI 自定义

### 修改主题颜色

在 `res/values/colors.xml` 中修改：
```xml
<color name="primary">#FF6200EE</color>
<color name="primary_dark">#FF3700B3</color>
<color name="accent">#FF03DAC5</color>
```

### 修改控制栏自动隐藏时间

在 `VideoPlayerActivity.kt` 中修改：
```kotlin
private fun resetHideControlsTimer() {
    hideControlsHandler.removeCallbacks(hideControlsRunnable)
    hideControlsHandler.postDelayed(hideControlsRunnable, 5000) // 改为你想要的毫秒数
}
```

## 🐛 常见问题

### Q: 视频无法播放
A: 检查以下几点：
1. 视频URL是否正确
2. 网络连接是否正常
3. 视频格式是否支持（支持 MP4, M3U8, HLS 等）
4. 是否有必要的网络权限

### Q: 手势不响应
A: 检查：
1. 是否锁定了屏幕
2. 是否在控制栏上操作（控制栏会拦截手势）

### Q: 投屏功能无法使用
A: 需要安装第三方投屏应用，如：
- 乐播投屏
- 快点投屏
- 小米投屏

### Q: 截屏功能不可用
A: 截屏功能需要 Android 8.0+ 系统

## 📊 性能优化建议

1. **网络视频**
   - 使用 CDN 加速
   - 启用 HLS 自适应码率
   - 预加载下一集

2. **本地视频**
   - 使用硬件解码
   - 避免频繁 seek 操作

3. **内存管理**
   - 及时释放播放器资源
   - 避免内存泄漏

## 🔐 权限要求

在 `AndroidManifest.xml` 中添加：
```xml
<!-- 网络权限 -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- 存储权限（截屏功能） -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />

<!-- 屏幕常亮 -->
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

## 📚 相关文档

- [ExoPlayer 官方文档](https://exoplayer.dev/)
- [Material Design 指南](https://material.io/design)
- [Android 开发者文档](https://developer.android.com/)

## 💡 提示

1. 首次使用建议先测试本地视频
2. 网络视频建议使用 HTTPS 协议
3. 大文件建议使用 HLS 流式传输
4. 定期清理播放缓存
5. 监控播放器性能指标

## 🎯 下一步

1. 测试所有手势功能
2. 测试播放列表功能
3. 测试横竖屏切换
4. 测试网速监控
5. 测试片头片尾跳过
6. 自定义 UI 样式
7. 添加更多功能

祝你使用愉快！🎉
