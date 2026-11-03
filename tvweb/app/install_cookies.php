<?php
/**
 * Cookie 配置表安装脚本
 */

// 数据库配置
$config = [
    'host' => 'localhost',
    'dbname' => 'yughplvt',
    'username' => 'yughplvt',
    'password' => 'REDACTED',
    'charset' => 'utf8mb4'
];

header('Content-Type: text/html; charset=utf-8');

echo '<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <title>Cookie 配置表安装</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: "Microsoft YaHei", Arial, sans-serif; background: #f5f5f5; padding: 20px; }
        .container { max-width: 800px; margin: 0 auto; background: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
        h1 { color: #333; margin-bottom: 20px; text-align: center; }
        .log { background: #f8f8f8; padding: 15px; border-radius: 4px; margin: 10px 0; font-family: monospace; font-size: 14px; line-height: 1.6; max-height: 500px; overflow-y: auto; }
        .success { color: #67C23A; }
        .error { color: #F56C6C; }
        .info { color: #409EFF; }
        .btn { display: inline-block; padding: 10px 20px; background: #409EFF; color: white; text-decoration: none; border-radius: 4px; margin-top: 20px; }
    </style>
</head>
<body>
    <div class="container">
        <h1>Cookie 配置表安装</h1>
        <div class="log">';

try {
    $dsn = "mysql:host={$config['host']};dbname={$config['dbname']};charset={$config['charset']}";
    $pdo = new PDO($dsn, $config['username'], $config['password']);
    $pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);

    echo '<div class="success">✓ 数据库连接成功</div>';

    // 创建 Cookie 配置表
    echo '<div class="info">正在创建 app_cookies 表...</div>';
    $pdo->exec("
        CREATE TABLE IF NOT EXISTS app_cookies (
            id INT AUTO_INCREMENT PRIMARY KEY,
            cookie TEXT NOT NULL COMMENT 'Cookie内容',
            description VARCHAR(255) COMMENT '描述',
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Cookie配置表'
    ");
    echo '<div class="success">✓ app_cookies 表创建成功</div>';

    // 检查是否已有数据
    $stmt = $pdo->query("SELECT COUNT(*) FROM app_cookies");
    $count = $stmt->fetchColumn();

    if ($count == 0) {
        echo '<div class="info">正在插入默认 Cookie 配置...</div>';
        $pdo->exec("INSERT INTO app_cookies (cookie, description) VALUES ('', '默认 Cookie 配置')");
        echo '<div class="success">✓ 默认配置插入成功</div>';
    } else {
        echo '<div class="info">Cookie 配置已存在，跳过插入</div>';
    }

    echo '<div class="success" style="margin-top: 20px; font-size: 16px; font-weight: bold;">
        ✓ Cookie 配置表安装完成！
    </div>';

    echo '<div class="info" style="margin-top: 15px;">
        <strong>提示：</strong><br>
        1. 现在可以访问 cookie.html 管理 Cookie<br>
        2. Cookie 配置会自动保存到数据库
    </div>';

    echo '</div>
        <div style="text-align: center;">
            <a href="cookie.html" class="btn">进入 Cookie 管理</a>
            <a href="index.html" class="btn" style="background: #67C23A; margin-left: 10px;">返回管理后台</a>
        </div>
    </div>
</body>
</html>';

} catch (PDOException $e) {
    echo '<div class="error">✗ 错误: ' . htmlspecialchars($e->getMessage()) . '</div>';
    echo '</div></div></body></html>';
}
