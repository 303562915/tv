<?php
require_once 'api/config.php';

$db = Database::getInstance()->getConnection();

try {
    // 检查是否已存在"广告轮播"菜单
    $stmt = $db->prepare("SELECT id FROM app_menu WHERE name = '广告轮播' AND path = 'pages/profile_banners'");
    $stmt->execute();
    
    if ($stmt->fetch()) {
        echo "菜单已存在，无需重复添加\n";
        exit;
    }
    
    // 查找"轮播管理"的父菜单ID（如果存在）
    $stmt = $db->prepare("SELECT id FROM app_menu WHERE name LIKE '%轮播%' AND parent_id = 0 LIMIT 1");
    $stmt->execute();
    $parentMenu = $stmt->fetch();
    
    if ($parentMenu) {
        // 作为子菜单添加
        $parentId = $parentMenu['id'];
        echo "找到父菜单ID: $parentId\n";
    } else {
        // 作为顶级菜单添加
        $parentId = 0;
        echo "作为顶级菜单添加\n";
    }
    
    // 插入新菜单
    $stmt = $db->prepare("
        INSERT INTO app_menu (parent_id, name, icon, path, sort, permission, status, created_at, updated_at)
        VALUES (?, '广告轮播', 'el-icon-picture', 'pages/profile_banners', 100, '', 1, NOW(), NOW())
    ");
    $stmt->execute([$parentId]);
    
    echo "成功添加'广告轮播'菜单！\n";
    echo "菜单ID: " . $db->lastInsertId() . "\n";
    echo "\n访问地址: https://your-server-domain.com/app/pages/profile_banners.php\n";
    
} catch (PDOException $e) {
    echo "错误: " . $e->getMessage() . "\n";
}
