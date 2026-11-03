#!/bin/bash

# 达达趣调试脚本
# 用法: ./test_dadaqu.sh

echo "=========================================="
echo "达达趣视频源调试工具"
echo "=========================================="
echo ""

# 检查adb是否可用
if ! command -v adb &> /dev/null; then
    echo "❌ 错误: 未找到 adb 命令"
    echo "请确保已安装 Android SDK 并配置环境变量"
    exit 1
fi

# 检查设备连接
echo "1. 检查设备连接..."
DEVICES=$(adb devices | grep -v "List" | grep "device$" | wc -l)
if [ "$DEVICES" -eq 0 ]; then
    echo "❌ 错误: 未检测到已连接的设备"
    echo "请确保设备已连接并开启USB调试"
    exit 1
fi
echo "✅ 设备已连接"
echo ""

# 清空日志缓冲区
echo "2. 清空日志缓冲区..."
adb logcat -c
echo "✅ 日志已清空"
echo ""

# 提示用户操作
echo "3. 请在应用中执行以下操作："
echo "   a) 选择达达趣视频源"
echo "   b) 返回主页并下拉刷新"
echo ""
echo "按 Enter 键开始捕获日志..."
read

# 捕获日志
echo "4. 正在捕获日志（10秒）..."
echo ""
timeout 10 adb logcat | grep -E "(达达趣|dadaqu|HomeViewModel)" > dadaqu_debug.log &
LOGCAT_PID=$!

sleep 10

# 停止日志捕获
kill $LOGCAT_PID 2>/dev/null

echo ""
echo "=========================================="
echo "日志分析结果"
echo "=========================================="
echo ""

# 分析日志
if [ ! -s dadaqu_debug.log ]; then
    echo "❌ 未捕获到任何日志"
    echo "可能的原因："
    echo "  1. 应用未运行"
    echo "  2. 未选择达达趣视频源"
    echo "  3. 未刷新主页"
    exit 1
fi

# 检查是否开始解析
if grep -q "达达趣解析开始" dadaqu_debug.log; then
    echo "✅ 检测到解析开始"
    
    # 获取HTML长度
    HTML_LENGTH=$(grep "HTML长度:" dadaqu_debug.log | tail -1 | sed 's/.*HTML长度: //' | sed 's/ .*//')
    echo "   HTML长度: $HTML_LENGTH"
    
    if [ "$HTML_LENGTH" -lt 1000 ]; then
        echo "   ⚠️  警告: HTML内容过短，可能是网络请求失败"
    fi
else
    echo "❌ 未检测到解析开始"
    echo "可能的原因："
    echo "  1. 未选择达达趣视频源"
    echo "  2. 应用使用了其他视频源"
    exit 1
fi

echo ""

# 检查匹配模式
if grep -q "模式1匹配" dadaqu_debug.log; then
    MATCH_COUNT=$(grep "模式1匹配" dadaqu_debug.log | wc -l)
    echo "✅ 模式1匹配成功 ($MATCH_COUNT 个视频)"
elif grep -q "模式2匹配" dadaqu_debug.log; then
    MATCH_COUNT=$(grep "模式2匹配" dadaqu_debug.log | wc -l)
    echo "✅ 模式2匹配成功 ($MATCH_COUNT 个视频)"
elif grep -q "模式3匹配" dadaqu_debug.log; then
    MATCH_COUNT=$(grep "模式3匹配" dadaqu_debug.log | wc -l)
    echo "✅ 模式3匹配成功 ($MATCH_COUNT 个视频)"
else
    echo "❌ 所有模式都未匹配到视频"
fi

echo ""

# 检查解析结果
if grep -q "达达趣解析结束" dadaqu_debug.log; then
    RESULT=$(grep "匹配数:" dadaqu_debug.log | tail -1)
    echo "📊 解析结果: $RESULT"
fi

echo ""

# 检查是否有错误
if grep -q "解析失败！HTML示例" dadaqu_debug.log; then
    echo "❌ 解析失败！"
    echo ""
    echo "HTML结构检查："
    grep "包含" dadaqu_debug.log | tail -6
    echo ""
    echo "详细信息请查看: dadaqu_debug.log"
else
    echo "✅ 解析成功"
fi

echo ""
echo "=========================================="
echo "完整日志已保存到: dadaqu_debug.log"
echo "=========================================="
echo ""
echo "如需查看完整日志，请运行:"
echo "  cat dadaqu_debug.log"
echo ""
echo "如需实时查看日志，请运行:"
echo "  adb logcat | grep -E '(达达趣|dadaqu|HomeViewModel)'"
