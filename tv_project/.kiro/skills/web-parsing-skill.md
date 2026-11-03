---
name: Web Parsing Skill
description: 当需要解析网页HTML时,先获取实际源码再编写正则表达式
keywords: 解析网站, 网页解析, HTML结构, 正则匹配, 网页源码, 解析HTML, 爬虫, 网页抓取, 解析详情页, 解析列表页
activation: 当用户提到需要解析网页、编写正则表达式匹配HTML、或者抱怨解析不正确时自动激活
---

# Web Parsing Skill

## 核心原则
**永远不要凭空猜测HTML结构!必须先获取实际网页源码,再编写正则表达式。**

## 工作流程

### 1. 获取网页源码
当需要解析网页时,首先使用以下方法之一获取实际HTML:

```bash
# 方法1: 使用curl获取完整HTML
curl -H "User-Agent: Mozilla/5.0" "URL" 2>$null | Out-File -Encoding UTF8 page.html

# 方法2: 使用webFetch工具(如果网站允许)
webFetch(url, mode="full")

# 方法3: 使用curl并直接查看关键部分
curl -H "User-Agent: Mozilla/5.0" "URL" 2>$null | Select-String -Pattern "关键词" -Context 5,10
```

### 2. 分析HTML结构
获取源码后,仔细分析:
- 目标数据在哪个HTML标签中
- 标签的class、id等属性
- 数据的层级结构
- 是否有多个相似结构(列表)

### 3. 编写正则表达式
基于实际HTML结构编写正则:
```kotlin
// 示例: 根据实际HTML编写
val pattern = """<div class="actual-class-name">.*?<h3>([^<]+)</h3>.*?</div>""".toRegex(RegexOption.DOT_MATCHES_ALL)
```

### 4. 测试验证
- 使用Select-String在本地测试正则是否匹配
- 检查是否能提取到所有需要的数据
- 确认没有遗漏或错误匹配

## 常见错误示例

❌ **错误做法:**
```kotlin
// 凭空猜测HTML结构
val pattern = """<h4>([^<]*播放[^<]*)</h4>""".toRegex()
```

✅ **正确做法:**
```bash
# 1. 先获取实际HTML
curl -H "User-Agent: Mozilla/5.0" "https://example.com/detail/123.html" 2>$null | Out-File page.html

# 2. 查看实际结构
Select-String -Path page.html -Pattern "播放" -Context 5,10

# 3. 根据实际结构编写
# 实际HTML: <div class="playlist-panel"><div class="panel-head"><h3>BD5播放</h3>
val pattern = """<div class="playlist-panel">\s*<div class="panel-head">\s*<h3>([^<]+)</h3>""".toRegex()
```

## 实战案例: libvio网站解析

### 问题
用户反馈: "没看到BD5播放和UC下载按钮"

### 解决步骤

1. **获取实际HTML:**
```bash
curl -H "User-Agent: Mozilla/5.0" "https://www.libvio.la/detail/714893358.html" 2>$null | Out-File page.html
```

2. **查找播放源结构:**
```bash
Select-String -Path page.html -Pattern "BD5|HD5" -Context 5,10
```

发现实际结构:
```html
<div class="playlist-panel">
    <div class="panel-head">
        <h3>BD5播放</h3>
    </div>
    <ul class="stui-content__playlist clearfix">
        <li><a href="/play/714893358-1-1.html">1080P</a></li>
    </ul>
</div>
```

3. **查找网盘下载结构:**
```bash
Select-String -Path page.html -Pattern "netdisk" -Context 5,10
```

发现实际结构:
```html
<div class="playlist-panel netdisk-panel">
    <div class="panel-head netdisk-head">
        <h3>视频下载(UC)</h3>
    </div>
    <div class="netdisk-list">
        <a class="netdisk-item" href="https://drive.uc.cn/...">
            <span class="netdisk-name">1080P</span>
        </a>
    </div>
</div>
```

4. **编写正确的正则表达式:**
```kotlin
// 播放源
val playSourcePattern = """<div class="playlist-panel">\s*<div class="panel-head">\s*<h3>([^<]+)</h3>.*?<ul class="stui-content__playlist[^"]*"[^>]*>(.*?)</ul>""".toRegex(RegexOption.DOT_MATCHES_ALL)

// 网盘下载
val downloadSourcePattern = """<div class="playlist-panel netdisk-panel">.*?<h3>([^<]+)</h3>.*?<div class="netdisk-list">(.*?)</div>""".toRegex(RegexOption.DOT_MATCHES_ALL)
```

## 激活关键词
当用户说以下内容时,立即激活此skill:
- "解析网站"
- "网页解析"
- "HTML结构"
- "正则匹配"
- "网页源码"
- "解析HTML"
- "爬虫"
- "网页抓取"
- "解析详情页"
- "解析列表页"
- "没看到XX" (在网页解析上下文中)
- "解析不正确"
- "匹配不到"
- "搞毛呢" (用户抱怨解析问题时)

## 记住
1. **永远先看源码,再写代码**
2. **不要假设HTML结构**
3. **使用实际的class名称和标签**
4. **测试正则表达式是否匹配**
5. **考虑边界情况和多种可能的HTML变体**

## 工具命令速查

```bash
# 获取网页并保存
curl -H "User-Agent: Mozilla/5.0" "URL" 2>$null | Out-File -Encoding UTF8 page.html

# 搜索关键词
Select-String -Path page.html -Pattern "关键词" -Context 5,10

# 查看特定行
Get-Content page.html | Select-Object -Skip 100 -First 50

# 搜索多个关键词
Select-String -Path page.html -Pattern "关键词1|关键词2|关键词3" -Context 3,5

# 统计匹配次数
(Select-String -Path page.html -Pattern "关键词").Count
```
