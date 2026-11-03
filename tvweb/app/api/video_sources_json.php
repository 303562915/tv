<?php
require_once 'config.php';

header('Content-Type: application/json; charset=utf-8');

$db = Database::getInstance()->getConnection();

try {
    // 获取启用的视频源配置
    $stmt = $db->prepare("
        SELECT source_key, source_name, source_type, base_url
        FROM app_video_sources
        WHERE status = 1
        ORDER BY sort_order ASC, id ASC
    ");
    $stmt->execute();
    $sources = $stmt->fetchAll();

    // 转换为软件需要的格式
    $result = [];
    foreach ($sources as $source) {
        $result[] = [
            'key' => $source['source_key'],
            'name' => $source['source_name'],
            'type' => $source['source_type'],
            'baseUrl' => $source['base_url']
        ];
    }

    echo json_encode($result, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);

} catch (PDOException $e) {
    echo json_encode([], JSON_UNESCAPED_UNICODE);
}
