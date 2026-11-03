@echo off
REM FFmpeg 转换失败诊断脚本 (Windows)
REM 使用方法: diagnose_ffmpeg.bat

echo ==========================================
echo FFmpeg 转换失败诊断工具
echo ==========================================
echo.

REM 检查设备连接
echo [1/6] 检查设备连接...
adb devices | findstr "device" >nul
if errorlevel 1 (
    echo X 未检测到 Android 设备
    echo 请确保设备已连接并启用 USB 调试
    exit /b 1
)
echo √ 设备已连接
echo.

REM 检查应用是否安装
echo [2/6] 检查应用安装...
set PACKAGE=com.tv.ball
adb shell pm list packages | findstr "%PACKAGE%" >nul
if errorlevel 1 (
    echo X 应用未安装
    exit /b 1
)
echo √ 应用已安装
echo.

REM 检查 FFmpeg 库
echo [3/6] 检查 FFmpeg 库...
adb shell "ls /data/app/%PACKAGE%*/lib/*/libffmpegkit*.so" 2>nul
if errorlevel 1 (
    echo ! 未找到 FFmpeg 库文件
) else (
    echo √ FFmpeg 库已安装
)
echo.

REM 查看最近的日志
echo [4/6] 查看最近的 FFmpeg 日志...
echo ----------------------------------------
adb logcat -d | findstr /C:"VideoMerger" /C:"FFmpeg" | more +1
echo ----------------------------------------
echo.

REM 检查临时文件
echo [5/6] 检查临时文件...
set CACHE_DIR=/sdcard/Android/data/%PACKAGE%/cache
echo 缓存目录: %CACHE_DIR%
adb shell "ls -lh %CACHE_DIR%/*_temp.ts 2>/dev/null"
if errorlevel 1 echo 未找到临时 TS 文件
echo.

REM 检查存储空间
echo [6/6] 检查存储空间...
adb shell df -h | findstr /C:"Filesystem" /C:"/data" /C:"/sdcard"
echo.

echo ==========================================
echo 诊断完成
echo ==========================================
echo.
echo 如果需要实时查看日志，运行：
echo   adb logcat ^| findstr /C:"VideoMerger" /C:"FFmpeg"
echo.
echo 如果需要导出临时 TS 文件进行测试，运行：
echo   adb pull %CACHE_DIR%/xxx_temp.ts .
echo.
pause
