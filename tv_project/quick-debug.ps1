# 快速调试脚本
$adb = "G:\SDK\platform-tools\adb.exe"
$package = "com.tv.ball"
$activity = "$package/.presentation.MainActivity"
$apk = "app\build\outputs\apk\debug\app-debug.apk"

Write-Host "=== 快速调试 ===" -ForegroundColor Green

# 检查设备
Write-Host "`n1. 检查设备连接..." -ForegroundColor Cyan
& $adb devices
$devices = & $adb devices | Select-String "device$"

if ($devices.Count -eq 0) {
    Write-Host "`n错误: 没有检测到设备!" -ForegroundColor Red
    Write-Host "请确保:" -ForegroundColor Yellow
    Write-Host "  - 手机已通过USB连接" -ForegroundColor Yellow
    Write-Host "  - 已启用USB调试" -ForegroundColor Yellow
    Write-Host "  - 已授权此电脑" -ForegroundColor Yellow
    Write-Host "`n连接设备后，重新运行此脚本" -ForegroundColor Yellow
    exit 1
}

Write-Host "设备已连接!" -ForegroundColor Green

# 安装APK
Write-Host "`n2. 安装APK..." -ForegroundColor Cyan
& $adb install -r $apk

if ($LASTEXITCODE -ne 0) {
    Write-Host "安装失败!" -ForegroundColor Red
    exit 1
}

Write-Host "安装成功!" -ForegroundColor Green

# 清除旧日志
Write-Host "`n3. 清除旧日志..." -ForegroundColor Cyan
& $adb logcat -c

# 启动应用
Write-Host "`n4. 启动应用..." -ForegroundColor Cyan
& $adb shell am start -n $activity

Start-Sleep -Seconds 2

# 查看日志
Write-Host "`n5. 查看实时日志 (按 Ctrl+C 停止)..." -ForegroundColor Cyan
Write-Host "=" * 80 -ForegroundColor Gray

& $adb logcat -v time | Select-String -Pattern "$package|AndroidRuntime|FATAL"
