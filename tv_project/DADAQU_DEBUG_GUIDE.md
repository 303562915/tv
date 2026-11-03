# 达达趣调试指南

## 🐛 首页没有视频内容 - 调试步骤

### 步骤1: 查看日志

使用 adb 查看详细日志：

```bash
# 查看达达趣解析日志
adb logcat | grep "达达趣解析"

# 或者查看所有HomeViewModel日志
adb logcat -s HomeViewModel:D

# 或者保存日志到文件
adb logcat > dadaqu_debug.log
```

### 步骤2: 查找关键信息

在日志中查找以下信息：

#### 2.1 解析开始标记
```
========== 达达趣解析开始 ==========
HTML长度: XXXX
基础URL: https://www.dadaqu.me
```

#### 2.2 模式匹配信息
```
模式1未匹配，尝试模式2
模式2未匹配，尝试模式3（通用）
```

#### 2.3 匹配结果
```
模式X匹配 #1: 视频标题
  链接: /detail/121753.html
  图片: https://...
```

#### 2.4 解析结束统计
```
========== 达达趣解析结束 ==========
匹配数: X, 有效视频数: X
```

#### 2.5 如果解析失败
```
========== 解析失败！HTML示例 ==========
[HTML内容前3000字符]
包含 module-item: true/false
包含 stui-vodlist__thumb: true/false
包含 /detail/: true/false
包含 /play/: true/false
包含 data-original: true/false
包含 <img: true/false
```

### 步骤3: 分析问题

根据日志输出判断问题：

#### 情况A: HTML长度为0或很小
**问题**: 网络请求失败或返回空内容
**解决方案**:
1. 检查网络连接
2. 检查是否能在浏览器中访问 https://www.dadaqu.me/show/1--------2---.html
3. 检查是否需要特殊的请求头或Cookie

#### 情况B: HTML有内容，但所有模式都未匹配
**问题**: HTML结构与预期不符
**解决方案**:
1. 查看日志中的HTML示例
2. 检查关键元素是否存在（module-item, /detail/, <img等）
3. 可能需要调整正则表达式

#### 情况C: 匹配到链接，但标题为空
**问题**: 标题提取失败
**解决方案**:
1. 查看HTML结构中标题的实际位置
2. 可能需要添加新的标题匹配模式

### 步骤4: 获取实际HTML结构

如果需要查看实际的HTML结构，可以：

#### 方法1: 使用浏览器
1. 在浏览器中打开：https://www.dadaqu.me/show/1--------2---.html
2. 右键 -> 查看网页源代码
3. 搜索第一个视频的标题
4. 查看该视频项的完整HTML结构

#### 方法2: 使用curl
```bash
curl "https://www.dadaqu.me/show/1--------2---.html" > dadaqu_page.html
```

然后查看 dadaqu_page.html 文件

### 步骤5: 提供反馈信息

如果问题仍未解决，请提供以下信息：

1. **日志输出**（特别是"解析失败！HTML示例"部分）
2. **HTML结构示例**（一个视频项的完整HTML）
3. **URL**（你访问的具体URL）
4. **网络状态**（是否能在浏览器中正常访问）

## 🔧 临时解决方案

如果急需使用，可以尝试：

### 方案1: 切换到其他视频源
暂时使用其他已经正常工作的视频源（如可可、在线之家等）

### 方案2: 使用搜索功能
如果搜索功能正常，可以通过搜索来查找视频

### 方案3: 直接输入详情页URL
如果知道视频的详情页URL，可以直接访问

## 📊 常见HTML结构示例

### 结构1: module-item（苹果CMS）
```html
<a class="module-item" href="/detail/121753.html">
  <div class="module-item-pic">
    <img data-original="https://example.com/image.jpg">
  </div>
  <div class="module-item-text">视频标题</div>
</a>
```

### 结构2: stui-vodlist（STUI模板）
```html
<a href="/detail/121753.html" class="stui-vodlist__thumb">
  <img data-original="https://example.com/image.jpg">
  <span class="pic-text">HD</span>
</a>
<h4 class="title">视频标题</h4>
```

### 结构3: 其他可能的结构
```html
<div class="video-item">
  <a href="/detail/121753.html">
    <img src="https://example.com/image.jpg">
    <h3>视频标题</h3>
  </a>
</div>
```

## 🎯 优化后的解析特性

最新版本的解析器支持：

1. **模式1**: module-item 结构（苹果CMS标准格式）
2. **模式2**: stui-vodlist__thumb 结构（STUI模板）
3. **模式3**: 通用智能解析
   - 查找所有 /detail/ 或 /play/ 链接
   - 在链接附近智能查找标题（支持h1-h6, div, span, p等标签）
   - 在链接附近查找图片（支持data-original和src）
   - 支持title属性作为备用标题来源

## 📞 需要帮助？

如果按照以上步骤仍无法解决问题，请提供：
1. 完整的日志输出
2. 实际的HTML结构示例
3. 访问的URL
4. 是否能在浏览器中正常访问

我会根据实际情况调整解析规则。
