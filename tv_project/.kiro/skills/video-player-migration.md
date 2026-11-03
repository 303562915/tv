# 视频播放器功能迁移计划

## Lua播放器功能清单

### 1. 手势控制 ✅
- 单击：显示/隐藏控制栏
- 双击：播放/暂停
- 长按：加速播放
- 左右滑动：调整进度
- 上下滑动（左侧）：调整亮度
- 上下滑动（右侧）：调整音量

### 2. 播放控制
- 播放/暂停按钮
- 进度条拖动
- 全屏/竖屏切换
- 下一集播放
- 倍速播放（0.25x - 5x）
- 屏幕锁定/解锁
- 截屏功能

### 3. 视频信息显示
- 当前时间/总时长
- 缓冲进度
- 网络状态（WiFi/数据）
- 电池状态和电量
- 视频清晰度（根据分辨率）
- 丢帧信息
- 加载带宽

### 4. 播放列表支持
- 支持多集播放
- 选集功能
- 自动播放下一集
- 记忆播放位置

### 5. 视频画面调整
- 自适应缩放
- 适应放大裁剪
- 铺满屏幕
- 原始尺寸

### 6. 菜单功能
- 字幕切换
- 重复播放模式
- 视频信息
- 调用系统播放器
- 调试模式

### 7. 其他功能
- 投屏
- 分享视频
- 视频嗅探解析
- 字幕显示
- 弹幕（预留接口）

## 迁移优先级

### P0 - 核心播放功能（当前）
- [x] 基础播放器初始化
- [x] 播放/暂停
- [x] 进度条显示
- [ ] 播放列表支持
- [ ] 记忆播放位置

### P1 - 基础交互
- [ ] 手势控制（进度/亮度/音量）
- [ ] 控制栏自动隐藏
- [ ] 全屏切换
- [ ] 倍速播放
- [ ] 屏幕锁定

### P2 - 增强功能
- [ ] 视频画面比例调整
- [ ] 清晰度显示
- [ ] 网络状态显示
- [ ] 电池状态显示
- [ ] 下一集播放

### P3 - 高级功能
- [ ] 视频嗅探解析
- [ ] 字幕支持
- [ ] 投屏功能
- [ ] 分享功能
- [ ] 截屏功能

## 实现步骤

### 第一步：完善播放器布局
1. 添加顶部工具栏（返回、标题、投屏、分享、菜单）
2. 添加底部控制栏（播放、进度、选集、倍速、全屏）
3. 添加左侧控制栏（锁定、截屏）
4. 添加中央提示区域（加载、手势反馈）

### 第二步：实现手势控制
1. 创建GestureDetector
2. 实现单击、双击、长按
3. 实现滑动手势（进度/亮度/音量）
4. 添加手势反馈UI

### 第三步：实现播放列表
1. 支持传入播放列表数据
2. 实现选集对话框
3. 实现下一集自动播放
4. 记忆播放位置

### 第四步：实现倍速和画面调整
1. 倍速播放对话框
2. 画面比例调整对话框
3. 全屏切换逻辑

### 第五步：实现状态显示
1. 网络状态监听
2. 电池状态监听
3. 视频信息显示
4. 清晰度计算

## 关键代码对照

### Lua -> Kotlin 映射

#### 播放器初始化
```lua
-- Lua
local player = SimpleExoPlayer.Builder(activity.getApplicationContext())
  .setTrackSelector(App.values.trackSelector)
  .build()
```

```kotlin
// Kotlin
val player = ExoPlayer.Builder(context)
  .setTrackSelector(trackSelector)
  .build()
```

#### 手势监听
```lua
-- Lua
gesture.setOnGestureListener(GestureView.OnGestureListener{
  onClick = function() ... end,
  onDoubleClick = function() ... end,
  onProgressChange = function(progress, duration, isFinish) ... end
})
```

```kotlin
// Kotlin
gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
  override fun onSingleTapConfirmed(e: MotionEvent): Boolean { ... }
  override fun onDoubleTap(e: MotionEvent): Boolean { ... }
  override fun onScroll(...): Boolean { ... }
})
```

#### 播放列表
```lua
-- Lua
local mediaSourceList = {}
for _, media in ipairs(App.temp.playlist) do
  local uri = Uri.parse(media.video)
  local singleMediaSource = ...
  mediaSourceList[#mediaSourceList+1] = singleMediaSource
end
App.values.mediaSource = ConcatenatingMediaSource(mediaSourceList)
```

```kotlin
// Kotlin
val mediaItems = playlist.map { episode ->
  MediaItem.fromUri(episode.url)
}
player.setMediaItems(mediaItems)
```

## 注意事项

1. ExoPlayer版本差异：Lua使用的是旧版本的ExoPlayer2，Kotlin使用新版本
2. 手势库：Lua使用自定义GestureView，Kotlin需要实现GestureDetector
3. 布局方式：Lua使用表格布局，Kotlin使用XML
4. 异步处理：Lua使用task/Handler，Kotlin使用Coroutines
5. 权限处理：需要添加系统设置权限（亮度调节）

## 当前状态

- 播放器基础框架：✅ 完成
- 播放器布局：⚠️ 简化版
- 手势控制：❌ 未实现
- 播放列表：❌ 未实现
- 状态显示：❌ 未实现
