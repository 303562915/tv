# 视频流程日志系统使用指南

## 功能概述

视频流程日志系统是一个完整的日志记录和查看工具，用于追踪从视频嗅探到播放的整个流程。主要特性：

- ✅ **完整流程追踪**：记录从资源嗅探、链接解析、网络请求到播放器初始化和播放的每一步
- ✅ **可折叠UI**：每个日志会话可以展开/收起，方便查看
- ✅ **长按复制**：长按日志会话可以复制完整日志到剪贴板
- ✅ **多会话管理**：自动保存最近10个会话的日志
- ✅ **详细信息**：每条日志包含时间戳、级别、阶段、消息和详细信息
- ✅ **错误追踪**：自动记录异常堆栈信息

## 文件结构

```
app/src/main/java/com/tv/ball/
├── util/
│   ├── VideoFlowLogger.kt                    # 日志管理器核心类
│   └── VideoFlowLoggerIntegration.kt         # 集成示例和文档
├── presentation/
│   └── debug/
│       └── VideoFlowLogActivity.kt           # 日志查看界面
└── res/
    ├── layout/
    │   ├── activity_video_flow_log.xml       # 主界面布局
    │   ├── item_log_session.xml              # 会话列表项布局
    │   └── item_log_entry.xml                # 日志条目布局
    └── drawable/
        ├── bg_stage_tag.xml                  # 阶段标签背景
        ├── bg_details.xml                    # 详情背景
        └── ic_expand_more.xml                # 展开图标
```

## 使用方法

### 1. 查看日志

在应用的"设置"页面，找到"视频流程日志"选项，点击进入日志查看界面。

### 2. 日志界面操作

- **展开/收起**：点击日志会话的标题区域
- **复制日志**：
  - 方法1：长按整个日志会话卡片
  - 方法2：点击会话右上角的"复制"按钮
- **清除日志**：点击顶部的"清除所有日志"按钮

### 3. 在代码中集成日志

#### 3.1 开始新会话

在播放器初始化时：

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    val videoTitle = intent.getStringExtra("title") ?: "未知视频"
    val videoUrl = intent.getStringExtra("url") ?: ""
    
    // 开始新的日志会话
    VideoFlowLogger.startSession(videoTitle, videoUrl)
    VideoFlowLogger.info(
        VideoFlowLogger.FlowStage.INIT,
        "初始化播放器",
        "视频标题: $videoTitle\n视频URL: $videoUrl"
    )
}
```

#### 3.2 记录日志

使用不同级别的日志方法：

```kotlin
// DEBUG - 调试信息
VideoFlowLogger.debug(
    VideoFlowLogger.FlowStage.NETWORK,
    "发起网络请求",
    "URL: $url"
)

// INFO - 一般信息
VideoFlowLogger.info(
    VideoFlowLogger.FlowStage.PARSE,
    "开始解析视频链接",
    "Parser: M3u8Parser"
)

// SUCCESS - 成功操作
VideoFlowLogger.success(
    VideoFlowLogger.FlowStage.SNIFF,
    "嗅探到视频资源",
    "URL: $url\nType: m3u8"
)

// WARNING - 警告信息
VideoFlowLogger.warning(
    VideoFlowLogger.FlowStage.PLAYER_LOAD,
    "缓冲速度较慢",
    "Speed: 100KB/s"
)

