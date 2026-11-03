# 需求文档：AndroLua 应用迁移到 Kotlin

## 简介

本需求文档描述了将基于 AndroLua (Lua 语言) 的 Android 视频播放器应用迁移到 Kotlin 的功能需求。该应用是一个功能完整的视频播放器，支持多种视频源解析、手势控制、播放列表管理、弹幕显示等功能。迁移后的应用将保持所有现有功能不变，同时采用现代 Android 开发最佳实践。

## 术语表

- **System**: 迁移后的 Kotlin 视频播放器应用
- **VideoSourceParser**: 视频源解析器，负责从网页中提取视频播放 URL
- **VideoPlayerActivity**: 视频播放器界面组件
- **HomeFragment**: 主页界面组件，显示视频列表和轮播图
- **VideoRepository**: 视频数据仓库，管理视频数据的获取和缓存
- **ConfigRepository**: 配置数据仓库，管理应用配置和用户设置
- **ExoPlayer**: 视频播放引擎
- **NetworkDataSource**: 网络数据源，负责 HTTP 请求
- **LocalDataSource**: 本地数据源，负责数据库和文件操作
- **ViewModel**: 视图模型，连接 UI 和数据层
- **GestureHandler**: 手势处理器，处理播放器手势操作
- **DanmakuView**: 弹幕视图组件
- **PlaylistManager**: 播放列表管理器

## 需求

### 需求 1：视频源解析

**用户故事：** 作为用户，我希望应用能够解析多种视频网站的视频源，以便我可以观看来自不同平台的视频内容。

#### 验收标准

1. WHEN 提供有效的视频页面 URL，THE VideoSourceParser SHALL 从网页内容中提取视频播放 URL
2. WHEN 视频源需要多步解析（如先获取 iframe 再解析实际视频），THE VideoSourceParser SHALL 执行完整的解析链
3. WHEN 解析过程中遇到网络错误，THE VideoSourceParser SHALL 返回描述性错误信息
4. WHEN 解析过程中遇到无效的网页内容，THE VideoSourceParser SHALL 返回解析失败状态
5. THE VideoSourceParser SHALL 支持至少 10 种不同的视频源解析规则

### 需求 2：视频播放

**用户故事：** 作为用户，我希望能够流畅播放视频，以便我可以观看视频内容。

#### 验收标准

1. WHEN 获取到有效的视频 URL，THE ExoPlayer SHALL 初始化并准备播放
2. WHEN 播放器准备就绪，THE VideoPlayerActivity SHALL 显示视频画面
3. WHEN 视频正在播放，THE System SHALL 显示当前播放进度和总时长
4. WHEN 网络连接中断，THE ExoPlayer SHALL 暂停播放并显示缓冲状态
5. WHEN 网络连接恢复，THE ExoPlayer SHALL 自动恢复播放
6. WHEN 视频播放完成，THE System SHALL 自动播放下一个视频（如果播放列表中有）

### 需求 3：手势控制

**用户故事：** 作为用户，我希望通过手势控制播放器，以便我可以方便地调整播放参数。

#### 验收标准

1. WHEN 用户在播放器左侧垂直滑动，THE GestureHandler SHALL 调整屏幕亮度
2. WHEN 用户在播放器右侧垂直滑动，THE GestureHandler SHALL 调整音量
3. WHEN 用户水平滑动，THE GestureHandler SHALL 调整播放进度
4. WHEN 用户单击播放器中央，THE System SHALL 切换播放/暂停状态
5. WHEN 用户双击播放器，THE System SHALL 切换全屏/窗口模式
6. WHEN 手势操作进行中，THE System SHALL 显示实时反馈（亮度/音量/进度指示器）

### 需求 4：播放列表管理

**用户故事：** 作为用户，我希望管理播放列表，以便我可以组织和连续观看多个视频。

#### 验收标准

1. WHEN 用户添加视频到播放列表，THE PlaylistManager SHALL 将视频信息保存到本地数据库
2. WHEN 用户从播放列表中删除视频，THE PlaylistManager SHALL 从数据库中移除该视频
3. WHEN 用户调整播放列表顺序，THE PlaylistManager SHALL 更新数据库中的顺序信息
4. WHEN 播放列表为空，THE System SHALL 显示空状态提示
5. WHEN 当前视频播放完成且播放列表中有下一个视频，THE System SHALL 自动播放下一个视频
6. THE PlaylistManager SHALL 持久化播放列表到本地存储

