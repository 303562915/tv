<?php
require_once 'api/config.php';

header('Content-Type: text/html; charset=utf-8');

$db = Database::getInstance()->getConnection();

echo "<h2>菜单结构检查</h2>";
echo "<table border='1' cellpadding='5'>";
echo "<tr><th>ID</th><th>父ID</th><th>名称</th><th>路径</th><th>排序</th><th>状态</th></tr>";

$stmt = $db->query("SELECT id, parent_id, name, path, sort, status FROM app_menu ORDER BY parent_id, sort");
while ($row = $stmt->fetch()) {
    $indent = $row['parent_id'] > 0 ? '&nbsp;&nbsp;&nbsp;&nbsp;' : '';
    echo "<tr>";
    echo "<td>{$row['id']}</td>";
    echo "<td>{$row['parent_id']}</td>";
    echo "<td>{$indent}{$row['name']}</td>";
    echo "<td>{$row['path']}</td>";
    echo "<td>{$row['sort']}</td>";
    echo "<td>{$row['status']}</td>";
    echo "</tr>";
}

echo "</table>";

echo "<h2>测试文件访问</h2>";
$files = ['member_list.php', 'points_logs.php', 'login_logs.php', 'system_settings.php'];
foreach ($files as $file) {
    $exists = file_exists($file);
    $color = $exists ? 'green' : 'red';
    echo "<div style='color: {$color};'>{$file}: " . ($exists ? '存在' : '不存在') . "</div>";
}
?>
