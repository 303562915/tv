# 更新日志

## 2026-04-11 更新内容

### 1. 修复视频源URL
- ✅ 豆瓣资源：更新为 `https://caiji.dbzy5.com/api.php/provide/vod/from/dbm3u8/at/json/`
- ✅ UU资源：新增 `https://uuzy.me/api.php/provide/vod/?ac=list`
- ✅ 量子资源：更新为 `http://23.224.101.30/api.php/provide/vod/?ac=list`
- ✅ 极速资源：更新为 `http://jisuziyuan999.com/api.php/provide/vod/`

### 2. 修复视频源分类映射
- 添加了分类映射系统，将通用分类ID映射到各个视频源的实际分类ID
- 国产剧：通用ID 6 → API分类ID 13
- 日韩剧：通用ID 8 → API分类ID 14
- 其他分类：电影(1)、剧集(2)、综艺(3)、动漫(4)

### 3. 新增收藏功能
创建了完整的收藏功能模块：
- `FavoriteFragment.kt` - 收藏页面
- `FavoriteViewModel.kt` - 收藏逻辑
- `FavoriteAdapter.kt` - 收藏列表适配器
- `FavoriteActivity.kt` - 收藏Activity
- 布局文件：`fragment_favorite.xml`、`item_favorite.xml`、`activity_favorite.xml`
- 在设置页面添加"我的收藏"入口
- 在AndroidManifest.xml中注册Activity

功能特点：
- 网格布局展示（3列）
- 支持点击查看详情
- 支持滑动删除
- 支持一键清空
- 显示收藏时间

### 4. 修复数据库崩溃问题
- 问题：Room数据库schema不匹配导致应用闪退
- 解决：卸载应用后重新安装，创建全新数据库

### 5. 修复UU资源解析问题
- 问题：UU资源API返回的数据中没有`vod_pic`字段，导致正则匹配失败
- 解决：修改正则表达式，让`vod_pic`和`vod_remarks`字段变为可选
- 修改文件：
  - `HomeViewModel.kt` - 首页视频列表解析
  - `VideoRepository.kt` - 搜索结果解析

### 6. 更新视频源列表
在`VideoSourceFragment.kt`中添加了UU资源到视频源选择列表

## 已知问题
- 极速资源当前返回503错误（服务不可用）
- UU资源部分视频可能没有封面图

## 测试建议
1. 测试各个视频源的切换和视频加载
2. 测试收藏功能的添加、删除、清空
3. 测试不同分类的视频加载
4. 测试网络错误时的提示信息