### 需求 5：弹幕显示

**用户故事：** 作为用户，我希望在视频播放时显示弹幕，以便我可以看到其他用户的评论和互动。

#### 验收标准

1. WHEN 视频开始播放，THE DanmakuView SHALL 加载并显示弹幕数据
2. WHEN 弹幕数据加载失败，THE System SHALL 继续播放视频而不显示弹幕
3. WHEN 用户发送弹幕，THE System SHALL 立即在视频上显示该弹幕
4. WHEN 用户关闭弹幕功能，THE DanmakuView SHALL 隐藏所有弹幕
5. WHEN 用户重新开启弹幕功能，THE DanmakuView SHALL 恢复显示弹幕
6. THE DanmakuView SHALL 支持不同颜色和大小的弹幕样式

### 需求 6：主页视频列表

**用户故事：** 作为用户，我希望在主页浏览视频列表，以便我可以发现和选择想要观看的视频。

#### 验收标准

1. WHEN 应用启动，THE HomeFragment SHALL 从网络加载视频列表数据
2. WHEN 视频列表数据加载成功，THE HomeFragment SHALL 显示视频缩略图、标题和简介
3. WHEN 视频列表数据加载失败，THE HomeFragment SHALL 显示错误提示和重试按钮
4. WHEN 用户下拉刷新，THE HomeFragment SHALL 重新加载最新的视频列表
5. WHEN 用户滚动到列表底部，THE HomeFragment SHALL 自动加载更多视频（分页加载）
6. WHEN 用户点击视频项，THE System SHALL 启动 VideoPlayerActivity 并开始播放

### 需求 7：轮播图

**用户故事：** 作为用户，我希望在主页看到推荐视频的轮播图，以便我可以快速访问热门内容。

#### 验收标准

1. WHEN 主页加载完成，THE HomeFragment SHALL 在顶部显示轮播图
2. THE System SHALL 每隔 3 秒自动切换到下一张轮播图
3. WHEN 用户手动滑动轮播图，THE System SHALL 切换到对应的轮播项
4. WHEN 用户点击轮播图项，THE System SHALL 跳转到对应的视频或页面
5. THE System SHALL 在轮播图底部显示指示器，标识当前位置

### 需求 8：应用配置管理

**用户故事：** 作为用户，我希望自定义应用设置，以便我可以根据个人偏好调整应用行为。

#### 验收标准

1. WHEN 用户修改设置项，THE ConfigRepository SHALL 立即保存到 SharedPreferences
2. WHEN 应用重启，THE System SHALL 从 SharedPreferences 加载上次保存的设置
3. THE System SHALL 支持配置默认视频质量（高清/标清/流畅）
4. THE System SHALL 支持配置自动播放下一集的开关
5. THE System SHALL 支持配置弹幕显示的开关
6. THE System SHALL 支持配置播放器手势控制的开关

### 需求 9：历史记录

**用户故事：** 作为用户，我希望查看观看历史，以便我可以继续观看之前未看完的视频。

#### 验收标准

1. WHEN 用户开始播放视频，THE System SHALL 将视频信息添加到历史记录
2. WHEN 用户退出播放器，THE System SHALL 保存当前播放进度到历史记录
3. WHEN 用户从历史记录中选择视频，THE System SHALL 从上次播放位置继续播放
4. WHEN 用户清除历史记录，THE LocalDataSource SHALL 从数据库中删除所有历史记录
5. THE System SHALL 在历史记录中显示视频缩略图、标题和观看进度
6. THE System SHALL 按观看时间倒序排列历史记录

### 需求 10：网络请求处理

**用户故事：** 作为开发者，我希望应用能够可靠地处理网络请求，以便用户可以稳定地获取视频数据。

#### 验收标准

