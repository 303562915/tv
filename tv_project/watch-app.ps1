# 实时监控应用日志
$package = "com.tv.ball"

Write-Host "=== 实时监控应用日志 ===" -ForegroundColor Green
Write-Host "监控包名: $package" -ForegroundColor Cyan
Write-Host "按 Ctrl+C 停止监控" -ForegroundColor Yellow
Write-Host "=" * 80 -ForegroundColor Gray

# 找到 adb
$adb = "G:\SDK\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    $adb = "adb"
}

# 清除旧日志
& $adb logcat -c

# 启动应用
Write-Host "`n启动应用..." -ForegroundColor Cyan
& $adb shell am start -n "$package/.presentation.MainActivity"

Start-Sleep -Seconds 1

# 实时显示日志
Write-Host "`n开始监控日志..." -ForegroundColor Cyan
Write-Host "=" * 80 -ForegroundColor Gray

try {
    & $adb logcat -v time | Select-String -Pattern "$package|BannerAdapter|ExoPlayerManager|LocalVideo|AndroidRuntime|FATAL|ERROR" -Context 0,1
} catch {
    Write-Host "`n监控被中断" -ForegroundColor Yellow
}
