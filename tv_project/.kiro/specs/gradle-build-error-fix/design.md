# Gradle Build Error Fix 设计文档

## 概述

本设计文档针对 Android 项目中的 Gradle 构建失败问题，主要由 JavaPoet 版本不兼容引起。错误 `Unable to find method 'java.lang.String com.squareup.javapoet.ClassName.canonicalName()'` 阻止了项目编译。修复策略包括：(1) 降级 Hilt 版本以解决 JavaPoet 兼容性问题，(2) 更新已弃用的 Thumbnails API 以消除警告。

## 术语表

- **Bug_Condition (C)**: 触发构建失败的条件 - 当 Hilt 2.52 与 KSP 2.0.21-1.0.28 组合使用时，导致 JavaPoet 方法解析失败
- **Property (P)**: 期望的行为 - Gradle 构建成功完成，无 JavaPoet 错误，无弃用警告
- **Preservation**: 必须保持不变的现有功能 - Hilt 依赖注入、Room 数据库生成、Glide 代码生成、视频播放功能
- **JavaPoet**: Square 开发的 Java 代码生成库，被 Hilt/Dagger 用于生成依赖注入代码
- **KSP (Kotlin Symbol Processing)**: Kotlin 编译器插件，用于注解处理
- **Hilt**: Google 的依赖注入框架，基于 Dagger 构建
- **MediaStore.Video.Thumbnails**: Android API 用于获取视频缩略图（API 29 已弃用）
- **ThumbnailUtils**: Android 现代 API 用于生成缩略图（API 29+ 推荐）

## Bug 详情

### Bug Condition

构建失败发生在 Gradle 尝试同步或构建项目时，使用 Hilt 2.52 和 KSP 2.0.21-1.0.28 的组合。Hilt 的注解处理器依赖 JavaPoet 库生成代码，但当前版本组合导致 JavaPoet 的 `ClassName.canonicalName()` 方法无法找到。

**形式化规范：**
```
FUNCTION isBugCondition(buildConfig)
  INPUT: buildConfig of type GradleBuildConfiguration
  OUTPUT: boolean
  
  RETURN buildConfig.hiltVersion == "2.52"
         AND buildConfig.kspVersion == "2.0.21-1.0.28"
         AND buildConfig.buildAction IN ['sync', 'build', 'assembleDebug', 'assembleRelease']
         AND NOT javaPoetMethodResolutionSucceeds()
END FUNCTION
```

### 示例

- **示例 1**: 执行 `./gradlew build` 时，预期构建成功，实际失败并显示 `Unable to find method 'java.lang.String com.squareup.javapoet.ClassName.canonicalName()'`
- **示例 2**: 在 Android Studio 中同步项目时，预期同步成功，实际失败并显示相同的 JavaPoet 错误
- **示例 3**: 执行 `./gradlew assembleDebug` 时，预期生成 debug APK，实际在注解处理阶段失败
- **边缘情况**: LocalVideoFolderActivity 使用 `MediaStore.Video.Thumbnails.getThumbnail()` 时，预期生成弃用警告但不阻止构建

## 预期行为

### 保持不变的行为

**不变的行为：**
- Hilt 依赖注入必须继续正常工作，所有 `@AndroidEntryPoint`、`@Inject`、`@HiltViewModel` 注解必须正确处理
- Room 数据库代码生成必须继续正常工作，DAO 和 Database 类必须正确生成
- Glide 代码生成必须继续正常工作，`GlideApp` 必须正确生成
- ExoPlayer 视频播放功能必须保持不变
- LocalVideoFolderActivity 的视频文件扫描功能必须保持不变
- 视频缩略图加载必须继续显示（使用更新的 API），视觉效果相同

**范围：**
所有不涉及 Gradle 构建配置和 Thumbnails API 的输入应完全不受此修复影响。这包括：
- 应用程序运行时行为
- 依赖注入功能
- 数据库操作
- 网络请求
- 视频播放逻辑
- UI 交互

## 假设的根本原因

基于 bug 描述，最可能的问题是：

