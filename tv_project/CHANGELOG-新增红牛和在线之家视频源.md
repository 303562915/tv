# 新增红牛资源和在线之家视频源 - 更新日志

## 版本信息
- **更新日期**: 2026-04-15
- **功能**: 添加红牛资源（API）和在线之家（WEB）两个新视频源
- **状态**: ✅ 已完成

## 新增视频源

### 1. 红牛资源 (hongniu)
- **类型**: API（JSON接口）
- **网站**: https://www.hongniuzy2.com/
- **接口格式**: 标准MacCMS API
- **特点**: 
  - 数据量大（89315+条视频）
  - 更新及时
  - 支持多种分类
  - JSON格式，解析稳定

**API示例**:
```
列表: https://www.hongniuzy2.com/api.php/provide/vod/?ac=videolist&t={category}&pg={page}
搜索: https://www.hongniuzy2.com/api.php/provide/vod/?ac=videolist&wd={keyword}
详情: https://www.hongniuzy2.com/api.php/provide/vod/?ac=videolist&ids={id}
```

**分类映射**:
- 1 = 电影
- 2 = 连续剧
- 3 = 综艺
- 4 = 动漫
- 12 = 国产剧
- 18 = 韩剧

### 2. 在线之家 (zxzj)
- **类型**: WEB（HTML网页）
- **网站**: https://www.zxzjhd.com/
- **解析方式**: HTML正则表达式
- **特点**:
  - 简洁的HTML结构
  - 播放链接格式清晰
  - 类似libvod的结构

**URL格式**:
```
列表: https://www.zxzjhd.com/list/{category}-{page}.html
详情: https://www.zxzjhd.com/detail/{id}.html
播放: https://www.zxzjhd.com/video/{id}-{source}-{episode}.html
搜索: https://www.zxzjhd.com/search.html?wd={keyword}
```

**分类映射**:
- 1 = 电影
- 2 = 剧集
- 3 = 综艺
- 4 = 动漫

## 修改的文件

### 1. VideoSourceConfig.kt
**修改内容**: 添加两个新视频源配置

```kotlin
// 新增红牛资源（API类型）
VideoSourceConfig("hongniu", "红牛资源", SourceType.API, 
    "https://www.hongniuzy2.com/api.php/provide/vod/?ac=videolist&t={category}&pg={page}")

// 新增在线之家（WEB类型）
VideoSourceConfig("zxzj", "在线之家", SourceType.WEB, 
    "https://www.zxzjhd.com/list/{category}-{page}.html")
```

### 2. VideoSourceManager.kt
**修改内容**: 
1. 添加分类映射
2. 添加URL构建逻辑
3. 添加搜索URL
4. 添加详情URL

```kotlin
// 分类映射
"hongniu" to mapOf(
    1 to "1",    // 电影
    2 to "2",    // 连续剧
    3 to "3",    // 综艺
    4 to "4",    // 动漫
    6 to "12",   // 国产剧
    8 to "18"    // 韩剧
),
"zxzj" to mapOf(
    1 to "1",    // 电影
    2 to "2",    // 剧集
    3 to "3",    // 综艺
    4 to "4",    // 动漫
    6 to "2",    // 国产剧 -> 剧集
    8 to "2"     // 日韩剧 -> 剧集
)

// URL构建
"zxzj" -> {
    config.baseUrl
        .replace("{category}", mappedCategory)
        .replace("{page}", page.toString())
}

// 搜索URL
"hongniu" -> "https://www.hongniuzy2.com/api.php/provide/vod/?ac=videolist&wd=$keyword"
"zxzj" -> "https://www.zxzjhd.com/search.html?wd=$keyword"

// 详情URL
"hongniu" -> "https://www.hongniuzy2.com/api.php/provide/vod/?ac=videolist&ids=$videoId"
```

### 3. VideoSourceFragment.kt
**修改内容**: 在视频源列表中添加两个新源

```kotlin
VideoSource("红牛资源api", "https://www.hongniuzy2.com/favicon.ico", "hongniu"),
VideoSource("在线之家w", "https://www.zxzjhd.com/favicon.ico", "zxzj"),
```

### 4. HomeViewModel.kt
**修改内容**: 在parseWebHtml方法中添加在线之家的HTML解析逻辑

```kotlin
"zxzj" -> {
    val baseUrl = "https://www.zxzjhd.com"
    
    // 解析模式1: h4标题
    val pattern1 = """<a[^>]*href="(/detail/\d+\.html)"[^>]*>.*?<h4>([^<]+)</h4>""".toRegex(RegexOption.DOT_MATCHES_ALL)
    
    // 解析模式2: title属性
    val pattern2 = """<a[^>]*href="(/detail/\d+\.html)"[^>]*title="([^"]+)"""".toRegex()
    
    // 尝试两种模式解析
    // ...
}
```

