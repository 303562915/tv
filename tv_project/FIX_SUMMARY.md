# 短剧自动播放下一集 - 问题修复总结

## 问题根源 ✅ 已找到并修复

### 核心问题
**API 地址不一致！**

- **第一集播放**：使用 `ShortDramaDetailViewModel.getVideoUrl()`
  - API: `https://api-v2.cenguigui.cn/api/duanju/api.php?video_id=xxxxx`
  - ✅ 正确的短剧 API
  - ✅ 能够成功获取播放地址

- **自动播放下一集**：使用 `VideoPlayerActivity.fetchShortDramaVideoUrl()`
  - API: ~~`https://api-v2.cenguigui.cn/api/video/play?video_id=xxxxx`~~ ❌ 错误
  - ❌ 这是普通视频的 API，不是短剧 API
  - ❌ 导致获取播放地址失败

### 为什么第一集能播放，但不能自动播放下一集？

1. **第一集**：
   - 用户在短剧详情页点击播放
   - `ShortDramaDetailActivity.playEpisode()` 调用 `viewModel.getVideoUrl()`
   - 使用正确的短剧 API 获取播放地址
   - 把播放地址作为 `VIDEO_URL` 传递给播放器
   - ✅ 播放成功

2. **自动播放下一集**：
   - 第一集播放结束，触发 `onEnded()`
   - 调用 `playShortDramaEpisode(nextEpisode)`
   - `nextEpisode.url` 为空，需要获取播放地址
   - 调用 `fetchShortDramaVideoUrl(videoId)`
   - ❌ 使用了错误的 API 地址
   - ❌ 获取播放地址失败
   - ❌ 无法播放下一集

## 修复内容

### 1. 修复 API 地址 ✅
**文件**: `app/src/main/java/com/tv/ball/presentation/player/VideoPlayerActivity.kt`

**修改前**:
```kotlin
val apiBase = "https://api-v2.cenguigui.cn/api/video/play"
```

**修改后**:
```kotlin
// 使用正确的短剧 API 地址
val apiBase = "https://api-v2.cenguigui.cn/api/duanju/api.php"
```

### 2. 添加重试机制 ✅
- 获取播放地址失败时，自动重试最多 3 次
- 每次重试间隔 3 秒
- 3 次重试后仍失败，自动跳到下一集

### 3. 优化错误处理 ✅
- 区分不同的失败场景
- 提供清晰的用户提示
- 避免无限循环

### 4. 增强日志输出 ✅
- 详细记录 API 调用过程
- 记录请求 URL、响应数据、解析结果
- 便于后续调试

## 修改的文件

1. ✅ `app/src/main/java/com/tv/ball/presentation/player/VideoPlayerActivity.kt`
   - 修复 API 地址
   - 添加重试机制
   - 增强日志输出
   - 优化错误处理

2. ✅ `app/src/main/java/com/tv/ball/presentation/player/ShortDramaManager.kt`
   - 移除对 viewModel 的依赖
   - 简化自动播放逻辑
   - 添加详细日志

3. ✅ `app/src/main/java/com/tv/ball/presentation/shortdrama/ShortDramaDetailActivity.kt`
   - 添加剧集列表构建日志

## 测试步骤

1. **编译并运行应用**
2. **打开短剧详情页**
3. **播放任意一集**
4. **等待播放结束**
5. **观察是否自动播放下一集** ✅

## 预期结果

- ✅ 第一集播放正常
- ✅ 播放结束后，自动获取下一集播放地址
- ✅ 自动播放下一集
- ✅ 如果获取失败，会重试 3 次
- ✅ 重试失败后，自动跳到下一集
- ✅ 竖屏全屏模式下也能正常自动播放

## 技术细节

### API 对比

| 用途 | API 地址 | 参数 | 说明 |
|------|---------|------|------|
| 短剧播放 | `https://api-v2.cenguigui.cn/api/duanju/api.php` | `video_id` | ✅ 正确 |
| 普通视频 | `https://api-v2.cenguigui.cn/api/video/play` | `video_id` | ❌ 不适用于短剧 |

### 代码流程

```
播放结束 (onEnded)
    ↓
检查是否是短剧 && 有下一集
    ↓
调用 shortDramaManager.onPlaybackEnded()
    ↓
获取下一集信息 (ShortDramaEpisode)
    ↓
调用 playShortDramaEpisode(episode)
    ↓
检查 episode.url 是否为空
    ↓
调用 fetchShortDramaVideoUrl(videoId)  ← 这里使用正确的 API
    ↓
解析响应，获取播放地址
    ↓
调用 viewModel.loadVideo() 播放
```

## 总结

问题的根本原因是 **API 地址配置错误**。修复后，短剧的自动播放下一集功能应该能够正常工作了！

如果还有问题，请检查：
1. ✅ API 地址是否正确
2. ✅ videoId 是否有效
3. ✅ 网络连接是否正常
4. ✅ API 返回的数据格式是否符合预期
