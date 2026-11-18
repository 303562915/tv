<?php
/**
 * Nginx Gzip 压缩配置生成器
 * 自动生成并应用Nginx配置
 */

header('Content-Type: text/html; charset=utf-8');

echo "<!DOCTYPE html>
<html>
<head>
    <meta charset='utf-8'>
    <title>Nginx Gzip 配置工具</title>
    <style>
        body { font-family: Arial, sans-serif; padding: 20px; background: #f5f5f5; }
        .container { max-width: 900px; margin: 0 auto; background: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
        h1 { color: #333; border-bottom: 3px solid #409EFF; padding-bottom: 10px; }
        .success { color: #67C23A; padding: 15px; background: #f0f9ff; border-left: 4px solid #67C23A; margin: 15px 0; }
        .error { color: #F56C6C; padding: 15px; background: #fef0f0; border-left: 4px solid #F56C6C; margin: 15px 0; }
        .info { color: #409EFF; padding: 15px; background: #ecf5ff; border-left: 4px solid #409EFF; margin: 15px 0; }
        .warning { color: #E6A23C; padding: 15px; background: #fdf6ec; border-left: 4px solid #E6A23C; margin: 15px 0; }
        pre { background: #f5f5f5; padding: 15px; border-radius: 4px; overflow-x: auto; border: 1px solid #ddd; }
        .step { margin: 25px 0; padding: 20px; border: 1px solid #ddd; border-radius: 4px; background: #fafafa; }
        .step-title { font-weight: bold; font-size: 18px; margin-bottom: 15px; color: #409EFF; }
        button { background: #409EFF; color: white; border: none; padding: 12px 30px; border-radius: 4px; cursor: pointer; font-size: 16px; margin: 10px 5px; }
        button:hover { background: #66b1ff; }
        .code-block { background: #2d2d2d; color: #f8f8f2; padding: 20px; border-radius: 4px; margin: 15px 0; }
        .command { color: #50fa7b; }
    </style>
</head>
<body>
<div class='container'>
<h1>🚀 Nginx Gzip 压缩配置工具</h1>
";

// 检查当前压缩状态
echo "<div class='step'>";
echo "<div class='step-title'>📊 步骤 1: 检查当前状态</div>";

$testUrl = 'https://your-server-domain.com/app/app.js';
$ch = curl_init($testUrl);
curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
curl_setopt($ch, CURLOPT_HEADER, true);
curl_setopt($ch, CURLOPT_NOBODY, true);
curl_setopt($ch, CURLOPT_ENCODING, 'gzip');
$response = curl_exec($ch);
curl_close($ch);

$hasGzip = stripos($response, 'Content-Encoding: gzip') !== false;

if ($hasGzip) {
    echo "<div class='success'>✓ Gzip压缩已启用！</div>";
    echo "<div class='info'>当前配置正常，无需修改。</div>";
} else {
    echo "<div class='warning'>✗ Gzip压缩未启用</div>";
    echo "<div class='info'>需要配置Nginx启用Gzip压缩。</div>";
}

echo "</div>";

// 生成配置文件
echo "<div class='step'>";
echo "<div class='step-title'>📝 步骤 2: Nginx 配置</div>";

$nginxConfig = <<<'NGINX'
# Gzip 压缩配置
# 将以下内容添加到 Nginx 配置文件中

# 在 http 块中添加：
gzip on;
gzip_vary on;
gzip_proxied any;
gzip_comp_level 6;
gzip_types
    text/plain
    text/css
    text/xml
    text/javascript
    application/json
    application/javascript
    application/x-javascript
    application/xml
    application/xml+rss
    image/svg+xml;
gzip_min_length 1024;
gzip_disable "msie6";

# 静态资源缓存
location ~* \.(js|css)$ {
    expires 7d;
    add_header Cache-Control "public, max-age=604800";
    access_log off;
}

location ~* \.(png|jpg|jpeg|gif|ico|svg|webp)$ {
    expires 30d;
    add_header Cache-Control "public, max-age=2592000";
    access_log off;
}
NGINX;

echo "<pre>" . htmlspecialchars($nginxConfig) . "</pre>";

// 生成配置文件
$configFile = __DIR__ . '/nginx_gzip.conf';
file_put_contents($configFile, $nginxConfig);
echo "<div class='success'>✓ 配置文件已生成: nginx_gzip.conf</div>";

echo "</div>";

// 配置步骤
echo "<div class='step'>";
echo "<div class='step-title'>⚙️ 步骤 3: 应用配置</div>";

echo "<div class='info'><strong>方法1: 通过宝塔面板（推荐）</strong></div>";
echo "<ol>
<li>登录宝塔面板</li>
<li>进入「网站」→ 选择你的网站 → 「设置」</li>
<li>点击「配置文件」标签</li>
<li>在 <code>server</code> 块中添加上面的配置</li>
<li>点击「保存」</li>
<li>重启Nginx（面板会自动提示）</li>
</ol>";

echo "<div class='info'><strong>方法2: 通过SSH命令行</strong></div>";
echo "<div class='code-block'>";
echo "<span class='command'># 1. 编辑Nginx配置文件</span><br>";
echo "sudo nano /etc/nginx/nginx.conf<br><br>";
echo "<span class='command'># 2. 在 http 块中添加上面的gzip配置</span><br><br>";
echo "<span class='command'># 3. 测试配置是否正确</span><br>";
echo "sudo nginx -t<br><br>";
echo "<span class='command'># 4. 重启Nginx</span><br>";
echo "sudo systemctl reload nginx<br>";
echo "</div>";

echo "<div class='info'><strong>方法3: 使用PHP自动配置（需要权限）</strong></div>";
echo "<button onclick=\"location.href='?action=auto_config'\">自动配置Nginx</button>";

echo "</div>";

// 自动配置
if (isset($_GET['action']) && $_GET['action'] === 'auto_config') {
    echo "<div class='step'>";
    echo "<div class='step-title'>🔧 自动配置结果</div>";

    // 尝试找到Nginx配置文件
    $possiblePaths = [
        '/etc/nginx/nginx.conf',
        '/usr/local/nginx/conf/nginx.conf',
        '/www/server/nginx/conf/nginx.conf', // 宝塔
    ];

    $nginxConfPath = null;
    foreach ($possiblePaths as $path) {
        if (file_exists($path)) {
            $nginxConfPath = $path;
            break;
        }
    }

    if ($nginxConfPath && is_writable($nginxConfPath)) {
        $content = file_get_contents($nginxConfPath);

        // 检查是否已有gzip配置
        if (stripos($content, 'gzip on') !== false) {
            echo "<div class='warning'>配置文件中已存在gzip配置，请手动检查。</div>";
        } else {
            // 备份原配置
            $backupPath = $nginxConfPath . '.backup.' . date('YmdHis');
            copy($nginxConfPath, $backupPath);
            echo "<div class='success'>✓ 已备份原配置到: $backupPath</div>";

            // 添加gzip配置
            $content = str_replace('http {', "http {\n    # Gzip Configuration\n    gzip on;\n    gzip_vary on;\n    gzip_comp_level 6;\n    gzip_types text/plain text/css text/xml text/javascript application/json application/javascript application/xml;\n", $content);

            file_put_contents($nginxConfPath, $content);
            echo "<div class='success'>✓ 配置已添加</div>";

            // 测试配置
            exec('nginx -t 2>&1', $output, $return);
            if ($return === 0) {
                echo "<div class='success'>✓ 配置测试通过</div>";
                echo "<div class='info'>请手动执行: <code>sudo systemctl reload nginx</code></div>";
            } else {
                echo "<div class='error'>✗ 配置测试失败，已恢复备份</div>";
                copy($backupPath, $nginxConfPath);
            }
        }
    } else {
        echo "<div class='error'>✗ 无法自动配置，请使用手动方法</div>";
        echo "<div class='info'>原因：找不到Nginx配置文件或没有写入权限</div>";
    }

    echo "</div>";
}

// 验证步骤
echo "<div class='step'>";
echo "<div class='step-title'>✅ 步骤 4: 验证配置</div>";

echo "<div class='info'>配置完成后，使用以下方法验证：</div>";

echo "<div class='code-block'>";
echo "<span class='command'># 方法1: 使用curl命令</span><br>";
echo "curl -I -H 'Accept-Encoding: gzip' https://your-server-domain.com/app/app.js | grep -i content-encoding<br><br>";
echo "<span class='command'># 应该看到: Content-Encoding: gzip</span><br>";
echo "</div>";

echo "<button onclick=\"location.href='?action=verify'\">在线验证</button>";
echo "<button onclick=\"location.href='performance_test.html'\">性能测试</button>";

echo "</div>";

// 在线验证
if (isset($_GET['action']) && $_GET['action'] === 'verify') {
    echo "<div class='step'>";
    echo "<div class='step-title'>🔍 验证结果</div>";

    sleep(2); // 等待配置生效

    $ch = curl_init($testUrl);
    curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
    curl_setopt($ch, CURLOPT_HEADER, true);
    curl_setopt($ch, CURLOPT_NOBODY, true);
    curl_setopt($ch, CURLOPT_ENCODING, 'gzip');
    $response = curl_exec($ch);
    $info = curl_getinfo($ch);
    curl_close($ch);

    $hasGzip = stripos($response, 'Content-Encoding: gzip') !== false;

    if ($hasGzip) {
        echo "<div class='success'><strong>✓ Gzip压缩已成功启用！</strong></div>";
        echo "<div class='info'>压缩后大小: " . number_format($info['size_download'] / 1024, 2) . " KB</div>";

        // 计算压缩率
        $ch2 = curl_init($testUrl);
        curl_setopt($ch2, CURLOPT_RETURNTRANSFER, true);
        curl_setopt($ch2, CURLOPT_HEADER, true);
        curl_setopt($ch2, CURLOPT_NOBODY, true);
        $response2 = curl_exec($ch2);
        $info2 = curl_getinfo($ch2);
        curl_close($ch2);

        $originalSize = $info2['size_download'];
        $compressedSize = $info['size_download'];

        if ($originalSize > 0 && $compressedSize > 0) {
            $ratio = (1 - $compressedSize / $originalSize) * 100;
            echo "<div class='success'>原始大小: " . number_format($originalSize / 1024, 2) . " KB</div>";
            echo "<div class='success'>压缩后大小: " . number_format($compressedSize / 1024, 2) . " KB</div>";
            echo "<div class='success'>压缩率: " . number_format($ratio, 1) . "%</div>";
        } else {
            echo "<div class='info'>无法计算压缩率（使用HEAD请求）</div>";
            echo "<div class='info'>但Gzip压缩已确认启用！</div>";
        }
    } else {
        echo "<div class='error'>✗ Gzip压缩仍未启用</div>";
        echo "<div class='warning'>请检查：<br>
        1. Nginx配置是否正确添加<br>
        2. 是否重启了Nginx<br>
        3. 浏览器缓存是否清除</div>";
    }

    echo "<h3>响应头信息：</h3>";
    echo "<pre>" . htmlspecialchars($response) . "</pre>";

    echo "</div>";
}

// 故障排除
echo "<div class='step'>";
echo "<div class='step-title'>🔧 故障排除</div>";

echo "<div class='warning'><strong>如果配置后仍未生效：</strong></div>";
echo "<ol>
<li><strong>检查Nginx配置语法</strong><br>
<code>sudo nginx -t</code></li>

<li><strong>确认Nginx已重启</strong><br>
<code>sudo systemctl status nginx</code></li>

<li><strong>检查Nginx错误日志</strong><br>
<code>sudo tail -f /var/log/nginx/error.log</code></li>

<li><strong>清除浏览器缓存</strong><br>
按 Ctrl+Shift+Delete 清除缓存</li>

<li><strong>检查CDN/代理设置</strong><br>
如果使用了CDN，可能需要在CDN控制台启用压缩</li>
</ol>";

echo "</div>";

echo "<hr>";
echo "<p style='text-align: center; color: #999;'>Nginx Gzip 配置工具 | " . date('Y-m-d H:i:s') . "</p>";
echo "</div></body></html>";
