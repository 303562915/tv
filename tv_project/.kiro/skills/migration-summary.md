# AndroLua到Kotlin迁移总结

## 已完成的迁移工作

### 1. 底部导航栏 ✅
- **原来**：4个选项（主页、本地视频、历史记录、设置）
- **现在**：3个选项（主页推荐、项目列表、我的设置）
- **文件**：
  - `app/src/main/res/menu/bottom_navigation_menu.xml`
  - `app/src/main/java/com/tv/ball/presentation/MainActivity.kt`

### 2. 主题配置 ✅
- **移除ActionBar**：从`DarkActionBar`改为`NoActionBar`
- **文件**：
  - `app/src/main/res/values/themes.xml`
  - `app/src/main/res/values-night/themes.xml`

### 3. 轮播图功能 ✅
- **实现**：ViewPager2 + 自动轮播
- **数据源**：本地默认 + 云端JSON
- **特性**：
  - 无限循环
  - 4秒自动切换
  - 点击跳转链接
  - Glide图片加载
- **文件**：
  - `app/src/main/java/com/tv/ball/presentation/home/BannerItem.kt`
  - `app/src/main/java/com/tv/ball/presentation/home/BannerAdapter.kt`
  - `app/src/main/res/layout/item_banner.xml`
  - `app/src/main/res/layout/fragment_home.xml`
  - `app/src/main/java/com/tv/ball/presentation/home/HomeFragment.kt`
- **Skill文件**：`.kiro/skills/banner-migration.md`

### 4. 影视仓库（视频源列表）✅
- **功能**：显示10个视频源供用户选择
- **视频源**：
  - LiB w (8MB/s)
  - LK w (8MB/s)
  - 可可w (1MB/s)
  - 360源api
  - 爱奇艺源api
  - 量子通道api
  - 非凡通道api
  - 豆瓣通道api
  - 牛牛通道api
  - 极速通道api
- **文件**：
  - `app/src/main/java/com/tv/ball/presentation/videosource/VideoSource.kt`
  - `app/src/main/java/com/tv/ball/presentation/videosource/VideoSourceAdapter.kt`
  - `app/src/main/java/com/tv/ball/presentation/videosource/VideoSourceFragment.kt`
  - `app/src/main/res/layout/fragment_video_source.xml`
  - `app/src/main/res/layout/item_video_source.xml`

### 5. 视频源管理系统 ✅
- **功能**：
  - 切换视频源
  - 保存用户选择（SharedPreferences）
  - 区分API和WEB两种类型
  - 动态生成URL
- **文件**：
  - `app/src/main/java/com/tv/ball/data/VideoSourceManager.kt`
  - `app/src/main/java/com/tv/ball/data/VideoSourceConfig.kt`
  - `app/src/main/java/com/tv/ball/VideoPlayerApplication.kt`

### 6. 视频列表解析 ✅
- **API类型**（JSON）：360zy, iqiyi, lzizy8, ffzy5, dbzy, niuniuzy3, jisuzy
- **网页类型**（HTML）：libvod, lkvod, keke
- **实现**：
  - `parseApiJson()` - 解析JSON API
  - `parseWebHtml()` - 解析HTML网页
- **文件**：
  - `app/src/main/java/com/tv/ball/presentation/home/HomeViewModel.kt`

### 7. 视频详情页解析 ✅
- **解析器架构**：
  - `VideoDetailParser` 接口
  - `VideoDetailParserFactory` 工厂类
  - `ApiDetailParser` - JSON解析
  - `WebDetailParser` - HTML解析
- **功能**：
  - 解析标题、封面、简介
  - 解析播放列表
  - 支持不同视频源
- **文件**：
  - `app/src/main/java/com/tv/ball/data/parser/VideoDetailParser.kt`
  - `app/src/main/java/com/tv/ball/domain/repository/VideoRepository.kt`
- **Skill文件**：`.kiro/skills/video-source-detail-parsing.md`

## 架构对比

### AndroLua架构
```
main.lua (主页)
├── extend/banner.lua (轮播图)
├── extend/popmiddle.lua (视频源列表)
├── libvideo/360zy.lua (360源)
├── libvideo/iqiyi.lua (爱奇艺源)
├── libvideo/libvod.lua (LiB源)
├── libvideo/keke.lua (可可源)
└── mmm/main.lua (详情页)
```

### Kotlin架构
```
MainActivity
├── HomeFragment (主页)
│   ├── BannerAdapter (轮播图)
│   ├── VideoListAdapter (视频列表)
│   └── HomeViewModel (数据管理)
├── VideoSourceFragment (视频源列表)
│   └── VideoSourceAdapter
├── VideoDetailActivity (详情页)
│   ├── VideoDetailViewModel
│   └── EpisodeAdapter (集数列表)
└── SettingsFragment (设置)

Data Layer
├── VideoSourceManager (视频源管理)
├── VideoSourceConfig (视频源配置)
├── VideoDetailParser (详情解析器)
│   ├── ApiDetailParser
│   └── WebDetailParser
└── VideoRepository (数据仓库)
```

