# Android 构建优化技巧

## 构建速度优化

### 1. Gradle 配置优化

在项目根目录的 `gradle.properties` 文件中添加以下配置：

```properties
# 启用并行构建
org.gradle.parallel=true

# 启用配置缓存
org.gradle.configuration-cache=true

# 启用构建缓存
org.gradle.caching=true

# 增加 Gradle 守护进程的内存
org.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=1024m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8 -XX:+UseParallelGC

# 启用增量编译
kotlin.incremental=true
kotlin.incremental.java=true

# 启用 Kotlin 编译守护进程
kotlin.daemon.jvmargs=-Xmx2048m

# AndroidX 迁移
android.useAndroidX=true
android.enableJetifier=true

# 启用 R8 代码压缩和混淆
android.enableR8.fullMode=true

# 禁用不必要的任务
android.enableBuildCache=true
```

### 2. 构建变体优化

在 `app/build.gradle.kts` 中：

```kotlin
android {
    // 只构建需要的 ABI
    splits {
        abi {
            isEnable = false  // 开发时禁用 ABI 分割
        }
    }
    
    // 开发时只使用一个语言资源
    buildTypes {
        debug {
            // 只保留中文资源，加快构建
            resConfigs("zh", "zh-rCN")
        }
    }
    
    // 禁用不需要的构建功能
    buildFeatures {
        buildConfig = true
        viewBinding = true
        // 如果不用，禁用这些
        compose = false
        renderScript = false
        aidl = false
        shaders = false
    }
}
```

### 3. 依赖管理优化

```kotlin
dependencies {
    // 使用 implementation 而不是 api（除非必要）
    implementation("...")
    
    // 排除不需要的传递依赖
    implementation("...") {
        exclude(group = "...", module = "...")
    }
    
    // 使用 BOM 管理版本
    implementation(platform("..."))
}
```

### 4. 清理和重建策略

```bash
# 清理构建缓存（构建很慢时）
./gradlew clean

# 清理 Gradle 缓存（偶尔执行）
./gradlew cleanBuildCache

# 停止所有 Gradle 守护进程（内存不足时）
./gradlew --stop

# 刷新依赖（依赖问题时）
./gradlew build --refresh-dependencies

# 使用离线模式（网络慢时）
./gradlew build --offline
```

### 5. 为什么构建速度不一致？

#### 快速构建的情况：
- **增量编译**：只有少量文件改变
- **构建缓存命中**：Gradle 使用了缓存的构建结果
- **守护进程热启动**：Gradle 守护进程已经在运行
- **依赖已缓存**：所有依赖都在本地缓存中

#### 慢速构建的情况：
- **Clean Build**：执行了 `./gradlew clean`
- **首次构建**：没有任何缓存
- **依赖更新**：需要下载新的依赖
- **配置变更**：修改了 `build.gradle.kts` 或 `gradle.properties`
- **守护进程冷启动**：Gradle 守护进程刚启动
- **内存不足**：系统内存不足导致频繁 GC
- **网络问题**：下载依赖时网络慢
- **全量编译**：修改了影响全局的文件（如 Application 类）

### 6. 调试构建性能

```bash
# 查看构建耗时分析
./gradlew assembleDebug --profile

# 查看构建扫描（需要 Gradle Enterprise）
./gradlew assembleDebug --scan

# 查看详细日志
./gradlew assembleDebug --info

# 查看调试日志
./gradlew assembleDebug --debug
```

### 7. IDE 优化

在 Android Studio 中：
- **File > Settings > Build, Execution, Deployment > Compiler**
  - 勾选 "Configure on demand"
  - 勾选 "Compile independent modules in parallel"
  - 设置 "Command-line Options": `--parallel --configuration-cache`

- **File > Settings > Build, Execution, Deployment > Gradle**
  - 选择 "Gradle JDK": 使用 Android Studio 自带的 JDK
  - 勾选 "Build and run using: Gradle"

### 8. 硬件优化建议

- **内存**: 至少 16GB RAM（推荐 32GB）
- **存储**: 使用 SSD（NVMe 更好）
- **CPU**: 多核心处理器（至少 4 核）

### 9. 开发时的最佳实践

```bash
# 开发时使用 debug 变体
./gradlew assembleDebug

# 不要频繁执行 clean
# 只在遇到奇怪问题时才 clean

# 使用 Android Studio 的 "Apply Changes" 功能
# 而不是每次都重新安装

# 使用模拟器而不是真机（某些情况下更快）
```

### 10. 项目特定优化

对于这个视频播放器项目：

```kotlin
// 在 app/build.gradle.kts 中
android {
    // 开发时只构建一个 ABI
    defaultConfig {
        ndk {
            abiFilters.clear()
            abiFilters.add("arm64-v8a")  // 只构建 64 位 ARM
        }
    }
    
    // 禁用不需要的功能
    buildTypes {
        debug {
            isMinifyEnabled = false  // debug 不混淆
            isShrinkResources = false  // debug 不压缩资源
            resConfigs("zh")  // 只保留中文
        }
    }
}
```

## 常见问题排查

### 构建卡住不动
```bash
# 1. 停止所有 Gradle 进程
./gradlew --stop

# 2. 清理缓存
rm -rf ~/.gradle/caches/
rm -rf .gradle/

# 3. 重新构建
./gradlew assembleDebug
```

### 依赖下载失败
```bash
# 使用国内镜像（在 settings.gradle.kts 中）
pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

### 内存溢出
```properties
# 在 gradle.properties 中增加内存
org.gradle.jvmargs=-Xmx6144m -XX:MaxMetaspaceSize=1024m
```

## 总结

- **首次构建慢是正常的**（需要下载依赖、建立缓存）
- **增量构建应该很快**（只编译改变的部分）
- **定期清理缓存**（但不要太频繁）
- **使用 Gradle 守护进程**（不要每次都停止）
- **优化 gradle.properties**（最重要的优化点）
