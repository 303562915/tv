# 修复立即显示颜色和底部黑色区域问题

## 问题描述

用户反馈两个问题：

1. **进入页面没有立即显示颜色** - 需要等视频缓冲完成才改变颜色
2. **底部有黑色区域** - 详情区域下方有大片黑色空白

## 问题原因

### 问题1：颜色延迟显示
- 原代码只在 `onReady()` 回调中设置颜色
- 用户进入页面时，视频还在缓冲，详情区域是默认的黑色背景
- 需要等待视频加载完成才能看到颜色变化

### 问题2：底部黑色区域
- ScrollView的背景色设置为黑色
- containerLayout的背景色也是黑色
- portrait_detail_section的高度是 `wrap_content`，不能填满整个屏幕
- 导致底部出现黑色空白区域

## 解决方案

### 1. 立即设置默认颜色

在 `addPortraitDetailView()` 方法中，添加详情视图后立即设置默认颜色：

```kotlin
private fun addPortraitDetailView() {
    // ... 加载和添加视图 ...
    
    // 立即设置默认背景色（白色，普通影视模式）
    // 等视频加载完成后会根据实际时长更新
    setDetailSectionStyle(isMovie = false)
    
    // ... 其他设置 ...
}
```

### 2. 提取样式设置方法

创建独立的 `setDetailSectionStyle()` 方法，可以在任何时候调用：

```kotlin
private fun setDetailSectionStyle(isMovie: Boolean) {
    val detailSection = portraitDetailView?.findViewById<View>(R.id.portraitDetailSection)
    
    if (detailSection != null) {
        val backgroundColor = if (isMovie) {
            ContextCompat.getColor(this, R.color.episode_movie_bg)
        } else {
            ContextCompat.getColor(this, R.color.episode_normal_bg)
        }
        
        // 设置详情区域背景色
        detailSection.setBackgroundColor(backgroundColor)
        
        // 同时设置ScrollView的背景色，避免底部黑色区域
        scrollView?.setBackgroundColor(backgroundColor)
        
        // 更新文字颜色
        updateDetailSectionTextColors(detailSection, ...)
    }
}
```

### 3. 修改ScrollView和容器背景

```kotlin
// ScrollView：白色背景（默认普通模式）
scrollView = androidx.core.widget.NestedScrollView(this).apply {
    setBackgroundColor(android.graphics.Color.WHITE)
}

// containerLayout：透明背景
containerLayout = LinearLayout(this).apply {
    setBackgroundColor(android.graphics.Color.TRANSPARENT)
}
```

### 4. 修改详情区域布局

**portrait_detail_section.xml**

```xml
<LinearLayout
    android:id="@+id/portraitDetailSection"
    android:layout_width="match_parent"
    android:layout_height="match_parent"  <!-- 改为 match_parent -->
    android:background="#FFFFFF"  <!-- 默认白色 -->
    android:minHeight="400dp">  <!-- 最小高度 -->
    
    <!-- ... 内容 ... -->
    
    <!-- 底部填充空间 -->
    <View
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:background="@android:color/transparent" />
</LinearLayout>
```

## 修改的文件

### 1. `VideoPlayerActivity.kt`

**onCreate()** - 修改ScrollView和容器背景色
```kotlin
// ScrollView：白色背景
scrollView?.setBackgroundColor(android.graphics.Color.WHITE)

// containerLayout：透明背景
containerLayout?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
```

**addPortraitDetailView()** - 立即设置默认颜色
```kotlin
// 立即设置默认背景色（白色，普通影视模式）
setDetailSectionStyle(isMovie = false)
```

**setDetailSectionStyle()** - 新方法，同时更新ScrollView背景
```kotlin
private fun setDetailSectionStyle(isMovie: Boolean) {
    // 设置详情区域背景色
    detailSection.setBackgroundColor(backgroundColor)
    
    // 同时设置ScrollView的背景色
    scrollView?.setBackgroundColor(backgroundColor)
}
```

**updateDetailSectionBackground()** - 简化逻辑
```kotlin
private fun updateDetailSectionBackground() {
    val durationMs = exoPlayerManager.getDuration()
    val durationMinutes = (durationMs / 1000 / 60).toInt()
    val isMovie = durationMinutes >= 60
    
    setDetailSectionStyle(isMovie)
}
```

### 2. `portrait_detail_section.xml`

```xml
<!-- 根布局 -->
<LinearLayout
    android:layout_height="match_parent"  <!-- 改为 match_parent -->
    android:background="#FFFFFF"  <!-- 默认白色 -->
    android:minHeight="400dp">  <!-- 最小高度 -->
    
    <!-- ... 内容 ... -->
    
    <!-- 底部填充空间 -->
    <View
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />
</LinearLayout>
```

## 效果对比

### 修改前
```
┌─────────────────────┐
│  播放器（黑色）      │
└─────────────────────┘
┌─────────────────────┐
│  详情区域（黑色）    │  ← 等待缓冲完成才显示颜色
│                     │
│  标题、按钮、选集    │
└─────────────────────┘
█████████████████████  ← 底部黑色区域
█████████████████████
```

### 修改后
```
┌─────────────────────┐
│  播放器（黑色）      │
└─────────────────────┘
┌─────────────────────┐
│  详情区域（白色）    │  ← 立即显示白色背景
│                     │
│  标题、按钮、选集    │
│                     │
│  （填充空间）        │  ← 填满整个屏幕
└─────────────────────┘
```

## 工作流程

### 页面加载时
1. 创建ScrollView（白色背景）
2. 创建containerLayout（透明背景）
3. 添加播放器视图
4. 添加详情视图
5. **立即设置默认颜色**（白色背景 + 黑色文字）
6. 用户看到白色背景的详情区域

### 视频缓冲完成后
1. 触发 `onReady()` 回调
2. 获取视频时长
3. 判断是否为电影（≥60分钟）
4. 调用 `updateDetailSectionBackground()`
5. 根据时长更新颜色：
   - 电影：深蓝色背景 + 金黄色文字
   - 普通：保持白色背景 + 黑色文字

## 优势

✅ **立即显示** - 用户进入页面就能看到白色背景，不需要等待  
✅ **无黑色区域** - ScrollView和详情区域背景色同步，填满整个屏幕  
✅ **平滑过渡** - 视频加载完成后，颜色会根据时长自动更新  
✅ **代码复用** - `setDetailSectionStyle()` 方法可以在任何时候调用  

## 测试场景

| 场景 | 预期效果 |
|-----|---------|
| 进入页面（缓冲中） | 立即显示白色背景 + 黑色文字 |
| 视频加载完成（<60分钟） | 保持白色背景 + 黑色文字 |
| 视频加载完成（≥60分钟） | 切换到深蓝色背景 + 金黄色文字 |
| 滚动到底部 | 没有黑色区域，背景色填满整个屏幕 |
| 切换集数 | 颜色根据新视频时长自动更新 |
