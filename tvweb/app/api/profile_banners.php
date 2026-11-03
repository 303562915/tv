<?php
require_once 'config.php';

header('Content-Type: application/json; charset=utf-8');

$db = Database::getInstance()->getConnection();

try {
    // 获取"我的"页面的启用轮播图
    $stmt = $db->prepare("
        SELECT id, title, image_url, link_url, link_type, sort_order, click_count
        FROM app_banners
        WHERE status = 1
        AND position = 'profile'
        AND (start_time IS NULL OR start_time <= NOW())
        AND (end_time IS NULL OR end_time >= NOW())
        ORDER BY sort_order ASC, id DESC
    ");
    $stmt->execute();
    $banners = $stmt->fetchAll();

    // 转换为APP需要的格式
    $result = [];
    foreach ($banners as $banner) {
        $result[] = [
            'id' => $banner['id'],
            '图片链接' => $banner['image_url'],
            '跳转链接' => $banner['link_url'] ?: '',
            '描述' => $banner['title'],
            'visble' => 'true'
        ];
    }

    echo json_encode($result, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);

} catch (PDOException $e) {
    echo json_encode([], JSON_UNESCAPED_UNICODE);
}
