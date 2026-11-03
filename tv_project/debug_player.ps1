# 播放器调试脚本 (PowerShell)
# 用于实时监控播放器日志和错误

Write-Host "=== 开始监控播放器日志 ===" -ForegroundColor Cyan
Write-Host "按 Ctrl+C 停止监控" -ForegroundColor Cyan
Write-Host ""

# 清空日志
adb logcat -c

# 实时监控关键日志
adb logcat | Select-String -Pattern "tv\.ball|ExoPlayer|VideoPlayer|Error|Exception|Failed|Unable|timeout|connection|HTTP" | ForEach-Object {
    $line = $_.Line
    
    # 高亮错误
    if ($line -match "(?i)error|exception|failed|unable") {
        Write-Host $line -ForegroundColor Red
    }
    elseif ($line -match "(?i)warning|timeout") {
        Write-Host $line -ForegroundColor Yellow
    }
    elseif ($line -match "(?i)success|ready|playing|准备|播放") {
        Write-Host $line -ForegroundColor Green
    }
    else {
        Write-Host $line
    }
}
