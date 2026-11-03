# 简单的日志查看脚本
$package = "com.tv.ball"

Write-Host "=== 查看应用日志 ===" -ForegroundColor Green

# 尝试多个可能的 adb 路径
$adbPaths = @(
    "G:\SDK\platform-tools\adb.exe",
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    "$env:ANDROID_HOME\platform-tools\adb.exe",
    "adb"
)

$adb = $null
foreach ($path in $adbPaths) {
    if (Test-Path $path -ErrorAction SilentlyContinue) {
        $adb = $path
        Write-Host "找到 adb: $adb" -ForegroundColor Green
        break
    }
}

if (-not $adb) {
    # 尝试直接使用 adb 命令
    try {
        $null = & adb version 2>&1
        $adb = "adb"
        Write-Host "使用系统 PATH 中的 adb" -ForegroundColor Green
    } catch {
        Write-Host "错误: 找不到 adb 工具" -ForegroundColor Red
        Write-Host "请确保 Android SDK 已安装" -ForegroundColor Yellow
        Read-Host "按回车键退出"
        exit 1
    }
}

# 检查设备
Write-Host "`n检查设备连接..." -ForegroundColor Cyan
try {
    $devicesOutput = & $adb devices 2>&1
    Write-Host $devicesOutput
    
    $devices = $devicesOutput | Select-String "device$"
    if ($devices.Count -eq 0) {
        Write-Host "`n错误: 没有检测到设备" -ForegroundColor Red
        Write-Host "请确保:" -ForegroundColor Yellow
        Write-Host "1. 设备已通过 USB 连接" -ForegroundColor Yellow
        Write-Host "2. 已开启 USB 调试" -ForegroundColor Yellow
        Write-Host "3. 已授权此电脑进行调试" -ForegroundColor Yellow
        Read-Host "`n按回车键退出"
        exit 1
    }
    
    Write-Host "设备已连接" -ForegroundColor Green
} catch {
    Write-Host "错误: 无法执行 adb 命令" -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    Read-Host "按回车键退出"
    exit 1
}

# 清除旧日志
Write-Host "`n清除旧日志..." -ForegroundColor Cyan
& $adb logcat -c

Write-Host "等待2秒..." -ForegroundColor Cyan
Start-Sleep -Seconds 2

Write-Host "`n开始捕获日志 (按 Ctrl+C 停止)..." -ForegroundColor Cyan
Write-Host "过滤关键词: $package, BannerAdapter, AndroidRuntime, FATAL, ERROR" -ForegroundColor Yellow
Write-Host "=" * 80 -ForegroundColor Gray

# 显示日志 - 包含应用包名、Banner、错误和崩溃信息
try {
    & $adb logcat -v time | Select-String -Pattern "$package|BannerAdapter|AndroidRuntime|FATAL|ERROR|Exception" -Context 0,2
} catch {
    Write-Host "`n日志捕获被中断" -ForegroundColor Yellow
}

Write-Host "`n日志查看结束" -ForegroundColor Green
