# 图标和菜单改进说明

## 改进内容

### 1. 新的灰色媒体图标 ✅

创建了三个新的灰色图标供选择：

#### ic_film_gray.xml (推荐使用)
- 胶片风格图标
- 灰色 (#757575)
- 带有胶片孔和播放区域
- 最符合视频下载的主题

#### ic_video_file_gray.xml
- 视频文件图标
- 灰色 (#757575)
- 简洁的摄像机样式

#### ic_media_video_gray.xml
- 媒体播放器图标
- 灰色 (#757575)
- 带播放按钮的胶片框

### 2. 改进的长按菜单 ✅

#### 修复的问题
- 之前：只有 M3U8 且状态为 PAUSED 才显示菜单
- 现在：所有 M3U8 任务都可以长按显示菜单

#### 菜单内容（根据任务状态动态显示）

**M3U8 任务（暂停/失败状态）**
- ✅ 合并 M3U8 分片
- ✅ 继续下载
- ✅ 删除任务
- ✅ 复制链接

**M3U8 任务（已完成状态）**
- ✅ 播放视频
- ✅ 删除任务
- ✅ 复制链接

**普通任务（暂停/失败状态）**
- ✅ 继续下载
- ✅ 删除任务
- ✅ 复制链接

### 3. 详细的调试日志 ✅

添加了完整的日志输出：

```
=== 长按事件触发 ===
任务: [任务名称]
isM3u8: [true/false]
状态: [状态]
isSelectionMode: [true/false]
onMergeClick != null: [true/false]
显示 M3U8 上下文菜单
显示弹出菜单
菜单项点击: [菜单项名称]
```

## 使用方法

### 查看新图标
新图标已应用到下载列表项：
- 位置：`app/src/main/res/drawable/ic_film_gray.xml`
- 使用：在 `item_download.xml` 中已设置

### 长按显示菜单
1. 长按任何 M3U8 任务
2. 会显示上下文菜单
3. 根据任务状态显示不同选项

### 合并 M3U8 分片
1. 长按 M3U8 任务
2. 选择"合并 M3U8 分片"
3. 确认合并
4. 等待完成

## 图标对比

### 原图标 (szg)
- 可能显示为黑点
- 颜色不统一
- 不够清晰

### 新图标 (ic_film_gray)
- 统一的灰色 (#757575)
- 清晰的胶片样式
- 符合视频下载主题
- 与 Material Design 风格一致

## 图标预览

```
ic_film_gray.xml:
┌─────────────┐
│ ▪ ▪ ▪ ▪ ▪ ▪ │  <- 胶片孔
│             │
│   ┌─────┐   │  <- 播放区域
│   │     │   │
│   └─────┘   │
│             │
│ ▪ ▪ ▪ ▪ ▪ ▪ │  <- 胶片孔
└─────────────┘
```

## 技术细节

### 图标规格
- 尺寸：24dp x 24dp
- 视口：24 x 24
- 颜色：#757575 (Material Design Gray 600)
- 格式：Vector Drawable (XML)

### 布局更新
```xml
<ImageView
    android:id="@+id/ivIcon"
    android:layout_width="28dp"
    android:layout_height="28dp"
    android:src="@drawable/ic_film_gray"
    android:scaleType="fitCenter"
    android:layout_gravity="center" />
```

### 适配器更新
```kotlin
binding.ivIcon.setImageResource(com.tv.ball.R.drawable.ic_film_gray)
```

## 菜单逻辑

### 长按条件
```kotlin
if (task.isM3u8 && onMergeClick != null) {
    showContextMenu(task)
}
```

### 菜单项条件
```kotlin
// M3U8 且暂停/失败
if (task.isM3u8 && (task.status == PAUSED || task.status == FAILED)) {
    menu.add("合并 M3U8 分片")
}

// 已完成
if (task.status == COMPLETED) {
    menu.add("播放视频")
}

// 暂停/失败
if (task.status == PAUSED || task.status == FAILED) {
    menu.add("继续下载")
}
```

## 测试建议

### 测试图标显示
1. 打开下载列表
2. 查看任务图标
3. 确认显示为灰色胶片图标

### 测试长按菜单
1. 长按 M3U8 任务（任何状态）
2. 确认显示菜单
3. 查看 Logcat 日志

### 测试合并功能
1. 下载 M3U8 视频
2. 暂停下载
3. 长按任务
4. 选择"合并 M3U8 分片"
5. 确认合并成功

## 故障排除

### 问题1: 长按没有显示菜单
- 检查 Logcat 日志
- 确认 `isM3u8` 为 true
- 确认 `onMergeClick` 不为 null

### 问题2: 图标显示不正确
- 清理并重新构建项目
- 检查 drawable 文件是否存在
- 确认 R.drawable.ic_film_gray 可访问

### 问题3: 菜单项不显示
- 检查任务状态
- 查看日志中的状态信息
- 确认菜单逻辑条件

## 相关文件

- `app/src/main/res/drawable/ic_film_gray.xml` - 灰色胶片图标
- `app/src/main/res/drawable/ic_video_file_gray.xml` - 灰色视频文件图标
- `app/src/main/res/drawable/ic_media_video_gray.xml` - 灰色媒体图标
- `app/src/main/res/layout/item_download.xml` - 下载列表项布局
- `app/src/main/java/com/tv/ball/presentation/download/DownloadAdapter.kt` - 适配器
