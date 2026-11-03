<?php
/**
 * 数据库修复脚本
 * 自动检测并修复数据库表结构问题
 */

// 检测运行模式
$isCLI = php_sapi_name() === 'cli';

// CLI模式下设置REQUEST_METHOD避免警告
if ($isCLI) {
    $_SERVER['REQUEST_METHOD'] = 'GET';
}

require_once 'api/config.php';

// 设置为HTML输出，方便查看
if (!$isCLI) {
    header('Content-Type: text/html; charset=utf-8');
}

echo "<!DOCTYPE html>
<html>
<head>
    <meta charset='utf-8'>
    <title>数据库修复工具</title>
    <style>
        body { font-family: Arial, sans-serif; padding: 20px; background: #f5f5f5; }
        .container { max-width: 800px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        h1 { color: #333; border-bottom: 2px solid #4CAF50; padding-bottom: 10px; }
        .success { color: #4CAF50; padding: 10px; background: #e8f5e9; border-left: 4px solid #4CAF50; margin: 10px 0; }
        .error { color: #f44336; padding: 10px; background: #ffebee; border-left: 4px solid #f44336; margin: 10px 0; }
        .info { color: #2196F3; padding: 10px; background: #e3f2fd; border-left: 4px solid #2196F3; margin: 10px 0; }
        .warning { color: #ff9800; padding: 10px; background: #fff3e0; border-left: 4px solid #ff9800; margin: 10px 0; }
        pre { background: #f5f5f5; padding: 10px; border-radius: 4px; overflow-x: auto; }
        .step { margin: 20px 0; padding: 15px; border: 1px solid #ddd; border-radius: 4px; }
        .step-title { font-weight: bold; font-size: 18px; margin-bottom: 10px; color: #555; }
    </style>
</head>
<body>
<div class='container'>
<h1>🔧 数据库修复工具</h1>
<p>自动检测并修复数据库表结构问题</p>
<hr>
";

$db = Database::getInstance()->getConnection();
$hasErrors = false;

// 定义所有表的完整结构
$tableSchemas = [
    'app_config' => [
        'columns' => [
            'id' => 'INT AUTO_INCREMENT PRIMARY KEY',
            'config_key' => 'VARCHAR(100) NOT NULL UNIQUE COMMENT \'配置键\'',
            'config_value' => 'TEXT COMMENT \'配置值\'',
            'config_type' => 'VARCHAR(50) DEFAULT \'text\' COMMENT \'配置类型: text, number, boolean, json\'',
            'description' => 'VARCHAR(255) COMMENT \'配置描述\'',
            'group_name' => 'VARCHAR(50) DEFAULT \'default\' COMMENT \'配置分组\'',
            'sort_order' => 'INT DEFAULT 0 COMMENT \'排序\'',
            'created_at' => 'TIMESTAMP DEFAULT CURRENT_TIMESTAMP',
            'updated_at' => 'TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP'
        ]
    ],
    'app_menu' => [
        'columns' => [
            'id' => 'INT AUTO_INCREMENT PRIMARY KEY',
            'parent_id' => 'INT DEFAULT 0 COMMENT \'父菜单ID\'',
            'name' => 'VARCHAR(100) NOT NULL COMMENT \'菜单名称\'',
            'icon' => 'VARCHAR(100) COMMENT \'图标\'',
            'path' => 'VARCHAR(255) COMMENT \'路径\'',
            'sort' => 'INT DEFAULT 0 COMMENT \'排序\'',
            'permission' => 'VARCHAR(100) COMMENT \'权限标识\'',
            'status' => 'TINYINT DEFAULT 1 COMMENT \'状态: 0=禁用, 1=启用\'',
            'created_at' => 'TIMESTAMP DEFAULT CURRENT_TIMESTAMP',
            'updated_at' => 'TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP'
        ]
    ],
    'app_email_logs' => [
        'columns' => [
            'id' => 'INT AUTO_INCREMENT PRIMARY KEY',
            'user_id' => 'INT DEFAULT NULL COMMENT \'用户ID（可为空）\'',
            'recipient_email' => 'VARCHAR(255) NOT NULL COMMENT \'收件人邮箱\'',
            'email_type' => 'VARCHAR(50) NOT NULL COMMENT \'邮件类型: register, reset_password, notification, vip_expire, invite, system\'',
            'subject' => 'VARCHAR(255) COMMENT \'邮件主题\'',
            'content' => 'TEXT COMMENT \'邮件内容\'',
            'status' => 'TINYINT DEFAULT 0 COMMENT \'发送状态: 0=失败, 1=成功\'',
            'error_message' => 'TEXT COMMENT \'错误信息\'',
            'created_at' => 'TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT \'发送时间\''
        ],
        'indexes' => [
            'idx_user_id' => 'INDEX idx_user_id (user_id)',
            'idx_email_type' => 'INDEX idx_email_type (email_type)',
            'idx_status' => 'INDEX idx_status (status)',
            'idx_created_at' => 'INDEX idx_created_at (created_at)'
        ]
    ]
];

// 开始修复
echo "<div class='step'>";
echo "<div class='step-title'>📋 步骤 1: 检查表结构</div>";

foreach ($tableSchemas as $tableName => $schema) {
    echo "<div class='info'><strong>检查表: {$tableName}</strong></div>";

    // 检查表是否存在
    $stmt = $db->query("SHOW TABLES LIKE '{$tableName}'");
    $tableExists = $stmt->rowCount() > 0;

    if (!$tableExists) {
        echo "<div class='warning'>表 {$tableName} 不存在，将创建...</div>";

        // 创建表
        $columns = [];
        foreach ($schema['columns'] as $colName => $colDef) {
            $columns[] = "`{$colName}` {$colDef}";
        }

        // 添加索引（如果有）
        if (isset($schema['indexes'])) {
            foreach ($schema['indexes'] as $indexDef) {
                $columns[] = $indexDef;
            }
        }

        $createSQL = "CREATE TABLE `{$tableName}` (\n  " . implode(",\n  ", $columns) . "\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

        try {
            $db->exec($createSQL);
            echo "<div class='success'>✓ 表 {$tableName} 创建成功</div>";
            echo "<pre>" . htmlspecialchars($createSQL) . "</pre>";
        } catch (PDOException $e) {
            echo "<div class='error'>✗ 创建表失败: " . htmlspecialchars($e->getMessage()) . "</div>";
            $hasErrors = true;
        }
        continue;
    }

    // 获取现有列
    $stmt = $db->query("SHOW COLUMNS FROM {$tableName}");
    $existingColumns = $stmt->fetchAll(PDO::FETCH_COLUMN);

    echo "<div class='info'>现有字段: " . implode(', ', $existingColumns) . "</div>";

    // 检查缺失的列
    foreach ($schema['columns'] as $colName => $colDef) {
        if (!in_array($colName, $existingColumns)) {
            echo "<div class='warning'>缺少字段: {$colName}</div>";

            // 添加列
            $alterSQL = "ALTER TABLE `{$tableName}` ADD COLUMN `{$colName}` {$colDef}";

            try {
                $db->exec($alterSQL);
                echo "<div class='success'>✓ 字段 {$colName} 添加成功</div>";
                echo "<pre>" . htmlspecialchars($alterSQL) . "</pre>";
            } catch (PDOException $e) {
                echo "<div class='error'>✗ 添加字段失败: " . htmlspecialchars($e->getMessage()) . "</div>";
                $hasErrors = true;
            }
        }
    }
}

echo "</div>";

// 步骤2: 检查并插入默认数据
echo "<div class='step'>";
echo "<div class='step-title'>📝 步骤 2: 检查默认数据</div>";

// 检查 app_config 表的数据
$stmt = $db->query("SELECT COUNT(*) FROM app_config");
$configCount = $stmt->fetchColumn();

if ($configCount == 0) {
    echo "<div class='info'>app_config 表为空，插入默认配置...</div>";

    $defaultConfigs = [
        ['config_key' => 'site_name', 'config_value' => 'TV后台', 'config_type' => 'text', 'description' => '网站名称', 'group_name' => 'basic', 'sort_order' => 1],
        ['config_key' => 'register_points', 'config_value' => '10', 'config_type' => 'number', 'description' => '注册积分', 'group_name' => 'points', 'sort_order' => 1],
        ['config_key' => 'invite_points', 'config_value' => '20', 'config_type' => 'number', 'description' => '邀请积分', 'group_name' => 'points', 'sort_order' => 2],
        ['config_key' => 'signin_points', 'config_value' => '5', 'config_type' => 'number', 'description' => '签到积分', 'group_name' => 'points', 'sort_order' => 3],
        ['config_key' => 'vip_month_price', 'config_value' => '15.00', 'config_type' => 'number', 'description' => 'VIP月费', 'group_name' => 'vip', 'sort_order' => 1],
        ['config_key' => 'vip_year_price', 'config_value' => '150.00', 'config_type' => 'number', 'description' => 'VIP年费', 'group_name' => 'vip', 'sort_order' => 2],
        ['config_key' => 'svip_month_price', 'config_value' => '30.00', 'config_type' => 'number', 'description' => 'SVIP月费', 'group_name' => 'vip', 'sort_order' => 3],
        ['config_key' => 'svip_year_price', 'config_value' => '300.00', 'config_type' => 'number', 'description' => 'SVIP年费', 'group_name' => 'vip', 'sort_order' => 4],
        ['config_key' => 'allow_register', 'config_value' => '1', 'config_type' => 'boolean', 'description' => '允许注册', 'group_name' => 'security', 'sort_order' => 1],
        ['config_key' => 'allow_comment', 'config_value' => '1', 'config_type' => 'boolean', 'description' => '用户允许评论', 'group_name' => 'security', 'sort_order' => 2],
        ['config_key' => 'email_limit_enabled', 'config_value' => '1', 'config_type' => 'boolean', 'description' => '是否启用邮件发送次数限制', 'group_name' => 'email', 'sort_order' => 1],
        ['config_key' => 'email_daily_limit', 'config_value' => '10', 'config_type' => 'number', 'description' => '每日邮件发送次数限制', 'group_name' => 'email', 'sort_order' => 2],
    ];

    $stmt = $db->prepare("INSERT INTO app_config (config_key, config_value, config_type, description, group_name, sort_order) VALUES (?, ?, ?, ?, ?, ?)");

    foreach ($defaultConfigs as $config) {
        try {
            $stmt->execute([
                $config['config_key'],
                $config['config_value'],
                $config['config_type'],
                $config['description'],
                $config['group_name'],
                $config['sort_order']
            ]);
            echo "<div class='success'>✓ 插入配置: {$config['config_key']}</div>";
        } catch (PDOException $e) {
            echo "<div class='error'>✗ 插入配置失败: " . htmlspecialchars($e->getMessage()) . "</div>";
        }
    }
} else {
    echo "<div class='success'>✓ app_config 表已有 {$configCount} 条数据</div>";
}

// 检查 app_menu 表的数据
$stmt = $db->query("SELECT COUNT(*) FROM app_menu");
$menuCount = $stmt->fetchColumn();

if ($menuCount == 0) {
    echo "<div class='info'>app_menu 表为空，插入默认菜单...</div>";

    $defaultMenus = [
        ['parent_id' => 0, 'name' => '仪表盘', 'icon' => 'el-icon-s-home', 'path' => 'dashboard', 'sort' => 1, 'permission' => '', 'status' => 1],
        ['parent_id' => 0, 'name' => '用户管理', 'icon' => 'el-icon-user', 'path' => 'users', 'sort' => 2, 'permission' => 'admin', 'status' => 1],
        ['parent_id' => 0, 'name' => '公告管理', 'icon' => 'el-icon-bell', 'path' => 'announcements', 'sort' => 3, 'permission' => '', 'status' => 1],
        ['parent_id' => 0, 'name' => '轮播管理', 'icon' => 'el-icon-picture', 'path' => 'banners', 'sort' => 4, 'permission' => '', 'status' => 1],
        ['parent_id' => 0, 'name' => '视频源管理', 'icon' => 'el-icon-video-camera', 'path' => 'video_sources', 'sort' => 5, 'permission' => '', 'status' => 1],
        ['parent_id' => 0, 'name' => '配置管理', 'icon' => 'el-icon-setting', 'path' => 'config', 'sort' => 6, 'permission' => 'admin', 'status' => 1],
        ['parent_id' => 0, 'name' => '菜单管理', 'icon' => 'el-icon-menu', 'path' => 'menu', 'sort' => 7, 'permission' => 'admin', 'status' => 1],
    ];

    $stmt = $db->prepare("INSERT INTO app_menu (parent_id, name, icon, path, sort, permission, status) VALUES (?, ?, ?, ?, ?, ?, ?)");

    foreach ($defaultMenus as $menu) {
        try {
            $stmt->execute([
                $menu['parent_id'],
                $menu['name'],
                $menu['icon'],
                $menu['path'],
                $menu['sort'],
                $menu['permission'],
                $menu['status']
            ]);
            echo "<div class='success'>✓ 插入菜单: {$menu['name']}</div>";
        } catch (PDOException $e) {
            echo "<div class='error'>✗ 插入菜单失败: " . htmlspecialchars($e->getMessage()) . "</div>";
        }
    }
} else {
    echo "<div class='success'>✓ app_menu 表已有 {$menuCount} 条数据</div>";
}

echo "</div>";

// 总结
echo "<div class='step'>";
echo "<div class='step-title'>📊 修复总结</div>";

if ($hasErrors) {
    echo "<div class='error'><strong>⚠️ 修复过程中出现错误，请检查上面的错误信息</strong></div>";
} else {
    echo "<div class='success'><strong>✓ 数据库修复完成！所有表结构正常</strong></div>";
}

echo "<div class='info'>";
echo "<strong>下一步操作：</strong><br>";
echo "1. 返回管理后台，刷新页面<br>";
echo "2. 如果问题仍然存在，请检查 PHP 错误日志<br>";
echo "3. 确保数据库用户有足够的权限<br>";
echo "</div>";

echo "</div>";

echo "<hr>";
echo "<p style='text-align: center; color: #999;'>数据库修复工具 v1.0 | " . date('Y-m-d H:i:s') . "</p>";
echo "</div></body></html>";
