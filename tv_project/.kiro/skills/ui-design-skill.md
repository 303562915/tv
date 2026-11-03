---
name: UI Design Skill
description: Android应用UI设计规范和常用组件模板
keywords: UI设计, 弹窗, 对话框, 底部弹窗, BottomSheet, 布局, 样式, 漂亮, 好看, 美化, 界面
activation: 当用户提到UI设计、弹窗样式、布局美化时自动激活
---

# UI Design Skill - Android应用UI设计规范

## 设计原则

### Material Design 3
- 使用圆角设计 (12-20dp)
- 使用阴影和层次感
- 使用合适的颜色和对比度
- 使用图标增强可读性
- 使用动画和过渡效果

### 颜色规范
```kotlin
// 主色调
Primary: #1976D2 (蓝色)
PrimaryLight: #E3F2FD
PrimaryDark: #0D47A1

// 强调色
Accent: #FF9800 (橙色)
AccentLight: #FFE0B2

// 文字颜色
TextPrimary: #212121
TextSecondary: #757575
TextHint: #9E9E9E

// 背景色
Background: #FFFFFF
BackgroundGray: #F5F5F5
Divider: #E0E0E0
```

## 常用组件模板

### 1. 底部弹窗 (BottomSheet)

**使用场景**: 选择操作、显示详情、网盘资源处理

**布局特点**:
- 顶部圆角 (20dp)
- 顶部指示条 (40x4dp, 圆角2dp)
- 标题区域 (图标 + 标题 + 副标题)
- 内容区域
- 操作按钮区域
- 取消按钮

**示例**: `bottom_sheet_cloud_storage.xml`

```xml
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:background="@drawable/bg_bottom_sheet">
    
    <!-- 顶部指示条 -->
    <View
        android:layout_width="40dp"
        android:layout_height="4dp"
        android:layout_gravity="center_horizontal"
        android:background="@drawable/bg_bottom_sheet_indicator" />
    
    <!-- 标题区域 -->
    <LinearLayout
        android:orientation="horizontal"
        android:gravity="center_vertical">
        
        <ImageView
            android:id="@+id/icon"
            android:layout_width="32dp"
            android:layout_height="32dp" />
        
        <LinearLayout
            android:orientation="vertical">
            
            <TextView
                android:id="@+id/title"
                android:textSize="18sp"
                android:textStyle="bold"
                android:textColor="#212121" />
            
            <TextView
                android:id="@+id/subtitle"
                android:textSize="13sp"
                android:textColor="#757575" />
        </LinearLayout>
    </LinearLayout>
    
    <!-- 操作按钮 -->
    <LinearLayout
        android:orientation="horizontal">
        
        <LinearLayout
            android:id="@+id/btnPrimary"
            android:background="@drawable/bg_button_primary"
            android:gravity="center">
            
            <ImageView
                android:layout_width="24dp"
                android:layout_height="24dp" />
            
            <TextView
                android:textColor="@android:color/white"
                android:textStyle="bold" />
        </LinearLayout>
        
        <LinearLayout
            android:id="@+id/btnSecondary"
            android:background="@drawable/bg_button_secondary"
            android:gravity="center">
            
            <ImageView
                android:layout_width="24dp"
                android:layout_height="24dp" />
            
            <TextView
                android:textColor="#1976D2"
                android:textStyle="bold" />
        </LinearLayout>
    </LinearLayout>
    
    <!-- 取消按钮 -->
    <TextView
        android:id="@+id/btnCancel"
        android:text="取消"
        android:gravity="center"
        android:textColor="#757575" />
</LinearLayout>
```

**Kotlin代码**:
```kotlin
private fun showBottomSheet() {
    val bottomSheetDialog = BottomSheetDialog(this)
    val view = layoutInflater.inflate(R.layout.bottom_sheet_xxx, null)
    
    // 设置内容
    view.findViewById<TextView>(R.id.title).text = "标题"
    view.findViewById<TextView>(R.id.subtitle).text = "副标题"
    
    // 设置按钮点击
    view.findViewById<View>(R.id.btnPrimary).setOnClickListener {
        // 主要操作
        bottomSheetDialog.dismiss()
    }
    
    view.findViewById<View>(R.id.btnSecondary).setOnClickListener {
        // 次要操作
        bottomSheetDialog.dismiss()
    }
    
    view.findViewById<View>(R.id.btnCancel).setOnClickListener {
        bottomSheetDialog.dismiss()
    }
    
    bottomSheetDialog.setContentView(view)
    bottomSheetDialog.show()
}
```

### 2. 按钮样式

**主要按钮** (`bg_button_primary.xml`):
```xml
<ripple android:color="#40FFFFFF">
    <item>
        <shape android:shape="rectangle">
            <solid android:color="#1976D2" />
            <corners android:radius="12dp" />
        </shape>
    </item>
</ripple>
```

**次要按钮** (`bg_button_secondary.xml`):
```xml
<ripple android:color="#201976D2">
    <item>
        <shape android:shape="rectangle">
            <solid android:color="#E3F2FD" />
            <corners android:radius="12dp" />
        </shape>
    </item>
</ripple>
```

**文字按钮**:
```xml
<TextView
    android:background="?attr/selectableItemBackground"
    android:clickable="true"
    android:focusable="true" />
```

