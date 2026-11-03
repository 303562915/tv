# 测试透明沉浸式状态栏和首页布局
Write-Host "开始测试透明沉浸式状态栏和首页布局..." -ForegroundColor Green

# 1. 清理并编译项目
Write-Host "`n1. 清理并编译项目..." -ForegroundColor Yellow
./gradlew clean assembleDebug

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

# 3. 启动应用
Write-Host "`n3. 启动应用..." -ForegroundColor Yellow
adb shell am start -n com.tv.ball/.presentation.MainActivity

Start-Sleep -Seconds 3

Write-Host "`n测试完成！请在设备上验证以下内容：" -ForegroundColor Green
Write-Host "`n✓ 状态栏透明效果：" -ForegroundColor Cyan
Write-Host "  - 状态栏应该是透明的" -ForegroundColor White
Write-Host "  - 状态栏图标应该是深色（适配浅色背景）" -ForegroundColor White
Write-Host "  - 内容应该延伸到状态栏下方" -ForegroundColor White
Write-Host "  - 所有页面都应该有透明状态栏：" -ForegroundColor White
Write-Host "    • 首页" -ForegroundColor Gray
Write-Host "    • 历史页面" -ForegroundColor Gray
Write-Host "    • 影视仓库" -ForegroundColor Gray
Write-Host "    • 设置页面" -ForegroundColor Gray
Write-Host "    • 本地视频列表" -ForegroundColor Gray
Write-Host "    • 本地视频文件夹" -ForegroundColor Gray
Write-Host "    • 搜索页面" -ForegroundColor Gray
Write-Host "    • 视频详情页面" -ForegroundColor Gray

Write-Host "`n✓ 首页布局：" -ForegroundColor Cyan
Write-Host "  - 视频列表应该一行显示 3 个视频" -ForegroundColor White
Write-Host "  - 不再是之前的 2 个视频" -ForegroundColor White

Write-Host "`n✓ 测试步骤：" -ForegroundColor Cyan
Write-Host "  1. 查看首页状态栏是否透明" -ForegroundColor White
Write-Host "  2. 查看首页视频列表是否一行 3 个" -ForegroundColor White
Write-Host "  3. 切换到历史页面，查看状态栏" -ForegroundColor White
Write-Host "  4. 切换到影视仓库页面，查看状态栏" -ForegroundColor White
Write-Host "  5. 切换到设置页面，查看状态栏" -ForegroundColor White
Write-Host "  6. 点击本地视频，查看状态栏" -ForegroundColor White
Write-Host "  7. 点击搜索，查看状态栏" -ForegroundColor White
Write-Host "  8. 点击任意视频进入详情，查看状态栏" -ForegroundColor White
