@echo off
echo ========================================
echo 可可影视调试日志查看器
echo ========================================
echo.
echo 正在启动 logcat，过滤关键日志...
echo 按 Ctrl+C 停止查看
echo.
adb logcat -c
adb logcat | findstr /i "HomeViewModel NetworkDataSource VideoSourceManager"
