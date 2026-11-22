# UI 设计规范

## 状态栏和顶栏设计规则

### 状态栏
- 状态栏必须设置为透明：`android:statusBarColor="@android:color/transparent"`
- 使用浅色状态栏图标：`android:windowLightStatusBar="true"`
- 所有页面都要添加：`android:fitsSystemWindows="true"`

### 顶部工具栏 (Toolbar)
- 背景色使用白色：`android:background="@color/white"`
- 标题文字颜色：`app:titleTextColor="@color/theme_text_primary"`
- 返回图标颜色：`app:navigationIconTint="@color/theme_text_primary"`
- 去除阴影：`android:elevation="0dp"`

### 特殊页面布局规则
- 短剧页面（ShortDramaActivity）：不需要 Toolbar，直接从 TabLayout 开始
- 根布局不需要 `android:fitsSystemWindows="true"`（因为没有 Toolbar）
- 只保留 TabLayout + SwipeRefreshLayout + RecyclerView 的简洁结构

### 示例代码
```xml
<!-- 标准页面（带 Toolbar） -->
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fitsSystemWindows="true"
    android:orientation="vertical">

    <com.google.android.material.appbar.MaterialToolbar
        android:id="@+id/toolbar"
        android:layout_width="match_parent"
        android:layout_height="?attr/actionBarSize"
        android:background="@color/white"
        android:elevation="0dp"
        app:navigationIcon="@drawable/back"
        app:navigationIconTint="@color/theme_text_primary"
        app:title="页面标题"
        app:titleTextColor="@color/theme_text_primary" />
</LinearLayout>

<!-- 短剧页面（无 Toolbar） -->
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="@color/theme_background">

    <com.google.android.material.tabs.TabLayout
        android:id="@+id/tabLayout"
        android:layout_width="match_parent"
        android:layout_height="wrap_content" />
    
    <!-- 内容区域 -->
</LinearLayout>
```

## 主题配色

主题配色：
- 主色 (theme_primary): #2196F3 (亮蓝色)
- 强调色 (theme_accent): #FF5722 (橙红色)
- 文本主色 (theme_text_primary): #212121 (深色文本)
- 文本副色 (theme_text_secondary): #757575 (浅灰色文本)
- 背景色 (theme_background): #FFFFFF (纯白色)
- 卡片色 (theme_card): #13C5CD (青兰色)

## 字体

使用自定义字体：
- 普通字体：product.ttf
- 粗体字体：product_bold.ttf
- 在主题中全局应用：`android:fontFamily="@font/custom_font"`

## Tab 样式

### 选中状态
- 颜色：橙红色 (#FF5722)
- 字体：粗体 (Typeface.BOLD)
- 字号：20sp

### 未选中状态
- 颜色：浅灰色 (#757575)
- 字体：普通 (Typeface.NORMAL)
- 字号：16sp

### Tab 配置
- 去除下划线指示器：`app:tabIndicatorHeight="0dp"`
- 去除点击波纹效果：`app:tabRippleColor="@android:color/transparent"`
- 增大点击区域：`app:tabMinWidth="60dp"`
- 设置内边距：`app:tabPaddingStart="12dp"`, `app:tabPaddingEnd="12dp"`

## 底部导航栏

### 配置
- 去除点击波纹：`app:itemRippleColor="@android:color/transparent"`
- 文字默认显示：`app:labelVisibilityMode="labeled"`

### 图标
- 首页：home.png
- 历史：tips.png
- 项目列表：tips.png
- 设置：center.png

## 快捷入口图标

设置页面顶部的快捷入口使用以下图标：
- 历史收藏：history_collection.png (原 9o.png)
- 本地视频：vn.png
- 高清收藏：ic_pv.png (原 pV.png)
- 扩展工具：ic_lw.png (原 lW.png)

## 构建和安装流程

每次修改后必须执行：
1. `./gradlew assembleDebug` - 构建 APK
2. `adb install -r app/build/outputs/apk/debug/app-debug.apk` - 安装到设备

## 注意事项

1. 所有新建的 Activity 必须在 AndroidManifest.xml 中注册
2. 图标文件名必须小写，只能包含字母、数字和下划线
3. 图标文件名不能以数字开头
4. 所有页面都要设置透明状态栏和 fitsSystemWindows
5. 工具栏背景统一使用白色，文字使用深色
