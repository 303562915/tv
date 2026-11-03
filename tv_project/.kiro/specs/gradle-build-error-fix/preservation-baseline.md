# Preservation 基线行为记录

## 概述

此文档记录了在未修复代码上观察到的基线行为。由于 bug 阻止了构建过程，我们无法运行单元测试，但我们可以通过代码审查和配置检查来记录当前行为。

修复后，这些行为应保持不变（无回归）。

**验证: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6**

---

## 1. Hilt 依赖注入功能 (Requirement 3.1)

### 当前配置
- **Hilt 版本**: 2.52
- **KSP 版本**: 2.0.21-1.0.28
- **Hilt 插件**: com.google.dagger.hilt.android:2.52

### 注解使用情况

#### @AndroidEntryPoint 注解的 Activity
1. `LocalVideoFolderActivity` - 本地视频文件夹浏览
2. `VideoDetailActivity` - 视频详情页面
3. `VideoPlayerActivity` - 视频播放器
4. 其他 Activity（需要依赖注入的）

#### @HiltViewModel 注解的 ViewModel
1. `HomeViewModel` - 首页视频列表
2. `VideoDetailViewModel` - 视频详情
3. `HistoryViewModel` - 播放历史
4. `LocalVideoViewModel` - 本地视频

#### @Module 注解的模块
1. `AppModule` - 应用级依赖提供
   - 提供 OkHttpClient
   - 提供 AppDatabase
   - 提供 VideoDao, HistoryDao, PlaylistDao
   - 提供 LocalDataSource

### 预期生成的代码
- `Hilt_LocalVideoFolderActivity` - Activity 注入器
- `App_HiltComponents` - Hilt 组件
- `AppModule_ProvideAppDatabaseFactory` - 工厂类
- 其他 Hilt 生成的依赖注入代码

### 基线行为
✅ **Hilt 依赖注入应继续正常工作**
- 所有 `@Inject` 注解的依赖应正确注入
- 所有 `@AndroidEntryPoint` 注解的 Activity 应正确初始化
- 所有 `@HiltViewModel` 注解的 ViewModel 应正确创建
- 所有 `@Provides` 方法应正确提供依赖

---

## 2. Room 数据库代码生成功能 (Requirement 3.2)

### 当前配置
- **Room 版本**: 2.6.1
- **KSP 处理器**: androidx.room:room-compiler:2.6.1

### 数据库配置

#### @Database 注解的类
- `AppDatabase` (version = 1)
  - 实体: VideoEntity, HistoryEntity, PlaylistEntity
  - DAO: VideoDao, HistoryDao, PlaylistDao

#### @Dao 注解的接口
1. `VideoDao` - 视频数据访问
2. `HistoryDao` - 历史记录数据访问
3. `PlaylistDao` - 播放列表数据访问

#### @Entity 注解的实体
1. `VideoEntity` - 视频实体
2. `HistoryEntity` - 历史记录实体
3. `PlaylistEntity` - 播放列表实体

### 预期生成的代码
- `AppDatabase_Impl` - 数据库实现
- `VideoDao_Impl` - DAO 实现
- `HistoryDao_Impl` - DAO 实现
- `PlaylistDao_Impl` - DAO 实现

### 基线行为
✅ **Room 数据库代码生成应继续正常工作**
- 数据库应正确创建和初始化
- 所有 DAO 方法应正确生成
- 数据库迁移应正常工作
- 查询应正确执行

---

## 3. Glide 代码生成功能 (Requirement 3.3)

### 当前配置
- **Glide 版本**: 4.16.0
- **KSP 处理器**: com.github.bumptech.glide:compiler:4.16.0

### Glide 使用情况
- 用于加载视频封面图片
- 用于加载视频缩略图
- 用于图片缓存和优化

### 预期生成的代码
- `GlideApp` - Glide 应用类（如果配置了 @GlideModule）
- `GeneratedAppGlideModule` - 生成的模块

### 基线行为
✅ **Glide 代码生成应继续正常工作**
- 图片加载应正常工作
- 图片缓存应正常工作
- Glide 配置应正确应用

---

## 4. ExoPlayer 视频播放功能 (Requirement 3.4)

### 当前配置
- **ExoPlayer 版本**: 2.19.1
- **模块**:
  - exoplayer-core:2.19.1
  - exoplayer-ui:2.19.1
  - exoplayer-hls:2.19.1

### 视频播放功能
- 支持多种视频格式（MP4, MKV, AVI, MOV, RMVB, FLV, WMV）
- 支持 HLS 流媒体
- 支持本地视频文件播放
- 支持在线视频播放

### 相关类
- `VideoPlayerActivity` - 视频播放器 Activity
- `VideoDetailActivity` - 视频详情页面

### 基线行为
✅ **ExoPlayer 视频播放功能应保持不变**
- 视频播放应正常工作
- 播放控制应正常工作
- HLS 流媒体应正常工作
- 本地视频播放应正常工作

---

## 5. LocalVideoFolderActivity 视频文件扫描功能 (Requirement 3.5)

