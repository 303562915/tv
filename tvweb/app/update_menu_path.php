<?php
/**
 * 更新菜单路径，添加.php后缀
 */

// 数据库配置
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

    echo '<h2>更新菜单路径...</h2>';

    // 更新所有菜单路径，添加.php后缀
    $pdo->exec("
        UPDATE app_menu SET path = CONCAT(path, '.php')
        WHERE path NOT LIKE '%.php' AND path != ''
    ");

    echo '<p style="color: green;">✓ 菜单路径已更新为.php格式</p>';

    // 显示更新后的菜单
    $stmt = $pdo->query("SELECT id, name, path FROM app_menu ORDER BY sort");
    $menus = $stmt->fetchAll();

    echo '<h3>更新后的菜单列表：</h3>';
    echo '<table border="1" cellpadding="10" style="border-collapse: collapse;">';
    echo '<tr><th>ID</th><th>菜单名称</th><th>路径</th></tr>';
    foreach ($menus as $menu) {
        echo '<tr>';
        echo '<td>' . $menu['id'] . '</td>';
        echo '<td>' . htmlspecialchars($menu['name']) . '</td>';
        echo '<td>' . htmlspecialchars($menu['path']) . '</td>';
        echo '</tr>';
    }
    echo '</table>';

    echo '<p style="margin-top: 20px;"><a href="dashboard.php">返回仪表盘</a></p>';

} catch (PDOException $e) {
    echo '<p style="color: red;">✗ 错误: ' . htmlspecialchars($e->getMessage()) . '</p>';
}
