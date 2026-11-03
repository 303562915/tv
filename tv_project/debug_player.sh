#!/bin/bash

# 播放器调试脚本
# 用于实时监控播放器日志和错误

echo "=== 开始监控播放器日志 ==="
echo "按 Ctrl+C 停止监控"
echo ""

# 清空日志
adb logcat -c

# 实时监控关键日志
adb logcat | grep -E "tv\.ball|ExoPlayer|VideoPlayer|Error|Exception|Failed|Unable|timeout|connection|HTTP" --color=always | while read line; do
    # 高亮错误
    if echo "$line" | grep -qi "error\|exception\|failed\|unable"; then
        echo -e "\033[1;31m$line\033[0m"  # 红色
    elif echo "$line" | grep -qi "warning\|timeout"; then
        echo -e "\033[1;33m$line\033[0m"  # 黄色
    elif echo "$line" | grep -qi "success\|ready\|playing"; then
        echo -e "\033[1;32m$line\033[0m"  # 绿色
    else
        echo "$line"
    fi
done
