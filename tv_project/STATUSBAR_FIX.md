# 透明沉浸式状态栏和首页布局修复

## 修复内容

### 1. 透明沉浸式状态栏 ✅

#### 创建工具类 StatusBarUtils.kt
创建了一个统一的工具类来管理状态栏设置：
```kotlin
object StatusBarUtils {
    fun setupTransparentStatusBar(activity: Activity, lightStatusBar: Boolean = true)
    fun setLightStatusBar(activity: Activity)
    fun clearLightStatusBar(activity: Activity)
}
```

#### 已应用透明状态栏的所有页面：

1. **MainActivity** - 主界面
   - 使用 `StatusBarUtils.setupTransparentStatusBar(this)`
   
2. **LocalVideoActivity** - 本地视频列表
   - 显示所有视频文件夹
   
3. **LocalVideoFolderActivity** - 本地视频文件夹
   - 显示文件夹内的视频文件
   
4. **SearchActivity** - 搜索页面
   - 视频搜索功能
   
5. **VideoDetailActivity** - 视频详情页面
   - 显示视频详情和选集

#### 所有 Fragment（通过 MainActivity）：
- HomeFragment - 首页
- HistoryFragment - 历史页面
- VideoSourceFragment - 影视仓库
- SettingsFragment - 设置页面

#### 布局文件修改
所有页面的根布局都添加了 `android:fitsSystemWindows="true"`：

**Activity 布局：**
- activity_main.xml - 移除 fitsSystemWindows（让内容延伸）
- activity_local_video.xml ✅
- activity_local_video_folder.xml ✅
- activity_search.xml ✅
- activity_video_detail.xml ✅

**Fragment 布局：**
- fragment_home.xml ✅
- fragment_history.xml ✅
- fragment_video_source.xml ✅
- fragment_settings.xml ✅

### 2. 首页一行三个视频 ✅

#### HomeFragment.kt
修改 GridLayoutManager 的列数：
```kotlin
// 从 2 改为 3
val layoutManager = GridLayoutManager(requireContext(), 3)
```

## 技术实现

### StatusBarUtils 工具类特点

1. **统一管理**
   - 所有状态栏设置集中在一个工具类
   - 避免代码重复
   - 易于维护和修改

2. **兼容性处理**
   - Android 11+ (API 30): 使用 WindowInsetsController
   - Android 6+ (API 23): 使用 SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
   - 自动处理不同版本的 API 差异

3. **灵活配置**
   - 支持深色/浅色图标切换
   - 默认使用深色图标（适配浅色背景）

### 边到边显示原理

1. **WindowCompat.setDecorFitsSystemWindows(window, false)**
   - 让内容可以延伸到系统栏区域
   - 实现真正的沉浸式体验

2. **window.statusBarColor = Color.TRANSPARENT**
   - 状态栏背景透明
   - 内容可以透过状态栏显示

3. **android:fitsSystemWindows="true"**
   - 在各个页面的根布局中添加
   - 自动添加状态栏高度的 padding
   - 避免内容被状态栏遮挡

## 完整的页面列表

### ✅ 已完成透明状态栏的页面（8个）

| 页面 | 类型 | 文件 |
|------|------|------|
| 主界面 | Activity | MainActivity.kt |
| 首页 | Fragment | HomeFragment.kt |
| 历史 | Fragment | HistoryFragment.kt |
| 影视仓库 | Fragment | VideoSourceFragment.kt |
| 设置 | Fragment | SettingsFragment.kt |
| 本地视频列表 | Activity | LocalVideoActivity.kt |
| 本地视频文件夹 | Activity | LocalVideoFolderActivity.kt |
| 搜索 | Activity | SearchActivity.kt |
| 视频详情 | Activity | VideoDetailActivity.kt |

### 视觉效果

#### 状态栏
- ✅ 透明背景
- ✅ 深色图标（适配浅色背景）
- ✅ 内容延伸到状态栏下方
- ✅ 所有页面统一效果

#### 首页布局
- ✅ 视频列表从 2 列改为 3 列
- ✅ 更好地利用屏幕空间
- ✅ 显示更多内容

## 测试方法

运行测试脚本：
```powershell
./test_statusbar.ps1
```

或手动测试：
1. 编译并安装应用
2. 打开应用，查看首页状态栏是否透明
3. 查看首页视频列表是否一行 3 个
4. 切换到其他 Fragment（历史、影视仓库、设置）
5. 点击本地视频，查看列表和文件夹页面
6. 使用搜索功能
7. 进入视频详情页面
8. 验证所有页面的状态栏都是透明的

## 兼容性

- Android 6.0 (API 23) 及以上：完整支持深色状态栏图标
- Android 11 (API 30) 及以上：使用新的 WindowInsetsController API
- 低于 Android 6.0：状态栏透明但图标可能不够清晰

## 代码结构

### 新增文件
- `app/src/main/java/com/tv/ball/utils/StatusBarUtils.kt` - 状态栏工具类

### 修改的文件

**Kotlin 代码：**
- `app/src/main/java/com/tv/ball/presentation/MainActivity.kt`
- `app/src/main/java/com/tv/ball/presentation/home/HomeFragment.kt`
- `app/src/main/java/com/tv/ball/presentation/localvideo/LocalVideoActivity.kt`
- `app/src/main/java/com/tv/ball/presentation/localvideo/LocalVideoFolderActivity.kt`
- `app/src/main/java/com/tv/ball/presentation/search/SearchActivity.kt`
- `app/src/main/java/com/tv/ball/presentation/detail/VideoDetailActivity.kt`

**布局文件：**
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/layout/activity_local_video.xml`
- `app/src/main/res/layout/activity_local_video_folder.xml`
- `app/src/main/res/layout/activity_search.xml`
- `app/src/main/res/layout/activity_video_detail.xml`
- `app/src/main/res/layout/fragment_home.xml`
- `app/src/main/res/layout/fragment_history.xml`
- `app/src/main/res/layout/fragment_video_source.xml`
- `app/src/main/res/layout/fragment_settings.xml`

**测试和文档：**
- `test_statusbar.ps1` - 测试脚本
- `STATUSBAR_FIX.md` - 本文档

## 注意事项

1. **状态栏透明后的对比度**
   - 确保顶部内容有足够的对比度
   - 使用白色或浅色背景的页面效果最佳

2. **fitsSystemWindows 的使用**
   - MainActivity 不使用（让内容延伸）
   - 所有 Fragment 和子 Activity 使用（自动添加 padding）

3. **工具类的使用**
   - 所有需要透明状态栏的 Activity 都调用 `StatusBarUtils.setupTransparentStatusBar(this)`
   - 在 `onCreate()` 中 `setContentView()` 之后调用

4. **深色主题支持**
   - 如果需要支持深色主题，可以使用 `lightStatusBar = false` 参数
   - 深色背景应该使用浅色图标

## 优势

1. **代码复用**
   - 统一的工具类，避免重复代码
   - 易于维护和更新

2. **一致性**
   - 所有页面使用相同的状态栏样式
   - 提供统一的用户体验

3. **易于扩展**
   - 新页面只需一行代码即可应用透明状态栏
   - 工具类支持自定义配置

4. **兼容性好**
   - 自动处理不同 Android 版本的 API 差异
   - 向下兼容到 Android 6.0

