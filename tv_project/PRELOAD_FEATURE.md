# 短剧自动播放预加载功能

## 功能概述

为了实现**无缝切换下一集**，在短剧播放时会提前预加载下一集的播放地址。

## 工作原理

### 1. 触发时机
- 当视频剩余时间 ≤ **10秒** 时，自动触发预加载
- 只在短剧模式下生效
- 每个视频只触发一次预加载（避免重复）

### 2. 预加载流程

```
播放进度监听 (updateProgress)
    ↓
检查剩余时间 ≤ 10秒？
    ↓ 是
检查是否是短剧 && 有下一集？
    ↓ 是
在后台线程预加载下一集
    ↓
调用 API 获取播放地址
    ↓
缓存到 episode.url
    ↓
显示提示："下一集已准备就绪"
    ↓
播放结束时直接使用缓存的 URL
    ↓
无缝切换到下一集 ✅
```

### 3. 技术实现

#### 关键变量
```kotlin
// VideoPlayerActivity.kt
private var isPreloadingNextEpisode = false      // 是否正在预加载
private var preloadedNextEpisodeUrl: String? = null  // 预加载的URL
private var hasTriggeredPreload = false          // 是否已触发（避免重复）
private val preloadThresholdSeconds = 10         // 提前10秒预加载
```

#### 核心方法

**1. checkAndPreloadNextEpisode()**
- 在 `updateProgress()` 中每次更新进度时调用
- 检查剩余时间是否 ≤ 10秒
- 触发预加载

**2. preloadNextShortDramaEpisode()**
- 在后台线程（IO Dispatcher）执行
- 获取下一集信息（不改变当前索引）
- 调用 API 获取播放地址
- 缓存到 episode 对象

**3. ShortDramaManager 新增方法**
- `getCurrentEpisodeIndex()`: 获取当前索引
- `getEpisodeAt(index)`: 获取指定索引的剧集（不改变索引）

## 优势

### 1. 无缝切换 ✅
- 播放结束时，下一集的 URL 已经准备好
- 不需要等待 API 调用
- 用户体验流畅

### 2. 节省时间 ✅
- 提前 10 秒开始预加载
- API 调用和网络请求在后台完成
- 播放结束时立即切换

### 3. 智能控制 ✅
- 只在短剧模式下生效
- 每个视频只预加载一次
- 避免重复请求和资源浪费

### 4. 容错处理 ✅
- 预加载失败不影响正常播放
- 播放结束时会重试获取
- 有完整的重试机制（3次）

## 配置参数

### 预加载时机
```kotlin
private val preloadThresholdSeconds = 10  // 提前10秒
```

可以根据需要调整：
- **5秒**: 更激进，但可能来不及加载
- **10秒**: 平衡，推荐值
- **15秒**: 保守，但可能浪费流量

### 调整方法
在 `VideoPlayerActivity.kt` 中修改：
```kotlin
private val preloadThresholdSeconds = 15  // 改为15秒
```

## 日志输出

### 预加载触发
```
VideoPlayer: 触发预加载：剩余 9 秒，开始预加载下一集
VideoPlayer: 开始预加载下一集: 第2集, videoId=12346
```

### 预加载成功
```
VideoPlayer: 预加载成功: https://...
Toast: "下一集已准备就绪"
```

### 预加载失败
```
VideoPlayer: 预加载失败：无法获取播放地址
```

### 使用预加载的URL
```
VideoPlayer: 播放结束 onEnded 被调用
VideoPlayer: 使用预加载的URL播放
VideoPlayer: URL已存在，直接播放
```

## 性能影响

### 网络流量
- 每集额外一次 API 请求（约 1KB）
- 不会预加载视频内容，只获取播放地址
- 流量消耗可忽略不计

### CPU 使用
- 后台线程执行，不阻塞主线程
- 对播放性能无影响

### 内存占用
- 只缓存 URL 字符串（几十字节）
- 内存占用可忽略不计

## 兼容性

### 支持场景
- ✅ 短剧自动播放下一集
- ✅ 竖屏全屏模式
- ✅ 横屏模式
- ✅ 网络切换（WiFi/移动数据）

### 不支持场景
- ❌ 普通视频（非短剧）
- ❌ 最后一集（没有下一集）
- ❌ 手动切换集数（会重置预加载）

## 测试方法

### 1. 正常流程测试
1. 播放短剧第1集
2. 等待播放到剩余10秒
3. 观察日志：应该看到"触发预加载"
4. 等待几秒，应该看到"下一集已准备就绪"
5. 播放结束，观察是否无缝切换到第2集

### 2. 预加载失败测试
1. 断开网络
2. 播放到剩余10秒
3. 观察日志：应该看到"预加载失败"
4. 播放结束，应该触发重试机制
5. 重新连接网络，应该能正常播放

### 3. 性能测试
1. 使用 Android Profiler 监控
2. 观察 CPU 使用率（应该无明显增加）
3. 观察内存占用（应该无明显增加）
4. 观察网络请求（应该只有一次额外请求）

## 未来优化

### 1. 可配置化
- 在设置中允许用户调整预加载时机
- 允许用户关闭预加载功能

### 2. 智能预加载
- 根据网络速度动态调整预加载时机
- WiFi 下提前更多时间，移动数据下延后

### 3. 预加载多集
- 在 WiFi 下预加载多集
- 实现真正的离线播放

### 4. 预加载视频内容
- 不仅预加载 URL，还预加载视频片段
- 实现秒开下一集

## 相关文件

- `app/src/main/java/com/tv/ball/presentation/player/VideoPlayerActivity.kt`
  - `checkAndPreloadNextEpisode()`: 检查并触发预加载
  - `preloadNextShortDramaEpisode()`: 执行预加载

- `app/src/main/java/com/tv/ball/presentation/player/ShortDramaManager.kt`
  - `getCurrentEpisodeIndex()`: 获取当前索引
  - `getEpisodeAt()`: 获取指定剧集

## 版本历史

### v1.0 (2024)
- ✅ 实现基础预加载功能
- ✅ 提前10秒预加载下一集
- ✅ 后台线程执行
- ✅ 完整的错误处理
- ✅ 日志输出和用户提示
