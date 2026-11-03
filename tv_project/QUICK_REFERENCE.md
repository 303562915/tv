# 快速参考 - 透明状态栏实现

## 如何给新页面添加透明状态栏？

### 对于 Activity

1. 在 `onCreate()` 中添加一行代码：
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityYourBinding.inflate(layoutInflater)
    setContentView(binding.root)
    
    // 添加这一行
    com.tv.ball.utils.StatusBarUtils.setupTransparentStatusBar(this)
    
    // 其他初始化代码...
}
```

2. 在布局文件的根布局添加属性：
```xml
<YourRootLayout
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fitsSystemWindows="true">
    
    <!-- 你的内容 -->
    
</YourRootLayout>
```

### 对于 Fragment

Fragment 不需要在代码中设置，只需要在布局文件的根布局添加：
```xml
<YourRootLayout
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fitsSystemWindows="true">
    
    <!-- 你的内容 -->
    
</YourRootLayout>
```

## 已完成的页面清单

✅ MainActivity
✅ HomeFragment
✅ HistoryFragment
✅ VideoSourceFragment
✅ SettingsFragment
✅ LocalVideoActivity
✅ LocalVideoFolderActivity
✅ SearchActivity
✅ VideoDetailActivity

## 常见问题

### Q: 为什么内容被状态栏遮挡了？
A: 确保布局文件的根布局添加了 `android:fitsSystemWindows="true"`

### Q: 如何让状态栏图标变成浅色（适配深色背景）？
A: 使用 `StatusBarUtils.setupTransparentStatusBar(this, lightStatusBar = false)`

### Q: Fragment 需要在代码中设置吗？
A: 不需要，Fragment 会继承 Activity 的状态栏设置，只需要在布局中添加 `fitsSystemWindows="true"`

### Q: 播放器页面需要透明状态栏吗？
A: 通常播放器页面是全屏的，不需要透明状态栏。如果需要，可以单独设置。

## 工具类 API

### StatusBarUtils.setupTransparentStatusBar()
```kotlin
/**
 * 设置透明沉浸式状态栏
 * @param activity Activity 实例
 * @param lightStatusBar 是否使用深色图标（适配浅色背景），默认为 true
 */
fun setupTransparentStatusBar(activity: Activity, lightStatusBar: Boolean = true)
```

### StatusBarUtils.setLightStatusBar()
```kotlin
/**
 * 设置状态栏图标为深色（适配浅色背景）
 */
fun setLightStatusBar(activity: Activity)
```

### StatusBarUtils.clearLightStatusBar()
```kotlin
/**
 * 设置状态栏图标为浅色（适配深色背景）
 */
fun clearLightStatusBar(activity: Activity)
```

## 示例代码

### 完整的 Activity 示例
```kotlin
class MyActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMyBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // 设置透明状态栏
        StatusBarUtils.setupTransparentStatusBar(this)
        
        // 其他初始化...
    }
}
```

### 对应的布局文件
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:fitsSystemWindows="true">

    <!-- Toolbar 或其他顶部内容 -->
    <androidx.appcompat.widget.Toolbar
        android:layout_width="match_parent"
        android:layout_height="?attr/actionBarSize"
        android:background="@color/white" />

    <!-- 主要内容 -->
    <FrameLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent">
        <!-- 你的内容 -->
    </FrameLayout>

</LinearLayout>
```
