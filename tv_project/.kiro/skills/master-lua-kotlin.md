# Lua 到 Kotlin 转换主技能

## 技能概述

这是一个综合技能，整合了 Lua 到 Kotlin 的完整转换、构建、测试和优化流程。

## 依赖技能

1. **lua-to-kotlin-workflow.md** - Lua 到 Kotlin 转换规则
2. **android-layout-conversion.md** - Android 布局转换规则
3. **log-analysis-fix.md** - 日志分析和自动修复
4. **auto-build-test.md** - 自动构建和测试
5. **chinese-response.md** - 中文回复规则
6. **skill-optimization.md** - 技能优化和学习

## 完整工作流程

### 阶段 1: 代码转换

1. **读取 Lua 源文件**
   ```bash
   # 从 androlua源码 目录读取
   ls androlua源码/*.lua
   ```

2. **解析 Lua 代码结构**
   - 识别导入语句
   - 识别变量声明
   - 识别函数定义
   - 识别布局定义
   - 识别事件处理

3. **应用转换规则**
   - 导入语句转换
   - 变量类型推断和声明
   - 函数签名转换
   - 布局 DSL 转换为 Kotlin/XML
   - 回调函数转换为 Lambda

4. **生成 Kotlin 代码**
   ```kotlin
   // 输出到 app/src/main/java/com/tv/ball/
   // 保持包结构和命名规范
   ```

### 阶段 2: 构建项目

1. **清理项目**
   ```bash
   ./gradlew clean
   ```

2. **构建 Debug 版本**
   ```bash
   ./gradlew assembleDebug --stacktrace
   ```

3. **检查构建结果**
   - 如果成功，进入阶段 3
   - 如果失败，进入错误修复流程

### 阶段 3: 错误分析和修复

1. **读取错误日志**
   ```bash
   # Kotlin 编译错误
   cat .kotlin/errors/*.log
   
   # Gradle 构建日志
   cat build/outputs/logs/gradle-build.log
   ```

2. **分析错误类型**
   - 语法错误
   - 类型错误
   - 导入缺失
   - 资源未找到
   - 依赖缺失

3. **自动修复**
   根据错误类型应用对应的修复规则:
   
   ```kotlin
   when (errorType) {
     ErrorType.UNRESOLVED_REFERENCE -> addImport()
     ErrorType.TYPE_MISMATCH -> addTypeConversion()
     ErrorType.NULL_SAFETY -> addSafeCall()
     ErrorType.RESOURCE_NOT_FOUND -> syncGradle()
     ErrorType.MISSING_DEPENDENCY -> addDependency()
   }
   ```

4. **验证修复**
   - 重新构建项目
   - 检查是否还有错误
   - 如果有，继续修复
   - 最多尝试 3 次

### 阶段 4: 安装和测试

1. **安装到设备**
   ```bash
   ./gradlew installDebug
   ```

2. **启动应用**
   ```bash
   adb shell am start -n com.tv.ball/.MainActivity
   ```

3. **监控运行日志**
   ```bash
   adb logcat -c  # 清空日志
   adb logcat | grep -E "(E/|W/|AndroidRuntime)"
   ```

4. **检查运行状态**
   - 是否成功启动
   - 是否有崩溃
   - 是否有异常日志
   - UI 是否正常显示

### 阶段 5: 问题修复

如果运行时出现问题:

1. **分析崩溃日志**
   ```bash
   adb logcat -d > crash.log
   ```

2. **定位问题代码**
   - 查看堆栈跟踪
   - 找到出错的文件和行号

3. **修复问题**
   - 空指针检查
   - 资源引用修正
   - 线程安全处理
   - 生命周期管理

4. **重新构建和测试**
   - 重复阶段 2-4

### 阶段 6: 优化和学习

1. **记录转换案例**
   ```markdown
   ## 案例: XXX 功能转换
   
   ### 原始 Lua 代码
   ```lua
   -- Lua 代码
   ```
   
   ### 转换后 Kotlin 代码
   ```kotlin
   // Kotlin 代码
   ```
   
   ### 遇到的问题
   - 问题 1: XXX
   - 解决方案: XXX
   
   ### 优化建议
   - 建议 1: XXX
   ```

2. **更新转换规则**
   - 新增成功的转换模式
   - 优化现有规则
   - 删除过时规则

3. **更新错误修复库**
   - 记录新的错误类型
   - 添加修复方案
   - 统计成功率

## 快速命令

### 完整流程 (一键执行)
```bash
# 清理 -> 构建 -> 安装 -> 启动 -> 监控
./gradlew clean assembleDebug installDebug && \
adb shell am start -n com.tv.ball/.MainActivity && \
adb logcat -c && \
adb logcat | grep -E "(E/|W/)"
```

### 仅构建和安装
```bash
./gradlew assembleDebug installDebug
```

### 查看错误日志
```bash
# Kotlin 编译错误
cat .kotlin/errors/*.log

# 应用崩溃日志
adb logcat -d | grep -A 20 "AndroidRuntime: FATAL"
```

## 检查清单

### 转换阶段
- [ ] 所有 Lua 文件已识别
- [ ] 导入语句已转换
- [ ] 变量类型已推断
- [ ] 函数签名已转换
- [ ] 布局已转换
- [ ] 事件处理已转换

### 构建阶段
- [ ] 项目清理成功
- [ ] 编译无错误
- [ ] 编译无警告
- [ ] APK 生成成功

### 测试阶段
- [ ] 安装成功
- [ ] 启动成功
- [ ] 无崩溃
- [ ] UI 显示正常
- [ ] 功能运行正常

### 优化阶段
- [ ] 代码符合 Kotlin 规范
- [ ] 性能良好
- [ ] 无内存泄漏
- [ ] 转换规则已更新

## 常见问题解决

### Q1: 构建失败，提示找不到符号
**A**: 检查导入语句是否正确，依赖是否已添加

### Q2: 运行时崩溃，空指针异常
**A**: 添加空安全检查，使用 `?.` 或 `!!`

### Q3: 布局显示不正确
**A**: 检查布局参数转换是否正确，dp/sp 单位是否转换

### Q4: 图片加载失败
**A**: 检查资源路径，使用 Glide 或 Coil 加载网络图片

### Q5: 点击事件无响应
**A**: 检查事件监听器是否正确设置

## 性能指标

### 转换效率
- 目标: 单个文件 < 30 秒
- 当前: 待测量

### 构建时间
- 目标: 完整构建 < 2 分钟
- 当前: 待测量

### 修复成功率
- 目标: > 80%
- 当前: 待测量

### 一次构建成功率
- 目标: > 85%
- 当前: 待测量

## 持续改进

每次转换后:
1. 记录转换时间
2. 记录遇到的问题
3. 记录修复方案
4. 更新转换规则
5. 优化自动修复逻辑
6. 提高成功率
