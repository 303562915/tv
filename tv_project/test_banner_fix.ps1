# 测试轮播图修复
Write-Host "测试轮播图加载修复..." -ForegroundColor Green

# 1. 编译项目
Write-Host "`n1. 编译项目..." -ForegroundColor Yellow
./gradlew assembleDebug

if ($LASTEXITCODE -ne 0) {
    Write-Host "编译失败!" -ForegroundColor Red
    exit 1
}

Write-Host "编译成功!" -ForegroundColor Green

# 2. 安装应用
Write-Host "`n2. 安装应用..." -ForegroundColor Yellow
adb install -r app/build/outputs/apk/debug/app-debug.apk

if ($LASTEXITCODE -ne 0) {
    Write-Host "安装失败!" -ForegroundColor Red
    exit 1
}

Write-Host "安装成功!" -ForegroundColor Green

# 3. 清除应用数据和缓存
Write-Host "`n3. 清除应用数据..." -ForegroundColor Yellow
adb shell pm clear com.tv.ball

# 4. 启动应用
Write-Host "`n4. 启动应用..." -ForegroundColor Yellow
adb shell am start -n com.tv.ball/.presentation.MainActivity

Start-Sleep -Seconds 3

# 5. 查看轮播图日志
Write-Host "`n5. 查看轮播图日志..." -ForegroundColor Yellow
Write-Host "正在监控日志，按 Ctrl+C 停止..." -ForegroundColor Cyan
adb logcat -c
adb logcat | Select-String -Pattern "BannerAdapter|Glide"

Write-Host "`n测试完成！" -ForegroundColor Green
Write-Host "`n请在设备上验证：" -ForegroundColor Cyan
Write-Host "  1. 轮播图是否正常显示" -ForegroundColor White
Write-Host "  2. 轮播图是否自动切换" -ForegroundColor White
Write-Host "  3. 轮播图指示器是否正常" -ForegroundColor White
Write-Host "  4. 没有 Glide 加载失败的错误" -ForegroundColor White
