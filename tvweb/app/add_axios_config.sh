#!/bin/bash

# 为所有会员管理页面添加axios配置

files=(
    "points_logs.php"
    "balance_logs.php"
    "login_logs.php"
    "invite_logs.php"
    "system_settings.php"
)

for file in "${files[@]}"; do
    if [ -f "$file" ]; then
        echo "处理 $file ..."

        # 在 <script> 标签后添加axios配置
        sed -i '/<script src="assets\/js\/axios.min.js"><\/script>/a\    <script>\n        // 配置axios\n        axios.defaults.baseURL = "./";\n        axios.interceptors.response.use(\n            response => response.data,\n            error => {\n                console.error("请求错误:", error);\n                return Promise.reject(error);\n            }\n        );\n    </script>' "$file"

        echo "$file 处理完成"
    fi
done

echo "所有文件处理完成！"
