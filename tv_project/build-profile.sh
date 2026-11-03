#!/bin/bash

# 构建性能分析脚本

echo "=========================================="
echo "  构建性能分析"
echo "=========================================="

# 清理之前的构建
echo "🧹 清理构建..."
./gradlew clean

# 执行带性能分析的构建
echo "🔍 开始性能分析构建..."
./gradlew assembleDebug --profile --scan --build-cache

# 检查构建是否成功
if [ $? -eq 0 ]; then
    echo "✅ 构建成功！"
    
    # 查找性能报告
    PROFILE_REPORT=$(find build/reports/profile -name "*.html" | head -n 1)
    
    if [ -n "$PROFILE_REPORT" ]; then
        echo "📊 性能报告: $PROFILE_REPORT"
        
        # 尝试在浏览器中打开报告
        if command -v xdg-open &> /dev/null; then
            xdg-open "$PROFILE_REPORT"
        elif command -v open &> /dev/null; then
            open "$PROFILE_REPORT"
        else
            echo "请手动打开报告文件查看"
        fi
    fi
    
    echo ""
    echo "📈 构建扫描报告将在浏览器中打开（如果启用了 --scan）"
    echo ""
    echo "💡 优化建议："
    echo "  1. 查看哪些任务耗时最长"
    echo "  2. 检查是否有不必要的依赖"
    echo "  3. 确认并行构建是否生效"
    echo "  4. 查看缓存命中率"
else
    echo "❌ 构建失败"
    exit 1
fi

echo "=========================================="
echo "  完成"
echo "=========================================="