### 当前实现
**文件**: `app/src/main/java/com/tv/ball/presentation/localvideo/LocalVideoFolderActivity.kt`

### 扫描行为

#### 支持的视频格式
```kotlin
val videoExtensions = listOf(".mp4", ".mkv", ".avi", ".mov", ".rmvb", ".flv", ".wmv")
```

#### 扫描逻辑
1. 接收文件夹路径参数
2. 扫描指定文件夹中的所有文件
3. 过滤出支持的视频格式
4. 获取视频 ID（通过 MediaStore 查询）
5. 获取视频缩略图
6. 按文件名排序
7. 显示在 RecyclerView 中

#### 关键方法
- `scanVideosInFolder(folderPath: String)` - 扫描视频文件
- `getVideoId(filePath: String)` - 获取视频 ID
- `getThumbnail(videoId: Long)` - 获取缩略图

### 基线行为
✅ **视频文件扫描功能应保持不变**
- 应扫描指定文件夹中的视频文件
- 应支持 7 种视频格式
- 应按文件名排序
- 不应递归扫描子文件夹
- 应正确获取视频元数据

---

## 6. 视频缩略图加载功能 (Requirement 3.6)

### 当前实现
**文件**: `app/src/main/java/com/tv/ball/presentation/localvideo/LocalVideoFolderActivity.kt`

### 当前 API 使用
```kotlin
private fun getThumbnail(videoId: Long) = try {
    MediaStore.Video.Thumbnails.getThumbnail(
        contentResolver,
        videoId,
        MediaStore.Video.Thumbnails.MINI_KIND,
        null
    )
} catch (e: Exception) {
    null
}
```

### 当前行为特征
- **API**: MediaStore.Video.Thumbnails
- **方法**: getThumbnail()
- **参数**: MINI_KIND
- **错误处理**: 返回 null
- **弃用状态**: 已弃用（API 29+）

### 预期修复后的 API
```kotlin
private fun getThumbnail(videoId: Long): Bitmap? {
    return try {
        val filePath = getVideoFilePath(videoId) ?: return null
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val file = File(filePath)
            ThumbnailUtils.createVideoThumbnail(
                file,
                Size(512, 384),
                null
            )
        } else {
            @Suppress("DEPRECATION")
            ThumbnailUtils.createVideoThumbnail(
                filePath,
                MediaStore.Video.Thumbnails.MINI_KIND
            )
        }
    } catch (e: Exception) {
        null
    }
}
```

### 基线行为
✅ **视频缩略图加载功能应保持不变**
- 应显示视频缩略图
- 失败时应返回 null（不崩溃）
- 应处理异常情况
- **修复后视觉效果应保持相同**
- 缩略图尺寸应相似（MINI_KIND ≈ 512x384）

---

## 7. 修复范围验证

### 修复应该影响的部分
✅ Gradle 构建配置（Hilt 版本）
✅ 缩略图 API 使用（MediaStore → ThumbnailUtils）

### 修复不应该影响的部分
❌ 应用程序运行时行为
❌ 依赖注入功能
❌ 数据库操作
❌ 网络请求
❌ 视频播放逻辑
❌ UI 交互
❌ 视频文件扫描逻辑
❌ 缩略图显示效果

---

## 验证计划

### 修复后验证步骤

1. **构建验证**
   - ✅ `./gradlew build` 应成功
   - ✅ 无 JavaPoet 错误
   - ✅ 无弃用警告

2. **Hilt 依赖注入验证**
   - ✅ 所有 Activity 应正确初始化
   - ✅ 所有 ViewModel 应正确创建
   - ✅ 所有依赖应正确注入

3. **Room 数据库验证**
   - ✅ 数据库应正确创建
   - ✅ DAO 方法应正常工作
   - ✅ 查询应正确执行

4. **Glide 图片加载验证**
   - ✅ 图片应正常加载
   - ✅ 缓存应正常工作

5. **ExoPlayer 视频播放验证**
   - ✅ 视频应正常播放
   - ✅ 播放控制应正常工作

6. **视频扫描验证**
   - ✅ 应扫描到所有支持的视频文件
   - ✅ 应按文件名排序

7. **缩略图加载验证**
   - ✅ 应显示视频缩略图
   - ✅ 视觉效果应与修复前相同
   - ✅ 无弃用警告

---

## 测试执行状态

- [x] Preservation 测试已编写
- [x] 基线行为已记录（通过代码审查）
- [ ] 测试在未修复代码上运行（无法运行 - 构建失败）
- [ ] 测试在修复后代码上运行（待执行）
- [ ] 验证无回归（待执行）

---

## 注意事项

由于 bug 阻止了构建过程，我们无法在未修复代码上运行单元测试。但是，我们通过以下方式记录了基线行为：

1. **代码审查** - 检查所有相关类和配置
2. **配置检查** - 验证依赖版本和注解使用
3. **文档记录** - 记录预期行为和关键特征

修复后，我们将运行 `PreservationPropertyTest.kt` 中的测试来验证所有功能保持不变。

