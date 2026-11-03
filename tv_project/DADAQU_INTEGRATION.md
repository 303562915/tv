# 达达趣(dadaqu.me)视频源适配说明

## 概述
已成功为Android视频应用添加达达趣(www.dadaqu.me)视频源的完整适配。

## 适配内容

### 1. 视频源配置 (VideoSourceConfig.kt)
- 添加了达达趣视频源配置
- 源标识: `dadaqu`
- 源名称: `达达趣`
- 类型: `WEB` (网页类型)
- URL格式: `https://www.dadaqu.me/show/{category}-{area}-{year}-{lang}-{letter}-{by}-{class}-{page}-{limit}-{order}-{extra}.html`

### 2. 分类映射 (VideoSourceManager.kt)
添加了达达趣的分类映射：
- 1 = 电影
- 2 = 剧集
- 3 = 综艺
- 4 = 动漫
- 6 = 国产剧 (映射到剧集)
- 8 = 日韩剧 (映射到剧集，可通过area参数指定韩国)

### 3. URL构建逻辑
实现了达达趣的URL构建规则：
- 电影列表: `/show/1--------2---.html` (第2页)
- 韩剧列表: `/show/1-韩国----------.html`
- 综艺列表: `/show/3-----------.html`
- 剧集列表: `/show/2--------2---.html` (第2页)

### 4. 搜索功能
- 搜索URL: `https://www.dadaqu.me/search.html?wd={keyword}`

### 5. 列表页解析 (HomeViewModel.kt)
在`parseWebHtml`函数中添加了达达趣的HTML解析逻辑，支持三种解析模式：

#### 模式1: module-item 结构
```html
<a class="module-item" href="/detail/121753.html">
  <div class="module-item-pic">
    <img data-original="图片URL">
  </div>
  <div class="module-item-text">标题</div>
</a>
```

#### 模式2: stui-vodlist__thumb 结构
```html
<a href="/detail/121753.html" class="stui-vodlist__thumb">
  <img data-original="图片URL">
  <span class="pic-text">更新信息</span>
</a>
<h4 class="title">标题</h4>
```

#### 模式3: 简化版（备用）
只匹配链接和标题，用于兜底解析

### 6. 详情页解析 (VideoDetailParser.kt)
添加了完整的详情页解析支持：

#### 基本信息解析
- 标题：支持多种HTML结构
- 封面：支持多种图片属性（data-original, src等）
- 简介：支持多种描述结构
- 年份、地区、类型、导演、主演等元数据

#### 剧集列表解析 (parseDadaquEpisodes)
实现了三种剧集解析模式：

1. **标准播放源结构**
   - 支持多个播放源
   - 每个源有独立的剧集列表
   - 播放链接格式: `/play/121753-1-1.html`

2. **module-play-list 结构**（备用）
   - 适配不同的HTML布局

3. **playlist-panel 结构**（备用）
   - 兼容更多页面变体

### 7. 播放详情页
- 播放页URL格式: `/play/121753-1-1.html`
- 自动解析播放源和剧集信息
- 支持多线路切换

## URL示例

### 列表页
- 电影第2页: `https://www.dadaqu.me/show/1--------2---.html`
- 韩国电影: `https://www.dadaqu.me/show/1-韩国----------.html`
- 综艺: `https://www.dadaqu.me/show/3-----------.html`
- 剧集第2页: `https://www.dadaqu.me/show/2--------2---.html`

### 详情页
- 详情页: `https://www.dadaqu.me/detail/121753.html`

### 播放页
- 播放页: `https://www.dadaqu.me/play/121753-1-1.html`

### 搜索
- 搜索: `https://www.dadaqu.me/search.html?wd=关键词`

## 特性

### 1. 智能解析
- 多模式匹配，提高解析成功率
- 自动处理相对路径和协议
- 支持图片URL修复（补全https协议）

### 2. 详细日志
- 每个解析步骤都有详细的日志输出
- 便于调试和问题排查
- 解析失败时输出HTML示例

### 3. 容错处理
- 多种备用解析模式
- 自动过滤无效数据
- 优雅降级处理

### 4. 完整功能
- ✅ 视频列表浏览
- ✅ 分类筛选
- ✅ 分页加载
- ✅ 搜索功能
- ✅ 详情页展示
- ✅ 多线路播放
- ✅ 剧集切换

## 使用方法

1. 在应用中选择"达达趣"视频源
2. 浏览不同分类的视频内容
3. 使用筛选功能（地区、年份等）
4. 点击视频查看详情
5. 选择播放线路和剧集

## 技术细节

### 解析器类型
- 列表页: WebDetailParser (网页解析)
- 详情页: WebDetailParser (网页解析)

### 数据流程
1. 用户选择分类 → 构建列表URL
2. 获取HTML → parseWebHtml解析
3. 显示视频列表 → 用户点击
4. 获取详情HTML → WebDetailParser解析
5. 显示详情和剧集 → 用户选择播放
6. 跳转播放页

### 关键文件
- `VideoSourceConfig.kt` - 源配置
- `VideoSourceManager.kt` - URL构建和管理
- `HomeViewModel.kt` - 列表解析
- `VideoDetailParser.kt` - 详情解析

## 注意事项

1. **网站可访问性**: 确保网络环境可以访问 www.dadaqu.me
2. **HTML结构变化**: 如果网站更新HTML结构，可能需要调整解析规则
3. **图片加载**: 使用懒加载图片，注意data-original属性
4. **播放链接**: 播放页可能需要进一步解析真实视频URL

## 测试建议

1. 测试不同分类的列表加载
2. 测试分页功能
3. 测试搜索功能
4. 测试详情页显示
5. 测试多线路切换
6. 测试剧集播放

## 后续优化

1. 根据实际HTML结构微调解析规则
2. 添加更多错误处理
3. 优化图片加载性能
4. 支持更多筛选条件
5. 添加缓存机制

## 版本信息
- 适配日期: 2026-04-17
- 适配版本: 1.0
- 状态: 已完成基础适配
