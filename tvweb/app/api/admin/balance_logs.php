<?php
/**
 * 积分记录API
 * GET /api/admin/balance_logs.php
 */

require_once __DIR__ . '/../config.php';

header('Content-Type: application/json; charset=utf-8');

$db = Database::getInstance()->getConnection();

try {
    $page = isset($_GET['page']) ? intval($_GET['page']) : 1;
    $pageSize = isset($_GET['pageSize']) ? intval($_GET['pageSize']) : 20;
    $keyword = isset($_GET['keyword']) ? trim($_GET['keyword']) : '';
    $type = isset($_GET['type']) ? trim($_GET['type']) : '';

    $offset = ($page - 1) * $pageSize;

    // 构建查询条件
    $where = [];
    $params = [];

    if (!empty($keyword)) {
        $where[] = "(l.member_id = ? OR m.username LIKE ?)";
        $params[] = $keyword;
        $params[] = "%{$keyword}%";
    }

    if (!empty($type)) {
        $where[] = "l.type = ?";
        $params[] = $type;
    }

    $whereStr = !empty($where) ? 'WHERE ' . implode(' AND ', $where) : '';

    // 查询总数
    $stmt = $db->prepare("
        SELECT COUNT(*) as total
        FROM app_balance_logs l
        LEFT JOIN app_members m ON l.member_id = m.id
        {$whereStr}
    ");
    $stmt->execute($params);
    $total = $stmt->fetch()['total'];

    // 查询列表
    $stmt = $db->prepare("
        SELECT l.*, m.username
        FROM app_balance_logs l
        LEFT JOIN app_members m ON l.member_id = m.id
        {$whereStr}
        ORDER BY l.id DESC
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
