# UI调整说明

## 修改内容

### 1. 深蓝色背景颜色调整
**问题**：原来的深蓝色 `#1E3A8A` 不够深  
**修改**：改为更深的蓝色 `#1A237E`（Material Design Indigo 900）

```xml
<!-- 修改前 -->
<color name="episode_movie_bg">#1E3A8A</color>

<!-- 修改后 -->
<color name="episode_movie_bg">#1A237E</color>
```

### 2. 选集item高度调整
**问题**："正片"按钮太大（64dp）  
**修改**：降低高度到 48dp

```xml
<!-- 修改前 -->
android:layout_height="64dp"

<!-- 修改后 -->
android:layout_height="48dp"
```

### 3. 选集item文字大小调整
**问题**：文字太大，占用空间多  
**修改**：
- 集数文字：15sp → 13sp
- 集名称文字：8sp → 7sp
- VIP标签：7sp → 6sp

```xml
<!-- 集数 -->
<!-- 修改前：15sp，修改后：13sp -->
android:textSize="13sp"

<!-- 集名称 -->
<!-- 修改前：8sp，修改后：7sp -->
android:textSize="7sp"

<!-- VIP标签 -->
<!-- 修改前：7sp，修改后：6sp -->
android:textSize="6sp"
```

### 4. 选集item内边距调整
**问题**：内边距太大（4dp）  
**修改**：减小到 2dp

```xml
<!-- 修改前 -->
android:padding="4dp"

<!-- 修改后 -->
android:padding="2dp"
```

### 5. 取消分割线
**问题**：功能按钮区域和选集区域之间有分割线  
**修改**：删除分割线View

```xml
<!-- 删除以下代码 -->
<View
    android:layout_width="match_parent"
    android:layout_height="1dp"
    android:background="#2A2A2A" />
```

## 修改的文件

1. **`app/src/main/res/values/colors.xml`**
   - 更新 `episode_movie_bg` 颜色

2. **`app/src/main/res/layout/item_episode_tencent.xml`**
   - 调整CardView高度：64dp → 48dp
   - 调整LinearLayout内边距：4dp → 2dp
   - 调整集数文字大小：15sp → 13sp
   - 调整集名称文字大小：8sp → 7sp
   - 调整VIP标签大小：7sp → 6sp

3. **`app/src/main/res/layout/portrait_detail_section.xml`**
   - 删除功能按钮和选集区域之间的分割线

## 效果对比

### 修改前
- 背景色：较浅的蓝色 `#1E3A8A`
- 选集item高度：64dp（太高）
- 有分割线
- 文字较大

### 修改后
- 背景色：更深的蓝色 `#1A237E` ✅
- 选集item高度：48dp（更紧凑）✅
- 无分割线（更流畅）✅
- 文字更小（更精致）✅

## 视觉效果

```
┌─────────────────────────────────────┐
│  播放器视频区域                      │
└─────────────────────────────────────┘
┌─────────────────────────────────────┐
│  更深的蓝色背景 #1A237E             │
│                                     │
│  ⭐ 异星迷航                        │
│  ✨ 8.5分 · 8118播放                │
│                                     │
│  ┌────┐ ┌────┐ ┌────┐ ┌────┐      │
│  │下载│ │收藏│ │投屏│ │分享│      │
│  └────┘ └────┘ └────┘ └────┘      │
│  (无分割线)                         │
│  ⭐ 选集          全1集 ✨          │
│  ┌──────────────────────┐          │
│  │      正片            │ (48dp)   │
│  │      1集             │          │
│  └──────────────────────┘          │
└─────────────────────────────────────┘
```

## 颜色参考

| 颜色名称 | 颜色值 | 说明 |
|---------|--------|------|
| 更深的蓝色 | `#1A237E` | Material Design Indigo 900 |
| 金黄色 | `#FFD700` | 文字颜色 |
| 白色 | `#FFFFFF` | 普通模式背景 |
| 黑色 | `#000000` | 普通模式文字 |

## 尺寸参考

| 元素 | 原尺寸 | 新尺寸 |
|-----|--------|--------|
| 选集item高度 | 64dp | 48dp |
| 集数文字 | 15sp | 13sp |
| 集名称文字 | 8sp | 7sp |
| VIP标签 | 7sp | 6sp |
| 内边距 | 4dp | 2dp |
