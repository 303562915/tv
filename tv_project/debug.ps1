# Android调试脚本

Write-Host "=== Android应用调试工具 ===" -ForegroundColor Green

# 尝试找到ADB
$adbPaths = @(
    "G:\SDK\platform-tools\adb.exe",
    "C:\Users\$env:USERNAME\AppData\Local\Android\Sdk\platform-tools\adb.exe",
    "C:\Program Files\Android\Android Studio\jbr\bin\adb.exe",
    "$env:ANDROID_HOME\platform-tools\adb.exe"
)

$adb = $null
foreach ($path in $adbPaths) {
    if (Test-Path $path) {
        $adb = $path
        Write-Host "找到ADB: $adb" -ForegroundColor Cyan
        break
    }
}

if (-not $adb) {
    Write-Host "错误: 未找到ADB工具" -ForegroundColor Red
    Write-Host "请确保已安装Android Studio和Android SDK" -ForegroundColor Yellow
    Write-Host "或手动设置ANDROID_HOME环境变量" -ForegroundColor Yellow
    exit 1
}

# 检查设备
Write-Host "`n检查连接的设备..." -ForegroundColor Yellow
& $adb devices

$devices = & $adb devices | Select-String "device$"
if ($devices.Count -eq 0) {
    Write-Host "错误: 没有检测到连接的设备" -ForegroundColor Red
    Write-Host "请确保:" -ForegroundColor Yellow
    Write-Host "  1. 手机已通过USB连接到电脑" -ForegroundColor Yellow
    Write-Host "  2. 手机已启用USB调试" -ForegroundColor Yellow
    Write-Host "  3. 已授权此电脑进行调试" -ForegroundColor Yellow
    exit 1
}

Write-Host "设备已连接!" -ForegroundColor Green

# 菜单
Write-Host "`n请选择操作:" -ForegroundColor Cyan
Write-Host "1. 安装APK"
Write-Host "2. 启动应用"
Write-Host "3. 查看实时日志"
Write-Host "4. 清除日志并重启应用"
Write-Host "5. 卸载应用"
Write-Host "6. 全部执行(安装+启动+日志)"

$choice = Read-Host "`n输入选项 (1-6)"

$apkPath = "app\build\outputs\apk\debug\app-debug.apk"
$package = "com.tv.ball"
$activity = "$package/.presentation.MainActivity"

switch ($choice) {
    "1" {
        Write-Host "`n安装APK..." -ForegroundColor Yellow
        & $adb install -r $apkPath
    }
    "2" {
        Write-Host "`n启动应用..." -ForegroundColor Yellow
        & $adb shell am start -n $activity
    }
    "3" {
        Write-Host "`n查看实时日志 (按Ctrl+C停止)..." -ForegroundColor Yellow
        & $adb logcat -v time | Select-String $package
    }
    "4" {
        Write-Host "`n清除日志..." -ForegroundColor Yellow
        & $adb logcat -c
        Write-Host "停止应用..." -ForegroundColor Yellow
        & $adb shell am force-stop $package
        Write-Host "启动应用..." -ForegroundColor Yellow
        & $adb shell am start -n $activity
        Start-Sleep -Seconds 2
        Write-Host "查看日志 (按Ctrl+C停止)..." -ForegroundColor Yellow
        & $adb logcat -v time
    }
    "5" {
        Write-Host "`n卸载应用..." -ForegroundColor Yellow
        & $adb uninstall $package
    }
    "6" {
        Write-Host "`n执行完整流程..." -ForegroundColor Yellow
        Write-Host "1. 安装APK..." -ForegroundColor Cyan
        & $adb install -r $apkPath
        Write-Host "2. 清除旧日志..." -ForegroundColor Cyan
        & $adb logcat -c
        Write-Host "3. 启动应用..." -ForegroundColor Cyan
        & $adb shell am start -n $activity
        Start-Sleep -Seconds 2
        Write-Host "4. 查看日志 (按Ctrl+C停止)..." -ForegroundColor Cyan
        & $adb logcat -v time | Select-String -Pattern "$package|AndroidRuntime"
    }
    default {
        Write-Host "无效选项" -ForegroundColor Red
    }
}
