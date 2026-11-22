# 快速参考指南

## ⚠️ 重要提醒
**构建只走 GitHub Actions**：改完代码 `git push origin main` 触发 CI，开发版 APK 出在
<https://github.com/303562915/tv/releases/tag/dev>。本地不跑 `gradlew`（详见仓库根 `AGENTS.md`）。

---

## 视频源配置速查

### API类型（JSON接口）
| 源名称 | sourceKey | 列表URL模板 | 详情URL模板 |
|--------|-----------|-------------|-------------|
| 爱奇艺源 | iqiyi | `https://iqiyizyapi.com/api.php/provide/vod/?ac=videolist&t={category}&pg={page}` | `https://iqiyizyapi.com/api.php/provide/vod/?ac=videolist&ids={id}` |
| 360源 | 360zy | `https://360zy5.com/api.php/provide/vod/?ac=videolist&t={category}&pg={page}` | `https://360zy5.com/api.php/provide/vod/?ac=videolist&ids={id}` |
| 量子通道 | lzizy8 | `http://www.lzizy8.com/api.php/provide/vod/?ac=videolist&t={category}&pg={page}` | `http://www.lzizy8.com/api.php/provide/vod/?ac=videolist&ids={id}` |
| 非凡通道 | ffzy5 | `http://ffzy5.tv/api.php/provide/vod/?ac=videolist&t={category}&pg={page}` | `http://ffzy5.tv/api.php/provide/vod/?ac=videolist&ids={id}` |
| 豆瓣通道 | dbzy | `https://dbzy.vip/api.php/provide/vod/?ac=videolist&t={category}&pg={page}` | `https://dbzy.vip/api.php/provide/vod/?ac=videolist&ids={id}` |
| 牛牛通道 | niuniuzy3 | `http://niuniuzy3.com/api.php/provide/vod/?ac=videolist&t={category}&pg={page}` | `http://niuniuzy3.com/api.php/provide/vod/?ac=videolist&ids={id}` |
| 极速通道 | jisuzy | `https://api.jisuzy.com/api.php/provide/vod/?ac=videolist&t={category}&pg={page}` | `https://api.jisuzy.com/api.php/provide/vod/?ac=videolist&ids={id}` |

### 网页类型（HTML解析）
| 源名称 | sourceKey | 列表URL模板 | 详情URL |
|--------|-----------|-------------|---------|
| LiB | libvod | `https://www.libvio.cc/show/{category}-{area}-{year}--{lang}----{page}---{order}.html` | 完整URL |
| LK | lkvod | `https://lkvod.org/show/{category}-{area}-{year}--{lang}----{page}---{order}.html` | 完整URL |
| 可可 | keke | `https://www.keke1.app/show/{category}-{area}-{year}--{lang}--{page}---{order}.html` | 完整URL |

## 分类ID对照表

| 分类名称 | ID |
|----------|-----|
| 电影 | 1 |
| 剧集/电视剧 | 2 |
| 综艺 | 3 |
| 动漫 | 4 |
| 国产剧 | 6 |
| 日韩剧 | 8 |
| 短剧 | 38 |

## 常用代码片段

### 切换视频源
```kotlin
VideoSourceManager.setCurrentSource("iqiyi")
```

### 获取当前视频源
```kotlin
val currentSource = VideoSourceManager.getCurrentSource()
val sourceName = VideoSourceManager.getSourceName(currentSource)
```

### 生成列表URL
```kotlin
val url = VideoSourceManager.getSourceApiUrl("iqiyi", category = 1, page = 1)
```

### 生成详情URL
```kotlin
val detailUrl = VideoSourceManager.getDetailUrl("iqiyi", "123456")
```

### 判断视频源类型
```kotlin
val isApi = VideoSourceManager.isApiSource("iqiyi")  // true
val isWeb = VideoSourceManager.isApiSource("libvod") // false
```

### 解析视频详情
```kotlin
val parser = VideoDetailParserFactory.getParser(sourceKey)
val detail = parser.parse(content, url)
```

## JSON格式示例

### API列表响应
```json
{
  "code": 1,
  "list": [
    {
      "vod_id": 123456,
      "vod_name": "视频标题",
      "vod_pic": "https://example.com/cover.jpg",
      "vod_remarks": "更新至10集"
    }
  ]
}
```

### API详情响应
```json
{
  "code": 1,
  "list": [
    {
      "vod_id": 123456,
      "vod_name": "视频标题",
      "vod_pic": "https://example.com/cover.jpg",
      "vod_content": "视频简介",
      "vod_play_url": "第01集$url1#第02集$url2#第03集$url3"
    }
  ]
}
```

## 正则表达式速查

