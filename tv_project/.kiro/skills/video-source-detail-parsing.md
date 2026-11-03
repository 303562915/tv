# 视频源详情页解析规则

## 概述
不同的视频源需要使用不同的解析方式来获取视频详情和播放列表。

## 视频源分类

### API类型（JSON）
使用JSON接口返回结构化数据：
- iqiyi（爱奇艺源）
- 360zy（360源）
- lzizy8（量子通道）
- ffzy5（非凡通道）
- dbzy（豆瓣通道）
- niuniuzy3（牛牛通道）
- jisuzy（极速通道）

### 网页类型（HTML）
需要解析HTML页面：
- libvod（LiB）
- lkvod（LK）
- keke（可可）

## 核心组件

### 1. VideoDetailParser接口
```kotlin
interface VideoDetailParser {
    fun parse(html: String, sourceUrl: String): VideoDetail?
}
```

### 2. VideoDetailParserFactory
根据视频源类型返回对应的解析器：
```kotlin
fun getParser(sourceKey: String): VideoDetailParser
```

### 3. ApiDetailParser
解析JSON格式的详情数据：
- 解析字段：vod_name, vod_pic, vod_content, vod_play_url
- 播放列表格式：`第01集$url#第02集$url#...`

### 4. WebDetailParser
解析HTML格式的详情页面：
- 使用正则表达式提取标题、封面、简介
- 解析播放列表链接

## 详情URL生成

### API类型
格式：`{baseUrl}/api.php/provide/vod/?ac=videolist&ids={videoId}`

示例：
- iqiyi: `https://iqiyizyapi.com/api.php/provide/vod/?ac=videolist&ids=123456`
- 360zy: `https://360zy5.com/api.php/provide/vod/?ac=videolist&ids=123456`

### 网页类型
直接使用完整URL（从列表页获取）

示例：
- libvod: `https://www.libvio.cc/detail/123456.html`
- keke: `https://www.keke1.app/show/123456.html`

## 数据流程

1. **用户点击视频** → 传递videoId或完整URL
2. **VideoRepository.getVideoDetail()** → 判断是ID还是URL
3. **VideoSourceManager.getDetailUrl()** → 生成完整详情URL
4. **NetworkDataSource.fetchHtml()** → 获取内容
5. **VideoDetailParserFactory.getParser()** → 获取对应解析器
6. **Parser.parse()** → 解析返回VideoDetail

## JSON格式示例

### API响应格式
```json
{
  "code": 1,
  "msg": "数据列表",
  "page": 1,
  "pagecount": 1,
  "limit": "1",
  "total": 1,
  "list": [
    {
      "vod_id": 123456,
      "vod_name": "视频标题",
      "vod_pic": "https://example.com/cover.jpg",
      "vod_content": "视频简介",
      "vod_play_url": "第01集$https://play.url/ep1.m3u8#第02集$https://play.url/ep2.m3u8"
    }
  ]
}
```

### 播放列表解析
- 分隔符：`#` 分隔不同集数
- 格式：`集数名称$播放URL`
- 示例：`第01集$url1#第02集$url2#第03集$url3`

## HTML解析模式

### 标题提取
```regex
<h1[^>]*class="[^"]*title[^"]*"[^>]*>([^<]+)</h1>
```

### 封面提取
```regex
<img[^>]*class="[^"]*lazyload[^"]*"[^>]*data-original="([^"]+)"
```

### 简介提取
```regex
<span class="detail-content"[^>]*>([^<]+)</span>
```

### 播放列表提取
```regex
<a[^>]*href="([^"]+)"[^>]*>([^<]+)</a>
```
过滤条件：名称包含"第...集"、"EP"或数字

## 使用示例

### 在VideoRepository中使用
```kotlin
suspend fun getVideoDetail(url: String): VideoDetail {
    val currentSource = VideoSourceManager.getCurrentSource()
    val detailUrl = if (url.startsWith("http")) {
        url
    } else {
        VideoSourceManager.getDetailUrl(currentSource, url)
    }
    
    val content = networkDataSource.fetchHtml(detailUrl)
    val parser = VideoDetailParserFactory.getParser(currentSource)
    return parser.parse(content, detailUrl) ?: defaultVideoDetail()
}
```

### 在HomeFragment中传递数据
```kotlin
// API类型：传递ID
VideoItem(
    title = vodName,
    coverUrl = vodPic,
    detailUrl = vodId,  // 只传ID
    quality = vodRemarks
)

// 网页类型：传递完整URL
VideoItem(
    title = title,
    coverUrl = img,
    detailUrl = "https://www.libvio.cc/detail/123.html",  // 完整URL
    quality = quality
)
```

## 注意事项

1. **URL处理**
   - API类型存储ID，使用时生成完整URL
   - 网页类型直接存储完整URL

2. **错误处理**
   - 解析失败返回默认VideoDetail
   - 记录日志便于调试

3. **字符转义**
   - JSON中的`\/`需要替换为`/`
   - HTML实体需要解码

4. **播放列表**
   - API类型：解析`$`和`#`分隔的字符串
   - 网页类型：提取符合集数格式的链接

5. **兼容性**
   - 每个视频源可能有细微差异
   - 需要针对性调整正则表达式

## 测试检查清单

- [ ] API类型视频源能正确获取详情
- [ ] 网页类型视频源能正确获取详情
- [ ] 播放列表正确解析
- [ ] 封面图片URL正确处理
- [ ] 简介内容正确显示
- [ ] 点击集数能正确播放
- [ ] 切换视频源后详情页正常工作

## 扩展新视频源

添加新视频源时需要：

1. 在`VideoSourceConfig.kt`中添加配置
2. 确定是API还是WEB类型
3. 如果是API类型，在`VideoSourceManager.getDetailUrl()`添加URL模板
4. 如果需要特殊解析，在对应Parser中添加逻辑
5. 测试列表页和详情页是否正常工作
