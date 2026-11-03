# 缓冲动画修复

## 问题描述
正常播放时，缓冲动画一直显示不消失。

## 根本原因
1. `onBuffering()` 回调在开始缓冲时显示加载动画（`binding.loadingView.visibility = View.VISIBLE`）
2. `onPlayingChanged(isPlaying)` 回调只更新播放/暂停状态，但没有隐藏加载动画
3. 当缓冲完成恢复播放时，加载动画保持可见状态

## 解决方案
在 `onPlayingChanged()` 回调中，当 `isPlaying == true` 时隐藏加载动画：

```kotlin
override fun onPlayingChanged(isPlaying: Boolean) {
    if (isPlaying) {
        viewModel.play()
        // 隐藏加载动画，因为视频正在播放
        binding.loadingView.visibility = View.GONE
    } else {
        viewModel.pause()
    }
    updatePlayPauseButton()
}
```

## 修改文件
- `app/src/main/java/com/tv/ball/presentation/player/VideoPlayerActivity.kt` (第1012-1020行)

## 工作流程
1. 视频开始缓冲 → `onBuffering()` 被调用 → 显示加载动画
2. 缓冲完成，视频开始播放 → `onPlayingChanged(true)` 被调用 → 隐藏加载动画
3. 视频暂停 → `onPlayingChanged(false)` 被调用 → 不影响加载动画状态

## 测试建议
1. 播放视频，观察缓冲时加载动画是否正常显示
2. 缓冲完成后，加载动画应该自动消失
3. 暂停/播放切换时，加载动画不应该出现（除非真的在缓冲）
4. 网络较慢时，多次缓冲应该正常显示/隐藏加载动画
