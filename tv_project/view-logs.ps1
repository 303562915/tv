# 查看应用日志
$adb = "G:\SDK\platform-tools\adb.exe"
$package = "com.tv.ball"

Write-Host "=== 查看应用日志 ===" -ForegroundColor Green

# 检查设备
$devices = & $adb devices | Select-String "device$"
if ($devices.Count -eq 0) {
    Write-Host "错误: 没有检测到设备" -ForegroundColor Red
    exit 1
}

Write-Host "设备已连接" -ForegroundColor Green

# 清除旧日志
Write-Host "`n清除旧日志..." -ForegroundColor Cyan
& $adb logcat -c

Write-Host "等待2秒..." -ForegroundColor Cyan
Start-Sleep -Seconds 2

Write-Host "`n开始捕获日志 (按 Ctrl+C 停止)..." -ForegroundColor Cyan
Write-Host "=" * 80 -ForegroundColor Gray

# 显示日志 - 包含应用包名、错误和崩溃信息
& $adb logcat -v time | Select-String -Pattern "$package|AndroidRuntime|FATAL|ERROR|Exception" -Context 0,3
