# 自动构建和测试技能

## 构建流程

### 1. 清理项目
```bash
./gradlew clean
```

### 2. 构建 Debug 版本
```bash
./gradlew assembleDebug
```

### 3. 安装到设备
```bash
./gradlew installDebug
```

### 4. 运行测试
```bash
./gradlew test
```

### 5. 完整流程
```bash
./gradlew clean assembleDebug installDebug
```

## 错误日志分析

### 常见错误类型

#### 1. 编译错误
```
错误: 找不到符号
位置: 类 MainActivity
```
**解决方案**: 检查导入语句和类名拼写

#### 2. 类型不匹配
```
错误: 不兼容的类型: String无法转换为Int
```
**解决方案**: 添加类型转换或修正类型声明

#### 3. 空指针异常
```
错误: 可能为空的值
```
**解决方案**: 使用安全调用 `?.` 或非空断言 `!!`

#### 4. 资源未找到
```
错误: 无法解析符号 'R'
```
**解决方案**: 同步 Gradle 或清理重建项目

### 日志查看命令

```bash
# 查看构建日志
./gradlew assembleDebug --stacktrace

# 查看详细日志
./gradlew assembleDebug --info

# 查看调试日志
./gradlew assembleDebug --debug

# 查看运行时日志
adb logcat | grep "YourAppTag"
```

## 自动修复策略

### 1. 导入缺失
检测到 "Unresolved reference" 错误时:
- 自动添加对应的 import 语句
- 检查依赖是否已添加到 build.gradle

### 2. 类型推断失败
检测到类型错误时:
- 显式声明变量类型
- 添加类型转换函数

### 3. 空安全问题
检测到空指针警告时:
- 使用 `?.let { }` 安全调用
- 添加空值检查 `if (value != null)`

### 4. 资源引用错误
检测到资源未找到时:
- 检查资源文件是否存在
- 同步 Gradle 文件
- 清理并重建项目

## 测试检查清单

- [ ] 应用能否成功编译
- [ ] 应用能否成功安装
- [ ] 应用能否正常启动
- [ ] 主界面是否正确显示
- [ ] 点击事件是否响应
- [ ] 网络请求是否正常
- [ ] 图片加载是否正常
- [ ] 列表滚动是否流畅
- [ ] 无崩溃和 ANR

## 性能优化建议

1. **布局优化**
   - 减少布局层级
   - 使用 ConstraintLayout
   - 避免过度绘制

2. **内存优化**
   - 及时释放资源
   - 使用图片缓存
   - 避免内存泄漏

3. **网络优化**
   - 使用连接池
   - 启用缓存
   - 压缩数据传输

4. **启动优化**
   - 延迟初始化
   - 异步加载
   - 减少主线程工作

5. 构建安装命令
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
