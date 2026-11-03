<?php
/**
 * 自动化数据库更新脚本
 * 访问此文件后自动执行数据库更新，完成后自动删除自己
 */

// 开启错误显示
error_reporting(E_ALL);
ini_set('display_errors', 1);

header('Content-Type: text/html; charset=utf-8');

// 定义常量以允许加载配置文件
define('IN_CRONLITE', true);

// 引入数据库配置
require_once __DIR__ . '/../confing/config.php';

// 创建数据库连接
$DB = new DB($host, $user, $pwd, $dbname, $port);

echo '<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>数据库更新</title>
    <style>
        body {
            font-family: Arial, sans-serif;
            max-width: 800px;
            margin: 50px auto;
            padding: 20px;
            background: #f5f5f5;
        }
        .container {
            background: white;
            padding: 30px;
            border-radius: 10px;
            box-shadow: 0 2px 10px rgba(0,0,0,0.1);
        }
        h1 { color: #333; }
        .success { color: #28a745; padding: 10px; background: #d4edda; border-radius: 5px; margin: 10px 0; }
        .error { color: #dc3545; padding: 10px; background: #f8d7da; border-radius: 5px; margin: 10px 0; }
        .info { color: #0c5460; padding: 10px; background: #d1ecf1; border-radius: 5px; margin: 10px 0; }
        .step { margin: 15px 0; padding: 10px; border-left: 4px solid #007bff; background: #f8f9fa; }
    </style>
</head>
<body>
    <div class="container">
        <h1>🚀 App 模块数据库更新</h1>';

$errors = [];
$success = [];

try {
    // 步骤1: 检查表是否已存在
    echo '<div class="step"><strong>步骤 1:</strong> 检查数据库表...</div>';
    
    $checkTable = $DB->query("SHOW TABLES LIKE 'app_config'");
    $tableExists = $DB->fetch($checkTable);
    
    if ($tableExists) {
        echo '<div class="info">✓ 表 app_config 已存在，跳过创建</div>';
        $success[] = '✓ 表已存在';
    } else {
        // 步骤2: 创建表
        echo '<div class="step"><strong>步骤 2:</strong> 创建 app_config 表...</div>';
        
        $createTableSQL = "CREATE TABLE IF NOT EXISTS `app_config` (
          `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '配置ID',
          `cookie` text COMMENT 'Cookie信息',
          `updated_at` datetime DEFAULT NULL COMMENT '更新时间',
          `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
          PRIMARY KEY (`id`)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用配置表'";
        
        if ($DB->query($createTableSQL)) {
            $success[] = '✓ 表 app_config 创建成功';
            echo '<div class="success">' . end($success) . '</div>';
            
            // 步骤3: 插入默认数据
            echo '<div class="step"><strong>步骤 3:</strong> 插入默认配置...</div>';
            
            $insertSQL = "INSERT INTO `app_config` (`id`, `cookie`, `updated_at`) VALUES (1, '', NULL)";
            
            if ($DB->query($insertSQL)) {
                $success[] = '✓ 默认配置插入成功';
                echo '<div class="success">' . end($success) . '</div>';
            } else {
                $errors[] = '✗ 插入默认配置失败: ' . $DB->error();
                echo '<div class="error">' . end($errors) . '</div>';
            }
        } else {
            $errors[] = '✗ 创建表失败: ' . $DB->error();
            echo '<div class="error">' . end($errors) . '</div>';
        }
    }
    
    // 步骤4: 验证安装
    echo '<div class="step"><strong>步骤 4:</strong> 验证安装...</div>';
    
    $verifySQL = "SELECT COUNT(*) as count FROM app_config";
    $count = $DB->count($verifySQL);
    
    if ($count !== false) {
        $success[] = '✓ 数据库验证成功，当前记录数: ' . $count;
        echo '<div class="success">' . end($success) . '</div>';
    } else {
        $errors[] = '✗ 验证失败: ' . $DB->error();
        echo '<div class="error">' . end($errors) . '</div>';
    }
    
} catch (Exception $e) {
    $errors[] = '✗ 系统错误: ' . $e->getMessage();
    echo '<div class="error">' . end($errors) . '</div>';
}

// 显示总结
echo '<hr style="margin: 30px 0;">';
echo '<h2>📊 更新总结</h2>';

if (count($errors) === 0) {
    echo '<div class="success">';
    echo '<h3>✅ 更新完成！</h3>';
    echo '<p>所有操作执行成功</p>';
    echo '<ul>';
    foreach ($success as $msg) {
        echo '<li>' . $msg . '</li>';
    }
    echo '</ul>';
    echo '</div>';
    
    echo '<div class="info">';
    echo '<p><strong>下一步:</strong></p>';
    echo '<ul>';
    echo '<li>访问管理页面: <a href="index.html" target="_blank">app/index.html</a></li>';
    echo '<li>本脚本将在 5 秒后自动删除...</li>';
    echo '</ul>';
    echo '</div>';
    
    // 步骤5: 自动删除脚本
    echo '<div class="step"><strong>步骤 5:</strong> 清理安装文件...</div>';
    
    // 延迟删除
    echo '<script>
        setTimeout(function() {
            fetch("' . basename(__FILE__) . '?action=delete")
                .then(response => response.json())
                .then(data => {
                    if (data.success) {
                        document.body.innerHTML += "<div class=\'success\'><h3>✅ " + data.message + "</h3><p>安装完成！可以关闭此页面</p></div>";
                    } else {
                        document.body.innerHTML += "<div class=\'error\'><h3>⚠️ " + data.message + "</h3><p>请手动删除文件: app/install_update.php</p></div>";
                    }
                });
        }, 5000);
    </script>';
    
} else {
    echo '<div class="error">';
    echo '<h3>❌ 更新失败</h3>';
    echo '<p>发现以下错误:</p>';
    echo '<ul>';
    foreach ($errors as $error) {
        echo '<li>' . $error . '</li>';
    }
    echo '</ul>';
    echo '</div>';
    
    if (count($success) > 0) {
        echo '<div class="info">';
        echo '<p>已完成的操作:</p>';
        echo '<ul>';
        foreach ($success as $msg) {
            echo '<li>' . $msg . '</li>';
        }
        echo '</ul>';
        echo '</div>';
    }
}

echo '</div></body></html>';

// 处理删除请求
if (isset($_GET['action']) && $_GET['action'] === 'delete') {
    header('Content-Type: application/json');
    
    $scriptPath = __FILE__;
    
    if (file_exists($scriptPath)) {
        if (unlink($scriptPath)) {
            echo json_encode([
                'success' => true,
                'message' => '安装脚本已自动删除'
            ]);
        } else {
            echo json_encode([
                'success' => false,
                'message' => '无法删除安装脚本，请手动删除'
            ]);
        }
    } else {
        echo json_encode([
            'success' => true,
            'message' => '安装脚本已不存在'
        ]);
    }
    exit;
}
?>
