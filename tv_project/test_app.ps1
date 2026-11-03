# 测试应用脚本

Write-Host "=== 启动应用 ===" -ForegroundColor Green
G:\SDK\platform-tools\adb.exe shell am start -n com.tv.ball/.presentation.MainActivity

Start-Sleep -Seconds 3

Write-Host "`n=== 截图主页 ===" -ForegroundColor Green
G:\SDK\platform-tools\adb.exe shell screencap -p /sdcard/screenshot_home.png
G:\SDK\platform-tools\adb.exe pull /sdcard/screenshot_home.png .

Write-Host "`n=== 点击搜索框 ===" -ForegroundColor Green
G:\SDK\platform-tools\adb.exe shell input tap 500 200

Start-Sleep -Seconds 2

Write-Host "`n=== 截图搜索页面 ===" -ForegroundColor Green
G:\SDK\platform-tools\adb.exe shell screencap -p /sdcard/screenshot_search.png
G:\SDK\platform-tools\adb.exe pull /sdcard/screenshot_search.png .

Write-Host "`n=== 返回主页 ===" -ForegroundColor Green
G:\SDK\platform-tools\adb.exe shell input keyevent KEYCODE_BACK

Start-Sleep -Seconds 1

Write-Host "`n=== 点击历史 Tab ===" -ForegroundColor Green
G:\SDK\platform-tools\adb.exe shell input tap 400 2200

Start-Sleep -Seconds 2

Write-Host "`n=== 截图历史页面 ===" -ForegroundColor Green
G:\SDK\platform-tools\adb.exe shell screencap -p /sdcard/screenshot_history.png
G:\SDK\platform-tools\adb.exe pull /sdcard/screenshot_history.png .

Write-Host "`n=== 查看应用日志 ===" -ForegroundColor Green
G:\SDK\platform-tools\adb.exe logcat -d | Select-String "HomeViewModel|SearchActivity" | Select-Object -Last 10

Write-Host "`n=== 测试完成 ===" -ForegroundColor Green
Write-Host "截图已保存到当前目录:" -ForegroundColor Yellow
Write-Host "  - screenshot_home.png (主页)" -ForegroundColor Cyan
Write-Host "  - screenshot_search.png (搜索页)" -ForegroundColor Cyan
Write-Host "  - screenshot_history.png (历史页)" -ForegroundColor Cyan
