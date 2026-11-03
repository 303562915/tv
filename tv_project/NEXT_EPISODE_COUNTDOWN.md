# 下一集倒计时提示功能

## 功能概述

在短剧播放到最后 **5 秒**时，右下角会显示一个小按钮提示"X秒后播放下一集"（类似"跳过片头"按钮），用户可以点击立即跳转，或等待倒计时结束自动播放。

## 效果展示

```
┌─────────────────────────────────┐
│                                 │
│                                 │
│                                 │
│                                 │
│                                 │
│                                 │
│                    ┌──────────┐ │
│                    │ 5秒后播放 │ │ ← 右下角小按钮
│                    │  下一集   │ │   (可点击)
│                    └──────────┘ │
└─────────────────────────────────┘
```

## 工作流程

### 1. 触发时机
```
播放中...
    ↓
剩余 10 秒 → 开始预加载下一集 URL
    ↓
剩余 5 秒 → 右下角显示小按钮 ⏰
    ↓
按钮文字: "5秒后播放下一集"
         "4秒后播放下一集"
         "3秒后播放下一集"
         "2秒后播放下一集"
         "1秒后播放下一集"
    ↓
倒计时结束 或 用户点击
    ↓
立即播放下一集 ✅
```

### 2. 用户交互

#### 场景 A: 等待自动播放
1. 用户继续观看
2. 按钮文字从"5秒"递减到"1秒"
3. 自动播放下一集

#### 场景 B: 点击立即播放
1. 用户点击按钮
2. 立即停止当前播放
3. 立即播放下一集（无需等待）

## 技术实现

### 1. 布局文件
**文件**: `app/src/main/res/layout/activity_video_player.xml`

```xml
<!-- 即将播放下一集提示（小按钮样式，类似跳过片头） -->
<Button
    android:id="@+id/nextEpisodeHintButton"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_gravity="end|bottom"
    android:layout_marginEnd="16dp"
    android:layout_marginBottom="120dp"
    android:background="@drawable/gesture_hint_background"
    android:text="5秒后播放下一集"
    android:textColor="@android:color/white"
    android:textSize="14sp"
    android:padding="12dp"
    android:visibility="gone" />
```

**特点**:
- ✅ 与"跳过片头"按钮样式一致
- ✅ 位置相同（右下角）
- ✅ 半透明背景
- ✅ 小巧不遮挡内容

### 2. 核心变量
**文件**: `VideoPlayerActivity.kt`

```kotlin
// 下一集倒计时相关
private var nextEpisodeCountdown = 5              // 倒计时秒数
private var isShowingNextEpisodeHint = false      // 是否正在显示提示
private val countdownHandler = Handler(Looper.getMainLooper())
private var countdownRunnable: Runnable? = null
```

### 3. 核心方法

#### showNextEpisodeHint()
显示倒计时按钮
```kotlin
private fun showNextEpisodeHint() {
    isShowingNextEpisodeHint = true
    nextEpisodeCountdown = 5
    
    // 显示按钮
    binding.nextEpisodeHintButton.visibility = View.VISIBLE
    binding.nextEpisodeHintButton.text = "${nextEpisodeCountdown}秒后播放下一集"
    
    // 设置点击事件
    binding.nextEpisodeHintButton.setOnClickListener {
        playNextEpisodeImmediately()
    }
    
    // 开始倒计时
    startNextEpisodeCountdown()
}
```

#### startNextEpisodeCountdown()
开始倒计时（更新按钮文字）
```kotlin
private fun startNextEpisodeCountdown() {
    countdownRunnable = object : Runnable {
        override fun run() {
            if (nextEpisodeCountdown > 0) {
                // 更新按钮文字
                binding.nextEpisodeHintButton.text = "${nextEpisodeCountdown}秒后播放下一集"
                nextEpisodeCountdown--
                countdownHandler.postDelayed(this, 1000)  // 1秒后再次执行
            } else {
                // 倒计时结束，隐藏按钮
                hideNextEpisodeHint()
            }
        }
    }
    countdownHandler.post(countdownRunnable!!)
}
```

## 时间线

```
时间轴:  0s ────────────────────────────────────────────────────────────> 视频结束
         │                                                              │
         │                                                              │
剩余10秒: │                                    ▼ 开始预加载              │
         │                                    (后台获取URL)             │
         │                                                              │
剩余5秒:  │                                              ▼ 显示按钮      │
         │                                              "5秒后..."     │
         │                                              "4秒后..."     │
         │                                              "3秒后..."     │
         │                                              "2秒后..."     │
         │                                              "1秒后..."     │
结束:     │                                                            ▼ 自动播放
```

