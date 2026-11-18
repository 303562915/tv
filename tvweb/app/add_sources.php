<?php
require_once 'api/config.php';

try {
    $db = Database::getInstance()->getConnection();

    // 检查表是否存在
    $checkTable = $db->query("SHOW TABLES LIKE 'app_video_sources'");
    if ($checkTable->rowCount() == 0) {
        die("错误：app_video_sources 表不存在\n");
    }

    // 检查是否已存在这些源
    $checkShandian = $db->prepare("SELECT id FROM app_video_sources WHERE source_key = 'shandian'");
    $checkShandian->execute();

    $checkMaotai = $db->prepare("SELECT id FROM app_video_sources WHERE source_key = 'maotai'");
    $checkMaotai->execute();

    $added = [];

    // 添加闪电资源
    if ($checkShandian->rowCount() == 0) {
        $stmt = $db->prepare("INSERT INTO app_video_sources (source_key, source_name, source_type, base_url, description, sort_order, status) VALUES ('shandian', '闪电资源', 'API', 'https://xsd.sdzyapi.com/api.php/provide/vod/from/sdm3u8/at/xml', '闪电资源 sdm3u8', 10, 1)");
        $stmt->execute();
        $added[] = "闪电资源";
        echo "✓ 成功添加：闪电资源\n";
    } else {
        echo "- 闪电资源已存在，跳过\n";
    }

    // 添加茅台资源
    if ($checkMaotai->rowCount() == 0) {
        $stmt = $db->prepare("INSERT INTO app_video_sources (source_key, source_name, source_type, base_url, description, sort_order, status) VALUES ('maotai', '茅台资源', 'API', 'https://caiji.maotaizy.cc/api.php/provide/vod/from/mtm3u8/at/json', '茅台资源 mtm3u8', 11, 1)");
        $stmt->execute();
        $added[] = "茅台资源";
        echo "✓ 成功添加：茅台资源\n";
    } else {
        echo "- 茅台资源已存在，跳过\n";
    }

    if (count($added) > 0) {
        echo "\n成功添加 " . count($added) . " 个视频源：" . implode(", ", $added) . "\n";
    } else {
        echo "\n所有视频源都已存在，无需添加\n";
    }

    // 显示当前所有视频源
    echo "\n当前所有视频源：\n";
    $sources = $db->query("SELECT source_key, source_name, source_type, status FROM app_video_sources ORDER BY sort_order");
    while ($row = $sources->fetch()) {
        $status = $row['status'] == 1 ? '启用' : '禁用';
        echo "  - {$row['source_name']} ({$row['source_key']}) [{$row['source_type']}] - {$status}\n";
    }

} catch (PDOException $e) {
    die("数据库错误：" . $e->getMessage() . "\n");
}