1. WHEN 发起网络请求，THE NetworkDataSource SHALL 使用 OkHttp 客户端执行请求
2. WHEN 网络请求超时，THE NetworkDataSource SHALL 返回超时错误
3. WHEN 网络请求返回非 200 状态码，THE NetworkDataSource SHALL 返回 HTTP 错误信息
4. THE NetworkDataSource SHALL 支持自定义请求头（User-Agent、Referer 等）
5. THE NetworkDataSource SHALL 支持 HTTPS 请求
6. WHEN 网络请求失败，THE System SHALL 提供重试机制

### 需求 11：图片加载

**用户故事：** 作为用户，我希望快速加载视频缩略图，以便我可以流畅浏览视频列表。

#### 验收标准

1. WHEN 显示视频列表项，THE System SHALL 使用 Glide 加载缩略图
2. WHEN 图片加载中，THE System SHALL 显示占位图
3. WHEN 图片加载失败，THE System SHALL 显示错误占位图
4. THE System SHALL 缓存已加载的图片到内存和磁盘
5. WHEN 列表快速滚动，THE System SHALL 取消不可见项的图片加载请求
6. THE System SHALL 根据 ImageView 尺寸自动调整加载的图片大小

### 需求 12：数据持久化

**用户故事：** 作为开发者，我希望应用能够可靠地持久化数据，以便用户数据不会丢失。

#### 验收标准

1. THE LocalDataSource SHALL 使用 Room 数据库存储结构化数据
2. WHEN 数据库操作失败，THE LocalDataSource SHALL 返回错误信息
3. THE System SHALL 在后台线程执行所有数据库操作
4. THE System SHALL 支持数据库迁移以保持向后兼容
5. THE ConfigRepository SHALL 使用 SharedPreferences 存储简单配置数据
6. WHEN 应用卸载，THE System SHALL 清除所有本地数据

### 需求 13：错误处理

**用户故事：** 作为用户，我希望应用能够优雅地处理错误，以便我知道发生了什么问题以及如何解决。

#### 验收标准

1. WHEN 发生网络错误，THE System SHALL 显示用户友好的错误消息
2. WHEN 发生解析错误，THE System SHALL 显示"视频源解析失败"提示
3. WHEN 发生播放错误，THE System SHALL 显示"播放失败"提示并提供重试选项
4. WHEN 发生未知错误，THE System SHALL 记录错误日志并显示通用错误提示
5. THE System SHALL 不因错误而崩溃，应优雅降级
6. WHEN 错误可恢复，THE System SHALL 提供明确的恢复操作（如重试按钮）

### 需求 14：架构分层

**用户故事：** 作为开发者，我希望应用采用清晰的架构分层，以便代码易于维护和扩展。

#### 验收标准

1. THE System SHALL 采用 MVVM 架构模式
2. THE System SHALL 将 UI 逻辑放在 Activity/Fragment 中
3. THE System SHALL 将业务逻辑放在 ViewModel 中
4. THE System SHALL 将数据访问逻辑放在 Repository 中
5. THE System SHALL 使用 Kotlin 协程处理异步操作
6. THE System SHALL 使用 LiveData 或 StateFlow 进行数据观察

### 需求 15：依赖注入

**用户故事：** 作为开发者，我希望使用依赖注入管理对象生命周期，以便代码更易测试和维护。

#### 验收标准

1. THE System SHALL 使用 Hilt 或 Koin 作为依赖注入框架
2. THE System SHALL 通过构造函数注入依赖
3. THE System SHALL 为 ViewModel 提供工厂类
4. THE System SHALL 为 Repository 提供单例实例
5. THE System SHALL 为网络客户端提供单例实例
6. THE System SHALL 支持测试时替换依赖实现

## 需求优先级

### P0（必须实现）
- 需求 1：视频源解析
- 需求 2：视频播放
- 需求 6：主页视频列表
- 需求 10：网络请求处理
- 需求 12：数据持久化
- 需求 13：错误处理
- 需求 14：架构分层

### P1（高优先级）
- 需求 3：手势控制
- 需求 4：播放列表管理
- 需求 8：应用配置管理
- 需求 9：历史记录
- 需求 11：图片加载

### P2（中优先级）
- 需求 5：弹幕显示
- 需求 7：轮播图
- 需求 15：依赖注入
