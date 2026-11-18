<?php
/**
 * 添加邮件发送次数限制配置
 */

require_once 'api/config.php';

$db = Database::getInstance()->getConnection();

echo "<!DOCTYPE html>
<html>
<head>
    <meta charset='utf-8'>
    <title>添加邮件次数限制配置</title>
    <style>
        body { font-family: Arial, sans-serif; padding: 20px; background: #f5f5f5; }
        .container { max-width: 800px; margin: 0 auto; background: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
        h1 { color: #333; border-bottom: 3px solid #409EFF; padding-bottom: 10px; }
        .success { color: #67C23A; padding: 15px; background: #f0f9ff; border-left: 4px solid #67C23A; margin: 15px 0; }
        .error { color: #F56C6C; padding: 15px; background: #fef0f0; border-left: 4px solid #F56C6C; margin: 15px 0; }
        .info { color: #409EFF; padding: 15px; background: #ecf5ff; border-left: 4px solid #409EFF; margin: 15px 0; }
        pre { background: #f5f5f5; padding: 15px; border-radius: 4px; overflow-x: auto; }
    </style>
</head>
<body>
<div class='container'>
<h1>📧 添加邮件次数限制配置</h1>
";

try {
    // 检查配置是否已存在
    $stmt = $db->prepare("SELECT * FROM app_config WHERE config_key IN ('email_daily_limit', 'email_limit_enabled')");
    $stmt->execute();
    $existing = $stmt->fetchAll(PDO::FETCH_COLUMN, 0);

    $configs = [
        [
            'config_key' => 'email_daily_limit',
            'config_value' => '10',
            'config_type' => 'number',
            'description' => '每日邮件发送次数限制',
            'group_name' => 'email',
            'sort_order' => 1
        ],
        [
            'config_key' => 'email_limit_enabled',
            'config_value' => '1',
            'config_type' => 'boolean',
            'description' => '是否启用邮件发送次数限制（0=关闭，1=开启）',
            'group_name' => 'email',
            'sort_order' => 2
        ]
    ];

    $added = 0;
    $skipped = 0;

    foreach ($configs as $config) {
        // 检查是否已存在
        $stmt = $db->prepare("SELECT id FROM app_config WHERE config_key = ?");
        $stmt->execute([$config['config_key']]);

        if ($stmt->fetch()) {
            echo "<div class='info'>配置项 <strong>{$config['config_key']}</strong> 已存在，跳过</div>";
            $skipped++;
        } else {
            // 插入配置
            $stmt = $db->prepare("
                INSERT INTO app_config (config_key, config_value, config_type, description, group_name, sort_order)
                VALUES (?, ?, ?, ?, ?, ?)
            ");
            $stmt->execute([
                $config['config_key'],
                $config['config_value'],
                $config['config_type'],
                $config['description'],
                $config['group_name'],
                $config['sort_order']
            ]);

            echo "<div class='success'>✓ 成功添加配置项: <strong>{$config['config_key']}</strong></div>";
            echo "<pre>键: {$config['config_key']}\n值: {$config['config_value']}\n描述: {$config['description']}</pre>";
            $added++;
        }
    }

    echo "<hr>";
    echo "<div class='success'><strong>完成！</strong><br>添加: {$added} 个<br>跳过: {$skipped} 个</div>";

    // 显示当前配置
    echo "<h2>📋 当前邮件配置</h2>";
    $stmt = $db->query("SELECT * FROM app_config WHERE group_name = 'email' ORDER BY sort_order");
    $emailConfigs = $stmt->fetchAll();

    if ($emailConfigs) {
        echo "<table style='width:100%; border-collapse: collapse;'>";
        echo "<tr style='background:#409EFF; color:white;'>";
        echo "<th style='padding:10px; text-align:left;'>配置键</th>";
        echo "<th style='padding:10px; text-align:left;'>当前值</th>";
        echo "<th style='padding:10px; text-align:left;'>描述</th>";
        echo "</tr>";

        foreach ($emailConfigs as $cfg) {
            echo "<tr style='border-bottom:1px solid #ddd;'>";
            echo "<td style='padding:10px;'><strong>{$cfg['config_key']}</strong></td>";
            echo "<td style='padding:10px;'>{$cfg['config_value']}</td>";
            echo "<td style='padding:10px;'>{$cfg['description']}</td>";
            echo "</tr>";
        }

        echo "</table>";
    }

    echo "<hr>";
    echo "<div class='info'>";
    echo "<h3>📝 使用说明</h3>";
    echo "<ol>";
    echo "<li>登录后台管理系统</li>";
    echo "<li>进入「系统设置」或「配置管理」</li>";
    echo "<li>找到「邮件」分组</li>";
    echo "<li>修改以下配置：</li>";
    echo "<ul>";
    echo "<li><strong>email_limit_enabled</strong>: 设置为 0 关闭限制，1 开启限制</li>";
    echo "<li><strong>email_daily_limit</strong>: 设置每日发送次数（如 10、20、50）</li>";
    echo "</ul>";
    echo "</ol>";
    echo "</div>";

    echo "<div class='info'>";
    echo "<h3>🔧 快速操作</h3>";
    echo "<p><strong>关闭次数限制：</strong></p>";
    echo "<pre>UPDATE app_config SET config_value = '0' WHERE config_key = 'email_limit_enabled';</pre>";
    echo "<p><strong>修改每日限制为50次：</strong></p>";
    echo "<pre>UPDATE app_config SET config_value = '50' WHERE config_key = 'email_daily_limit';</pre>";
    echo "</div>";

} catch (PDOException $e) {
    echo "<div class='error'>✗ 错误: " . htmlspecialchars($e->getMessage()) . "</div>";
}

echo "</div></body></html>";
