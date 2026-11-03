# 测试轮播图和本地视频功能
$adb = "G:\SDK\platform-tools\adb.exe"
$package = "com.tv.ball"

Write-Host "=== 测试应用功能 ===" -ForegroundColor Green

# 清除日志
& $adb logcat -c

# 启动应用
Write-Host "`n1. 启动应用..." -ForegroundColor Cyan
& $adb shell am start -n "$package/.presentation.MainActivity"
Start-Sleep -Seconds 3

# 检查轮播图日志
Write-Host "`n2. 检查轮播图加载情况..." -ForegroundColor Cyan
$bannerLogs = & $adb logcat -d -v time | Select-String -Pattern "BannerAdapter" | Select-Object -Last 20
if ($bannerLogs) {
    Write-Host "轮播图日志:" -ForegroundColor Yellow
    $bannerLogs | ForEach-Object { Write-Host $_ }
} else {
    Write-Host "未找到轮播图日志" -ForegroundColor Red
}

Write-Host "`n3. 等待5秒后检查图片加载..." -ForegroundColor Cyan
Start-Sleep -Seconds 5

$glideLogs = & $adb logcat -d -v time | Select-String -Pattern "Glide.*Load" | Select-Object -Last 10
if ($glideLogs) {
    Write-Host "Glide 加载日志:" -ForegroundColor Yellow
    $glideLogs | ForEach-Object { Write-Host $_ }
}

# 检查是否有错误
Write-Host "`n4. 检查错误日志..." -ForegroundColor Cyan
$errorLogs = & $adb logcat -d -v time | Select-String -Pattern "ERROR|FATAL|Exception" | Select-String -Pattern "$package" | Select-Object -Last 10
if ($errorLogs) {
    Write-Host "发现错误:" -ForegroundColor Red
    $errorLogs | ForEach-Object { Write-Host $_ -ForegroundColor Red }
} else {
    Write-Host "未发现错误" -ForegroundColor Green
}

Write-Host "`n测试完成！" -ForegroundColor Green
Write-Host "请在手机上:" -ForegroundColor Yellow
Write-Host "1. 查看首页轮播图是否显示" -ForegroundColor Yellow
Write-Host "2. 点击'本地'标签，查看本地视频列表" -ForegroundColor Yellow
Write-Host "3. 点击某个视频文件夹，查看视频缩略图" -ForegroundColor Yellow
Write-Host "4. 点击某个视频，测试播放功能" -ForegroundColor Yellow
