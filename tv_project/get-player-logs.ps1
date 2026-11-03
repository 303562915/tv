# 获取播放器日志（最近1000行）
Write-Host "=== 获取播放器日志 ===" -ForegroundColor Green
Write-Host ""

# 获取最近的日志
adb logcat -d -s VideoPlayer:D *:S -t 1000

Write-Host ""
Write-Host "=== 日志获取完成 ===" -ForegroundColor Green
Write-Host "提示: 如果需要保存到文件，运行: .\get-player-logs.ps1 > logs.txt" -ForegroundColor Yellow
