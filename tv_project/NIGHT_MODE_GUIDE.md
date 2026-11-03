# 夜间模式适配指南

## 已完成的工作

### 1. 核心功能实现

#### ConfigRepository 扩展
- 添加了 `getNightMode()` 和 `setNightMode()` 方法
- 支持三种模式：
  - `NIGHT_MODE_FOLLOW_SYSTEM (0)` - 跟随系统
  - `NIGHT_MODE_OFF (1)` - 浅色模式
  - `NIGHT_MODE_ON (2)` - 深色模式

#### ThemeUtils 扩展
- 添加了 `applyNightMode()` 方法
- 使用 `AppCompatDelegate.setDefaultNightMode()` 实现全局夜间模式切换

#### Application 初始化
- 在 `VideoPlayerApplication.onCreate()` 中应用保存的夜间模式设置
- 确保应用启动时立即应用用户选择的模式

#### MainActivity 适配
- 在 `onCreate()` 中应用夜间模式设置（在 super.onCreate 之前）
- 确保每次启动 Activity 时都应用正确的模式

### 2. UI 界面实现

#### ThemeActivity 更新
- 添加了夜间模式选择卡片
- 三个选项：跟随系统、浅色模式、深色模式
- 实时切换，立即生效
- 显示当前选中状态

#### 布局文件适配
已将以下布局文件中的硬编码颜色值替换为主题属性：

- `activity_theme.xml`
  - 背景色：`?android:attr/colorBackground`
  - 卡片背景：`?attr/colorSurface`
  - 文字颜色：`?attr/colorOnSurface` 和 `?android:attr/textColorSecondary`

- `fragment_settings.xml`
  - 文字颜色：`?android:attr/textColorPrimary` 和 `?android:attr/textColorSecondary`
  - 分割线：`@color/theme_divider`

- `fragment_home.xml`
  - 背景色：`?attr/colorSurface`
  - 文字颜色：`?android:attr/textColorSecondary`

### 3. 颜色资源配置

#### values/colors.xml (浅色模式)
```xml
<color name="theme_background">#FFFFFF</color>
<color name="theme_card">#4ECDC4</color>
<color name="theme_text_primary">#212121</color>
<color name="theme_text_secondary">#757575</color>
<color name="theme_card_background">#F5F5F5</color>
<color name="theme_divider">#E0E0E0</color>
```

#### values-night/colors.xml (深色模式)
```xml
<color name="theme_background">#121212</color>
<color name="theme_card">#1E1E1E</color>
<color name="theme_text_primary">#E0E0E0</color>
<color name="theme_text_secondary">#B0B0B0</color>
<color name="theme_card_background">#1E1E1E</color>
<color name="theme_divider">#2C2C2C</color>
```

## 使用方法

### 用户操作
1. 打开应用
2. 进入"设置"页面
3. 点击"更换主题色"
4. 在"夜间模式"卡片中选择：
   - 跟随系统：根据系统设置自动切换
   - 浅色模式：始终使用浅色主题
   - 深色模式：始终使用深色主题
5. 选择后立即生效

### 开发者使用
```kotlin
// 获取当前夜间模式
val nightMode = Dependencies.configRepository.getNightMode()

// 设置夜间模式
Dependencies.configRepository.setNightMode(ConfigRepository.NIGHT_MODE_ON)

// 应用夜间模式
ThemeUtils.applyNightMode(ConfigRepository.NIGHT_MODE_ON)
```

## 注意事项

1. **布局文件适配**
   - 使用 `?attr/colorSurface` 代替硬编码的背景色
   - 使用 `?android:attr/textColorPrimary` 代替硬编码的文字颜色
   - 使用 `?android:attr/textColorSecondary` 代替硬编码的次要文字颜色
   - 使用 `@color/theme_divider` 代替硬编码的分割线颜色

2. **主题配置**
   - 确保 `values/themes.xml` 和 `values-night/themes.xml` 都正确配置
   - 使用 `Theme.MaterialComponents.DayNight.NoActionBar` 作为父主题

3. **即时生效**
   - 使用 `AppCompatDelegate.setDefaultNightMode()` 可以立即切换模式
   - 不需要重启应用

## 待优化项

1. **更多布局文件适配**
   - 还有一些布局文件可能包含硬编码颜色值
   - 建议逐步检查并替换

2. **自定义 View 适配**
   - 如果有自定义 View，需要确保它们也支持夜间模式
   - 在 `onConfigurationChanged()` 中处理主题变化

3. **图片资源适配**
   - 某些图标可能需要提供夜间模式版本
   - 使用 `drawable-night` 文件夹存放夜间模式图标

## 测试建议

1. 测试三种模式切换是否正常
2. 测试应用重启后是否保持选择的模式
3. 测试"跟随系统"模式是否正确响应系统设置变化
4. 检查所有页面在夜间模式下的显示效果
5. 确保文字在深色背景上清晰可读