1. **JavaPoet 版本不兼容**: Hilt 2.52 可能依赖较新版本的 JavaPoet，该版本引入了 `canonicalName()` 方法，但 KSP 2.0.21-1.0.28 或其他传递依赖可能强制使用旧版本的 JavaPoet
   - Hilt 2.52 是较新版本，可能与当前 KSP 版本不完全兼容
   - 降级到 Hilt 2.48-2.51 可能解决兼容性问题

2. **KSP 版本问题**: KSP 2.0.21-1.0.28 可能与 Hilt 2.52 的代码生成逻辑不兼容

3. **传递依赖冲突**: 项目中的其他依赖（Room、Glide）也使用 KSP，可能存在版本冲突

4. **弃用 API 使用**: LocalVideoFolderActivity.kt 使用 `MediaStore.Video.Thumbnails.getThumbnail()` 和 `MINI_KIND`，这些在 API 29 (Android 10) 已弃用
   - 应替换为 `ThumbnailUtils.createVideoThumbnail(File, Size, CancellationSignal)`
   - 需要处理 API 级别兼容性（minSdk = 24）

## 正确性属性

Property 1: Bug Condition - Gradle 构建成功

_对于任何_ 使用兼容依赖版本配置的构建输入，修复后的 Gradle 配置应成功完成同步和构建过程，无 JavaPoet 方法解析错误，并生成可用的 APK。

**验证: Requirements 2.1, 2.2**

Property 2: Preservation - 依赖注入和代码生成功能

_对于任何_ 不涉及 Gradle 构建配置更改的输入（应用程序运行时行为、依赖注入、数据库操作），修复后的代码应产生与原始代码完全相同的行为，保持所有 Hilt、Room、Glide 功能正常工作。

**验证: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6**

Property 3: API 现代化 - 无弃用警告

_对于任何_ 视频缩略图加载操作，修复后的代码应使用现代 ThumbnailUtils API，不产生弃用警告，同时保持相同的视觉效果和功能。

**验证: Requirements 2.3, 3.6**

## 修复实现

### 需要的更改

假设我们的根本原因分析正确：

**文件**: `gradle/libs.versions.toml`

**依赖版本**: Hilt 版本

**具体更改**:
1. **降级 Hilt 版本**: 将 Hilt 从 2.52 降级到 2.51 或 2.48
   - 修改 `[versions]` 部分: `hilt = "2.51"` 或 `hilt = "2.48"`
   - 这应该解决 JavaPoet 兼容性问题

2. **验证 KSP 版本**: 保持 KSP 2.0.21-1.0.28 不变
   - 如果 Hilt 降级后仍有问题，考虑调整 KSP 版本

3. **清理构建缓存**: 执行 `./gradlew clean` 和 `./gradlew build --refresh-dependencies`

**文件**: `app/src/main/java/com/tv/ball/presentation/localvideo/LocalVideoFolderActivity.kt`

**函数**: `getThumbnail(videoId: Long)`

**具体更改**:
1. **替换弃用的 Thumbnails API**: 使用 ThumbnailUtils 替代 MediaStore.Video.Thumbnails
   - 移除 `MediaStore.Video.Thumbnails.getThumbnail()` 调用
   - 使用 `ThumbnailUtils.createVideoThumbnail()` (API 29+) 或 `ThumbnailUtils.createVideoThumbnail(String, int)` (API 8+)

2. **处理 API 级别兼容性**: 
   - 对于 API 29+，使用 `ThumbnailUtils.createVideoThumbnail(File, Size, CancellationSignal)`
   - 对于 API 24-28，使用 `ThumbnailUtils.createVideoThumbnail(String, int)`

3. **更新导入语句**:
   - 添加 `import android.media.ThumbnailUtils`
   - 添加 `import android.util.Size` (API 29+)
   - 添加 `import android.os.Build`

4. **修改 getThumbnail 函数**:
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

