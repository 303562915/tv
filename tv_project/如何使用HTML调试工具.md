# 如何使用HTML调试工具

## 🎯 目的

这个工具可以帮你查看可可影视网站的实际HTML结构，从而更好地优化解析逻辑。

## 📱 使用步骤

### 1. 打开调试工具

在应用中添加入口（临时测试用）：

```kotlin
// 在任意Activity中添加
val intent = Intent(this, HtmlDebugActivity::class.java)
startActivity(intent)
```

或者通过adb启动：

```bash
adb shell am start -n com.tv.ball/.presentation.debug.HtmlDebugActivity
```

### 2. 输入URL

默认已填入：`https://103.194.185.51:51120/show/1--------1---.html`

你也可以测试其他URL：
- 第2页: `https://103.194.185.51:51120/show/1--------2---.html`
- 搜索: `https://103.194.185.51:51120/search?t=bGEt7qN3fnzhrkO989OwEA==&k=测试`
- 详情: `https://103.194.185.51:51120/detail/333723.html`

### 3. 点击"获取HTML"

工具会：
1. 下载网页HTML
2. 自动分析结构
3. 显示关键信息

### 4. 查看分析结果

工具会自动识别：

#### 视频列表容器
```
✓ 找到 'stui-vodlist': 20 次
✓ 找到 'module-items': 0 次
```

#### 视频项结构
```
✓ 找到 'stui-vodlist__box': 20 次
```

#### 链接结构
```
示例详情链接:
  /detail/333723.html
  /detail/333724.html
  /detail/333725.html
```

#### 图片结构
```
✓ 找到 'data-original': 20 次
示例图片URL:
  https://pic.xxx.com/upload/vod/2024/xxx.jpg
```

#### 标题结构
```
示例标题:
  电影名称1
  电影名称2
  电影名称3
```

### 5. 复制结果

- **复制HTML**: 复制完整的HTML源码
- **复制分析**: 复制结构分析结果

### 6. 发送给我

将分析结果发送给我，我会根据实际结构优化解析逻辑。

## 📋 需要关注的信息

### 关键HTML结构

1. **视频列表容器的class名称**
   - 例如: `stui-vodlist`, `module-items`, `video-list`

2. **单个视频项的class名称**
   - 例如: `stui-vodlist__box`, `module-item`

3. **链接的格式**
   - 例如: `<a href="/detail/123.html">`

4. **图片的属性名**
   - 例如: `data-original`, `data-src`, `src`

5. **标题的位置**
   - 例如: `<h4 class="title"><a>标题</a></h4>`

6. **质量标签的位置**
   - 例如: `<span class="pic-text">HD</span>`

## 🔍 示例分析

假设HTML结构是这样的：

```html
<div class="stui-vodlist__box">
  <a class="stui-vodlist__thumb" href="/detail/123.html">
    <img data-original="https://pic.com/123.jpg">
    <span class="pic-text">HD</span>
  </a>
  <div class="stui-vodlist__detail">
    <h4 class="title">
      <a href="/detail/123.html">电影名称</a>
    </h4>
  </div>
</div>
```

那么解析模式应该是：

```kotlin
val pattern = """
<a[^>]*class="[^"]*stui-vodlist__thumb[^"]*"[^>]*href="([^"]+)"[^>]*>
.*?<img[^>]*data-original="([^"]+)"[^>]*>
.*?<span[^>]*class="[^"]*pic-text[^"]*"[^>]*>([^<]*)</span>
.*?<h4[^>]*class="[^"]*title[^"]*"[^>]*>.*?<a[^>]*>([^<]+)</a>
""".toRegex(RegexOption.DOT_MATCHES_ALL)
```

## 💡 提示

1. **如果只显示一个视频**
   - 可能是解析模式不匹配
   - 查看HTML结构，找到正确的class名称

2. **如果图片不显示**
   - 检查图片URL是否完整
   - 可能需要拼接baseUrl

3. **如果标题乱码**
   - 检查编码问题
   - 可能需要HTML解码

## 🚀 快速测试

1. 打开调试工具
2. 点击"获取HTML"
3. 查看"HTML结构分析"
4. 复制"分析结果"
5. 发送给我

我会根据实际结构立即优化解析逻辑！
