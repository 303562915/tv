<?php
require_once 'config.php';

header('Content-Type: application/json; charset=utf-8');

$db = Database::getInstance()->getConnection();
$data = getInput();

try {
    if (!isset($data['cookie'])) {
        error('Cookie 内容不能为空');
    }

    $cookie = $data['cookie'];

    // 检查是否已有配置
    $stmt = $db->query("SELECT COUNT(*) FROM app_cookies");
    $count = $stmt->fetchColumn();

    if ($count > 0) {
        // 更新现有配置
        $stmt = $db->prepare("UPDATE app_cookies SET cookie = ?, updated_at = NOW() WHERE id = (SELECT id FROM (SELECT id FROM app_cookies ORDER BY id DESC LIMIT 1) AS tmp)");
        $stmt->execute([$cookie]);
    } else {
        // 插入新配置
        $stmt = $db->prepare("INSERT INTO app_cookies (cookie) VALUES (?)");
        $stmt->execute([$cookie]);
    }

    success([], 'Cookie 更新成功');

} catch (PDOException $e) {
    error('更新失败: ' . $e->getMessage());
}
