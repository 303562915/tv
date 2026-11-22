# 轮播图迁移规则 (Banner Migration)

## 概述
轮播图使用 ViewPager2 实现无限循环轮播。

## 快速检查清单

每次运行项目前，确保以下文件存在且正确：

### 必需文件
- [ ] `app/src/main/java/com/tv/ball/presentation/home/BannerItem.kt`
- [ ] `app/src/main/java/com/tv/ball/presentation/home/BannerAdapter.kt`
- [ ] `app/src/main/res/layout/item_banner.xml`
- [ ] `app/build.gradle.kts` 包含 ViewPager2 依赖
- [ ] `app/src/main/res/layout/fragment_home.xml` 包含 bannerViewPager
- [ ] `HomeFragment.kt` 包含轮播图相关代码

### 构建命令
构建交给 GitHub Actions（push 到 `main`），本地不跑 `gradlew`。

## 核心组件

### 1. 数据模型 (BannerItem.kt)
```kotlin
data class BannerItem(
    val imageUrl: String,      // 图片链接
    val linkUrl: String,        // 跳转链接
    val description: String,    // 描述文字
    val visible: Boolean = true // 是否显示描述
)
```

### 2. 适配器 (BannerAdapter.kt)
- 继承 `RecyclerView.Adapter`
- 使用 Glide 加载图片
- 实现无限循环：`getItemCount() = Int.MAX_VALUE`
- 点击事件：检测URL格式，使用Intent打开链接

### 3. 布局文件
- `item_banner.xml`: 单个轮播项布局
  - MaterialCardView 包裹
  - ImageView 显示图片 (scaleType="fitXY")
  - TextView 显示描述 (底部左侧，白色粗体)

### 4. Fragment集成 (HomeFragment.kt)

#### 必需变量
```kotlin
private lateinit var bannerAdapter: BannerAdapter
private val bannerHandler = android.os.Handler(android.os.Looper.getMainLooper())
```

#### 初始化流程
1. `setupBanner()` - 设置轮播图
   - 创建本地默认数据
   - 初始化适配器
   - 设置初始位置到中间位置
   - 启动自动轮播
   - 异步加载云端数据

2. `loadBannerDataFromCloud()` - 加载云端数据
   - 使用 OkHttp 请求JSON
   - 解析JSON更新数据
   - 失败时保持默认数据

3. `parseBannerJson()` - 解析JSON
   - 字段映射：图片链接、跳转链接、描述、visble

4. `startBannerAutoScroll()` - 自动轮播
   - 使用Handler延迟4秒
   - 循环调用 `setCurrentItem(nextItem, true)`

#### 生命周期管理
```kotlin
override fun onDestroyView() {
    super.onDestroyView()
    bannerHandler.removeCallbacksAndMessages(null) // 清理Handler
    _binding = null
}
```

## 依赖配置

### build.gradle.kts
```kotlin
// ViewPager2 for Banner
implementation("androidx.viewpager2:viewpager2:1.1.0")
```

## 布局集成

### fragment_home.xml
在 topBar 和 tabLayout 之间添加：
```xml
<androidx.viewpager2.widget.ViewPager2
    android:id="@+id/bannerViewPager"
    android:layout_width="0dp"
    android:layout_height="180dp"
    android:layout_marginStart="12dp"
    android:layout_marginEnd="12dp"
    android:layout_marginTop="8dp"
    app:layout_constraintTop_toBottomOf="@id/topBar" />
```

tabLayout 的约束改为：
```xml
app:layout_constraintTop_toBottomOf="@id/bannerViewPager"
```

## 数据格式

### 本地默认数据
```kotlin
val defaultBannerData = listOf(
    BannerItem(
        imageUrl = "https://img20.360buyimg.com/openfeedback/jfs/t1/324048/26/9302/364644/68a7e311F388244bc/f99ede9fdb14b712.webp",
        linkUrl = "sd1",
        description = "生命不止热爱不息",
        visible = true
    ),
    BannerItem(
        imageUrl = "https://jianhancloud.cn/303562915/retouch_2025062914275290.webp",
        linkUrl = "https://172.lot-ml.com/ProductEn/Index/fff7dc002429b54f",
        description = "剩余 : 20张",
        visible = true
    ),
    BannerItem(
        imageUrl = "https://img20.360buyimg.com/openfeedback/jfs/t1/327775/2/9134/480804/68a7e35cF2985a072/6048b26601ec1545.webp",
        linkUrl = "sd5",
        description = "梅里雪山",
        visible = true
    )
)
```

### 云端JSON格式
```json
[
  {
    "图片链接": "https://...",
    "跳转链接": "https://...",
    "描述": "描述文字",
    "visble": "true"
  }
]
```

云端数据URL: `https://jianhancloud.cn/303562915/banner.json`

## 关键特性

1. **无限循环**: 使用 `Int.MAX_VALUE` 作为总数，初始位置设在中间
2. **自动轮播**: Handler每4秒切换一次
3. **云端数据**: 优先使用云端数据，失败时使用本地默认数据
4. **点击跳转**: 检测http/https链接，使用Intent打开
5. **图片加载**: Glide加载，centerCrop裁剪
6. **描述显示**: 根据visible字段控制显示/隐藏

## 迁移检查清单

- [x] 添加ViewPager2依赖
- [x] 创建BannerItem数据类
- [x] 创建BannerAdapter适配器
- [x] 创建item_banner.xml布局
- [x] 在fragment_home.xml添加ViewPager2
- [x] 在HomeFragment添加bannerAdapter和bannerHandler变量
- [x] 实现setupBanner()方法
- [x] 实现loadBannerDataFromCloud()方法
- [x] 实现parseBannerJson()方法
- [x] 实现startBannerAutoScroll()方法
- [x] 在onViewCreated()调用setupBanner()
- [x] 在onDestroyView()清理Handler

## 注意事项

1. **每次运行项目时确保轮播图功能已迁移** ✅
2. Handler必须在onDestroyView中清理，避免内存泄漏
3. 云端数据加载失败时自动使用本地数据
4. 图片URL必须是有效的网络地址
5. 点击事件只处理http/https链接
6. 如果构建失败提示文件被占用，关闭Android Studio或IDE后重试

## 故障排除

### 构建失败
构建在 CI 上失败时，用 `gh run view <run-id> --log-failed` 读日志定位，不转本地构建。

### 轮播图不显示
- 检查网络权限：`AndroidManifest.xml` 需要 `INTERNET` 权限
- 检查布局约束是否正确
- 检查ViewPager2的高度是否设置为180dp

### 自动轮播不工作
- 确保在onDestroyView中清理了Handler
- 检查Handler的延迟时间（4000ms）
- 确保_binding不为null时才执行轮播

