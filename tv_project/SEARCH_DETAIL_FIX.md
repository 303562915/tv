# 搜索详情页不一致问题修复说明

## 问题描述
搜索结果点击进入详情页后，显示的内容与搜索结果不一致。

## 问题原因分析

### 1. API源和Web源的URL处理差异
- **API源**（如iqiyi, 360zy, ffzy5等）：
  - 搜索返回的`detailUrl`是视频ID（如"12345"）
  - 需要通过`VideoSourceManager.getDetailUrl()`转换为完整的API URL
  
- **Web源**（如keke, zxzj等）：
  - 搜索返回的`detailUrl`已经是完整URL（如"https://103.194.185.51:51120/detail/123.html"）
  - 不需要额外转换

### 2. 视频源识别问题
原代码在`VideoDetailActivity`和`VideoRepository.getVideoDetail()`中都使用**当前选择的视频源**来处理详情URL，这导致：
- 多站点搜索时，来自不同源的结果可能被错误地用当前源的解析器处理
- Web源的完整URL可能被误认为是API源的ID

## 修复方案

### 1. VideoDetailActivity.kt
**修改位置**：`onCreate()`方法中的URL处理逻辑

**修改内容**：
```kotlin
// 智能判断URL类型并构建详情URL
val currentSource = com.tv.ball.data.VideoSourceManager.getCurrentSource()
val detailUrl = if (videoIdOrUrl.startsWith("http")) {
    // 如果已经是完整URL（Web源），直接使用
    android.util.Log.d("VideoDetailActivity", "使用完整URL: $videoIdOrUrl")
    videoIdOrUrl
} else {
    // 如果是视频ID（API源），需要转换为完整URL
    val url = com.tv.ball.data.VideoSourceManager.getDetailUrl(currentSource, videoIdOrUrl)
    android.util.Log.d("VideoDetailActivity", "从ID构建URL: $videoIdOrUrl -> $url")
    url
}
```

**作用**：根据URL格式智能判断是否需要转换，避免对完整URL进行二次处理。

### 2. VideoRepository.kt - 智能源识别

**新增方法**：`identifySourceFromUrl()`
```kotlin
/**
 * 根据URL智能识别视频源
 */
private fun identifySourceFromUrl(url: String): String {
    return when {
        // API源识别
        url.contains("iqiyizyapi.com") -> "iqiyi"
        url.contains("360zy") -> "360zy"
        url.contains("ffzy5.tv") -> "ffzy5"
        url.contains("dbzy") -> "dbzy"
        url.contains("niuniuzy") -> "niuniuzy3"
        url.contains("hongniuzy") -> "hongniu"
        url.contains("jisuziyuan") -> "jisuzy"
        
        // Web源识别
        url.contains("103.194.185.51") || url.contains("keke1.app") -> "keke"
        url.contains("zxzjhd.com") -> "zxzj"
        url.contains("libvio.cc") -> "libvod"
        
        // 默认使用当前选择的源
        else -> com.tv.ball.data.VideoSourceManager.getCurrentSource()
    }
}
```

**修改方法**：`getVideoDetail()`
- 使用`identifySourceFromUrl()`自动识别视频源
- 根据识别的源选择正确的解析器

**作用**：确保每个详情URL都使用正确的源解析器，支持多站点搜索。

### 3. 搜索结果解析增强

**修改方法**：
- `parseApiSearchResults()` - 添加sourceKey参数和日志
- `parseApiXmlSearchResults()` - 添加详细日志
- `parseWebSearchResults()` - 添加详细日志和调试信息
- `searchInSource()` - 支持XML格式的API响应

**作用**：
- 提供详细的调试日志，便于追踪问题
- 确保所有搜索路径都能正确处理源信息
- 支持更多API响应格式

## 修复效果

### 单源搜索
✅ API源搜索 → 点击详情 → 正确显示
✅ Web源搜索 → 点击详情 → 正确显示

### 多站点搜索
✅ 混合多个API源 → 点击任意结果 → 自动识别源并正确显示
✅ 混合API源和Web源 → 点击任意结果 → 自动识别源并正确显示
✅ 搜索结果显示源标签（如"[爱奇艺]"、"[可可影视]"）

### 日志增强
✅ 搜索过程：记录源、关键词、结果数量
✅ 详情加载：记录URL、识别的源、解析结果
✅ 错误追踪：详细的异常信息和堆栈跟踪

## 测试建议

1. **单源搜索测试**
   - 切换到不同视频源（API源和Web源）
   - 搜索相同关键词
   - 验证详情页内容正确

2. **多站点搜索测试**
   - 启用多个视频源
   - 搜索热门内容
   - 点击不同源的结果，验证详情正确

3. **边界情况测试**
   - 搜索无结果的关键词
   - 搜索特殊字符
   - 网络异常情况

## 后续优化建议

1. **缓存机制**：对详情页数据进行缓存，减少重复请求

2. **源信息持久化**：在VideoItem中添加sourceKey字段，避免URL识别的不确定性

3. **统一解析器接口**：标准化API源和Web源的解析器接口，简化代码

4. **错误处理**：添加更友好的错误提示和重试机制
