# 视频源自动切换功能

## 功能概述

当首页加载视频内容失败时，应用会自动尝试切换到其他可用的视频源，确保用户能够正常观看内容。

## 工作原理

### 1. 自动检测与切换

- **触发条件**: 当首页刷新加载视频失败时（网络错误或解析失败）
- **切换策略**: 
  - 优先测试 API 类型的视频源（响应更快，更稳定）
  - 其次测试 WEB 类型的视频源
  - 跳过最近5分钟内已标记为失败的视频源
- **最大尝试次数**: 最多自动切换3次，避免无限循环

### 2. 视频源测试

系统会快速测试每个备用视频源：
- 发送测试请求到视频源API
- 验证响应内容是否包含有效的视频数据
- 测试通过后立即切换并重新加载

### 3. 失败记录管理

- **记录失败源**: 自动记录无法访问的视频源
- **冷却时间**: 5分钟后自动清除失败记录，允许重试
- **手动重置**: 用户可以手动重置所有失败记录

## 用户体验

### 自动切换提示

当视频源自动切换时，会显示Toast提示：
```
当前源不可用，已自动切换到: [新视频源名称]
```

### 切换失败提示

如果所有视频源都不可用，会显示：
```
已尝试多个视频源，请检查网络连接
```

## 手动重置功能

如果需要手动重置视频源失败记录：

1. 进入 **设置** 页面
2. **长按** "清理缓存" 选项
3. 在弹出的对话框中点击 **确定**

这将清除所有失败记录，允许系统重新尝试所有视频源。

## 技术实现

### 核心类

#### VideoSourceAutoSwitcher
```kotlin
// 位置: app/src/main/java/com/tv/ball/data/VideoSourceAutoSwitcher.kt

// 主要方法:
- switchToAvailableSource(): 切换到可用的视频源
- testSource(): 测试单个视频源是否可用
- resetFailedSources(): 重置失败记录
```

#### HomeViewModel
```kotlin
// 位置: app/src/main/java/com/tv/ball/presentation/home/HomeViewModel.kt

// 集成点:
- loadVideos(): 在网络错误时触发自动切换
- 最大尝试次数: MAX_AUTO_SWITCH_ATTEMPTS = 3
```

### 配置参数

```kotlin
// 重试冷却时间（5分钟）
private const val RETRY_COOLDOWN = 5 * 60 * 1000L

// 最大自动切换次数
private const val MAX_AUTO_SWITCH_ATTEMPTS = 3
```

## 支持的视频源

当前支持自动切换的视频源包括：

### API 类型（优先级高）
- 爱奇艺 (iqiyi)
- 360源 (360zy)
- 非凡通道 (ffzy5)
- 豆瓣通道 (dbzy)
- 牛牛通道 (niuniuzy3)
- 红牛资源 (hongniu)

### WEB 类型（优先级低）
- LiB (libvod)
- 可可 (keke)
- 在线之家 (zxzj)

## 网络优化

配合 `SmartNetworkSelector` 实现：
- WiFi 优先策略
- WiFi 不可用时自动切换到移动数据
- 快速网络可用性测试（100ms超时）

## 日志输出

所有切换操作都会输出详细日志，便于调试：

```
VideoSourceAutoSwitcher: 测试视频源: iqiyi
VideoSourceAutoSwitcher: ✓ 视频源 iqiyi 可用
VideoSourceAutoSwitcher: ✓ 找到可用视频源: 爱奇艺
```

## 注意事项

1. **网络要求**: 需要设备有可用的网络连接
2. **权限要求**: 需要 INTERNET 权限
3. **性能影响**: 自动切换会增加少量加载时间（通常<2秒）
4. **用户体验**: 切换过程对用户透明，只显示简短提示

## 未来改进

- [ ] 添加视频源响应时间统计
- [ ] 根据历史成功率智能排序视频源
- [ ] 支持用户自定义视频源优先级
- [ ] 添加视频源健康度监控面板