## 优势

### 1. 用户体验 ✅
- **不遮挡内容**: 小按钮在右下角，不影响观看
- **熟悉的位置**: 与"跳过片头"相同位置，用户习惯
- **清晰提示**: 倒计时秒数实时更新
- **可控性**: 用户可以选择立即播放或等待

### 2. 视觉效果 ✅
- **统一风格**: 与"跳过片头"按钮样式一致
- **半透明背景**: 不突兀
- **动态文字**: 实时显示剩余秒数
- **小巧精致**: 不占用太多空间

### 3. 交互设计 ✅
- **点击整个按钮**: 易于操作
- **即时响应**: 点击后立即播放
- **自动隐藏**: 倒计时结束自动消失

## 配置参数

### 倒计时时长
```kotlin
private var nextEpisodeCountdown = 5  // 默认5秒
```

可以调整为：
- **3秒**: 更快
- **5秒**: 平衡，推荐值 ✅
- **10秒**: 更长

### 触发时机
```kotlin
if (remainingSeconds <= 5 && remainingSeconds > 0) {
    showNextEpisodeHint()
}
```

### 按钮位置
```xml
android:layout_gravity="end|bottom"
android:layout_marginEnd="16dp"
android:layout_marginBottom="120dp"
```

可以调整为：
- `start|bottom`: 左下角
- `center|bottom`: 底部居中
- 调整 `marginBottom` 改变高度

## 样式定制

### 修改按钮文字大小
```xml
<Button
    android:textSize="14sp"  <!-- 改为 16sp 更大 -->
    ... />
```

### 修改按钮颜色
```xml
<Button
    android:textColor="@android:color/white"  <!-- 改为其他颜色 -->
    ... />
```

### 修改背景透明度
编辑 `@drawable/gesture_hint_background`

## 兼容性

### 支持场景
- ✅ 短剧自动播放
- ✅ 竖屏全屏模式
- ✅ 横屏模式
- ✅ 所有屏幕尺寸

### 不支持场景
- ❌ 普通视频（非短剧）
- ❌ 最后一集（没有下一集）
- ❌ 手动暂停时

## 测试方法

### 1. 正常流程测试
1. 播放短剧第1集
2. 快进到最后10秒
3. 观察：剩余5秒时右下角应显示按钮
4. 观察：按钮文字从"5秒"递减到"1秒"
5. 等待倒计时结束
6. 验证：自动播放第2集

### 2. 点击测试
1. 播放到最后5秒
2. 按钮显示后，立即点击
3. 验证：立即播放下一集（不等待倒计时）

### 3. 边界测试
1. 播放最后一集
2. 验证：不显示按钮（因为没有下一集）

## 日志输出

### 显示按钮
```
VideoPlayer: 触发预加载：剩余 9 秒，开始预加载下一集
VideoPlayer: 显示下一集倒计时提示
```

### 用户点击
```
VideoPlayer: 用户点击立即播放下一集
VideoPlayer: 立即播放下一集
```

### 自动播放
```
VideoPlayer: 倒计时结束
VideoPlayer: 播放结束 onEnded 被调用
```

## 与"跳过片头"的对比

| 特性 | 跳过片头 | 播放下一集 |
|------|---------|-----------|
| 位置 | 右下角 | 右下角 ✅ |
| 样式 | 小按钮 | 小按钮 ✅ |
| 背景 | 半透明 | 半透明 ✅ |
| 触发 | 片头时段 | 最后5秒 |
| 功能 | 跳过片头 | 播放下一集 |
| 倒计时 | 无 | 有 ✅ |

## 相关文件

- `app/src/main/res/layout/activity_video_player.xml`
  - `nextEpisodeHintButton`: 倒计时按钮

- `app/src/main/java/com/tv/ball/presentation/player/VideoPlayerActivity.kt`
  - `showNextEpisodeHint()`: 显示按钮
  - `startNextEpisodeCountdown()`: 开始倒计时
  - `playNextEpisodeImmediately()`: 立即播放
  - `hideNextEpisodeHint()`: 隐藏按钮

## 版本历史

### v1.0 (2024)
- ✅ 实现小按钮样式（类似跳过片头）
- ✅ 5秒倒计时
- ✅ 点击立即播放
- ✅ 自动播放下一集
- ✅ 完整的生命周期管理