### 5. VideoDetailParser.kt
**修改内容**: 
1. 添加在线之家到WebDetailParser
2. 添加parseZxzjEpisodes方法

```kotlin
// 在getParser中添加
"zxzj" -> WebDetailParser()

// 在parse方法中添加判断
val isZxzj = sourceUrl.contains("zxzjhd.com")

// 添加新的解析方法
private fun parseZxzjEpisodes(html: String, baseUrl: String): List<Episode> {
    // 模式1: /video/格式
    val pattern1 = """<a[^>]*href="(/video/\d+-\d+-\d+\.html)"[^>]*>([^<]+)</a>""".toRegex()
    
    // 模式2: /play/格式（备用）
    val pattern2 = """<a[^>]*href="(/play/[^"]+)"[^>]*>([^<]+)</a>""".toRegex()
    
    // ...
}
```

### 6. VideoRepository.kt
**修改内容**: 在parseWebSearchResults方法中添加在线之家的搜索结果解析

```kotlin
"zxzj" -> {
    val baseUrl = "https://www.zxzjhd.com"
    val pattern = """<a[^>]*href="(/detail/\d+\.html)"[^>]*>.*?<h4>([^<]+)</h4>""".toRegex(RegexOption.DOT_MATCHES_ALL)
    
    for (match in pattern.findAll(cleanHtml)) {
        val link = match.groupValues[1]
        val title = match.groupValues[2].trim()
        // ...
    }
}
```

## 技术实现

### 红牛资源（API类型）
- **优点**: 
  - 使用现有的API解析器（ApiDetailParser）
  - 无需额外的HTML解析
  - 数据结构标准，稳定可靠
- **实现**: 
  - 复用现有的parseApiJson方法
  - 复用现有的ApiDetailParser
  - 只需添加配置和URL映射

### 在线之家（WEB类型）
- **解析策略**: 
  - 列表页：使用正则表达式匹配`<a href="/detail/xxx.html"><h4>标题</h4></a>`
  - 详情页：复用WebDetailParser，自动适配
  - 播放页：解析`/video/{id}-{source}-{episode}.html`格式
- **特点**:
  - HTML结构简洁，易于解析
  - 不依赖封面图（使用占位图）
  - 支持多种解析模式（主模式+备用模式）

## 解析模式

### 在线之家列表页解析
```kotlin
// 主模式：h4标题
<a href="/detail/4519.html">
  <h4>美国队长4</h4>
</a>

// 备用模式：title属性
<a href="/detail/4519.html" title="美国队长4">
```

### 在线之家详情页解析
```kotlin
// 播放链接格式
<a href="/video/4446-1-1.html">第1集</a>
<a href="/video/4446-1-2.html">第2集</a>

// 格式说明：/video/{视频ID}-{播放源}-{集数}.html
```

## 测试建议

### 红牛资源测试
1. 切换到"红牛资源api"
2. 测试各个分类（电影、剧集、综艺、动漫）
3. 测试分页加载
4. 测试搜索功能
5. 测试详情页和播放

### 在线之家测试
1. 切换到"在线之家w"
2. 测试各个分类
3. 测试分页加载
4. 测试搜索功能
5. 测试详情页解析（标题、简介等）
6. 测试播放链接解析

### 验证点
- ✓ 视频列表正确显示
- ✓ 标题正确显示
- ✓ 封面图正确显示（红牛）或使用占位图（在线之家）
- ✓ 分页功能正常
- ✓ 搜索功能正常
- ✓ 详情页信息完整
- ✓ 播放链接可用

## 兼容性

### 向后兼容
- ✓ 不影响现有视频源
- ✓ 使用现有的解析框架
- ✓ 遵循现有的代码规范

### 扩展性
- ✓ 易于添加更多API类型的视频源（参考红牛）
- ✓ 易于添加更多WEB类型的视频源（参考在线之家）
- ✓ 解析模式可复用

## 已知限制

### 红牛资源
1. **依赖API稳定性**: 如果API接口变更，需要更新配置
2. **网络要求**: 需要稳定的网络连接

### 在线之家
1. **无封面图**: 列表页不显示封面，使用占位图
2. **HTML结构依赖**: 如果网站更新HTML结构，可能需要更新解析模式
3. **JavaScript内容**: 如果内容通过JS动态加载，当前解析器无法获取

## 后续优化建议

### 红牛资源
1. 添加更多分类支持
2. 优化图片加载
3. 添加缓存机制

### 在线之家
1. 尝试获取封面图
2. 添加更多解析模式
3. 优化搜索结果展示
4. 考虑使用Jsoup等HTML解析库

## 总结

此次更新成功添加了两个新视频源：
- **红牛资源**: 使用标准API接口，数据量大，更新及时
- **在线之家**: 使用HTML解析，结构简洁，易于维护

两个视频源采用不同的技术方案，展示了系统的灵活性和扩展性。红牛资源复用了现有的API解析框架，在线之家则展示了如何添加新的WEB类型视频源。
