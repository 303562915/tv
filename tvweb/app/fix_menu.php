<?php
/**
 * 修复仪表盘菜单路径
 */

$config = [
    'host' => 'localhost',
    'dbname' => 'yughplvt',
    'username' => 'yughplvt',
    'password' => getenv('DB_PASS') ?: '',
    'charset' => 'utf8mb4'
];

header('Content-Type: text/html; charset=utf-8');

try {
    $dsn = "mysql:host={$config['host']};dbname={$config['dbname']};charset={$config['charset']}";
    $pdo = new PDO($dsn, $config['username'], $config['password']);
    $pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);

    echo '<h2>修复菜单路径...</h2>';

    // 修复仪表盘路径
    $pdo->exec("UPDATE app_menu SET path = 'dashboard.php', icon = 'el-icon-s-home' WHERE id = 1");
    echo '<p style="color: green;">✓ 仪表盘路径已修复为 dashboard.php</p>';

    // 修复其他空路径和空图标
    $pdo->exec("UPDATE app_menu SET icon = 'el-icon-bell' WHERE id = 3 AND icon = ''");
    echo '<p style="color: green;">✓ 公告管理图标已修复</p>';

    // 显示所有菜单
    $stmt = $pdo->query("SELECT id, name, icon, path, sort FROM app_menu ORDER BY sort, id");
    $menus = $stmt->fetchAll();

    echo '<h3>当前菜单列表：</h3>';
    echo '<table border="1" cellpadding="10" style="border-collapse: collapse;">';
    echo '<tr><th>ID</th><th>菜单名称</th><th>图标</th><th>路径</th><th>排序</th></tr>';
    foreach ($menus as $menu) {
        echo '<tr>';
        echo '<td>' . $menu['id'] . '</td>';
        echo '<td>' . htmlspecialchars($menu['name']) . '</td>';
        echo '<td>' . htmlspecialchars($menu['icon']) . '</td>';
        echo '<td>' . htmlspecialchars($menu['path']) . '</td>';
        echo '<td>' . $menu['sort'] . '</td>';
        echo '</tr>';
    }
    echo '</table>';

    echo '<p style="margin-top: 20px;"><a href="dashboard.php">返回仪表盘</a></p>';

} catch (PDOException $e) {
    echo '<p style="color: red;">✗ 错误: ' . htmlspecialchars($e->getMessage()) . '</p>';
}
