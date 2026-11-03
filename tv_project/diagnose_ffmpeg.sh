#!/bin/bash

# FFmpeg 转换失败诊断脚本
# 使用方法: ./diagnose_ffmpeg.sh

echo "=========================================="
echo "FFmpeg 转换失败诊断工具"
echo "=========================================="
echo ""

# 检查设备连接
echo "[1/6] 检查设备连接..."
if ! adb devices | grep -q "device$"; then
    echo "❌ 未检测到 Android 设备"
    echo "请确保设备已连接并启用 USB 调试"
    exit 1
fi
echo "✓ 设备已连接"
echo ""

# 检查应用是否安装
echo "[2/6] 检查应用安装..."
PACKAGE="com.tv.ball"
if ! adb shell pm list packages | grep -q "$PACKAGE"; then
    echo "❌ 应用未安装"
    exit 1
fi
echo "✓ 应用已安装"
echo ""

# 检查 FFmpeg 库
echo "[3/6] 检查 FFmpeg 库..."
adb shell "ls /data/app/$PACKAGE*/lib/*/libffmpegkit*.so" 2>/dev/null
if [ $? -eq 0 ]; then
    echo "✓ FFmpeg 库已安装"
else
    echo "⚠ 未找到 FFmpeg 库文件"
fi
echo ""

# 查看最近的日志
echo "[4/6] 查看最近的 FFmpeg 日志..."
echo "----------------------------------------"
adb logcat -d | grep -E "VideoMerger|FFmpeg" | tail -50
echo "----------------------------------------"
echo ""

# 检查临时文件
echo "[5/6] 检查临时文件..."
CACHE_DIR="/sdcard/Android/data/$PACKAGE/cache"
echo "缓存目录: $CACHE_DIR"
adb shell "ls -lh $CACHE_DIR/*_temp.ts 2>/dev/null" || echo "未找到临时 TS 文件"
echo ""

# 检查存储空间
echo "[6/6] 检查存储空间..."
adb shell df -h | grep -E "Filesystem|/data|/sdcard"
echo ""

echo "=========================================="
echo "诊断完成"
echo "=========================================="
echo ""
echo "如果需要实时查看日志，运行："
echo "  adb logcat | grep -E 'VideoMerger|FFmpeg'"
echo ""
echo "如果需要导出临时 TS 文件进行测试，运行："
echo "  adb pull $CACHE_DIR/xxx_temp.ts ."
echo ""
