---
inclusion: auto
---

# Android 构建优化提示

## 当用户提到构建、编译、Gradle 相关问题时

### 快速诊断
- 首次构建慢（2-8分钟）是正常的
- 增量构建应该很快（5-60秒）
- 如果每次都很慢，检查是否频繁执行 clean

### 已优化的配置
项目的 `gradle.properties` 已经优化：
- JVM 内存：4GB
- 并行构建：已启用
- 构建缓存：已启用
- Kotlin 增量编译：已启用

### 快速构建命令
```bash
# Linux/Mac
./quick-build.sh

# Windows
quick-build.bat
```

### 常见问题
1. **构建卡住** → `./gradlew --stop` 然后重试
2. **内存溢出** → 增加 gradle.properties 中的内存
3. **依赖下载慢** → 使用 `--offline` 模式

### 不要做的事
- ❌ 频繁执行 `./gradlew clean`
- ❌ 每次都重新安装 APK
- ❌ 在网络慢时更新依赖

### 详细文档
- 查看 `BUILD-OPTIMIZATION.md` 了解详情
- 查看 `.kiro/skills/android-build-optimization.md` 了解完整优化技巧
