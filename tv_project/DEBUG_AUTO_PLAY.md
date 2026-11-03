# 短剧自动播放下一集调试指南 v2

## 问题描述
竖屏全屏播放短剧时，播放完一集后不会自动播放下一集。
**最新发现**: 一直在尝试获取播放地址，但获取不到。

## 最新修复

### 1. 添加重试机制
- 获取播放地址失败时，会重试最多 3 次
- 每次重试间隔 3 秒
- 重试 3 次后仍失败，会自动跳到下一集

### 2. 增强日志输出
- 详细记录 API 请求过程
- 记录响应数据
- 记录 URL 解析过程
- 记录每一步的成功/失败状态

## 关键日志标签

### VideoPlayer - 播放地址获取
查看完整的获取流程：
```
========== 开始获取播放地址 ==========
videoId: xxxxx
请求URL: https://api-v2.cenguigui.cn/api/video/play?video_id=xxxxx
API响应成功
响应数据: {...}
开始解析视频URL
JSON长度: xxx
尝试方法1: 直接匹配 url 字段
方法1找到URL: xxx
方法1成功，返回URL
解析后的URL: xxx
========== 获取播放地址结束 ==========
```

## 调试步骤（重点关注）

### 步骤 1: 确认 videoId 是否正确
1. 播放第一集，查看日志
2. 确认 `ShortDramaManager` 输出的剧集列表中，每一集都有 videoId
3. **关键**: videoId 不能为空

**检查点**:
```
ShortDramaManager: 剧集[0]: name=第1集, videoId=12345, url=
ShortDramaManager: 剧集[1]: name=第2集, videoId=12346, url=
```

### 步骤 2: 观察播放结束后的自动播放流程
1. 播放一集到结束
2. 查看日志，确认以下流程：

**预期日志流程**:
```
VideoPlayer: 播放结束 onEnded 被调用
VideoPlayer: isShortDrama=true, hasNextEpisode=true
VideoPlayer: 准备自动播放短剧下一集
ShortDramaManager: 自动播放下一集: 第2集, videoId=12346, url=
VideoPlayer: 收到下一集回调: 第2集
VideoPlayer: 播放短剧剧集: 第2集, url=, videoId=12346, retryCount=0
VideoPlayer: URL为空，开始获取播放地址: videoId=12346
```

### 步骤 3: 重点检查 API 调用
这是最关键的部分！查看 API 调用的详细日志：

**成功的情况**:
```
========== 开始获取播放地址 ==========
videoId: 12346
请求URL: https://api-v2.cenguigui.cn/api/video/play?video_id=12346
API响应成功
响应数据: {"code":200,"data":{"url":"https://..."}}
开始解析视频URL
JSON长度: 150
尝试方法1: 直接匹配 url 字段
方法1找到URL: https://...
方法1成功，返回URL
解析后的URL: https://...
========== 获取播放地址结束 ==========
获取播放地址成功: https://...
```

**失败的情况 - API 错误**:
```
========== 开始获取播放地址 ==========
videoId: 12346
请求URL: https://api-v2.cenguigui.cn/api/video/play?video_id=12346
API调用失败: Network error / Timeout / 404 / etc.
========== 获取播放地址失败 ==========
获取播放地址失败，retryCount=0
获取播放地址失败，2秒后重试...
```

**失败的情况 - 解析错误**:
```
========== 开始获取播放地址 ==========
videoId: 12346
请求URL: https://api-v2.cenguigui.cn/api/video/play?video_id=12346
API响应成功
响应数据: {"code":400,"message":"视频不存在"}
开始解析视频URL
JSON长度: 50
尝试方法1: 直接匹配 url 字段
方法1未找到匹配
尝试方法2: 从 data 对象中匹配
方法2未找到匹配
所有解析方法都失败，返回空字符串
解析后的URL: 
========== 获取播放地址结束 ==========
获取播放地址失败，retryCount=0
```

## 可能的问题和解决方案

### 问题 1: videoId 为空
**症状**: 
```
ShortDramaManager: 剧集[1]: name=第2集, videoId=, url=
```

**原因**: ShortDramaDetailActivity 中的 episode.videoId 为空

**解决**: 
1. 检查短剧数据源 API
2. 确认 `ShortDramaDetailViewModel.loadDetail()` 是否正确解析了 videoId
3. 检查 `EpisodeItem` 数据类

### 问题 2: API 返回错误
**症状**: 
```
API调用失败: 404 Not Found
或
API调用失败: Network error
```

**原因**: 
- videoId 无效
- API 服务器问题
- 网络问题

**解决**: 
1. 检查 videoId 是否正确
2. 在浏览器中手动测试 API: `https://api-v2.cenguigui.cn/api/video/play?video_id=你的videoId`
3. 检查网络连接

### 问题 3: API 返回成功但无法解析 URL
**症状**: 
```
API响应成功
响应数据: {"code":200,"data":{...}}
所有解析方法都失败，返回空字符串
```

**原因**: API 返回的 JSON 格式与预期不符

**解决**: 
1. 查看完整的响应数据
2. 检查 JSON 结构
3. 可能需要添加新的解析方法

### 问题 4: videoId 格式不对
**症状**: API 返回 400 或其他错误码

**原因**: videoId 的格式可能不是 API 期望的格式

**解决**: 
1. 对比第一集（能播放）和第二集（不能播放）的 videoId 格式
2. 检查是否需要对 videoId 进行转换或编码

## 下一步操作

1. **运行应用，播放一集短剧到结束**
2. **立即打开 Logcat，过滤 `VideoPlayer` 标签**
3. **找到 `========== 开始获取播放地址 ==========` 这一行**
4. **复制从这一行开始到 `========== 获取播放地址结束/失败 ==========` 的所有日志**
5. **把这些日志发给我分析**

## 特别关注

请特别关注以下几点：
1. ✅ videoId 是否存在且不为空
2. ✅ API 请求是否成功（HTTP 状态码）
3. ✅ API 返回的 JSON 数据结构
4. ✅ URL 解析是否成功

如果 API 返回成功但解析失败，请把完整的响应数据发给我，我可以添加新的解析逻辑。
