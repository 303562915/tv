# 可可影视筛选功能 - 更新日志

## 版本信息
- **更新日期**: 2026-04-15
- **功能**: 为可可影视添加分页和筛选功能
- **状态**: ✅ 已完成

## 功能概述

为可可影视视频源添加了完整的筛选功能，支持：
- **类型筛选**：全部、剧情、喜剧、动作、爱情、科幻、恐怖、犯罪、惊悚、冒险、战争、古装、家庭
- **地区筛选**：全部、韩国、大陆、香港、台湾、日本、美国、泰国、英国、新加坡、其他
- **年份筛选**：2015-2024年
- **语言筛选**：国语、英语、粤语、闽南语、韩语、日语、法语、德语、其它

## URL格式说明

可可影视的URL格式：
```
https://103.194.185.51:51120/show/{category}-{type}-{area}-{year}-{lang}-{letter}-{order}-{page}-{extra}.html
```

### 参数说明
- `{category}`: 分类ID（1=电影, 2=剧集）
- `{type}`: 类型（URL编码，如 %E5%89%A7%E6%83%85 = 剧情）
- `{area}`: 地区（URL编码，如 %E9%9F%A9%E5%9B%BD = 韩国）
- `{year}`: 年份（如 2024）
- `{lang}`: 语言（URL编码）
- `{letter}`: 字母筛选（暂未使用）
- `{order}`: 排序页码
- `{page}`: 固定为2
- `{extra}`: 额外参数（暂未使用）

### URL示例
```
# 基础分页
https://103.194.185.51:51120/show/1-----3-2.html

# 带类型筛选（剧情）
https://103.194.185.51:51120/show/1-%E5%89%A7%E6%83%85----3-2.html

# 带地区筛选（韩国）
https://103.194.185.51:51120/show/2--%E9%9F%A9%E5%9B%BD---3-2.html

# 组合筛选（电影+动作+美国+2024）
https://103.194.185.51:51120/show/1-%E5%8A%A8%E4%BD%9C-%E7%BE%8E%E5%9B%BD-2024--3-2.html
```

## 修改的文件

### 1. VideoSourceConfig.kt
**修改内容**: 更新可可影视的URL模板以支持所有筛选参数

```kotlin
// 修改前
VideoSourceConfig("keke", "可可", SourceType.WEB, 
    "https://103.194.185.51:51120/show/{category}--------{page}---.html")

// 修改后
VideoSourceConfig("keke", "可可", SourceType.WEB, 
    "https://103.194.185.51:51120/show/{category}-{type}-{area}-{year}-{lang}-{letter}-{order}-{page}-{extra}.html")
```

### 2. VideoSourceManager.kt
**修改内容**: 扩展 `getSourceApiUrl` 方法以支持筛选参数

```kotlin
// 修改前
fun getSourceApiUrl(sourceKey: String, category: Int, page: Int): String

// 修改后
fun getSourceApiUrl(
    sourceKey: String, 
    category: Int, 
    page: Int,
    type: String = "",
    area: String = "",
    year: String = "",
    lang: String = ""
): String
```

### 3. KekeFilterOptions.kt (新文件)
**功能**: 定义可可影视的所有筛选选项

包含：
- `types`: 类型映射（URL编码 -> 显示名称）
- `areas`: 地区映射
- `years`: 年份映射
- `languages`: 语言映射
- 辅助方法：获取显示名称

### 4. HomeViewModel.kt
**修改内容**: 添加筛选参数支持

```kotlin
// 添加筛选状态
private var currentType = ""
private var currentArea = ""
private var currentYear = ""
private var currentLang = ""

// 更新 loadVideos 方法签名
fun loadVideos(
    category: Int = 6, 
    refresh: Boolean = false,
    type: String = "",
    area: String = "",
    year: String = "",
    lang: String = ""
)
```

### 5. KekeFilterBottomSheet.kt (新文件)
**功能**: 筛选对话框UI组件