## 工作流程

### 1. 触发时机
```
播放中...
    ↓
剩余 10 秒 → 开始预加载下一集 URL
    ↓
剩余 5 秒 → 显示倒计时提示 ⏰
    ↓
倒计时: 5 → 4 → 3 → 2 → 1
    ↓
倒计时结束 或 用户点击
    ↓
立即播放下一集 ✅
```

### 2. 用户交互

#### 场景 A: 等待自动播放
1. 用户继续观看
2. 倒计时从 5 秒递减到 0
3. 自动播放下一集

#### 场景 B: 点击立即播放
1. 用户点击提示框
2. 立即停止当前播放
3. 立即播放下一集（无需等待）

## 技术实现

### 1. 布局文件
**文件**: `app/src/main/res/layout/activity_video_player.xml`

```xml
<LinearLayout
    android:id="@+id/nextEpisodeHintLayout"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_gravity="center"
    android:background="@drawable/gesture_hint_background"
    android:orientation="vertical"
    android:padding="20dp"
    android:visibility="gone">
    
    <!-- 倒计时数字 -->
    <TextView
        android:id="@+id/nextEpisodeCountdown"
        android:text="5"
        android:textSize="48sp"
        android:textStyle="bold" />
    
    <!-- 提示文字 -->
    <TextView
        android:text="秒后自动播放下一集"
        android:textSize="16sp" />
    
    <!-- 下一集名称 -->
    <TextView
        android:id="@+id/nextEpisodeName"
        android:text="第2集"
        android:textSize="14sp" />
    
    <!-- 点击提示 -->
    <TextView
        android:text="点击立即播放 →"
        android:textSize="14sp"
        android:textStyle="bold" />
</LinearLayout>
```

### 2. 核心变量
**文件**: `VideoPlayerActivity.kt`

```kotlin
// 下一集倒计时相关
private var nextEpisodeCountdown = 5              // 倒计时秒数
private var isShowingNextEpisodeHint = false      // 是否正在显示提示
private val countdownHandler = Handler(Looper.getMainLooper())
private var countdownRunnable: Runnable? = null
```

### 3. 核心方法

#### showNextEpisodeHint()
显示倒计时提示框
```kotlin
private fun showNextEpisodeHint() {
    isShowingNextEpisodeHint = true
    nextEpisodeCountdown = 5
    
    // 获取下一集信息
    val nextEpisode = shortDramaManager.getEpisodeAt(nextIndex)
    
    // 显示提示
    binding.nextEpisodeHintLayout.visibility = View.VISIBLE
    binding.nextEpisodeName.text = nextEpisode.name
    
    // 设置点击事件
    binding.nextEpisodeHintLayout.setOnClickListener {
        playNextEpisodeImmediately()
    }
    
    // 开始倒计时
    startNextEpisodeCountdown()
}
```

#### startNextEpisodeCountdown()
开始倒计时
```kotlin
private fun startNextEpisodeCountdown() {
    countdownRunnable = object : Runnable {
        override fun run() {
            if (nextEpisodeCountdown > 0) {
                // 更新倒计时数字
                binding.nextEpisodeCountdown.text = nextEpisodeCountdown.toString()
                nextEpisodeCountdown--
                countdownHandler.postDelayed(this, 1000)  // 1秒后再次执行
            } else {
                // 倒计时结束，隐藏提示
                hideNextEpisodeHint()
                // onEnded 会自动播放下一集
            }
        }
    }
    countdownHandler.post(countdownRunnable!!)
}
```

#### playNextEpisodeImmediately()
立即播放下一集
```kotlin
private fun playNextEpisodeImmediately() {
    // 停止当前播放
    exoPlayerManager.pause()
    
    // 取消倒计时
    cancelNextEpisodeCountdown()
    
    // 触发播放下一集
    shortDramaManager.onPlaybackEnded { episode ->
        playShortDramaEpisode(episode)
    }
}
```

## 时间线

```
时间轴:  0s ────────────────────────────────────────────────────────────> 视频结束
         │                                                              │
         │                                                              │
剩余10秒: │                                    ▼ 开始预加载              │
         │                                    (后台获取URL)             │
         │                                                              │
剩余5秒:  │                                              ▼ 显示倒计时    │
         │                                              (5→4→3→2→1)    │
         │                                                              │
结束:     │                                                            ▼ 自动播放
```

## 优势

