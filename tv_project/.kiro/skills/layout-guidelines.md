# Android 布局设计规范

## 状态栏样式

### 透明沉浸式状态栏
所有 Activity 都应该使用透明沉浸式状态栏，参考首页的实现：

```kotlin
// 在 onCreate 中调用
com.tv.ball.utils.StatusBarUtils.setupTransparentStatusBar(this)
```

布局文件中设置：
```xml
<androidx.core.widget.NestedScrollView
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#FFF6F6F6"
    android:fillViewport="true"
    android:overScrollMode="never"
    android:fitsSystemWindows="true">
    
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:paddingBottom="80dp">
        
        <!-- 内容卡片 -->
    </LinearLayout>
</androidx.core.widget.NestedScrollView>
```

### 标准布局规范（参考设置页面）

#### 根布局
- 使用 `NestedScrollView` 而不是 `ScrollView`
- 必须设置 `android:fitsSystemWindows="true"` 以正确处理状态栏
- 背景色：`#FFF6F6F6` (浅灰色)
- 设置 `android:fillViewport="true"` 和 `android:overScrollMode="never"`

#### 卡片样式
```xml
<com.google.android.material.card.MaterialCardView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_margin="12dp"
    android:layout_marginTop="10dp"
    app:cardBackgroundColor="#FFFFFFFF"
    app:cardCornerRadius="12dp"
    app:cardElevation="0dp"
    app:strokeWidth="0dp">
    
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="16dp">
        
        <!-- 卡片内容 -->
    </LinearLayout>
</com.google.android.material.card.MaterialCardView>
```

#### 间距规范
- 卡片外边距：`android:layout_margin="12dp"`
- 第一个卡片：`android:layout_marginTop="10dp"`
- 后续卡片：`android:layout_marginTop="0dp"`
- 卡片内边距：`android:padding="16dp"`
- 底部留白：`android:paddingBottom="80dp"` (为底部导航栏留空间)

#### 颜色规范
- 背景色：`#FFF6F6F6` (浅灰色)
- 卡片背景：`#FFFFFFFF` (白色)
- 主标题：`#333333`
- 副标题/说明：`#999999`
- 分割线：`#F0F0F0`

## Toolbar 和返回按钮

### 不使用 Toolbar
- 不要在布局中添加 `MaterialToolbar` 或 `Toolbar`
- 不要使用 `setSupportActionBar()`
- 不要添加返回按钮（navigationIcon）

### 原因
- 使用透明沉浸式状态栏后，内容直接延伸到状态栏下方
- 系统的返回手势已经足够，不需要额外的返回按钮
- 保持界面简洁，最大化内容显示区域

## 正确示例

### ❌ 错误做法
```xml
<androidx.constraintlayout.widget.ConstraintLayout>
    <com.google.android.material.appbar.MaterialToolbar
        android:id="@+id/toolbar"
        android:layout_width="match_parent"
        android:layout_height="?attr/actionBarSize"
        app:navigationIcon="@drawable/ic_arrow_back" />
    
    <RecyclerView
        app:layout_constraintTop_toBottomOf="@id/toolbar" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(binding.root)
    
    setSupportActionBar(binding.toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    binding.toolbar.setNavigationOnClickListener { finish() }
}
```

### ✅ 正确做法
```xml
<androidx.constraintlayout.widget.ConstraintLayout
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fitsSystemWindows="false">
    
    <RecyclerView
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:paddingTop="24dp"
        android:clipToPadding="false" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityDownloadBinding.inflate(layoutInflater)
    setContentView(binding.root)
    
    // 设置透明沉浸式状态栏
    com.tv.ball.utils.StatusBarUtils.setupTransparentStatusBar(this)
    
    // 不需要设置 Toolbar 和返回按钮
}
```

## 其他布局规范

### 列表项布局
- 使用 MaterialCardView 包裹
- 设置合适的 margin 和 padding
- 使用 elevation 和 cornerRadius 增加层次感

### 颜色使用
- 主色：`@color/theme_accent`
- 文本主色：`@color/theme_text_primary`
- 文本次色：`@color/theme_text_secondary`
- 危险操作：`#F44336`（红色）

### 间距规范
- 卡片外边距：8dp
- 卡片内边距：16dp
- 元素间距：8dp
- 小间距：4dp
- 状态栏下方间距：24dp

## 主题色应用

### 主题色仅用于UI元素
主题色应该只应用于UI元素（按钮、卡片等），不应用于状态栏：

```kotlin
// ❌ 错误：不要应用主题色到状态栏
val themeColor = com.tv.ball.di.Dependencies.configRepository.getThemeColor()
com.tv.ball.utils.ThemeUtils.applyThemeColor(this, themeColor)

// ✅ 正确：只应用到UI元素
val themeColor = com.tv.ball.di.Dependencies.configRepository.getThemeColor()
binding.continuePlayButton.setBackgroundColor(themeColor)
binding.parseButton.setBackgroundColor(themeColor)
```

### 继续播放按钮应用主题色
在HomeFragment中，继续播放卡片应该应用主题色：

```kotlin
private fun updateContinuePlayingCard(history: HistoryEntity?) {
    if (history == null) {
        binding.cardContinuePlay.visibility = View.GONE
        return
    }
    
    binding.cardContinuePlay.visibility = View.VISIBLE
    
    // 应用主题色到卡片
    val themeColor = com.tv.ball.di.Dependencies.configRepository.getThemeColor()
    binding.cardContinuePlay.setCardBackgroundColor(themeColor)
    binding.tvContinuePlayTitle.setTextColor(android.graphics.Color.WHITE)
}
```

### 聚合解析按钮应用主题色
在ParserActivity中，解析和播放按钮应该应用主题色：

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // 应用主题色到按钮
    val themeColor = com.tv.ball.di.Dependencies.configRepository.getThemeColor()
    binding.btnParse.setBackgroundColor(themeColor)
    binding.btnPlay.setBackgroundColor(themeColor)
}
```

## 适用范围
这些规范适用于所有新建的 Activity 和 Fragment，包括但不限于：
- 下载管理页面
- 视频详情页面
- 设置页面
- 搜索页面
- 历史记录页面
- 收藏页面
- 播放器页面（特殊处理，不使用 Toolbar）
- 聚合解析页面