// ERROR - 错误信息
VideoFlowLogger.error(
    VideoFlowLogger.FlowStage.PLAYER_ERROR,
    "播放失败",
    "Error code: 404",
    exception
)
```

#### 3.3 结束会话

在播放器销毁时：

```kotlin
override fun onDestroy() {
    VideoFlowLogger.info(VideoFlowLogger.FlowStage.OTHER, "播放器销毁")
    VideoFlowLogger.endSession()
    super.onDestroy()
}
```

## 日志阶段说明

| 阶段 | 说明 | 使用场景 |
|------|------|----------|
| INIT | 初始化 | 播放器、组件初始化 |
| SNIFF | 资源嗅探 | WebView嗅探视频资源 |
| PARSE | 链接解析 | 解析视频播放链接 |
| NETWORK | 网络请求 | HTTP请求、下载 |
| PLAYER_INIT | 播放器初始化 | ExoPlayer准备 |
| PLAYER_LOAD | 加载视频 | 缓冲、加载 |
| PLAYER_PLAY | 开始播放 | 播放、暂停、停止 |
| PLAYER_ERROR | 播放错误 | 播放异常 |
| DOWNLOAD | 下载 | 视频下载 |
| OTHER | 其他 | 其他操作 |

## 集成示例

### 在资源嗅探中集成

```kotlin
// ResourceSnifferWebViewClient.kt
override fun shouldInterceptRequest(
    view: WebView?,
    request: WebResourceRequest?
): WebResourceResponse? {
    val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
    
    if (isVideoResource(url)) {
        VideoFlowLogger.success(
            VideoFlowLogger.FlowStage.SNIFF,
            "嗅探到视频资源",
            "URL: $url\nType: ${getResourceType(url)}"
        )
        addResource(url)
    }
    
    return super.shouldInterceptRequest(view, request)
}
```

### 在视频解析中集成

```kotlin
// BaseParser.kt
suspend fun parse(url: String): ParseResult {
    VideoFlowLogger.info(
        VideoFlowLogger.FlowStage.PARSE,
        "开始解析视频链接",
        "Parser: ${this::class.simpleName}\nURL: $url"
    )
    
    try {
        val result = parseInternal(url)
        VideoFlowLogger.success(
            VideoFlowLogger.FlowStage.PARSE,
            "解析成功",
            "找到 ${result.urls.size} 个播放链接"
        )
        return result
    } catch (e: Exception) {
        VideoFlowLogger.error(
            VideoFlowLogger.FlowStage.PARSE,
            "解析失败",
            "Parser: ${this::class.simpleName}",
            e
        )
        throw e
    }
}
```

### 在播放器中集成

```kotlin
// ExoPlayerManager.kt
player.addListener(object : Player.Listener {
    override fun onPlaybackStateChanged(state: Int) {
        when (state) {
            Player.STATE_BUFFERING -> {
                VideoFlowLogger.debug(
                    VideoFlowLogger.FlowStage.PLAYER_LOAD,
                    "正在缓冲"
                )
            }
            Player.STATE_READY -> {
                VideoFlowLogger.success(
                    VideoFlowLogger.FlowStage.PLAYER_LOAD,
                    "缓冲完成，准备播放"
                )
            }
        }
    }
    
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            VideoFlowLogger.success(
                VideoFlowLogger.FlowStage.PLAYER_PLAY,
                "开始播放"
            )
        }
    }
    
    override fun onPlayerError(error: PlaybackException) {
        VideoFlowLogger.error(
            VideoFlowLogger.FlowStage.PLAYER_ERROR,
            "播放错误",
            "Error code: ${error.errorCode}",
            error
        )
    }
})
```

## 日志格式

复制的日志格式如下：

```
==================================================
视频流程日志
==================================================
会话ID: 550e8400-e29b-41d4-a716-446655440000
开始时间: 2024-01-15 14:30:25
视频标题: 示例视频
视频URL: https://example.com/video.m3u8
==================================================

[14:30:25.123] [INFO] [初始化]
初始化播放器
详情: 视频标题: 示例视频
视频URL: https://example.com/video.m3u8
--------------------------------------------------
[14:30:25.456] [SUCCESS] [资源嗅探]
嗅探到视频资源
详情: URL: https://example.com/video.m3u8
Type: m3u8
--------------------------------------------------
[14:30:26.789] [SUCCESS] [链接解析]
解析成功
详情: 找到 1 个播放链接
--------------------------------------------------
```

## 注意事项

1. **性能影响**：日志记录会有轻微的性能开销，但已经过优化
2. **内存管理**：系统自动保留最近10个会话，旧会话会被自动清除
3. **隐私保护**：日志可能包含URL等敏感信息，分享时请注意
4. **Logcat同步**：所有日志同时输出到Android Logcat，标签为"VideoFlowLogger"

## 故障排查

### 问题：日志界面为空

**解决方案**：
1. 确保已经播放过视频（日志会话在播放器初始化时创建）
2. 检查是否调用了 `VideoFlowLogger.startSession()`

### 问题：复制功能不工作

**解决方案**：
1. 确保应用有剪贴板权限
2. 尝试使用"复制"按钮而不是长按

### 问题：日志不完整

**解决方案**：
1. 检查是否在关键流程中都添加了日志记录
2. 查看 `VideoFlowLoggerIntegration.kt` 中的集成示例
3. 确保在播放器销毁时调用了 `VideoFlowLogger.endSession()`

## 未来改进

- [ ] 支持导出日志到文件
- [ ] 支持日志搜索和过滤
- [ ] 支持日志统计和分析
- [ ] 支持自定义日志级别和阶段
- [ ] 支持日志上传到服务器

## 技术支持

如有问题或建议，请通过以下方式联系：
- 在应用内通过"官网"反馈
- 复制日志并发送给开发者
