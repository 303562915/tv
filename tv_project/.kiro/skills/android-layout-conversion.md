# Android 布局转换技能

## AndroLua 布局 DSL 到 Kotlin 转换

### 基础布局容器

#### LinearLayout
```lua
-- Lua
{
  LinearLayout;
  layout_width = 'fill';
  layout_height = 'wrap';
  orientation = 'vertical';
  background = '#ffffff';
  padding = '10dp';
}
```

```kotlin
// Kotlin
LinearLayout(context).apply {
  layoutParams = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT,
    LinearLayout.LayoutParams.WRAP_CONTENT
  )
  orientation = LinearLayout.VERTICAL
  setBackgroundColor(Color.parseColor("#ffffff"))
  setPadding(dp(10), dp(10), dp(10), dp(10))
}
```

#### CardView
```lua
-- Lua
{
  CardView;
  layout_width = 'fill';
  layout_height = 'wrap';
  cardBackgroundColor = '#ffffff';
  radius = '8dp';
  cardElevation = '4dp';
}
```

```kotlin
// Kotlin
MaterialCardView(context).apply {
  layoutParams = ViewGroup.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,
    ViewGroup.LayoutParams.WRAP_CONTENT
  )
  setCardBackgroundColor(Color.parseColor("#ffffff"))
  radius = dp(8).toFloat()
  cardElevation = dp(4).toFloat()
}
```

### 常用控件

#### TextView
```lua
-- Lua
{
  TextView;
  text = '文本内容';
  textSize = '16sp';
  textColor = '#333333';
  id = 'myTextView';
}
```

```kotlin
// Kotlin
TextView(context).apply {
  text = "文本内容"
  textSize = 16f
  setTextColor(Color.parseColor("#333333"))
  id = R.id.myTextView
}
```

#### ImageView
```lua
-- Lua
{
  ImageView;
  src = 'img/icon.png';
  layout_width = '48dp';
  layout_height = '48dp';
  scaleType = 'centerCrop';
}
```

```kotlin
// Kotlin
ImageView(context).apply {
  layoutParams = ViewGroup.LayoutParams(dp(48), dp(48))
  setImageResource(R.drawable.icon)
  scaleType = ImageView.ScaleType.CENTER_CROP
}
```

### Material Design 组件

#### TabLayout
```lua
-- Lua
{
  TabLayout;
  id = 'tabs';
  layout_width = 'fill';
  layout_height = '48dp';
  tabMode = TabLayout.MODE_SCROLLABLE;
}
```

```kotlin
// Kotlin
TabLayout(context).apply {
  id = R.id.tabs
  layoutParams = ViewGroup.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,
    dp(48)
  )
  tabMode = TabLayout.MODE_SCROLLABLE
}
```

#### RecyclerView
```lua
-- Lua
{
  RecyclerView;
  id = 'recyclerView';
  layout_width = 'fill';
  layout_height = 'fill';
}
```

```kotlin
// Kotlin
RecyclerView(context).apply {
  id = R.id.recyclerView
  layoutParams = ViewGroup.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,
    ViewGroup.LayoutParams.MATCH_PARENT
  )
  layoutManager = LinearLayoutManager(context)
}
```

### 辅助函数

```kotlin
// dp 转 px
fun Context.dp(value: Int): Int {
  return (value * resources.displayMetrics.density).toInt()
}

fun Context.dp(value: Float): Int {
  return (value * resources.displayMetrics.density).toInt()
}

// sp 转 px
fun Context.sp(value: Int): Int {
  return (value * resources.displayMetrics.scaledDensity).toInt()
}
```

### 布局参数映射

| Lua | Kotlin |
|-----|--------|
| `layout_width = 'fill'` | `MATCH_PARENT` |
| `layout_width = 'wrap'` | `WRAP_CONTENT` |
| `layout_width = '100dp'` | `dp(100)` |
| `gravity = 'center'` | `Gravity.CENTER` |
| `orientation = 'vertical'` | `LinearLayout.VERTICAL` |
| `orientation = 'horizontal'` | `LinearLayout.HORIZONTAL` |

### 颜色处理

```lua
-- Lua
background = '#FF0000'
textColor = 0xFF00FF00
```

```kotlin
// Kotlin
setBackgroundColor(Color.parseColor("#FF0000"))
setTextColor(0xFF00FF00.toInt())
```

## 使用 View Binding (推荐)

```kotlin
// 在 build.gradle.kts 中启用
android {
  buildFeatures {
    viewBinding = true
  }
}

// 在 Activity 中使用
class MainActivity : AppCompatActivity() {
  private lateinit var binding: ActivityMainBinding
  
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)
    
    binding.textView.text = "Hello"
  }
}
```
