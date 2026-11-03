# 视频时长背景色和文字颜色功能

## 功能说明

在播放器缓冲完成后，根据视频的总时长自动改变播放器下方区域的背景颜色和文字颜色：

- **电影（≥60分钟）**：
  - 背景色：深蓝色 `#1E3A8A`
  - 主要文字：金黄色 `#FFD700`
  - 次要文字：半透明金黄色（70%透明度）
  
- **普通影视（<60分钟）**：
  - 背景色：白色 `#FFFFFF`
  - 主要文字：黑色 `#000000`
  - 次要文字：灰色 `#AAAAAA`

## 修改文件

### 1. 颜色资源 (`app/src/main/res/values/colors.xml`)

添加了新的颜色定义：

```xml
<!-- 选集背景颜色 - 根据时长区分 -->
<color name="episode_movie_bg">#1E3A8A</color><!-- 电影背景 - 深蓝色 (≥60分钟) -->
<color name="episode_normal_bg">#FFFFFF</color><!-- 普通影视背景 - 白色 (<60分钟) -->
<color name="episode_movie_text">#FFD700</color><!-- 电影文字 - 金黄色 -->
<color name="episode_normal_text">#000000</color><!-- 普通影视文字 - 黑色 -->
```

### 2. 播放器Activity (`VideoPlayerActivity.kt`)

#### 添加的方法：

**主方法：`updateDetailSectionBackground()`**
```kotlin
private fun updateDetailSectionBackground() {
    val durationMs = exoPlayerManager.getDuration()
    val durationMinutes = (durationMs / 1000 / 60).toInt()
    
    val detailSection = portraitDetailView?.findViewById<View>(R.id.portraitDetailSection)
    
    if (detailSection != null) {
        val isMovie = durationMinutes >= 60
        
        if (isMovie) {
            // 电影：深蓝色背景 + 金黄色文字
            detailSection.setBackgroundColor(
                ContextCompat.getColor(this, R.color.episode_movie_bg)
            )
            updateDetailSectionTextColors(detailSection, R.color.episode_movie_text)
        } else {
            // 普通影视：白色背景 + 黑色文字
            detailSection.setBackgroundColor(
                ContextCompat.getColor(this, R.color.episode_normal_bg)
            )
            updateDetailSectionTextColors(detailSection, R.color.episode_normal_text)
        }
    }
}
```

**辅助方法：`updateDetailSectionTextColors()`**
```kotlin
private fun updateDetailSectionTextColors(detailSection: View, textColorRes: Int) {
    val textColor = ContextCompat.getColor(this, textColorRes)
    val secondaryTextColor = if (textColorRes == R.color.episode_movie_text) {
        // 电影模式：次要文字使用稍暗的金黄色（70%透明度）
        // ...
    } else {
        // 普通模式：次要文字使用灰色
        0xFFAAAAAA.toInt()
    }
    
    // 更新所有文字元素的颜色
    // - 视频标题
    // - 视频信息
    // - 选集标题
    // - 集数统计
    // - 功能按钮文字（下载、收藏、投屏、分享、解析）
    // - 功能按钮图标
}
```

#### 调用位置：

在播放器就绪回调 `onReady()` 中调用：

```kotlin
override fun onReady() {
    // ... 其他代码 ...
    
    // 根据视频时长改变播放器下方区域的背景颜色和文字颜色
    updateDetailSectionBackground()
    
    // ... 其他代码 ...
}
```

## 工作原理

1. **触发时机**：当播放器缓冲完成，进入 `STATE_READY` 状态时
2. **获取时长**：通过 `exoPlayerManager.getDuration()` 获取视频总时长（毫秒）
3. **计算分钟**：将毫秒转换为分钟数
4. **判断类型**：
   - 时长 ≥ 60分钟 → 电影
   - 时长 < 60分钟 → 普通影视
5. **应用样式**：
   - 设置 `portraitDetailSection` 的背景色
   - 更新所有文字元素的颜色（标题、信息、按钮文字等）
   - 更新所有图标的颜色

## 更新的UI元素

### 主要文字（使用主色）
- 视频标题 (`videoTitleDetail`)
- 选集标题

### 次要文字（使用次要色）
- 视频信息 (`videoInfoText`) - 评分、播放量等
- 集数统计 (`episodeCountText`) - "全25集"等
- 功能按钮文字 - "下载"、"收藏"、"投屏"、"分享"、"解析"

### 图标
- 功能按钮图标 - 下载、收藏、投屏、分享、解析的图标

## 效果展示

### 电影模式（≥60分钟）
- 🎬 深蓝色背景
- ⭐ 金黄色标题和主要文字
- ✨ 半透明金黄色次要文字和图标

### 普通影视模式（<60分钟）
- 📺 白色背景
- 🖤 黑色标题和主要文字
- 🔘 灰色次要文字和图标

## 注意事项

- 背景色和文字颜色变化仅在竖屏模式下的详情区域生效
- 如果视频时长无法获取（返回0），默认显示白色背景 + 黑色文字
- 颜色变化是动态的，每次播放新视频时都会重新判断
- 次要文字在电影模式下使用70%透明度的金黄色，保持视觉层次