## 关键技术点

### 1. 视频源类型识别
```kotlin
enum class SourceType {
    API,  // JSON API
    WEB   // HTML 网页
}
```

### 2. URL动态生成
- 列表URL：`VideoSourceManager.getSourceApiUrl()`
- 详情URL：`VideoSourceManager.getDetailUrl()`
- 搜索URL：`VideoSourceManager.getSearchApiUrl()`

### 3. 解析器模式
```kotlin
val parser = VideoDetailParserFactory.getParser(sourceKey)
val detail = parser.parse(content, url)
```

### 4. 数据持久化
- 当前视频源：SharedPreferences
- 播放历史：Room数据库
- 图片缓存：Glide

## 数据流程

### 主页视频列表
```
用户选择分类
    ↓
HomeViewModel.loadVideos()
    ↓
VideoSourceManager.getSourceApiUrl() → 生成URL
    ↓
NetworkDataSource.get() → 获取数据
    ↓
parseApiJson() 或 parseWebHtml() → 解析数据
    ↓
更新UI显示视频列表
```

### 视频详情
```
用户点击视频
    ↓
传递videoId或URL到VideoDetailActivity
    ↓
VideoRepository.getVideoDetail()
    ↓
VideoSourceManager.getDetailUrl() → 生成详情URL
    ↓
NetworkDataSource.fetchHtml() → 获取内容
    ↓
VideoDetailParserFactory.getParser() → 获取解析器
    ↓
Parser.parse() → 解析详情和播放列表
    ↓
更新UI显示详情和集数
```

### 切换视频源
```
用户点击视频源
    ↓
VideoSourceManager.setCurrentSource() → 保存选择
    ↓
返回主页
    ↓
HomeViewModel.loadVideos() → 使用新视频源
    ↓
显示新视频源的内容
```

## 待完成功能

### 高优先级
- [ ] 搜索功能完善
- [ ] 播放器集成测试
- [ ] 播放历史记录
- [ ] 收藏功能

### 中优先级
- [ ] 下载功能
- [ ] 设置页面完善
- [ ] 主题切换
- [ ] 缓存管理

### 低优先级
- [ ] 弹幕功能
- [ ] 投屏功能
- [ ] 多语言支持
- [ ] 夜间模式优化

## 测试清单

### 功能测试
- [x] 底部导航切换
- [x] 轮播图显示和自动播放
- [ ] 视频列表加载（API类型）
- [ ] 视频列表加载（网页类型）
- [ ] 视频源切换
- [ ] 视频详情显示
- [ ] 播放列表显示
- [ ] 视频播放

### 兼容性测试
- [ ] 不同视频源的列表解析
- [ ] 不同视频源的详情解析
- [ ] 网络异常处理
- [ ] 数据为空处理

### 性能测试
- [ ] 图片加载性能
- [ ] 列表滚动流畅度
- [ ] 内存占用
- [ ] 网络请求优化

## 构建和运行

### 构建命令
```bash
./gradlew clean
./gradlew assembleDebug
./gradlew installDebug
```

### 常见问题
1. **文件被占用**：关闭IDE和Gradle进程
2. **依赖冲突**：检查build.gradle.kts
3. **网络请求失败**：检查网络权限和SSL配置

## 代码规范

### 命名规范
- Activity: `XxxActivity`
- Fragment: `XxxFragment`
- ViewModel: `XxxViewModel`
- Adapter: `XxxAdapter`
- Repository: `XxxRepository`

### 文件组织
```
presentation/
├── home/          # 主页相关
├── detail/        # 详情页相关
├── videosource/   # 视频源相关
├── player/        # 播放器相关
└── settings/      # 设置相关

data/
├── local/         # 本地数据
├── remote/        # 网络数据
└── parser/        # 解析器

domain/
├── model/         # 数据模型
└── repository/    # 数据仓库
```

## 参考文档

- [AndroLua规则](.kiro/skills/lua-to-kotlin-workflow.md)
- [轮播图迁移](.kiro/skills/banner-migration.md)
- [详情页解析](.kiro/skills/video-source-detail-parsing.md)
- [布局转换](.kiro/skills/android-layout-conversion.md)

## 更新日志

### 2025-01-XX
- ✅ 完成底部导航调整（3个选项）
- ✅ 移除ActionBar
- ✅ 实现轮播图功能
- ✅ 创建影视仓库页面
- ✅ 实现视频源管理系统
- ✅ 支持API和网页两种解析方式
- ✅ 实现详情页解析器架构
- ✅ 创建相关skill文档

## 总结

已成功将AndroLua项目的核心功能迁移到Kotlin：
1. 界面布局和导航结构
2. 轮播图功能
3. 多视频源支持
4. 视频列表和详情解析
5. 视频源切换机制

代码结构清晰，采用MVVM架构，支持扩展新的视频源。