5. **添加辅助函数获取文件路径**:
   ```kotlin
   private fun getVideoFilePath(videoId: Long): String? {
       val projection = arrayOf(MediaStore.Video.Media.DATA)
       val selection = "${MediaStore.Video.Media._ID}=?"
       val selectionArgs = arrayOf(videoId.toString())
       
       contentResolver.query(
           MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
           projection,
           selection,
           selectionArgs,
           null
       )?.use { cursor ->
           if (cursor.moveToFirst()) {
               return cursor.getString(
                   cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
               )
           }
       }
       return null
   }
   ```

## 测试策略

### 验证方法

测试策略遵循两阶段方法：首先，在未修复的代码上展示 bug 的反例，然后验证修复正确工作并保持现有行为。

### 探索性 Bug Condition 检查

**目标**: 在实施修复之前，展示 bug 的反例。确认或反驳根本原因分析。如果反驳，我们需要重新假设。

**测试计划**: 在未修复的代码上执行 Gradle 构建命令，观察失败并理解根本原因。

**测试用例**:
1. **Gradle Sync 测试**: 在 Android Studio 中同步项目（将在未修复代码上失败）
2. **Gradle Build 测试**: 执行 `./gradlew build`（将在未修复代码上失败）
3. **Debug APK 构建测试**: 执行 `./gradlew assembleDebug`（将在未修复代码上失败）
4. **弃用警告测试**: 构建项目并检查弃用警告（将在未修复代码上显示警告）

**预期反例**:
- JavaPoet 方法解析失败，错误信息包含 `canonicalName()`
- 可能原因：Hilt 2.52 与 KSP 2.0.21-1.0.28 版本不兼容，JavaPoet 传递依赖版本冲突

### Fix Checking

**目标**: 验证对于所有满足 bug condition 的输入，修复后的配置产生预期行为。

**伪代码：**
```
FOR ALL buildConfig WHERE isBugCondition(buildConfig) DO
  result := gradleBuild_fixed(buildConfig)
  ASSERT buildSucceeds(result)
  ASSERT noJavaPoetErrors(result)
  ASSERT apkGenerated(result)
END FOR
```

### Preservation Checking

**目标**: 验证对于所有不满足 bug condition 的输入，修复后的代码产生与原始代码相同的结果。

**伪代码：**
```
FOR ALL runtimeInput WHERE NOT isBugCondition(runtimeInput) DO
  ASSERT application_original(runtimeInput) = application_fixed(runtimeInput)
END FOR
```

**测试方法**: 推荐使用基于属性的测试进行保持性检查，因为：
- 它自动生成许多测试用例覆盖输入域
- 它捕获手动单元测试可能遗漏的边缘情况
- 它为所有非 bug 输入提供强有力的行为不变保证

**测试计划**: 首先在未修复代码上观察运行时行为，然后编写基于属性的测试捕获该行为。

**测试用例**:
1. **Hilt 注入保持性**: 验证所有 `@Inject` 注解的依赖继续正确注入
2. **Room 数据库保持性**: 验证数据库操作继续正常工作
3. **Glide 图片加载保持性**: 验证图片加载继续正常工作
4. **视频播放保持性**: 验证 ExoPlayer 视频播放继续正常工作
5. **缩略图显示保持性**: 验证视频缩略图继续显示（使用更新的 API），视觉效果相同

### 单元测试

- 测试 Gradle 构建在不同配置下的成功
- 测试 Hilt 依赖注入在各种场景下的正确性
- 测试 ThumbnailUtils API 在不同 Android 版本下的行为
- 测试边缘情况（视频文件不存在、权限拒绝）

### 基于属性的测试

- 生成随机构建配置并验证兼容版本组合构建成功
- 生成随机视频文件路径并验证缩略图加载行为一致
- 测试跨多个 Android API 级别的缩略图生成

### 集成测试

- 测试完整的构建流程从同步到 APK 生成
- 测试应用程序启动并正确初始化 Hilt 组件
- 测试 LocalVideoFolderActivity 扫描视频并显示缩略图的完整流程
- 测试在不同 Android 版本设备上的缩略图显示
