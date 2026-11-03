# 实时查看播放器日志
Write-Host "=== 开始监控播放器日志 ===" -ForegroundColor Green
Write-Host "提示: 按 Ctrl+C 停止监控" -ForegroundColor Yellow
Write-Host ""

# 清空之前的日志
adb logcat -c

# 实时显示日志，只显示 VideoPlayer 标签
adb logcat -s VideoPlayer:D *:S
