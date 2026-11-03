# 修复选集列表显示问题

## 问题描述

用户反馈：
- 详情页显示"全29集"
- 进入播放器页面只显示"04 第04集"一个选集按钮
- 其他集数没有显示

## 问题原因

在 `parseEpisodesFromJson()` 方法中，代码过滤掉了所有标记为 `isCloudStorage=true` 的集数：

```kotlin
// 原代码
if (!isCloud) {
    episodes.add(...)  // 只添加非网盘资源
} else {
    android.util.Log.d("VideoPlayer", "过滤网盘资源: ${nameMatch.groupValues[1]}")
}
```

这导致如果大部分集数都是网盘资源，播放列表就会变得很少或为空。

## 解决方案

### 1. 不再过滤网盘资源

修改 `parseEpisodesFromJson()` 方法，保留所有集数（包括网盘资源）：

```kotlin
// 修改后
episodes.add(
    com.tv.ball.domain.model.Episode(
        name = name,
        url = url,
        isCloudStorage = isCloud  // 保留标记，但不过滤
    )
)
```

### 2. 添加详细的调试日志

```kotlin
android.util.Log.d("VideoPlayer", "开始解析剧集JSON，共 ${items.size} 个条目")
android.util.Log.d("VideoPlayer", "解析第${index+1}集: name=$name, isCloud=$isCloud, url=${url.take(50)}...")
android.util.Log.d("VideoPlayer", "解析剧集完成: 总共 ${episodes.size} 集")
```

### 3. 在 setupPortraitEpisodeList 中添加检查

```kotlin
android.util.Log.d("VideoPlayer", "setupPortraitEpisodeList: episodes.size=${episodes.size}")

if (episodes.isEmpty()) {
    android.util.Log.w("VideoPlayer", "setupPortraitEpisodeList: 播放列表为空，无法设置选集列表")
    return
}

// 检查所有必要的View是否存在
if (recyclerView == null || tabLayout == null || moreButton == null || episodeCountText == null) {
    android.util.Log.e("VideoPlayer", "setupPortraitEpisodeList: 找不到必要的View元素")
    return
}
```

## 修改的文件

**`app/src/main/java/com/tv/ball/presentation/player/VideoPlayerActivity.kt`**

1. **parseEpisodesFromJson()** 方法
   - 不再过滤网盘资源
   - 添加详细的解析日志
   - 保留 `isCloudStorage` 标记供后续使用

2. **setupPortraitEpisodeList()** 方法
   - 添加播放列表大小的日志
   - 添加View元素检查的日志
   - 更详细的错误提示

## 预期效果

修改后：
- ✅ 所有集数都会显示在选集列表中
- ✅ 网盘资源也会显示（可以在播放时特殊处理）
- ✅ 详细的日志帮助调试问题
- ✅ 用户可以看到完整的29集列表

## 后续优化建议

### 1. 网盘资源的特殊标记

可以在选集item上添加特殊标记，区分网盘资源和直接播放资源：

```kotlin
// 在 EpisodeSelectorAdapter 中
if (episode.isCloudStorage) {
    // 显示网盘图标或特殊颜色
    binding.cloudBadge.visibility = View.VISIBLE
}
```

### 2. 网盘资源的播放处理

点击网盘资源时，可以：
- 提示用户需要跳转到网盘
- 显示网盘链接
- 提供复制链接功能

```kotlin
if (episode.isCloudStorage) {
    // 显示网盘提示对话框
    showCloudStorageDialog(episode)
} else {
    // 直接播放
    playEpisode(index)
}
```

### 3. 过滤选项

在UI上提供过滤选项，让用户选择：
- 显示所有集数
- 只显示可直接播放的集数
- 只显示网盘资源

## 调试步骤

1. 运行应用并打开播放器
2. 查看Logcat日志，搜索 "VideoPlayer"
3. 检查以下日志：
   - "开始解析剧集JSON，共 X 个条目"
   - "解析第X集: name=..., isCloud=..."
   - "解析剧集完成: 总共 X 集"
   - "setupPortraitEpisodeList: episodes.size=X"
4. 确认所有集数都被正确解析和显示

## 测试用例

| 场景 | 预期结果 |
|-----|---------|
| 29集全部是直接播放 | 显示29个选集按钮 |
| 29集全部是网盘资源 | 显示29个选集按钮（带网盘标记） |
| 混合：10集直接播放 + 19集网盘 | 显示29个选集按钮 |
| 只有1集 | 显示1个选集按钮 |
| 播放列表为空 | 不显示选集区域 |
