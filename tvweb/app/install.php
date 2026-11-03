<?php
/**
 * 数据库在线安装脚本
 * 访问此文件自动创建数据库表和初始数据
 */

// 数据库配置
$config = [
    'host' => 'localhost',
    'dbname' => 'yughplvt',
    'username' => 'yughplvt',
    'password' => 'REDACTED',
    'charset' => 'utf8mb4'
];

// 设置响应头
header('Content-Type: text/html; charset=utf-8');

echo '<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>数据库安装</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: "Microsoft YaHei", Arial, sans-serif; background: #f5f5f5; padding: 20px; }
        .container { max-width: 800px; margin: 0 auto; background: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
        h1 { color: #333; margin-bottom: 20px; text-align: center; }
        .log { background: #f8f8f8; padding: 15px; border-radius: 4px; margin: 10px 0; font-family: monospace; font-size: 14px; line-height: 1.6; max-height: 500px; overflow-y: auto; }
        .success { color: #67C23A; }
        .error { color: #F56C6C; }
        .info { color: #409EFF; }
        .warning { color: #E6A23C; }
        .btn { display: inline-block; padding: 10px 20px; background: #409EFF; color: white; text-decoration: none; border-radius: 4px; margin-top: 20px; }
        .btn:hover { background: #66b1ff; }
    </style>
</head>
<body>
    <div class="container">
        <h1>TV管理后台 - 数据库安装</h1>
        <div class="log">';

try {
    // 连接数据库
    echo '<div class="info">正在连接数据库...</div>';
    $dsn = "mysql:host={$config['host']};charset={$config['charset']}";
    $pdo = new PDO($dsn, $config['username'], $config['password']);
    $pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    echo '<div class="success">✓ 数据库连接成功</div>';

    // 使用现有数据库
    echo '<div class="info">正在使用数据库 ' . $config['dbname'] . '...</div>';
    $pdo->exec("USE {$config['dbname']}");
    echo '<div class="success">✓ 数据库切换成功</div>';

    // 创建用户表
    echo '<div class="info">正在创建 app_users 表...</div>';
    $pdo->exec("
        CREATE TABLE IF NOT EXISTS app_users (
            id INT AUTO_INCREMENT PRIMARY KEY,
            username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
            password VARCHAR(255) NOT NULL COMMENT '密码(MD5加密)',
            email VARCHAR(100) COMMENT '邮箱',
            phone VARCHAR(20) COMMENT '手机号',
            role ENUM('admin', 'user') DEFAULT 'user' COMMENT '角色',
            status TINYINT DEFAULT 1 COMMENT '状态 1启用 0禁用',
            last_login_time DATETIME COMMENT '最后登录时间',
            last_login_ip VARCHAR(50) COMMENT '最后登录IP',
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
            INDEX idx_username (username),
            INDEX idx_status (status)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表'
    ");
    echo '<div class="success">✓ app_users 表创建成功</div>';

    // 创建配置表
    echo '<div class="info">正在创建 app_config 表...</div>';
    $pdo->exec("
        CREATE TABLE IF NOT EXISTS app_config (
            id INT AUTO_INCREMENT PRIMARY KEY,
            config_key VARCHAR(100) NOT NULL UNIQUE COMMENT '配置键',
            config_value TEXT COMMENT '配置值',
            config_type ENUM('text', 'number', 'json', 'boolean') DEFAULT 'text' COMMENT '配置类型',
            description VARCHAR(255) COMMENT '配置描述',
            group_name VARCHAR(50) DEFAULT 'default' COMMENT '配置分组',
            sort_order INT DEFAULT 0 COMMENT '排序',
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
            INDEX idx_group (group_name),
            INDEX idx_key (config_key)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配置表'
    ");
    echo '<div class="success">✓ app_config 表创建成功</div>';

    // 创建公告表
    echo '<div class="info">正在创建 app_announcements 表...</div>';
    $pdo->exec("
        CREATE TABLE IF NOT EXISTS app_announcements (
            id INT AUTO_INCREMENT PRIMARY KEY,
            title VARCHAR(200) NOT NULL COMMENT '公告标题',
            content TEXT NOT NULL COMMENT '公告内容',
            type ENUM('info', 'warning', 'success', 'error') DEFAULT 'info' COMMENT '公告类型',
            priority INT DEFAULT 0 COMMENT '优先级 数字越大越靠前',
            status TINYINT DEFAULT 1 COMMENT '状态 1显示 0隐藏',
            start_time DATETIME COMMENT '开始时间',
            end_time DATETIME COMMENT '结束时间',
            view_count INT DEFAULT 0 COMMENT '查看次数',
            created_by INT COMMENT '创建人ID',
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
            INDEX idx_status (status),
            INDEX idx_priority (priority),
            INDEX idx_time (start_time, end_time)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公告表'
    ");
    echo '<div class="success">✓ app_announcements 表创建成功</div>';

    // 创建轮播图表
    echo '<div class="info">正在创建 app_banners 表...</div>';
    $pdo->exec("
        CREATE TABLE IF NOT EXISTS app_banners (
            id INT AUTO_INCREMENT PRIMARY KEY,
            title VARCHAR(200) NOT NULL COMMENT '轮播图标题',
            image_url VARCHAR(500) NOT NULL COMMENT '图片URL',
            link_url VARCHAR(500) COMMENT '跳转链接',
            link_type ENUM('none', 'internal', 'external', 'video') DEFAULT 'none' COMMENT '链接类型',
            position VARCHAR(50) DEFAULT 'home' COMMENT '显示位置 home首页 detail详情页',
            sort_order INT DEFAULT 0 COMMENT '排序 数字越小越靠前',
            status TINYINT DEFAULT 1 COMMENT '状态 1显示 0隐藏',
            start_time DATETIME COMMENT '开始时间',
            end_time DATETIME COMMENT '结束时间',
            click_count INT DEFAULT 0 COMMENT '点击次数',
            created_by INT COMMENT '创建人ID',
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
            INDEX idx_position (position),
            INDEX idx_status (status),
            INDEX idx_sort (sort_order)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='轮播图表'
    ");
    echo '<div class="success">✓ app_banners 表创建成功</div>';

    // 创建操作日志表
    echo '<div class="info">正在创建 app_logs 表...</div>';
    $pdo->exec("
        CREATE TABLE IF NOT EXISTS app_logs (
            id INT AUTO_INCREMENT PRIMARY KEY,
            user_id INT COMMENT '操作用户ID',
            action VARCHAR(100) NOT NULL COMMENT '操作动作',
            module VARCHAR(50) NOT NULL COMMENT '操作模块',
            content TEXT COMMENT '操作内容',
            ip VARCHAR(50) COMMENT '操作IP',
            user_agent VARCHAR(500) COMMENT '用户代理',
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            INDEX idx_user (user_id),
            INDEX idx_module (module),
            INDEX idx_time (created_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志表'
    ");
    echo '<div class="success">✓ app_logs 表创建成功</div>';

    // 检查是否已有管理员账号
    $stmt = $pdo->query("SELECT COUNT(*) FROM app_users WHERE role = 'admin'");
    $adminCount = $stmt->fetchColumn();

    if ($adminCount == 0) {
        // 插入默认管理员账号
        echo '<div class="info">正在创建默认管理员账号...</div>';
        $pdo->exec("
            INSERT INTO app_users (username, password, email, role, status)
            VALUES ('admin', MD5('admin123'), 'admin@example.com', 'admin', 1)
        ");
        echo '<div class="success">✓ 默认管理员账号创建成功 (用户名: admin, 密码: admin123)</div>';
    } else {
        echo '<div class="warning">⚠ 管理员账号已存在，跳过创建</div>';
    }

    // 检查配置是否已存在
    $stmt = $pdo->query("SELECT COUNT(*) FROM app_config");
    $configCount = $stmt->fetchColumn();

    if ($configCount == 0) {
        // 插入默认配置
        echo '<div class="info">正在插入默认配置...</div>';
        $pdo->exec("
            INSERT INTO app_config (config_key, config_value, config_type, description, group_name) VALUES
            ('app_name', 'TV管理后台', 'text', '应用名称', 'basic'),
            ('app_version', '1.0.0', 'text', '应用版本', 'basic'),
            ('api_base_url', 'http://sk.nasxiaolin.top/app/', 'text', 'API基础地址', 'api'),
            ('page_size', '20', 'number', '每页显示数量', 'basic'),
            ('upload_max_size', '10', 'number', '上传文件最大大小(MB)', 'upload'),
            ('announcement_enabled', '1', 'boolean', '是否启用公告', 'feature'),
            ('banner_enabled', '1', 'boolean', '是否启用轮播', 'feature')
        ");
        echo '<div class="success">✓ 默认配置插入成功</div>';
    } else {
        echo '<div class="warning">⚠ 配置已存在，跳过插入</div>';
    }

    // 检查公告是否已存在
    $stmt = $pdo->query("SELECT COUNT(*) FROM app_announcements");
    $announcementCount = $stmt->fetchColumn();

    if ($announcementCount == 0) {
        // 插入示例公告
        echo '<div class="info">正在插入示例公告...</div>';
        $pdo->exec("
            INSERT INTO app_announcements (title, content, type, priority, status) VALUES
            ('欢迎使用TV管理后台', '这是一个示例公告，您可以在公告管理中编辑或删除。', 'info', 1, 1),
            ('系统维护通知', '系统将于本周六凌晨2:00-4:00进行维护升级。', 'warning', 2, 1)
        ");
        echo '<div class="success">✓ 示例公告插入成功</div>';
    } else {
        echo '<div class="warning">⚠ 公告已存在，跳过插入</div>';
    }

    // 检查轮播是否已存在
    $stmt = $pdo->query("SELECT COUNT(*) FROM app_banners");
    $bannerCount = $stmt->fetchColumn();

    if ($bannerCount == 0) {
        // 插入示例轮播图
        echo '<div class="info">正在插入示例轮播图...</div>';
        $pdo->exec("
            INSERT INTO app_banners (title, image_url, link_url, link_type, position, sort_order, status) VALUES
            ('首页轮播1', 'https://via.placeholder.com/800x400?text=Banner1', '', 'none', 'home', 1, 1),
            ('首页轮播2', 'https://via.placeholder.com/800x400?text=Banner2', '', 'none', 'home', 2, 1)
        ");
        echo '<div class="success">✓ 示例轮播图插入成功</div>';
    } else {
        echo '<div class="warning">⚠ 轮播图已存在，跳过插入</div>';
    }

    echo '<div class="success" style="margin-top: 20px; font-size: 16px; font-weight: bold;">
        ✓ 数据库安装完成！
    </div>';

    echo '<div class="info" style="margin-top: 15px;">
        <strong>安装信息：</strong><br>
        数据库名: ' . $config['dbname'] . '<br>
        管理员账号: admin<br>
        管理员密码: admin123<br>
        <br>
        <strong>提示：</strong><br>
        1. 请立即修改默认管理员密码<br>
        2. 如需修改数据库配置，请编辑 api/config.php 文件<br>
        3. 安装完成后建议删除此文件
    </div>';

    echo '</div>
        <div style="text-align: center;">
            <a href="index.html" class="btn">进入管理后台</a>
        </div>
    </div>
</body>
</html>';

} catch (PDOException $e) {
    echo '<div class="error">✗ 错误: ' . htmlspecialchars($e->getMessage()) . '</div>';
    echo '</div></div></body></html>';
    exit;
}
