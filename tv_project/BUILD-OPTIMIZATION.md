# 构建优化指南

## 问题：为什么构建速度不一致？

### 快速构建的情况（几秒到几十秒）
- ✅ **增量编译**：只修改了少量文件
- ✅ **缓存命中**：Gradle 使用了之前的构建缓存
- ✅ **守护进程热启动**：Gradle 守护进程已经在运行
- ✅ **依赖已缓存**：所有依赖都在本地

### 慢速构建的情况（几分钟）
- ❌ **Clean Build**：执行了 `./gradlew clean`
- ❌ **首次构建**：没有任何缓存
- ❌ **依赖更新**：需要下载新的依赖
- ❌ **配置变更**：修改了 build.gradle 文件
- ❌ **守护进程冷启动**：Gradle 守护进程刚启动
- ❌ **内存不足**：系统内存不足导致频繁 GC
- ❌ **网络问题**：下载依赖时网络慢

## 已应用的优化

### 1. gradle.properties 优化
已经优化了以下配置：
- ✅ 增加 JVM 内存到 4GB
- ✅ 启用并行构建
- ✅ 启用构建缓存
- ✅ 启用 Kotlin 增量编译
- ✅ 启用文件监视

### 2. 快速构建脚本

#### Linux/Mac 用户
```bash
# 给脚本添加执行权限
chmod +x quick-build.sh
chmod +x build-profile.sh

# 快速构建和安装
./quick-build.sh

# 清理后构建
./quick-build.sh clean

# 性能分析
./build-profile.sh
```

#### Windows 用户
```cmd
# 快速构建和安装
quick-build.bat

# 清理后构建
quick-build.bat clean
```

## 日常开发建议

### ✅ 推荐做法
1. **不要频繁 clean**
   - 只在遇到奇怪问题时才执行 clean
   - 增量构建比完整构建快 10-100 倍

2. **使用 Android Studio 的 Apply Changes**
   - 快捷键：Ctrl+F10 (Windows/Linux) 或 Cmd+F10 (Mac)
   - 比重新安装快得多

3. **保持 Gradle 守护进程运行**
   - 不要执行 `./gradlew --stop`
   - 守护进程会自动管理内存

4. **使用 Debug 变体开发**
   ```bash
   ./gradlew assembleDebug  # 快
   # 不要用 assembleRelease  # 慢（需要混淆和优化）
   ```

### ❌ 避免做法
1. **不要每次都 clean**
   ```bash
   # ❌ 不好
   ./gradlew clean assembleDebug
   
   # ✅ 好
   ./gradlew assembleDebug
   ```

2. **不要频繁修改 build.gradle**
   - 每次修改都会导致重新配置
   - 批量修改依赖，而不是一个一个加

3. **不要在网络慢时更新依赖**
   - 使用离线模式：`./gradlew assembleDebug --offline`

## 构建时间参考

### 首次构建（冷启动）
- 下载依赖：1-5 分钟（取决于网络）
- 编译代码：1-3 分钟
- 总计：2-8 分钟

### 增量构建（热启动）
- 修改 1-2 个文件：5-15 秒
- 修改 10+ 个文件：30-60 秒
- 修改 build.gradle：1-2 分钟

### Clean Build
- 完整重新编译：2-5 分钟

## 故障排查

### 构建卡住不动
```bash
# 1. 停止所有 Gradle 进程
./gradlew --stop

# 2. 清理缓存（Windows 用户删除对应目录）
rm -rf ~/.gradle/caches/
rm -rf .gradle/

# 3. 重新构建
./gradlew assembleDebug
```

### 内存溢出错误
```properties
# 在 gradle.properties 中增加内存
org.gradle.jvmargs=-Xmx6144m -XX:MaxMetaspaceSize=1024m
```

### 依赖下载失败
检查网络连接，或使用国内镜像（已在 settings.gradle.kts 中配置）

### 构建缓存问题
```bash
# 清理构建缓存
./gradlew cleanBuildCache

# 禁用缓存（临时）
./gradlew assembleDebug --no-build-cache
```

## 性能监控

### 查看构建耗时
```bash
# 生成性能报告
./gradlew assembleDebug --profile

# 报告位置：build/reports/profile/
```

### 查看详细日志
```bash
# 信息级别日志
./gradlew assembleDebug --info

# 调试级别日志
./gradlew assembleDebug --debug
```

## 硬件建议

### 最低配置
- CPU: 4 核
- RAM: 8GB
- 存储: HDD

### 推荐配置
- CPU: 8 核或更多
- RAM: 16GB 或更多
- 存储: SSD（NVMe 更好）

### 理想配置
- CPU: 12 核或更多
- RAM: 32GB 或更多
- 存储: NVMe SSD

## 更多信息

查看详细的优化技巧：
```bash
# 在 Kiro 中查看 skill
cat .kiro/skills/android-build-optimization.md
```

## 总结

- 🚀 **首次构建慢是正常的**（需要下载依赖、建立缓存）
- ⚡ **增量构建应该很快**（只编译改变的部分）
- 🧹 **不要频繁 clean**（会清除所有缓存）
- 💾 **使用 SSD**（最有效的硬件升级）
- 🔧 **已优化 gradle.properties**（应该能看到明显改善）
