<?php
/**
 * 邀请记录API
 * GET /api/admin/invite_logs.php
 */

require_once __DIR__ . '/../config.php';

header('Content-Type: application/json; charset=utf-8');

$db = Database::getInstance()->getConnection();

try {
    $page = isset($_GET['page']) ? intval($_GET['page']) : 1;
    $pageSize = isset($_GET['pageSize']) ? intval($_GET['pageSize']) : 20;
    $keyword = isset($_GET['keyword']) ? trim($_GET['keyword']) : '';
    $status = isset($_GET['status']) ? trim($_GET['status']) : '';

    $offset = ($page - 1) * $pageSize;

    // 构建查询条件
    $where = [];
    $params = [];

    if (!empty($keyword)) {
        $where[] = "(m1.username LIKE ? OR m2.username LIKE ?)";
        $params[] = "%{$keyword}%";
        $params[] = "%{$keyword}%";
    }

    if (!empty($status)) {
        $where[] = "i.reward_status = ?";
        $params[] = $status;
    }

    $whereStr = !empty($where) ? 'WHERE ' . implode(' AND ', $where) : '';

    // 查询总数
    $stmt = $db->prepare("
        SELECT COUNT(*) as total
        FROM app_invitations i
        LEFT JOIN app_members m1 ON i.inviter_id = m1.id
        LEFT JOIN app_members m2 ON i.invitee_id = m2.id
        {$whereStr}
    ");
    $stmt->execute($params);
    $total = $stmt->fetch()['total'];

    // 查询列表
    $stmt = $db->prepare("
        SELECT i.*,
               m1.username as inviter_username,
               m2.username as invitee_username
        FROM app_invitations i
        LEFT JOIN app_members m1 ON i.inviter_id = m1.id
        LEFT JOIN app_members m2 ON i.invitee_id = m2.id
        {$whereStr}
        ORDER BY i.id DESC
        LIMIT {$offset}, {$pageSize}
    ");
    $stmt->execute($params);
    $list = $stmt->fetchAll();

    success([
        'list' => $list,
        'total' => $total,
        'page' => $page,
        'pageSize' => $pageSize
    ]);

} catch (Exception $e) {
    error('系统错误：' . $e->getMessage());
}
