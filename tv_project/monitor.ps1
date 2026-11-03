# 实时监控应用日志
$adb = "G:\SDK\platform-tools\adb.exe"
$package = "com.tv.ball"

Write-Host "=== 实时监控应用 ===" -ForegroundColor Green

# 检查设备
$devices = & $adb devices | Select-String "device$"
if ($devices.Count -eq 0) {
    Write-Host "错误: 没有检测到设备" -ForegroundColor Red
    exit 1
}

# 检查应用是否运行
$process = & $adb shell "ps | grep $package"
if ($process) {
    Write-Host "✓ 应用正在运行" -ForegroundColor Green
    Write-Host $process -ForegroundColor Cyan
} else {
    Write-Host "✗ 应用未运行" -ForegroundColor Yellow
    Write-Host "启动应用..." -ForegroundColor Cyan
    & $adb shell am start -n "$package/.presentation.MainActivity"
    Start-Sleep -Seconds 2
}

Write-Host "`n清除旧日志..." -ForegroundColor Cyan
& $adb logcat -c

Write-Host "`n开始实时监控 (按 Ctrl+C 停止)..." -ForegroundColor Cyan
Write-Host "=" * 80 -ForegroundColor Gray
Write-Host "过滤条件: 应用日志 + 错误 + 崩溃信息" -ForegroundColor Yellow
Write-Host "=" * 80 -ForegroundColor Gray

# 实时显示日志
& $adb logcat -v time | ForEach-Object {
    $line = $_
    
    # 高亮显示不同级别的日志
    if ($line -match "FATAL|AndroidRuntime") {
        Write-Host $line -ForegroundColor Red
    }
    elseif ($line -match "ERROR|Exception") {
        Write-Host $line -ForegroundColor Magenta
    }
    elseif ($line -match "WARNING|WARN") {
        Write-Host $line -ForegroundColor Yellow
    }
    elseif ($line -match $package) {
        Write-Host $line -ForegroundColor Cyan
    }
    elseif ($line -match "DEBUG.*$package") {
        Write-Host $line -ForegroundColor Gray
    }
}
