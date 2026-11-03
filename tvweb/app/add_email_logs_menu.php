<?php
/**
 * 添加邮箱记录菜单
 */

require_once 'api/config.php';

$db = Database::getInstance()->getConnection();

try {
    // 检查菜单是否已存在
    $stmt = $db->prepare("SELECT id FROM app_menu WHERE path = 'email_logs'");
    $stmt->execute();
    $exists = $stmt->fetch();

    if ($exists) {
        echo "邮箱记录菜单已存在 (ID: {$exists['id']})\n";
        exit;
    }

    // 获取当前最大排序值
    $stmt = $db->query("SELECT MAX(sort) as max_sort FROM app_menu WHERE parent_id = 0");
    $result = $stmt->fetch();
    $nextSort = ($result['max_sort'] ?? 0) + 1;

    // 插入邮箱记录菜单
    $stmt = $db->prepare("INSERT INTO app_menu (parent_id, name, icon, path, sort, permission, status) VALUES (?, ?, ?, ?, ?, ?, ?)");
    $stmt->execute([
        0,                      // parent_id
        '邮箱记录',              // name
        'el-icon-message',      // icon
        'email_logs',           // path
        $nextSort,              // sort
        'admin',                // permission (仅管理员可见)
        1                       // status
    ]);

    $menuId = $db->lastInsertId();

    echo "✓ 邮箱记录菜单添加成功！\n";
    echo "菜单ID: {$menuId}\n";
    echo "排序: {$nextSort}\n";
    echo "\n请刷新后台页面查看新菜单。\n";

} catch (PDOException $e) {
    echo "✗ 添加菜单失败: " . $e->getMessage() . "\n";
}
