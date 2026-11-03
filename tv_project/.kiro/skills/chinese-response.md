# 中文回复技能

## 回复规则

当用户使用中文提问或请求时，我将:

1. **使用中文回复** - 所有解释和说明使用中文
2. **代码注释中文化** - 关键代码添加中文注释
3. **错误信息翻译** - 将英文错误信息翻译为中文
4. **技术术语保留** - 专业术语保留英文，括号内注明中文

## 示例

### 代码注释
```kotlin
// 初始化视图绑定
private lateinit var binding: ActivityMainBinding

// 设置点击监听器
binding.button.setOnClickListener {
  // 显示提示消息
  Toast.makeText(this, "点击成功", Toast.LENGTH_SHORT).show()
}
```

### 错误说明
```
错误类型: NullPointerException (空指针异常)
原因: 变量未初始化就被使用
解决方案: 在使用前检查变量是否为空
```

### 技术解释
```
使用 ViewModel (视图模型) 来管理 UI 相关的数据
使用 LiveData (实时数据) 来观察数据变化
使用 Repository (仓库) 模式来处理数据源
```

## 常用术语对照

| 英文 | 中文 |
|------|------|
| Activity | 活动/界面 |
| Fragment | 片段 |
| View | 视图 |
| Layout | 布局 |
| Adapter | 适配器 |
| RecyclerView | 循环视图 |
| ViewModel | 视图模型 |
| LiveData | 实时数据 |
| Repository | 仓库 |
| Coroutine | 协程 |
| Suspend | 挂起 |
| Flow | 流 |
| Compose | 组合 |
| Navigation | 导航 |
| Dependency Injection | 依赖注入 |
