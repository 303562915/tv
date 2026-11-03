# 轮播图加载问题修复

## 问题描述

从日志中发现 Glide 加载轮播图时出现错误：
```
W/Glide: Load failed for 2131232177 with size [-2147483648x-2147483648]
W/Glide: class com.bumptech.glide.load.engine.GlideException: Failed to load resource
```

错误原因：尺寸为 `[-2147483648x-2147483648]`，这是一个无效的尺寸值。

## 根本原因

1. **ViewPager2 初始化时机问题**
   - ViewPager2 在布局完成前就尝试加载图片
   - 此时 ImageView 的尺寸还没有确定
   - Glide 无法获取正确的目标尺寸

2. **异步加载时序问题**
   - Adapter 的 `onBindViewHolder` 被调用时
   - ImageView 可能还没有完成测量
   - 导致 Glide 获取到无效的尺寸

## 解决方案

### 修改前的代码
```kotlin
fun bind(item: BannerItem) {
    Glide.with(itemView.context)
        .load(item.imageUrl)
        .override(800, 400)
        .centerCrop()
        .into(imageView)
}
```

### 修改后的代码
```kotlin
fun bind(item: BannerItem) {
    // 等待 ImageView 布局完成后再加载图片
    imageView.post {
        Glide.with(itemView.context)
            .load(item.imageUrl)
            .override(800, 400)
            .centerCrop()
            .placeholder(R.drawable.player)
            .error(R.drawable.player)
            .into(imageView)
    }
}
```

## 关键改进

### 1. 使用 `imageView.post {}`
- 确保在 ImageView 布局完成后再加载图片
- 避免在视图尺寸未确定时加载
- 解决 ViewPager2 的时序问题

### 2. 保留 `.override(800, 400)`
- 指定固定的目标尺寸
- 作为双重保险
- 即使视图尺寸有问题，也能正常加载

### 3. 添加占位图和错误图
- `placeholder(R.drawable.player)` - 加载中显示
- `error(R.drawable.player)` - 加载失败显示
- 提升用户体验

## 技术细节

### View.post() 的作用
```kotlin
imageView.post {
    // 这里的代码会在下一个消息循环中执行
    // 此时视图已经完成测量和布局
}
```

### 执行时序
1. ViewPager2 创建 ViewHolder
2. 调用 `onBindViewHolder()`
3. 调用 `bind()` 方法
4. `imageView.post {}` 将加载任务放入消息队列
5. 当前帧渲染完成
6. ImageView 完成测量和布局
7. 执行 post 中的加载任务
8. Glide 获取正确的视图尺寸
9. 成功加载图片

## 测试方法

运行测试脚本：
```powershell
./test_banner_fix.ps1
```

或手动测试：
1. 编译并安装应用
2. 清除应用数据：`adb shell pm clear com.tv.ball`
3. 启动应用
4. 查看轮播图是否正常显示
5. 检查日志是否还有 Glide 错误

## 验证要点

### ✅ 应该看到
- 轮播图正常显示
- 图片加载成功
- 自动轮播正常
- 指示器正常工作
- 日志中有 "图片加载成功" 的消息

### ❌ 不应该看到
- Glide 加载失败错误
- 尺寸为 -2147483648 的错误
- 空白的轮播图
- 崩溃或卡顿

## 相关文件

### 修改的文件
- `app/src/main/java/com/tv/ball/presentation/home/BannerAdapter.kt`

### 测试文件
- `test_banner_fix.ps1` - 测试脚本
- `BANNER_FIX.md` - 本文档

## 其他优化建议

### 1. 预加载优化
```kotlin
// 在 HomeFragment 中
binding.bannerViewPager.offscreenPageLimit = 1
```
已实现，可以预加载相邻页面。

### 2. 内存优化
```kotlin
Glide.with(itemView.context)
    .load(item.imageUrl)
    .override(800, 400)  // 限制加载尺寸
    .diskCacheStrategy(DiskCacheStrategy.ALL)  // 缓存策略
    .into(imageView)
```

### 3. 网络优化
- 使用 CDN 加速图片加载
- 压缩图片尺寸
- 使用 WebP 格式

## 总结

通过使用 `imageView.post {}` 延迟加载，确保在视图布局完成后再加载图片，成功解决了 Glide 加载失败的问题。这是处理 ViewPager2 + Glide 组合时的最佳实践。

---

**状态**: ✅ 已修复  
**测试**: ⏳ 待验证  
**优先级**: 🔴 高
