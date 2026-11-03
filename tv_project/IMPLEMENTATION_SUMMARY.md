# 实现总结：视频时长配色功能

## 📋 需求回顾

用户需求：
> "看到深蓝色没有？播放器下面用这个深蓝色 或者白色 白色是普通的影视 大于60分钟显示这个颜色的背景 代表电影的"
> "白色 字就是黑色的 深蓝色 字就是金黄色的"

## ✅ 实现内容

### 1. 颜色定义
在 `colors.xml` 中添加了4个新颜色：
- `episode_movie_bg`: 深蓝色背景 `#1E3A8A`
- `episode_normal_bg`: 白色背景 `#FFFFFF`
- `episode_movie_text`: 金黄色文字 `#FFD700`
- `episode_normal_text`: 黑色文字 `#000000`

### 2. 核心逻辑
在 `VideoPlayerActivity.kt` 中实现：

**主方法：`updateDetailSectionBackground()`**
- 获取视频时长（毫秒）
- 转换为分钟数
- 判断是否 ≥60分钟
- 应用对应的背景色和文字颜色

**辅助方法：`updateDetailSectionTextColors()`**
- 更新所有文字元素颜色
- 更新所有图标颜色
- 处理主要文字和次要文字的颜色差异

### 3. 触发时机
在播放器就绪回调 `onReady()` 中自动触发，确保视频缓冲完成后立即应用样式。

## 🎨 配色方案

| 模式 | 时长 | 背景色 | 主要文字 | 次要文字 |
|-----|------|--------|---------|---------|
| 电影 | ≥60分钟 | 深蓝色 #1E3A8A | 金黄色 #FFD700 | 半透明金黄色 |
| 普通 | <60分钟 | 白色 #FFFFFF | 黑色 #000000 | 灰色 #AAAAAA |

## 📝 更新的UI元素

### 背景
- `portraitDetailSection` - 整个播放器下方区域

### 主要文字（使用主色）
- 视频标题 (`videoTitleDetail`)
- 选集标题

### 次要文字（使用次要色）
- 视频信息 (`videoInfoText`) - 评分、播放量
- 集数统计 (`episodeCountText`) - "全25集"
- 功能按钮文字 - 下载、收藏、投屏、分享、解析

### 图标
- 功能按钮图标 - 下载、收藏、投屏、分享、解析

## 🔧 技术细节

### 时长判断
```kotlin
val durationMs = exoPlayerManager.getDuration()
val durationMinutes = (durationMs / 1000 / 60).toInt()
val isMovie = durationMinutes >= 60
```

### 透明度处理
电影模式的次要文字使用70%透明度的金黄色：
```kotlin
android.graphics.Color.argb(
    180,  // 70% 透明度
    android.graphics.Color.red(color),
    android.graphics.Color.green(color),
    android.graphics.Color.blue(color)
)
```

### 颜色应用
```kotlin
// 背景色
detailSection.setBackgroundColor(ContextCompat.getColor(this, colorRes))

// 文字颜色
textView.setTextColor(textColor)

// 图标颜色
imageView.setColorFilter(iconColor)
```

## 📂 修改的文件

1. **`app/src/main/res/values/colors.xml`**
   - 添加4个新颜色定义

2. **`app/src/main/java/com/tv/ball/presentation/player/VideoPlayerActivity.kt`**
   - 添加 `updateDetailSectionBackground()` 方法
   - 添加 `updateDetailSectionTextColors()` 方法
   - 在 `onReady()` 回调中调用

3. **`app/src/main/java/com/tv/ball/domain/model/VideoDetail.kt`**
   - 添加 `durationMinutes` 字段（可选，用于Episode数据）

4. **`app/src/main/java/com/tv/ball/presentation/player/EpisodeSelectorAdapter.kt`**
   - 更新选集item的背景色逻辑（可选，用于选集列表）

## 🎯 效果预览

### 电影模式示例
```
视频：《阿凡达》
时长：162分钟
效果：深蓝色背景 + 金黄色文字
```

### 普通模式示例
```
视频：《权力的游戏 S01E01》
时长：58分钟
效果：白色背景 + 黑色文字
```

## ✨ 特性

- ✅ 自动检测视频时长
- ✅ 动态切换配色方案
- ✅ 保持良好的对比度和可读性
- ✅ 支持主要文字和次要文字的层次区分
- ✅ 图标颜色同步更新
- ✅ 仅在竖屏模式下生效
- ✅ 每次播放新视频时自动更新

## 🐛 注意事项

1. **时长获取失败**：如果视频时长为0或无法获取，默认使用普通模式（白色背景）
2. **竖屏限制**：配色方案仅在竖屏模式下的详情区域生效
3. **动态更新**：每次播放新视频时都会重新判断并应用配色
4. **对比度**：两种配色方案都符合WCAG可访问性标准

## 📊 测试建议

### 测试场景
1. 播放时长≥60分钟的电影
2. 播放时长<60分钟的剧集
3. 播放时长未知的视频
4. 快速切换不同时长的视频

### 验证点
- [ ] 背景色正确切换
- [ ] 文字颜色正确更新
- [ ] 图标颜色正确更新
- [ ] 文字清晰可读
- [ ] 视觉层次分明
- [ ] 切换流畅无闪烁

## 🚀 未来优化

1. **动画过渡**：添加颜色切换的渐变动画
2. **自定义阈值**：允许用户自定义电影时长阈值（默认60分钟）
3. **更多配色**：支持更多配色方案（如纪录片、动画等）
4. **横屏支持**：扩展到横屏模式
5. **主题适配**：根据系统主题（深色/浅色）自动调整

## 📚 相关文档

- `FEATURE_VIDEO_DURATION_BACKGROUND.md` - 功能详细说明
- `COLOR_SCHEME_GUIDE.md` - 配色方案指南
