<?php
require_once 'api/config.php';

header('Content-Type: text/html; charset=utf-8');

$db = Database::getInstance()->getConnection();

try {
    // 检查是否已有profile轮播
    $stmt = $db->prepare("SELECT COUNT(*) as count FROM app_banners WHERE position = 'profile'");
    $stmt->execute();
    $count = $stmt->fetch()['count'];
    
    if ($count > 0) {
        echo "<h2>已存在 {$count} 条profile轮播数据</h2>";
        echo "<p><a href='pages/profile_banners.php'>查看管理页面</a></p>";
        
        // 显示现有数据
        $stmt = $db->prepare("SELECT * FROM app_banners WHERE position = 'profile' ORDER BY sort_order ASC");
        $stmt->execute();
        $banners = $stmt->fetchAll();
        
        echo "<h3>现有数据：</h3>";
        echo "<table border='1' cellpadding='10'>";
        echo "<tr><th>ID</th><th>标题</th><th>图片URL</th><th>状态</th></tr>";
        foreach ($banners as $banner) {
            echo "<tr>";
            echo "<td>{$banner['id']}</td>";
            echo "<td>{$banner['title']}</td>";
            echo "<td><img src='{$banner['image_url']}' width='200'></td>";
            echo "<td>" . ($banner['status'] ? '启用' : '禁用') . "</td>";
            echo "</tr>";
        }
        echo "</table>";
        exit;
    }
    
    // 插入测试数据
    $testData = [
        [
            'title' => '线下实体卡推广',
            'image_url' => 'https://vres.bavdxfg.cn/vod1/vod/upload/202604/30/04/8c264aa77ac9d292ae366cc6c2b05661.jpg',
            'link_url' => 'https://example.com/card',
            'sort_order' => 1
        ],
        [
            'title' => '优惠活动',
            'image_url' => 'https://vres.bavdxfg.cn/vod1/vod/upload/202605/15/23/db749f1b1391c288f8bb44f98062bb4c.jpg',
            'link_url' => 'https://example.com/promotion',
            'sort_order' => 2
        ],
        [
            'title' => '会员福利',
            'image_url' => 'https://vres.bavdxfg.cn/vod1/vod/upload/202603/29/20/8f474c4b8bbd23e5a7b73831b87d316a.jpg',
            'link_url' => 'https://example.com/vip',
            'sort_order' => 3
        ]
    ];
    
    $stmt = $db->prepare("
        INSERT INTO app_banners (title, image_url, link_url, position, sort_order, status, created_at, updated_at)
        VALUES (?, ?, ?, 'profile', ?, 1, NOW(), NOW())
    ");
    
    foreach ($testData as $data) {
        $stmt->execute([
            $data['title'],
            $data['image_url'],
            $data['link_url'],
            $data['sort_order']
        ]);
    }
    
    echo "<h2>✅ 成功添加 " . count($testData) . " 条测试轮播数据！</h2>";
    echo "<p><a href='api/profile_banners.php' target='_blank'>查看API返回</a></p>";
    echo "<p><a href='pages/profile_banners.php'>进入管理页面</a></p>";
    
    // 显示添加的数据
    echo "<h3>已添加的数据：</h3>";
    echo "<table border='1' cellpadding='10'>";
    echo "<tr><th>标题</th><th>图片预览</th><th>跳转链接</th><th>排序</th></tr>";
    foreach ($testData as $data) {
        echo "<tr>";
        echo "<td>{$data['title']}</td>";
        echo "<td><img src='{$data['image_url']}' width='200'></td>";
        echo "<td>{$data['link_url']}</td>";
        echo "<td>{$data['sort_order']}</td>";
        echo "</tr>";
    }
    echo "</table>";
    
} catch (PDOException $e) {
    echo "<h2>❌ 错误：</h2>";
    echo "<p>" . $e->getMessage() . "</p>";
}
?>
