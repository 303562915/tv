# 局域网投屏功能测试脚本

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  局域网投屏功能测试" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# 1. 编译应用
Write-Host "[1/5] 编译应用..." -ForegroundColor Yellow
./gradlew :app:assembleDebug --console=plain 2>&1 | Out-Null
if ($LASTEXITCODE -eq 0) {
    Write-Host "✅ 编译成功" -ForegroundColor Green
} else {
    Write-Host "❌ 编译失败" -ForegroundColor Red
    exit 1
}

# 2. 安装应用
Write-Host "[2/5] 安装应用到设备..." -ForegroundColor Yellow
adb install -r app/build/outputs/apk/debug/app-debug.apk 2>&1 | Out-Null
if ($LASTEXITCODE -eq 0) {
    Write-Host "✅ 安装成功" -ForegroundColor Green
} else {
    Write-Host "❌ 安装失败，请检查设备连接" -ForegroundColor Red
    exit 1
}

# 3. 启动应用
Write-Host "[3/5] 启动应用..." -ForegroundColor Yellow
adb shell am start -n com.tv.ball/.presentation.MainActivity 2>&1 | Out-Null
Start-Sleep -Seconds 2
Write-Host "✅ 应用已启动" -ForegroundColor Green

# 4. 获取设备 IP
Write-Host "[4/5] 获取设备信息..." -ForegroundColor Yellow
$deviceIp = adb shell ip addr show wlan0 | Select-String -Pattern "inet\s+(\d+\.\d+\.\d+\.\d+)" | ForEach-Object { $_.Matches.Groups[1].Value }
if ($deviceIp) {
    Write-Host "✅ 设备 IP: $deviceIp" -ForegroundColor Green
} else {
    Write-Host "⚠️  无法获取设备 IP，请确保设备已连接 WiFi" -ForegroundColor Yellow
}

# 5. 显示测试说明
Write-Host ""
Write-Host "[5/5] 测试步骤：" -ForegroundColor Yellow
Write-Host ""
Write-Host "1️⃣  在应用中播放任意视频" -ForegroundColor White
Write-Host "2️⃣  点击右上角的 '投屏' 按钮" -ForegroundColor White
Write-Host "3️⃣  等待扫描局域网设备（约 10-30 秒）" -ForegroundColor White
Write-Host "4️⃣  查看 '局域网电脑' 区域是否显示电脑设备" -ForegroundColor White
Write-Host "5️⃣  点击电脑设备" -ForegroundColor White
Write-Host "6️⃣  复制显示的播放地址" -ForegroundColor White
Write-Host "7️⃣  在电脑浏览器中打开该地址" -ForegroundColor White
Write-Host "8️⃣  检查视频是否正常播放" -ForegroundColor White
Write-Host ""

# 6. 测试检查清单
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  测试检查清单" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "基础功能：" -ForegroundColor Yellow
Write-Host "  [ ] 投屏按钮可以点击" -ForegroundColor White
Write-Host "  [ ] 投屏菜单正常显示" -ForegroundColor White
Write-Host "  [ ] 显示 'DLNA 设备' 和 '局域网电脑' 两个区域" -ForegroundColor White
Write-Host ""
Write-Host "设备扫描：" -ForegroundColor Yellow
Write-Host "  [ ] 显示 '正在扫描局域网电脑...' 提示" -ForegroundColor White
Write-Host "  [ ] 能够扫描到局域网内的电脑" -ForegroundColor White
Write-Host "  [ ] 显示设备名称和 IP 地址" -ForegroundColor White
Write-Host "  [ ] 显示发现的设备数量" -ForegroundColor White
Write-Host "  [ ] 刷新按钮可以重新扫描" -ForegroundColor White
Write-Host ""
Write-Host "投屏功能：" -ForegroundColor Yellow
Write-Host "  [ ] 点击电脑设备后显示对话框" -ForegroundColor White
Write-Host "  [ ] 对话框显示播放地址" -ForegroundColor White
Write-Host "  [ ] 对话框显示设备信息" -ForegroundColor White
Write-Host "  [ ] 对话框显示视频类型（M3U8/MP4）" -ForegroundColor White
Write-Host "  [ ] '复制地址' 按钮可以复制地址" -ForegroundColor White
Write-Host ""
Write-Host "电脑播放：" -ForegroundColor Yellow
Write-Host "  [ ] 电脑浏览器可以打开播放地址" -ForegroundColor White
Write-Host "  [ ] M3U8 视频使用 DPlayer 播放器" -ForegroundColor White
Write-Host "  [ ] MP4 视频使用原生播放器" -ForegroundColor White
Write-Host "  [ ] 视频自动开始播放" -ForegroundColor White
Write-Host "  [ ] 显示视频标题和类型提示" -ForegroundColor White
Write-Host "  [ ] 3秒后提示信息自动消失" -ForegroundColor White
Write-Host "  [ ] 播放控制正常（播放/暂停/进度/音量）" -ForegroundColor White
Write-Host ""
Write-Host "异常处理：" -ForegroundColor Yellow
Write-Host "  [ ] 未连接 WiFi 时显示提示" -ForegroundColor White
Write-Host "  [ ] 扫描不到设备时显示空状态" -ForegroundColor White
Write-Host "  [ ] 服务器启动失败时显示错误" -ForegroundColor White
Write-Host ""

# 7. 日志监控
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  实时日志监控" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "按 Ctrl+C 停止日志监控" -ForegroundColor Yellow
Write-Host ""

# 清除旧日志
adb logcat -c

# 监控相关日志
adb logcat -s "LanDeviceScanner:D" "DLNACastBottomSheet:D" "LocalWebServer:D" "VideoPlayerActivity:D"
