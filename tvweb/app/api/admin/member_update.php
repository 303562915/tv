<?php
/**
 * 更新会员信息API
 * POST /api/admin/member_update.php
 */

require_once __DIR__ . '/../config.php';

header('Content-Type: application/json; charset=utf-8');

$db = Database::getInstance()->getConnection();

try {
    $input = json_decode(file_get_contents('php://input'), true);

    $id = $input['id'] ?? 0;
    if (empty($id)) {
        error('会员ID不能为空');
    }

    // 检查会员是否存在
    $stmt = $db->prepare("SELECT id FROM app_members WHERE id = ?");
    $stmt->execute([$id]);
    if (!$stmt->fetch()) {
        error('会员不存在');
    }

    // 构建更新字段
    $updates = [];
    $params = [];

    if (isset($input['nickname'])) {
        $updates[] = "nickname = ?";
        $params[] = $input['nickname'];
    }

    if (isset($input['level'])) {
        $updates[] = "level = ?";
        $params[] = $input['level'];
    }

    if (isset($input['vip_expire_time'])) {
        $updates[] = "vip_expire_time = ?";
        $params[] = $input['vip_expire_time'];
    }

    if (isset($input['points'])) {
        $updates[] = "points = ?";
        $params[] = $input['points'];
    }

    if (isset($input['balance'])) {
        $updates[] = "balance = ?";
        $params[] = $input['balance'];
    }

    if (isset($input['status'])) {
        $updates[] = "status = ?";
        $params[] = $input['status'];
    }

    if (empty($updates)) {
        error('没有需要更新的字段');
    }

    $params[] = $id;
    $updateStr = implode(', ', $updates);

    $stmt = $db->prepare("UPDATE app_members SET {$updateStr} WHERE id = ?");
    $stmt->execute($params);

    success(null, '更新成功');

} catch (Exception $e) {
    error('系统错误：' . $e->getMessage());
}