特性：
- 使用 Material Design 的 BottomSheetDialogFragment
- 使用 Chip 组件展示筛选选项
- 支持单选模式
- 提供重置和应用按钮

### 6. bottom_sheet_keke_filter.xml (新文件)
**功能**: 筛选对话框的布局文件

布局结构：
- 标题
- 类型筛选区域（ChipGroup）
- 地区筛选区域（ChipGroup）
- 年份筛选区域（ChipGroup）
- 语言筛选区域（ChipGroup）
- 重置和应用按钮

### 7. HomeFragment.kt
**修改内容**: 添加筛选对话框触发逻辑

```kotlin
private fun setupSearchButton() {
    binding.searchBox.setOnClickListener {
        val currentSource = VideoSourceManager.getCurrentSource()
        if (currentSource == "keke") {
            // 显示可可影视筛选对话框
            showKekeFilterDialog()
        } else {
            // 其他视频源跳转到搜索页面
            startActivity(Intent(requireContext(), SearchActivity::class.java))
        }
    }
}

private fun showKekeFilterDialog() {
    val filterSheet = KekeFilterBottomSheet.newInstance()
    filterSheet.setOnFilterAppliedListener { type, area, year, lang ->
        viewModel.loadVideos(
            category = currentCategory,
            refresh = true,
            type = type,
            area = area,
            year = year,
            lang = lang
        )
    }
    filterSheet.show(childFragmentManager, "KekeFilterBottomSheet")
}
```

## 使用说明

### 用户操作流程
1. 在首页选择"可可w"视频源
2. 点击顶部搜索框（显示"搜索 | 筛选"）
3. 在弹出的筛选对话框中选择筛选条件：
   - 类型：选择影片类型（剧情、动作等）
   - 地区：选择影片地区（韩国、美国等）
   - 年份：选择上映年份
   - 语言：选择影片语言
4. 点击"应用"按钮应用筛选
5. 点击"重置"按钮清除所有筛选条件

### 技术特性
- **智能切换**：仅在可可影视源时显示筛选，其他源显示搜索
- **URL编码**：自动处理中文参数的URL编码
- **状态保持**：筛选条件在加载更多时保持不变
- **刷新机制**：应用筛选时自动刷新列表

## 测试建议

### 功能测试
1. 切换到可可影视源
2. 测试单个筛选条件（类型、地区、年份、语言）
3. 测试组合筛选条件
4. 测试重置功能
5. 测试分页加载（滚动到底部）
6. 切换到其他视频源，确认搜索功能正常

### URL验证
使用浏览器或调试工具验证生成的URL格式是否正确：
```
# 检查URL是否符合格式
show/{category}-{type}-{area}-{year}-{lang}-{letter}-{order}-{page}-{extra}.html

# 检查中文参数是否正确编码
剧情 -> %E5%89%A7%E6%83%85
韩国 -> %E9%9F%A9%E5%9B%BD
```

## 已知限制

1. **字母筛选**：暂未实现（参数保留为空）
2. **排序功能**：使用页码作为排序参数
3. **其他视频源**：筛选功能仅适用于可可影视

## 后续优化建议

1. 添加筛选条件的持久化（保存用户偏好）
2. 添加快捷筛选标签（热门、最新等）
3. 支持多选筛选（如同时选择多个类型）
4. 添加筛选结果统计（显示匹配数量）
5. 优化筛选UI（添加动画效果）

## 兼容性

- **最低Android版本**: API 21 (Android 5.0)
- **Material Design**: 使用 Material 3 组件
- **依赖库**: 
  - Material Components
  - ViewPager2
  - BottomSheetDialogFragment

## 总结

此次更新为可可影视添加了完整的筛选功能，用户可以通过类型、地区、年份、语言等多个维度筛选视频内容。实现采用了Material Design规范，提供了良好的用户体验。
