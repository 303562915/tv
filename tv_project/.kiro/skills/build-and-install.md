# 构建和安装工作流程

## 重要提醒
每次修改代码后，必须执行以下步骤才能在设备上看到更新：

## 标准工作流程

### 1. 编译项目
```bash
./gradlew assembleDebug
```

### 2. 安装到设备
```bash
./gradlew installDebug
```

### 3. 或者一步完成
```bash
./gradlew installDebug
```
注意：`installDebug` 会自动触发 `assembleDebug`，所以可以直接运行这一个命令。

## 快速命令

### Windows PowerShell
```powershell
# 直接安装（推荐）
./gradlew installDebug

# 清理后安装
./gradlew clean installDebug
```

### 检查连接的设备
```bash
adb devices
```

## 何时需要构建安装

### 必须重新安装的情况
- ✅ 修改了任何 Kotlin/Java 代码
- ✅ 修改了 XML 布局文件
- ✅ 修改了资源文件（图片、字符串等）
- ✅ 修改了 Gradle 配置
- ✅ 添加了新的依赖

### 不需要重新安装的情况
- ❌ 只修改了文档（.md 文件）
- ❌ 只修改了 .gitignore
- ❌ 只查看代码没有修改

## 常见问题

### 设备未连接
```bash
# 检查设备
adb devices

# 如果没有设备，检查 USB 调试是否开启
# 重新连接 USB 线
```

### 安装失败
```bash
# 先卸载旧版本
adb uninstall com.tv.ball

# 再重新安装
./gradlew installDebug
```

### 编译错误
```bash
# 清理后重新编译
./gradlew clean
./gradlew assembleDebug
```

## 调试技巧

### 查看日志
```bash
# 查看应用日志
adb logcat | grep -E "HomeViewModel|BannerAdapter|VideoSourceManager|VideoDetailParser"

# 清除日志后查看
adb logcat -c
adb logcat
```

### 查看崩溃信息
```bash
adb logcat | grep -E "AndroidRuntime|FATAL"
```

## 自动化脚本

### PowerShell 脚本（推荐）
创建 `build-install.ps1`：
```powershell
#!/usr/bin/env pwsh
Write-Host "开始构建和安装..." -ForegroundColor Green

# 构建并安装
./gradlew installDebug

if ($LASTEXITCODE -eq 0) {
    Write-Host "✅ 安装成功！" -ForegroundColor Green
} else {
    Write-Host "❌ 安装失败！" -ForegroundColor Red
}
```

使用：
```powershell
./build-install.ps1
```

## 记住这个规则

**每次修改代码后，AI助手必须：**
1. 运行 `./gradlew installDebug`
2. 等待安装成功
3. 告知用户可以在设备上测试

**不要：**
- ❌ 修改代码后不安装就让用户测试
- ❌ 只编译不安装
- ❌ 忘记这个步骤

## 当前设备信息
- 设备ID: `23113RKC6C - 16`
- 包名: `com.tv.ball`
