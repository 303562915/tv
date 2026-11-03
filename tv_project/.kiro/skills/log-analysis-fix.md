# 日志分析和自动修复技能

## 日志分析流程

### 1. 读取日志文件
```bash
# Gradle 构建日志
cat build/outputs/logs/gradle-build.log

# Kotlin 编译错误日志
cat .kotlin/errors/*.log

# Android Logcat
adb logcat -d > logcat.txt
```

### 2. 错误模式识别

#### 编译错误模式
```regex
错误: (.+)
位置: (.+)
行号: (\d+)
```

#### 运行时错误模式
```regex
Exception: (.+)
at (.+):(\d+)
Caused by: (.+)
```

### 3. 自动修复规则

#### 规则 1: 未解析的引用
```
错误: Unresolved reference: XXX
```
**修复**: 
1. 搜索 XXX 的正确包名
2. 添加 `import` 语句
3. 如果是自定义类，检查文件是否存在

#### 规则 2: 类型不匹配
```
错误: Type mismatch: inferred type is String but Int was expected
```
**修复**:
1. 添加类型转换: `value.toInt()`
2. 或修改变量声明类型

#### 规则 3: 空安全错误
```
错误: Only safe (?.) or non-null asserted (!!.) calls are allowed
```
**修复**:
1. 使用安全调用: `value?.method()`
2. 或添加空检查: `if (value != null) { value.method() }`

#### 规则 4: 缺少依赖
```
错误: Cannot resolve symbol 'XXX'
```
**修复**:
1. 在 `build.gradle.kts` 中添加依赖
2. 同步 Gradle

#### 规则 5: 资源未找到
```
错误: Unresolved reference: R
```
**修复**:
1. 运行 `./gradlew clean`
2. 同步 Gradle
3. 重建项目

## 常见错误及解决方案

### Kotlin 编译错误

#### 1. 语法错误
```kotlin
// 错误
fun test() {
  val x = 10
  if x > 5 {  // 缺少括号
    println("大于5")
  }
}

// 修复
fun test() {
  val x = 10
  if (x > 5) {  // 添加括号
    println("大于5")
  }
}
```

#### 2. 类型推断失败
```kotlin
// 错误
val list = listOf()  // 无法推断类型

// 修复
val list = listOf<String>()  // 显式指定类型
// 或
val list: List<String> = listOf()
```

#### 3. 可变性错误
```kotlin
// 错误
val list = listOf(1, 2, 3)
list.add(4)  // val 声明的 List 不可变

// 修复
val list = mutableListOf(1, 2, 3)
list.add(4)
```

### Android 运行时错误

#### 1. NullPointerException
```kotlin
// 错误
val text = textView.text.toString()  // textView 可能为 null

// 修复
val text = textView?.text?.toString() ?: ""
```

#### 2. ClassCastException
```kotlin
// 错误
val view = findViewById(R.id.myView) as TextView  // 可能不是 TextView

// 修复
val view = findViewById<TextView>(R.id.myView)
// 或
val view = findViewById(R.id.myView) as? TextView
```

#### 3. ResourceNotFoundException
```kotlin
// 错误
val drawable = resources.getDrawable(R.drawable.icon)  // 资源不存在

// 修复
// 1. 检查资源文件是否存在
// 2. 检查资源名称是否正确
// 3. 同步 Gradle
```

## 自动修复脚本

```kotlin
// 错误日志解析器
class ErrorLogParser {
  fun parse(logContent: String): List<CompileError> {
    val errors = mutableListOf<CompileError>()
    val pattern = Regex("""错误: (.+)\n位置: (.+):(\d+)""")
    
    pattern.findAll(logContent).forEach { match ->
      val (message, file, line) = match.destructured
      errors.add(CompileError(message, file, line.toInt()))
    }
    
    return errors
  }
}

// 自动修复器
class AutoFixer {
  fun fix(error: CompileError): FixSuggestion {
    return when {
      error.message.contains("Unresolved reference") -> {
        val symbol = extractSymbol(error.message)
        FixSuggestion.AddImport(findImport(symbol))
      }
      error.message.contains("Type mismatch") -> {
        FixSuggestion.AddTypeConversion(extractTypes(error.message))
      }
      error.message.contains("Only safe") -> {
        FixSuggestion.AddSafeCall(error.file, error.line)
      }
      else -> FixSuggestion.Manual(error.message)
    }
  }
}
```

## 日志监控

```bash
# 实时监控构建日志
./gradlew assembleDebug --info | tee build.log

# 监控应用日志
adb logcat -c  # 清空日志
adb logcat | grep -E "(E/|W/)" | tee app.log
```

## 修复验证

修复后自动验证:
1. 重新编译项目
2. 检查是否还有错误
3. 如果有新错误，继续修复
4. 直到编译成功或达到最大尝试次数
