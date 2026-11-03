# 测试设置页面
Write-Host "开始测试设置页面..." -ForegroundColor Green

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

# 3. 启动应用并导航到设置页面
Write-Host "`n3. 启动应用..." -ForegroundColor Yellow
adb shell am start -n com.tv.ball/.presentation.MainActivity

Start-Sleep -Seconds 2

# 模拟点击底部导航栏的设置按钮（假设是第三个tab）
Write-Host "导航到设置页面..." -ForegroundColor Yellow
adb shell input tap 900 2200

Write-Host "`n测试完成！请在设备上查看设置页面。" -ForegroundColor Green
Write-Host "`n设置页面功能：" -ForegroundColor Cyan
Write-Host "  - 快捷入口：历史收藏、本地视频、高清收藏、扩展工具" -ForegroundColor White
Write-Host "  - 更换主题色" -ForegroundColor White
Write-Host "  - 清理缓存" -ForegroundColor White
Write-Host "  - 聚合解析" -ForegroundColor White
Write-Host "  - 免费短剧" -ForegroundColor White
Write-Host "  - 扩列交友" -ForegroundColor White
Write-Host "  - 官网" -ForegroundColor White
Write-Host "  - 软件分享" -ForegroundColor White
