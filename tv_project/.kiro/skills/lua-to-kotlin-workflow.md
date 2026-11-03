# Lua 到 Kotlin 转换工作流技能

## 核心转换规则

### 1. 导入语句转换
```lua
-- Lua
require "import"
import "android.app.*"
import "android.widget.*"
```

```kotlin
// Kotlin
import android.app.*
import android.widget.*
```

### 2. 变量声明转换
```lua
-- Lua
local 变量名 = 值
变量名 = 值  -- 全局变量
```

```kotlin
// Kotlin
val 变量名 = 值  // 不可变
var 变量名 = 值  // 可变
```

### 3. 函数定义转换
```lua
-- Lua
function 函数名(参数)
  -- 函数体
  return 结果
end

local function 局部函数(参数)
  return 结果
end
```

```kotlin
// Kotlin
fun 函数名(参数: 类型): 返回类型 {
  // 函数体
  return 结果
}

private fun 局部函数(参数: 类型): 返回类型 {
  return 结果
}
```

### 4. 表/对象转换
```lua
-- Lua
local 表 = {
  key = "value",
  数字 = 123
}
```

```kotlin
// Kotlin
data class 表类(
  val key: String = "value",
  val 数字: Int = 123
)
val 表 = 表类()

// 或使用 Map
val 表 = mapOf(
  "key" to "value",
  "数字" to 123
)
```

### 5. 布局转换
```lua
-- Lua
{
  LinearLayout;
  layout_width = 'fill';
  layout_height = 'wrap';
  orientation = 'vertical';
  {
    TextView;
    text = '文本';
    id = 'textView';
  }
}
```

```kotlin
// Kotlin (使用 View Binding 或 Compose)
LinearLayout(context).apply {
  layoutParams = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT,
    LinearLayout.LayoutParams.WRAP_CONTENT
  )
  orientation = LinearLayout.VERTICAL
  
  addView(TextView(context).apply {
    text = "文本"
    id = R.id.textView
  })
}
```

### 6. 回调函数转换
```lua
-- Lua
button.onClick = function()
  print("点击")
end
```

```kotlin
// Kotlin
button.setOnClickListener {
  println("点击")
}
```

### 7. 字符串操作转换
```lua
-- Lua
local str = "hello"
local upper = str:upper()
local sub = str:sub(1, 3)
local find = str:find("ll")
```

```kotlin
// Kotlin
val str = "hello"
val upper = str.uppercase()
val sub = str.substring(0, 3)
val find = str.indexOf("ll")
```

### 8. 数组/列表转换
```lua
-- Lua
local list = {1, 2, 3}
table.insert(list, 4)
for i, v in ipairs(list) do
  print(i, v)
end
```

```kotlin
// Kotlin
val list = mutableListOf(1, 2, 3)
list.add(4)
list.forEachIndexed { i, v ->
  println("$i $v")
}
```

### 9. 条件语句转换
```lua
-- Lua
if 条件 then
  -- 代码
elseif 其他条件 then
  -- 代码
else
  -- 代码
end
```

```kotlin
// Kotlin
if (条件) {
  // 代码
} else if (其他条件) {
  // 代码
} else {
  // 代码
}
```

### 10. 循环转换
```lua
-- Lua
for i = 1, 10 do
  print(i)
end

for k, v in pairs(table) do
  print(k, v)
end
```

```kotlin
// Kotlin
for (i in 1..10) {
  println(i)
}

map.forEach { (k, v) ->
  println("$k $v")
}
```

## 自动化工作流程

1. **读取 Lua 文件** - 从 `androlua源码` 目录读取
2. **解析 Lua 代码** - 识别关键结构
3. **应用转换规则** - 逐步转换为 Kotlin
4. **生成 Kotlin 文件** - 输出到 `app/src/main/java` 对应目录
5. **自动构建** - 运行 `./gradlew assembleDebug`
6. **检查错误** - 查看编译日志
7. **自动修复** - 根据错误信息调整代码
8. **安装测试** - 运行 `./gradlew installDebug`

## 注意事项

- Lua 的动态类型需要在 Kotlin 中明确指定
- AndroLua 的布局 DSL 需要转换为标准 Android 布局
- 回调函数需要转换为 Kotlin 的 lambda 表达式
- 字符串拼接使用 Kotlin 的字符串模板
- 错误处理使用 try-catch 而不是 pcall
