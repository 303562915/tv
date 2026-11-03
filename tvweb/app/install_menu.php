<?php
/**
 * 添加菜单表和初始数据
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

try {
    $dsn = "mysql:host={$config['host']};dbname={$config['dbname']};charset={$config['charset']}";
    $pdo = new PDO($dsn, $config['username'], $config['password']);
    $pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);

    echo '<h2>开始安装菜单表...</h2>';

    // 创建菜单表
    $pdo->exec("
        CREATE TABLE IF NOT EXISTS app_menu (
            id INT AUTO_INCREMENT PRIMARY KEY,
            parent_id INT DEFAULT 0 COMMENT '父级ID 0为顶级',
            name VARCHAR(50) NOT NULL COMMENT '菜单名称',
            icon VARCHAR(50) COMMENT '图标class',
            path VARCHAR(100) COMMENT '路径',
            sort INT DEFAULT 0 COMMENT '排序 数字越小越靠前',
            permission VARCHAR(50) DEFAULT '' COMMENT '权限标识 admin为管理员',
            status TINYINT DEFAULT 1 COMMENT '状态 1启用 0禁用',
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
            INDEX idx_parent (parent_id),
            INDEX idx_sort (sort),
            INDEX idx_status (status)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单表'
    ");
    echo '<p style="color: green;">✓ app_menu 表创建成功</p>';

    // 检查是否已有菜单数据
    $stmt = $pdo->query("SELECT COUNT(*) FROM app_menu");
    $count = $stmt->fetchColumn();

    if ($count == 0) {
        // 插入初始菜单数据
        $pdo->exec("
            INSERT INTO app_menu (parent_id, name, icon, path, sort, permission, status) VALUES
            (0, '仪表盘', 'el-icon-s-home', 'dashboard', 1, '', 1),
            (0, '用户管理', 'el-icon-user', 'users', 2, 'admin', 1),
            (0, '公告管理', 'el-icon-bell', 'announcements', 3, 'admin', 1),
            (0, '轮播管理', 'el-icon-picture', 'banners', 4, 'admin', 1),
            (0, '视频源管理', 'el-icon-video-camera', 'video_sources', 5, 'admin', 1),
            (0, 'Cookie管理', 'el-icon-document', 'cookies', 6, 'admin', 1),
            (0, '配置管理', 'el-icon-setting', 'config', 7, 'admin', 1),
            (0, '菜单管理', 'el-icon-menu', 'menu', 8, 'admin', 1)
        ");
        echo '<p style="color: green;">✓ 初始菜单数据插入成功</p>';
    } else {
        echo '<p style="color: orange;">⚠ 菜单数据已存在，跳过插入</p>';
    }

    echo '<h3 style="color: green;">安装完成！</h3>';
    echo '<p><a href="index.html">返回管理后台</a></p>';

} catch (PDOException $e) {
    echo '<p style="color: red;">✗ 错误: ' . htmlspecialchars($e->getMessage()) . '</p>';
}
