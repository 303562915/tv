<?php
/**
 * 会员列表API
 * GET /api/admin/member_list.php
 */

require_once __DIR__ . '/../config.php';

header('Content-Type: application/json; charset=utf-8');

$db = Database::getInstance()->getConnection();

try {
    $page = isset($_GET['page']) ? intval($_GET['page']) : 1;
    $pageSize = isset($_GET['pageSize']) ? intval($_GET['pageSize']) : 20;
    $keyword = isset($_GET['keyword']) ? trim($_GET['keyword']) : '';
    $level = isset($_GET['level']) ? trim($_GET['level']) : '';
    $status = isset($_GET['status']) ? trim($_GET['status']) : '';

    $offset = ($page - 1) * $pageSize;

    // 构建查询条件
    $where = [];
    $params = [];

    if (!empty($keyword)) {
        $where[] = "(username LIKE ? OR email LIKE ?)";
        $params[] = "%{$keyword}%";
        $params[] = "%{$keyword}%";
    }

    if (!empty($level)) {
        $where[] = "level = ?";
        $params[] = $level;
    }

    if (!empty($status)) {
        $where[] = "status = ?";
        $params[] = $status;
    }

    $whereStr = !empty($where) ? 'WHERE ' . implode(' AND ', $where) : '';

    // 查询总数
    $stmt = $db->prepare("SELECT COUNT(*) as total FROM app_members {$whereStr}");
    $stmt->execute($params);
    $total = $stmt->fetch()['total'];

    // 查询列表
    $stmt = $db->prepare("
        SELECT id, username, email, nickname, avatar, level, vip_expire_time,
               points, total_points, balance, total_recharge, invite_code,
               parent_invite_code, invite_count, status, login_count,
               last_login_time, last_login_ip, created_at
        FROM app_members
        {$whereStr}
        ORDER BY id DESC
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
