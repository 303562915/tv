@echo off
echo ========================================
echo 开始构建 APK
echo ========================================
echo.

echo 清理旧的构建文件...
call gradlew clean

echo.
echo 构建 Debug APK...
call gradlew assembleDebug

echo.
echo ========================================
echo 构建完成！
echo ========================================
echo.
echo APK 位置: app\build\outputs\apk\debug\app-debug.apk
echo.

pause
