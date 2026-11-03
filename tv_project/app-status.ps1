# 应用状态检查
$adb = "G:\SDK\platform-tools\adb.exe"
$package = "com.tv.ball"

Write-Host "=== 应用状态报告 ===" -ForegroundColor Green
Write-Host ""

# 1. 设备信息
Write-Host "【设备信息】" -ForegroundColor Cyan
$model = & $adb shell getprop ro.product.model
$android = & $adb shell getprop ro.build.version.release
Write-Host "  型号: $model" -ForegroundColor White
Write-Host "  Android版本: $android" -ForegroundColor White

# 2. 应用状态
Write-Host "`n【应用状态】" -ForegroundColor Cyan
$process = & $adb shell "ps | grep $package"
if ($process) {
    Write-Host "  ✓ 应用正在运行" -ForegroundColor Green
    $pid = ($process -split '\s+')[1]
    Write-Host "  进程ID: $pid" -ForegroundColor White
    
    # 内存使用
    $meminfo = & $adb shell "dumpsys meminfo $package | grep TOTAL"
    if ($meminfo) {
        Write-Host "  内存使用: $meminfo" -ForegroundColor White
    }
} else {
    Write-Host "  ✗ 应用未运行" -ForegroundColor Red
}

# 3. 应用版本
Write-Host "`n【应用信息】" -ForegroundColor Cyan
$version = & $adb shell "dumpsys package $package | grep versionName"
if ($version) {
    Write-Host "  $version" -ForegroundColor White
}

# 4. 最近的崩溃
Write-Host "`n【崩溃检查】" -ForegroundColor Cyan
$crashes = & $adb logcat -d -v time | Select-String "FATAL.*$package|AndroidRuntime.*$package" | Select-Object -Last 5
if ($crashes) {
    Write-Host "  ✗ 发现崩溃记录:" -ForegroundColor Red
    $crashes | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
} else {
    Write-Host "  ✓ 无崩溃记录" -ForegroundColor Green
}

# 5. 最近的错误
Write-Host "`n【错误检查】" -ForegroundColor Cyan
$errors = & $adb logcat -d -v time | Select-String "ERROR.*$package|Exception.*$package" | Select-Object -Last 5
if ($errors) {
    Write-Host "  ⚠ 发现错误:" -ForegroundColor Yellow
    $errors | ForEach-Object { Write-Host "    $_" -ForegroundColor Yellow }
} else {
    Write-Host "  ✓ 无错误记录" -ForegroundColor Green
}

# 6. 活动状态
Write-Host "`n【当前Activity】" -ForegroundColor Cyan
$activity = & $adb shell "dumpsys activity activities | grep mResumedActivity"
if ($activity -match $package) {
    Write-Host "  $activity" -ForegroundColor Green
} else {
    Write-Host "  应用不在前台" -ForegroundColor Gray
}

Write-Host "`n" + "=" * 80 -ForegroundColor Gray
Write-Host "报告完成" -ForegroundColor Green
