# 解析链接缓存功能 - 待完成

## 已完成
1. ✅ 数据库升级到版本7
2. ✅ 添加 `parsedVideoUrl` 字段
3. ✅ 修改索引为 `videoTitle + currentEpisode`
4. ✅ 清理重复的历史记录
5. ✅ Repository层支持保存解析后的链接

## 需要完成的代码修改

由于VideoPlayerViewModel编译错误，需要手动完成以下修改：

### 1. VideoPlayerViewModel.kt

在类中添加变量：
```kotlin
private var currentParsedVideoUrl: String? = null  // 保存解析后的真实视频链接
```

在 `savePlaybackProgress()` 方法中：
```kotlin
historyRepository.addOrUpdateHistory(
    videoTitle = currentVideoTitle,
    videoUrl = currentVideoSourceUrl,
    parsedVideoUrl = currentParsedVideoUrl,  // 新增这一行
    thumbnailUrl = currentThumbnailUrl,
    watchedPosition = _currentPosition.value,
    duration = _duration.value,
    detailUrl = currentDetailUrl,
    episodesJson = currentEpisodesJson,
    currentEpisode = currentEpisodeName
)
```

添加新方法：
```kotlin
fun setParsedVideoUrl(parsedUrl: String) {
    currentParsedVideoUrl = parsedUrl
    android.util.Log.d("VideoPlayerViewModel", "设置解析后的链接: $parsedUrl")
}
```

在 `loadVideo()` 开始时检查缓存：
```kotlin
// 先检查历史记录中是否有已保存的解析链接
viewModelScope.launch {
    val history = historyRepository.getHistoryByTitleAndEpisode(currentVideoTitle, episodeName)
    
    if (history?.parsedVideoUrl != null && history.parsedVideoUrl.isNotEmpty()) {
        // 有已保存的解析链接，直接使用
        android.util.Log.d("VideoPlayerViewModel", "找到已保存的解析链接，直接播放")
        currentParsedVideoUrl = history.parsedVideoUrl
        _playerState.value = PlayerState.LoadingWithMessage("使用缓存链接，快速播放...")
        kotlinx.coroutines.delay(300)
        _videoUrl.value = history.parsedVideoUrl
        _playerState.value = PlayerState.Ready
        _currentPosition.value = history.watchedPosition
        return@launch
    }
    
    // 没有缓存，继续原来的解析流程...
}
```

在解析成功后保存链接：
```kotlin
// WebView sniffing 成功后
currentParsedVideoUrl = finalUrl  // 保存解析后的链接

// 原解析方式成功后
currentParsedVideoUrl = result.videoUrl.url  // 保存解析后的链接
```

### 2. VideoPlayerActivity.kt

在嗅探成功后调用：
```kotlin
// 在 onLinkFound 回调中
viewModel.setParsedVideoUrl(link)
```

## 测试步骤

1. 编译安装应用
2. 播放一个视频（会解析并保存链接）
3. 退出播放器
4. 再次播放同一集 - 应该直接使用缓存链接，不再解析
5. 查看日志确认：`找到已保存的解析链接，直接播放`

## 预期效果

- 第一次播放：需要解析（3-5秒）
- 第二次播放：直接使用缓存（<1秒）
- 每集单独缓存，切换集数会重新解析
- 缓存永久保存，除非清除历史记录
