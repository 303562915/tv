<?php
require_once 'config.php';

header('Content-Type: application/json; charset=utf-8');

$db = Database::getInstance()->getConnection();

try {
    $data = getInput();

    if (empty($data['id'])) {
        error('轮播图ID不能为空');
    }

    // 增加点击次数
    $stmt = $db->prepare("UPDATE app_banners SET click_count = click_count + 1 WHERE id = ?");
    $stmt->execute([$data['id']]);

    success([], '点击统计成功');

} catch (PDOException $e) {
    error('统计失败');
}
