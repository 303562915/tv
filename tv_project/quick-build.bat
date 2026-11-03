@echo off
REM 快速构建脚本 - Windows 版本

echo ==========================================
echo   快速构建和安装
echo ==========================================

REM 检查是否需要清理
if "%1"=="clean" (
    echo 🧹 清理构建缓存...
    call gradlew.bat clean
)

REM 构建 Debug APK
echo 🔨 开始构建 Debug APK...
call gradlew.bat assembleDebug --parallel --build-cache --configuration-cache-problems=warn

if %ERRORLEVEL% EQU 0 (
    echo ✅ 构建成功！
    
    REM 查找 APK 文件
    for /f "delims=" %%i in ('dir /s /b app\build\outputs\apk\debug\*.apk 2^>nul') do set APK_PATH=%%i
    
    if defined APK_PATH (
        echo 📦 APK 位置: %APK_PATH%
        
        REM 检查是否有连接的设备
        adb devices | find "device" >nul
        if %ERRORLEVEL% EQU 0 (
            echo 📱 检测到设备
            echo 🚀 开始安装...
            adb install -r "%APK_PATH%"
            
            if %ERRORLEVEL% EQU 0 (
                echo ✅ 安装成功！
                
                REM 询问是否启动应用
                set /p LAUNCH="是否启动应用？(y/n) "
                if /i "%LAUNCH%"=="y" (
                    echo 🎬 启动应用...
                    adb shell am start -n com.tv.ball/.presentation.MainActivity
                )
            ) else (
                echo ❌ 安装失败
            )
        ) else (
            echo ⚠️  未检测到设备，请连接设备后手动安装
        )
    ) else (
        echo ❌ 未找到 APK 文件
    )
) else (
    echo ❌ 构建失败
    exit /b 1
)

echo ==========================================
echo   完成
echo ==========================================
pause