### API JSON解析
```kotlin
// 提取list数组
""""list":\[(.*?)\]""".toRegex(RegexOption.DOT_MATCHES_ALL)

// 提取视频项
"""\{[^}]*"vod_id":(\d+)[^}]*"vod_name":"([^"]+)"[^}]*"vod_pic":"([^"]+)"[^}]*"vod_remarks":"([^"]*)"[^}]*\}""".toRegex()
```

### HTML网页解析
```kotlin
// 提取标题
"""<h1[^>]*class="[^"]*title[^"]*"[^>]*>([^<]+)</h1>""".toRegex()

// 提取封面
"""<img[^>]*class="[^"]*lazyload[^"]*"[^>]*data-original="([^"]+)"""".toRegex()

// 提取播放链接
"""<a[^>]*href="([^"]+)"[^>]*>([^<]+)</a>""".toRegex()
```

## 调试命令

### 查看日志
```bash
# 查看所有日志
adb logcat

# 过滤特定标签
adb logcat | grep "HomeViewModel"
adb logcat | grep "BannerAdapter"
adb logcat | grep "VideoSourceManager"

# 清除日志
adb logcat -c
```

### 构建与安装
```bash
# 构建：push 到 main 由 CI 完成，本地不跑 gradlew
git push origin main

# 装到设备（用 CI 产物）
adb install -r TVPlayer-dev-<run>.apk

# 卸载
adb uninstall com.tv.ball

# 查看已安装应用
adb shell pm list packages | grep tv.ball
```

### 网络调试
```bash
# 查看网络请求
adb logcat | grep "OkHttp"

# 查看网络状态
adb shell dumpsys connectivity
```

## 文件路径速查

### 核心文件
```
视频源管理: app/src/main/java/com/tv/ball/data/VideoSourceManager.kt
视频源配置: app/src/main/java/com/tv/ball/data/VideoSourceConfig.kt
详情解析器: app/src/main/java/com/tv/ball/data/parser/VideoDetailParser.kt
主页ViewModel: app/src/main/java/com/tv/ball/presentation/home/HomeViewModel.kt
详情Repository: app/src/main/java/com/tv/ball/domain/repository/VideoRepository.kt
```

### 布局文件
```
主Activity: app/src/main/res/layout/activity_main.xml
主页Fragment: app/src/main/res/layout/fragment_home.xml
轮播图Item: app/src/main/res/layout/item_banner.xml
视频源Fragment: app/src/main/res/layout/fragment_video_source.xml
视频源Item: app/src/main/res/layout/item_video_source.xml
```

### 配置文件
```
主题: app/src/main/res/values/themes.xml
底部导航: app/src/main/res/menu/bottom_navigation_menu.xml
依赖: app/build.gradle.kts
权限: app/src/main/AndroidManifest.xml
```

## 常见错误代码

### 网络错误
- `code 200`: 成功
- `code 404`: 未找到
- `code 500`: 服务器错误
- `code -1`: 网络超时或连接失败

### 解析错误
- 空列表: 检查URL和解析逻辑
- 图片不显示: 检查URL格式和网络权限
- 详情加载失败: 检查解析器类型

## 性能优化建议

### 图片加载
```kotlin
Glide.with(context)
    .load(url)
    .placeholder(R.drawable.placeholder)  // 占位图
    .error(R.drawable.error)              // 错误图
    .diskCacheStrategy(DiskCacheStrategy.ALL)  // 缓存策略
    .into(imageView)
```

### 列表优化
```kotlin
// 使用DiffUtil
class VideoDiffCallback : DiffUtil.ItemCallback<VideoItem>() {
    override fun areItemsTheSame(old: VideoItem, new: VideoItem) = 
        old.detailUrl == new.detailUrl
    
    override fun areContentsTheSame(old: VideoItem, new: VideoItem) = 
        old == new
}
```

### 内存优化
```kotlin
// Fragment中清理资源
override fun onDestroyView() {
    super.onDestroyView()
    bannerHandler.removeCallbacksAndMessages(null)
    _binding = null
}
```

## 快速测试

### 测试视频源切换
1. 打开应用
2. 点击中间"项目列表"
3. 选择一个视频源
4. 返回主页
5. 下拉刷新
6. 检查是否显示新源的内容

### 测试详情页
1. 点击任意视频
2. 检查标题、封面、简介
3. 检查播放列表
4. 点击一集测试播放

### 测试轮播图
1. 打开主页
2. 观察轮播图是否自动切换
3. 手动滑动轮播图
4. 点击轮播图测试跳转

## 联系方式

如有问题，请查看：
- 轮播图文档: `.kiro/skills/banner-migration.md`
- 详情解析文档: `.kiro/skills/video-source-detail-parsing.md`