### 3. 卡片样式

```xml
<androidx.cardview.widget.CardView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:cardCornerRadius="12dp"
    app:cardElevation="2dp"
    app:cardBackgroundColor="@android:color/white">
    
    <!-- 卡片内容 -->
    
</androidx.cardview.widget.CardView>
```

### 4. 列表项样式

```xml
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:padding="16dp"
    android:background="?attr/selectableItemBackground"
    android:clickable="true"
    android:focusable="true">
    
    <ImageView
        android:layout_width="48dp"
        android:layout_height="48dp" />
    
    <LinearLayout
        android:layout_width="0dp"
        android:layout_weight="1"
        android:orientation="vertical">
        
        <TextView
            android:textSize="16sp"
            android:textColor="#212121"
            android:textStyle="bold" />
        
        <TextView
            android:textSize="14sp"
            android:textColor="#757575" />
    </LinearLayout>
    
    <ImageView
        android:layout_width="24dp"
        android:layout_height="24dp"
        android:src="@drawable/ic_arrow_right" />
</LinearLayout>
```

### 5. 输入框样式

```xml
<com.google.android.material.textfield.TextInputLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:boxCornerRadiusTopStart="12dp"
    app:boxCornerRadiusTopEnd="12dp"
    app:boxCornerRadiusBottomStart="12dp"
    app:boxCornerRadiusBottomEnd="12dp">
    
    <com.google.android.material.textfield.TextInputEditText
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="请输入..." />
</com.google.android.material.textfield.TextInputLayout>
```

## 常用Drawable资源

### 圆角背景
```xml
<!-- bg_rounded.xml -->
<shape android:shape="rectangle">
    <solid android:color="@android:color/white" />
    <corners android:radius="12dp" />
</shape>
```

### 渐变背景
```xml
<!-- bg_gradient.xml -->
<shape android:shape="rectangle">
    <gradient
        android:startColor="#1976D2"
        android:endColor="#0D47A1"
        android:angle="135" />
    <corners android:radius="12dp" />
</shape>
```

### 边框
```xml
<!-- bg_border.xml -->
<shape android:shape="rectangle">
    <stroke
        android:width="1dp"
        android:color="#E0E0E0" />
    <corners android:radius="12dp" />
</shape>
```

### 波纹效果
```xml
<!-- bg_ripple.xml -->
<ripple android:color="#20000000">
    <item>
        <shape android:shape="rectangle">
            <solid android:color="@android:color/white" />
            <corners android:radius="12dp" />
        </shape>
    </item>
</ripple>
```

## 尺寸规范

```xml
<!-- dimens.xml -->
<resources>
    <!-- 间距 -->
    <dimen name="spacing_tiny">4dp</dimen>
    <dimen name="spacing_small">8dp</dimen>
    <dimen name="spacing_medium">16dp</dimen>
    <dimen name="spacing_large">24dp</dimen>
    <dimen name="spacing_xlarge">32dp</dimen>
    
    <!-- 圆角 -->
    <dimen name="corner_small">8dp</dimen>
    <dimen name="corner_medium">12dp</dimen>
    <dimen name="corner_large">16dp</dimen>
    <dimen name="corner_xlarge">20dp</dimen>
    
    <!-- 文字大小 -->
    <dimen name="text_tiny">11sp</dimen>
    <dimen name="text_small">13sp</dimen>
    <dimen name="text_medium">15sp</dimen>
    <dimen name="text_large">18sp</dimen>
    <dimen name="text_xlarge">22sp</dimen>
    
    <!-- 图标大小 -->
    <dimen name="icon_small">20dp</dimen>
    <dimen name="icon_medium">24dp</dimen>
    <dimen name="icon_large">32dp</dimen>
    <dimen name="icon_xlarge">48dp</dimen>
</resources>
```

## 实战案例

### 网盘资源底部弹窗

**需求**: 显示网盘资源信息,提供"浏览器打开"和"复制链接"两个操作

**设计要点**:
1. 使用底部弹窗 (BottomSheetDialog)
2. 顶部显示网盘类型图标和标题
3. 显示完整链接
4. 两个操作按钮并排显示
5. 主要操作使用蓝色背景
6. 次要操作使用浅蓝色背景
7. 底部有取消按钮

**文件**:
- 布局: `bottom_sheet_cloud_storage.xml`
- 使用: `VideoDetailActivity.kt` 中的 `showCloudStorageDialog()`

## 激活关键词

当用户说以下内容时,立即激活此skill:
- "UI设计"
- "弹窗"
- "对话框"
- "底部弹窗"
- "BottomSheet"
- "布局"
- "样式"
- "漂亮"
- "好看"
- "美化"
- "界面"
- "太丑"
- "这个样式"
- "通用弹窗"

## 记住

1. **优先使用BottomSheet而不是AlertDialog**
2. **使用Material Design 3设计规范**
3. **保持一致的圆角、间距、颜色**
4. **使用图标增强可读性**
5. **主要操作使用蓝色,次要操作使用浅蓝色**
6. **所有可点击元素添加波纹效果**
7. **使用合适的padding和margin**
8. **文字大小和颜色要有层次感**
