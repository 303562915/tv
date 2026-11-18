<?php
require_once 'config.php';

header('Content-Type: application/json; charset=utf-8');

$db = Database::getInstance()->getConnection();

try {
    // 获取 Cookie 配置
    $stmt = $db->prepare("SELECT cookie, updated_at FROM app_cookies ORDER BY id DESC LIMIT 1");
    $stmt->execute();
    $result = $stmt->fetch();

    if ($result) {
        success([
            'cookie' => $result['cookie'],
            'updated_at' => $result['updated_at']
        ]);
    } else {
        success([
            'cookie' => '',
            'updated_at' => null
        ]);
    }

} catch (PDOException $e) {
    error('获取配置失败');
}
