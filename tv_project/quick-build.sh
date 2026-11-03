#!/bin/bash

# 快速构建脚本 - 用于开发时快速构建和安装

echo "=========================================="
echo "  快速构建和安装"
echo "=========================================="

# 检查是否需要清理
if [ "$1" == "clean" ]; then
    echo "🧹 清理构建缓存..."
    ./gradlew clean
fi

# 构建 Debug APK
echo "🔨 开始构建 Debug APK..."
./gradlew assembleDebug --parallel --build-cache --configuration-cache-problems=warn

# 检查构建是否成功
if [ $? -eq 0 ]; then
    echo "✅ 构建成功！"
    
    # 查找 APK 文件
    APK_PATH=$(find app/build/outputs/apk/debug -name "*.apk" | head -n 1)
    
    if [ -n "$APK_PATH" ]; then
        echo "📦 APK 位置: $APK_PATH"
        
        # 检查是否有连接的设备
        DEVICE_COUNT=$(adb devices | grep -v "List" | grep "device" | wc -l)
        
        if [ $DEVICE_COUNT -gt 0 ]; then
            echo "📱 检测到 $DEVICE_COUNT 个设备"
            echo "🚀 开始安装..."
            adb install -r "$APK_PATH"
            
            if [ $? -eq 0 ]; then
                echo "✅ 安装成功！"
                
                # 询问是否启动应用
                read -p "是否启动应用？(y/n) " -n 1 -r
                echo
                if [[ $REPLY =~ ^[Yy]$ ]]; then
                    echo "🎬 启动应用..."
                    adb shell am start -n com.tv.ball/.presentation.MainActivity
                fi
            else
                echo "❌ 安装失败"
            fi
        else
            echo "⚠️  未检测到设备，请连接设备后手动安装"
        fi
    else
        echo "❌ 未找到 APK 文件"
    fi
else
    echo "❌ 构建失败"
    exit 1
fi

echo "=========================================="
echo "  完成"
echo "=========================================="
