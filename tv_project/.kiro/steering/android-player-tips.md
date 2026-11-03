---
inclusion: auto
---

# Android 视频播放器开发提示

## 播放器相关的关键信息

### 屏幕方向处理
- 使用 `SCREEN_ORIENTATION_LANDSCAPE` 和 `SCREEN_ORIENTATION_PORTRAIT` 锁定方向
- 不要使用 `SCREEN_ORIENTATION_SENSOR_*` 变体（会自动旋转）
- 横屏时应该锁定横屏，不会因为手势而切换

### 视频解析流程
1. 检测 URL 类型（直接视频 vs 网页）
2. 直接视频 → 立即播放
3. 网页 → 显示解析日志 → 嗅探视频链接 → 播放

### 状态提示
播放器应该显示详细的状态提示：
- "检测到视频资源，准备播放..."
- "检测到网页，正在解析..."
- "正在加载页面..."
- "正在嗅探视频链接..."
- "解析到视频链接，准备播放..."
- "播放器就绪"

### ExoPlayer 请求头
ExoPlayerManager 已经配置了正确的请求头：
- User-Agent
- Referer（根据域名自动设置）
- Accept、Accept-Encoding 等

### 关键类
- `VideoPlayerViewModel` - 播放器逻辑
- `VideoPlayerActivity` - 播放器界面
- `VideoLinkSniffer` - 视频链接嗅探
- `ExoPlayerManager` - ExoPlayer 封装
