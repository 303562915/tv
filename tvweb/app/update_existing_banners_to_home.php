<?php
require_once 'api/config.php';

header('Content-Type: text/html; charset=utf-8');

$db = Database::getInstance()->getConnection();

try {
    // 查看当前所有轮播的position
    $stmt = $db->prepare("SELECT id, title, position FROM app_banners ORDER BY id");
    $stmt->execute();
    $banners = $stmt->fetchAll();
    
    echo "<h2>当前轮播数据：</h2>";
    echo "<table border='1' cellpadding='10'>";
    echo "<tr><th>ID</th><th>标题</th><th>位置(position)</th></tr>";
    foreach ($banners as $banner) {
        echo "<tr>";
        echo "<td>{$banner['id']}</td>";
        echo "<td>{$banner['title']}</td>";
        echo "<td>{$banner['position']}</td>";
        echo "</tr>";
    }
    echo "</table>";
    
    // 将所有position为空或不是'profile'的轮播设置为'home'
    $stmt = $db->prepare("
        UPDATE app_banners 
        SET position = 'home' 
        WHERE position IS NULL OR position = '' OR position NOT IN ('home', 'profile')
    ");
    $stmt->execute();
    $updated = $stmt->rowCount();
    
    if ($updated > 0) {
        echo "<h3 style='color: green;'>✅ 已将 {$updated} 条轮播的位置更新为 'home'</h3>";
    } else {
        echo "<h3 style='color: blue;'>ℹ️ 所有轮播位置已正确设置，无需更新</h3>";
    }
    
    // 显示更新后的数据
    $stmt = $db->prepare("SELECT id, title, position FROM app_banners ORDER BY id");
    $stmt->execute();
    $banners = $stmt->fetchAll();
    
    echo "<h2>更新后的数据：</h2>";
    echo "<table border='1' cellpadding='10'>";
    echo "<tr><th>ID</th><th>标题</th><th>位置(position)</th></tr>";
    foreach ($banners as $banner) {
        $color = $banner['position'] == 'home' ? '#d4edda' : '#fff3cd';
        echo "<tr style='background: {$color};'>";
        echo "<td>{$banner['id']}</td>";
        echo "<td>{$banner['title']}</td>";
        echo "<td><strong>{$banner['position']}</strong></td>";
        echo "</tr>";
    }
    echo "</table>";
    
    echo "<hr>";
    echo "<h3>说明：</h3>";
    echo "<ul>";
    echo "<li><span style='background: #d4edda; padding: 5px;'>绿色</span> = 首页轮播 (position='home')</li>";
    echo "<li><span style='background: #fff3cd; padding: 5px;'>黄色</span> = 我的页面轮播 (position='profile')</li>";
    echo "</ul>";
    
    echo "<h3>API地址：</h3>";
    echo "<ul>";
    echo "<li>首页轮播: <a href='api/banner_json.php' target='_blank'>api/banner_json.php</a></li>";
    echo "<li>我的页面轮播: <a href='api/profile_banners.php' target='_blank'>api/profile_banners.php</a></li>";
    echo "</ul>";
    
} catch (PDOException $e) {
    echo "<h2 style='color: red;'>❌ 错误：</h2>";
    echo "<p>" . $e->getMessage() . "</p>";
}
?>
