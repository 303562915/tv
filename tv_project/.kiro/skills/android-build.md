---
name: Android Build Configuration
description: Android 项目构建配置和最佳实践
tags: [android, build, gradle]
---

# Android 构建配置

## APK 架构配置

### 当前配置
项目已配置为**只生成 arm64-v8a 架构的 APK**，以加快编译速度。

**配置位置**: `app/build.gradle.kts`

```kotlin
splits {
    abi {
        isEnable = true
        reset()
        include("arm64-v8a")  // 只包含 arm64-v8a（现代设备）
        isUniversalApk = false  // 不生成通用 APK
    }
}
```

### 架构说明
- **arm64-v8a**: 64位 ARM 架构，适用于 2015 年后的大部分 Android 设备
- **armeabi-v7a**: 32位 ARM 架构，适用于老旧设备（已禁用以加快编译）
- **x86/x86_64**: Intel 架构，主要用于模拟器（已禁用）

### 如果需要支持更多架构
如果需要发布到应用商店或支持老旧设备，可以修改配置：

```kotlin
splits {
    abi {
        isEnable = true
        reset()
        include("arm64-v8a", "armeabi-v7a")  // 添加 32 位支持
        isUniversalApk = false
    }
}
```

## 构建命令

### Debug 版本
```bash
./gradlew assembleDebug
```

生成的 APK 位置：
- `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`

### Release 版本
```bash
./gradlew assembleRelease
```

生成的 APK 位置：
- `app/build/outputs/apk/release/app-arm64-v8a-release.apk`

### 清理构建
```bash
./gradlew clean
```

### 安装到设备
```bash
# 安装 Debug 版本
adb install app/build/outputs/apk/debug/app-arm64-v8a-debug.apk

# 或者使用 Gradle 直接安装
./gradlew installDebug
```

## 构建优化

### 加快编译速度
1. ✅ 只生成 arm64-v8a 架构（已配置）
2. 使用 Gradle 守护进程（默认启用）
3. 启用并行编译（在 `gradle.properties` 中配置）
4. 使用增量编译（Kotlin 默认启用）

### 减小 APK 体积
1. 启用代码混淆（Release 版本已启用）
2. 启用资源压缩
3. 使用 APK 分包（已配置）
4. 移除未使用的资源

## 常见问题

### 问题 1: 编译时生成多个架构的 APK
**原因**: `splits.abi.include()` 包含了多个架构

**解决**: 确保只包含 `arm64-v8a`

### 问题 2: 编译速度慢
**原因**: 
- 生成多个架构的 APK
- Gradle 缓存未启用
- 网络下载依赖慢

**解决**:
- 只生成需要的架构（已配置）
- 使用 Gradle 守护进程
- 配置国内镜像源

### 问题 3: APK 无法安装到设备
**原因**: 设备架构不匹配

**解决**: 
- 检查设备架构：`adb shell getprop ro.product.cpu.abi`
- 如果是 armeabi-v7a，需要添加该架构支持

## 版本管理

### 修改版本号
在 `app/build.gradle.kts` 中修改：

```kotlin
defaultConfig {
    versionCode = 1      // 整数，每次发布递增
    versionName = "1.0"  // 字符串，显示给用户
}
```

### 版本命名规范
- **versionCode**: 整数，每次发布必须递增
- **versionName**: 语义化版本号，如 `1.0.0`
  - 主版本号.次版本号.修订号
  - 例如：`1.2.3` 表示第 1 个主版本，第 2 个次版本，第 3 次修订

## 签名配置

### Debug 签名
Debug 版本使用默认的 debug.keystore，无需配置。

### Release 签名
Release 版本需要配置签名：

1. 生成签名文件：
```bash
keytool -genkey -v -keystore release.keystore -alias my-key-alias -keyalg RSA -keysize 2048 -validity 10000
```

2. 在 `app/build.gradle.kts` 中配置：
```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "your-password"
            keyAlias = "my-key-alias"
            keyPassword = "your-password"
        }
    }
    
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

## 依赖管理

### 查看依赖树
```bash
./gradlew app:dependencies
```

### 更新依赖
1. 修改 `libs.versions.toml` 中的版本号
2. 同步 Gradle
3. 测试应用

### 常用依赖库
- **ExoPlayer**: 视频播放
- **OkHttp**: 网络请求
- **Glide**: 图片加载
- **Room**: 数据库
- **Coroutines**: 协程

## 性能分析

### 生成 APK 分析报告
```bash
./gradlew assembleRelease --scan
```

### 查看 APK 内容
使用 Android Studio 的 APK Analyzer：
1. Build > Analyze APK
2. 选择生成的 APK 文件
3. 查看各部分大小占比

## 自动化构建

### CI/CD 配置示例
```yaml
# GitHub Actions 示例
name: Build APK
on: [push]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Set up JDK
        uses: actions/setup-java@v2
        with:
          java-version: '11'
      - name: Build APK
        run: ./gradlew assembleDebug
      - name: Upload APK
        uses: actions/upload-artifact@v2
        with:
          name: app-debug
          path: app/build/outputs/apk/debug/*.apk
```

## 记录

### 2024 配置历史
- ✅ 配置只生成 arm64-v8a 架构，加快编译速度
- ✅ 移除 armeabi-v7a 支持（可根据需要恢复）
- ✅ 启用代码混淆（Release 版本）
- ✅ 配置 APK 分包
