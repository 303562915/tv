# 测试云端轮播图加载
$adb = "G:\SDK\platform-tools\adb.exe"
$package = "com.tv.ball"

Write-Host "=== 测试云端轮播图加载 ===" -ForegroundColor Green

# 清除日志
& $adb logcat -c

# 启动应用
Write-Host "`n1. 启动应用..." -ForegroundColor Cyan
& $adb shell am start -n "$package/.presentation.MainActivity"

Write-Host "`n2. 等待5秒，让应用加载云端数据..." -ForegroundColor Cyan
Start-Sleep -Seconds 5

# 获取所有相关日志
Write-Host "`n3. 查看云端数据加载日志..." -ForegroundColor Cyan
$logs = & $adb logcat -d -v time | Select-String -Pattern "HomeFragment|BannerAdapter" | Select-Object -Last 50

if ($logs) {
    Write-Host "`n找到以下日志:" -ForegroundColor Yellow
    Write-Host "=" * 80 -ForegroundColor Gray
    $logs | ForEach-Object { 
        $line = $_.ToString()
        if ($line -match "云端|JSON|解析|更新") {
            Write-Host $line -ForegroundColor Green
        } elseif ($line -match "失败|错误|异常") {
            Write-Host $line -ForegroundColor Red
        } else {
            Write-Host $line
        }
    }
    Write-Host "=" * 80 -ForegroundColor Gray
} else {
    Write-Host "未找到相关日志" -ForegroundColor Red
}

Write-Host "`n4. 检查网络请求..." -ForegroundColor Cyan
$networkLogs = & $adb logcat -d -v time | Select-String -Pattern "jianhancloud|banner.json" | Select-Object -Last 10
if ($networkLogs) {
    Write-Host "网络请求日志:" -ForegroundColor Yellow
    $networkLogs | ForEach-Object { Write-Host $_ }
}

Write-Host "`n测试完成！" -ForegroundColor Green
Write-Host "请检查手机上的轮播图是否显示云端图片" -ForegroundColor Yellow