### 1. 用户体验 ✅
- **提前告知**: 用户知道即将播放下一集
- **可控性**: 用户可以选择立即播放或等待
- **无缝切换**: 预加载 + 倒计时 = 完美体验

### 2. 视觉效果 ✅
- **居中显示**: 不遮挡重要内容
- **半透明背景**: 保持视觉连贯性
- **大号数字**: 清晰易读
- **动态倒计时**: 实时反馈

### 3. 交互设计 ✅
- **点击整个区域**: 大触摸区域，易于操作
- **明确提示**: "点击立即播放 →" 引导用户
- **即时响应**: 点击后立即播放

## 配置参数

### 倒计时时长
```kotlin
private var nextEpisodeCountdown = 5  // 默认5秒
```

可以调整为：
- **3秒**: 更快，但可能太急促
- **5秒**: 平衡，推荐值 ✅
- **10秒**: 更长，但可能影响观看

### 触发时机
```kotlin
if (remainingSeconds <= 5 && remainingSeconds > 0) {
    showNextEpisodeHint()
}
```

可以调整为：
- `<= 3`: 更晚显示
- `<= 5`: 推荐值 ✅
- `<= 10`: 更早显示

## 样式定制

### 修改倒计时数字大小
```xml
<TextView
    android:id="@+id/nextEpisodeCountdown"
    android:textSize="48sp"  <!-- 改为 60sp 更大 -->
    android:textStyle="bold" />
```

### 修改背景透明度
编辑 `@drawable/gesture_hint_background`：
```xml
<solid android:color="#CC000000" />  <!-- CC = 80% 不透明 -->
```

### 修改文字颜色
```xml
<TextView
    android:textColor="#4CAF50"  <!-- 绿色 -->
    android:text="点击立即播放 →" />
```

## 兼容性

### 支持场景
- ✅ 短剧自动播放
- ✅ 竖屏全屏模式
- ✅ 横屏模式
- ✅ 所有屏幕尺寸

### 不支持场景
- ❌ 普通视频（非短剧）
- ❌ 最后一集（没有下一集）
- ❌ 手动暂停时

## 测试方法

### 1. 正常流程测试
1. 播放短剧第1集
2. 快进到最后10秒
3. 观察：剩余5秒时应显示倒计时
4. 等待倒计时结束
5. 验证：自动播放第2集

### 2. 点击测试
1. 播放到最后5秒
2. 倒计时显示后，立即点击提示框
3. 验证：立即播放下一集（不等待倒计时）

### 3. 边界测试
1. 播放最后一集
2. 验证：不显示倒计时（因为没有下一集）

### 4. 中断测试
1. 倒计时显示时，按返回键退出
2. 验证：倒计时被正确清理，无内存泄漏

## 日志输出

### 显示倒计时
```
VideoPlayer: 触发预加载：剩余 9 秒，开始预加载下一集
VideoPlayer: 显示下一集倒计时提示
```

### 用户点击
```
VideoPlayer: 用户点击立即播放下一集
VideoPlayer: 立即播放下一集
VideoPlayer: 收到下一集回调: 第2集
```

### 自动播放
```
VideoPlayer: 倒计时结束
VideoPlayer: 播放结束 onEnded 被调用
VideoPlayer: 准备自动播放短剧下一集
```

## 未来优化

### 1. 动画效果
- 倒计时数字缩放动画
- 提示框淡入淡出
- 进度环形动画

### 2. 个性化设置
- 允许用户调整倒计时时长
- 允许用户关闭倒计时提示
- 允许用户自定义提示位置

### 3. 智能提示
- 根据用户习惯调整提示时机
- 学习用户是否喜欢点击跳过
- 自适应倒计时时长

### 4. 更多信息
- 显示下一集封面图
- 显示下一集简介
- 显示剧集进度（第2集/共100集）

## 相关文件

- `app/src/main/res/layout/activity_video_player.xml`
  - 倒计时提示布局

- `app/src/main/java/com/tv/ball/presentation/player/VideoPlayerActivity.kt`
  - `showNextEpisodeHint()`: 显示提示
  - `startNextEpisodeCountdown()`: 开始倒计时
  - `playNextEpisodeImmediately()`: 立即播放
  - `hideNextEpisodeHint()`: 隐藏提示

## 版本历史

### v1.0 (2024)
- ✅ 实现基础倒计时功能
- ✅ 5秒倒计时
- ✅ 点击立即播放
- ✅ 自动播放下一集
- ✅ 完整的生命周期管理
