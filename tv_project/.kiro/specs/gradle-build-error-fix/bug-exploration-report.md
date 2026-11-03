# Bug Condition 探索报告

## 执行日期
2025年

## Bug Condition 验证

### 配置信息
- **Hilt 版本**: 2.52
- **KSP 版本**: 2.0.21-1.0.28
- **Kotlin 版本**: 2.0.21
- **AGP 版本**: 8.11.2

### 测试执行

#### 测试 1: Gradle Build
**命令**: `./gradlew build`

**结果**: ❌ 失败

**错误**: 资源编译错误（AAPT 错误）
- 多个 PNG 文件编译失败
- 任务失败: `:app:mergeReleaseResources`

**注意**: 此错误不是 JavaPoet 错误，而是资源问题。

---

#### 测试 2: Gradle AssembleDebug
**命令**: `./gradlew assembleDebug --stacktrace`

**结果**: ❌ 失败

**错误**: JavaPoet 方法解析失败

**完整错误信息**:
```
Execution failed for task ':app:hiltAggregateDepsDebug'.
> A failure occurred while executing dagger.hilt.android.plugin.task.AggregateDepsTask$WorkerAction
   > 'java.lang.String com.squareup.javapoet.ClassName.canonicalName()'
```

**异常堆栈**:
```
Caused by: java.lang.NoSuchMethodError: 'java.lang.String com.squareup.javapoet.ClassName.canonicalName()'
    at dagger.hilt.processor.internal.root.ir.AggregatedRootIrValidator.rootsToProcess(AggregatedRootIrValidator.kt:32)
    at dagger.hilt.android.plugin.task.AggregateDepsTask$WorkerAction.execute(AggregateDepsTask.kt:99)
```

**失败任务**: `:app:hiltAggregateDepsDebug`

**失败位置**: 
- 类: `dagger.hilt.processor.internal.root.ir.AggregatedRootIrValidator`
- 方法: `rootsToProcess`
- 行号: AggregatedRootIrValidator.kt:32

---

#### 测试 3: 弃用 API 警告检查
**命令**: `./gradlew assembleDebug --stacktrace`

**结果**: ⚠️ 警告（在 JavaPoet 错误之前）

**弃用警告列表**:

1. **LocalVideoFolderActivity.kt:108:26**
   - `'class Thumbnails : Any, BaseColumns' is deprecated. Deprecated in Java.`

2. **LocalVideoFolderActivity.kt:108:37**
   - `'static fun getThumbnail(p0: ContentResolver!, p1: Long, p2: Int, p3: BitmapFactory.Options!): Bitmap!' is deprecated. Deprecated in Java.`

3. **LocalVideoFolderActivity.kt:111:30**
   - `'class Thumbnails : Any, BaseColumns' is deprecated. Deprecated in Java.`

4. **LocalVideoFolderActivity.kt:111:41**
   - `'static field MINI_KIND: Int' is deprecated. Deprecated in Java.`

5. **LocalVideoViewModel.kt:92:30**
   - `'class Thumbnails : Any, BaseColumns' is deprecated. Deprecated in Java.`

6. **LocalVideoViewModel.kt:92:41**
   - `'static fun getThumbnail(p0: ContentResolver!, p1: Long, p2: Int, p3: BitmapFactory.Options!): Bitmap!' is deprecated. Deprecated in Java.`

7. **LocalVideoViewModel.kt:95:34**
   - `'class Thumbnails : Any, BaseColumns' is deprecated. Deprecated in Java.`

8. **LocalVideoViewModel.kt:95:45**
   - `'static field MINI_KIND: Int' is deprecated. Deprecated in Java.`

**额外弃用警告** (ExoPlayer):
- 多个 ExoPlayer 相关类和接口的弃用警告（不在此 bugfix 范围内）

---

## 反例分析

### 反例 1: JavaPoet 方法不存在

**Bug Condition**:
```
isBugCondition(buildConfig) WHERE
  buildConfig.hiltVersion == "2.52" AND
  buildConfig.kspVersion == "2.0.21-1.0.28" AND
  buildConfig.buildAction IN ['assembleDebug', 'assembleRelease']
```

**观察到的行为**:
- Hilt 注解处理器尝试调用 `com.squareup.javapoet.ClassName.canonicalName()`
- 该方法在运行时不存在（NoSuchMethodError）
- 构建在 Hilt 依赖聚合阶段失败

**根本原因假设**:
- Hilt 2.52 依赖较新版本的 JavaPoet，该版本包含 `canonicalName()` 方法
- KSP 2.0.21-1.0.28 或其他传递依赖强制使用旧版本的 JavaPoet
- 类加载器加载了旧版本的 JavaPoet，导致方法不存在

**验证状态**: ✅ 已确认 - Bug 存在

---

### 反例 2: 弃用 API 使用

**Bug Condition**:
```
使用 MediaStore.Video.Thumbnails API（API 29 已弃用）
```

**观察到的行为**:
- `MediaStore.Video.Thumbnails.getThumbnail()` 在 API 29+ 已弃用
- `MediaStore.Video.Thumbnails.MINI_KIND` 在 API 29+ 已弃用
- 编译器生成 8 个弃用警告
- 警告不阻止构建，但应修复以使用现代 API

**影响文件**:
- `app/src/main/java/com/tv/ball/presentation/localvideo/LocalVideoFolderActivity.kt`
- `app/src/main/java/com/tv/ball/presentation/localvideo/LocalVideoViewModel.kt`

**推荐替代 API**:
- API 29+: `ThumbnailUtils.createVideoThumbnail(File, Size, CancellationSignal)`
- API 24-28: `ThumbnailUtils.createVideoThumbnail(String, int)` (带 @Suppress)

**验证状态**: ✅ 已确认 - 弃用警告存在

---

## 结论

### Bug 存在性确认
✅ **已确认** - Bug Condition 在未修复代码上成功重现

### 关键发现
1. **JavaPoet 错误是阻塞性的** - 完全阻止构建完成
2. **弃用警告是非阻塞性的** - 生成警告但不阻止编译
3. **错误发生在 Hilt 注解处理阶段** - 在 Kotlin 编译之后，APK 打包之前

### 修复优先级
1. **高优先级**: 修复 JavaPoet 错误（阻塞所有开发工作）
2. **中优先级**: 修复弃用 API 警告（代码质量和未来兼容性）

### 下一步
1. 实施修复：降级 Hilt 版本到 2.51
2. 实施修复：更新 Thumbnails API 使用 ThumbnailUtils
3. 验证修复后构建成功
4. 验证 Preservation 测试通过（无回归）

---

## 测试状态

- [x] Bug Condition 探索测试已编写
- [x] Bug 在未修复代码上已确认
- [x] 反例已记录
- [x] 错误堆栈已捕获
- [x] 弃用警告已记录
- [ ] 修复实施（待执行）
- [ ] 修复验证（待执行）
