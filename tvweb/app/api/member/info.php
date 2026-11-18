<?php
/**
 * 获取会员信息API
 * GET /api/member/info.php
 */

require_once __DIR__ . '/../config.php';
require_once __DIR__ . '/../MemberHelper.php';

header('Content-Type: application/json; charset=utf-8');

$db = Database::getInstance()->getConnection();

try {
    // 获取Token
    $token = MemberHelper::getTokenFromHeader();
    if (!$token) {
        error('请先登录', 401);
    }

    // 验证Token
    $payload = MemberHelper::verifyToken($token);
    if (!$payload) {
        error('登录已过期，请重新登录', 401);
    }

    $memberId = $payload['member_id'];

    // 查询会员信息
    $stmt = $db->prepare("SELECT * FROM app_members WHERE id = ?");
    $stmt->execute([$memberId]);
    $member = $stmt->fetch();

    if (!$member) {
        error('会员不存在', 404);
    }

    // 返回会员信息
    success([
        'id' => $member['id'],
        'username' => $member['username'],
        'email' => $member['email'],
        'nickname' => $member['nickname'],
        'avatar' => $member['avatar'],
        'gender' => $member['gender'],
        'birthday' => $member['birthday'],
        'level' => $member['level'],
        'vip_expire_time' => $member['vip_expire_time'],
        'points' => $member['points'],
        'total_points' => $member['total_points'],
        'balance' => $member['balance'],
        'frozen_balance' => $member['frozen_balance'],
        'invite_code' => $member['invite_code'],
        'parent_invite_code' => $member['parent_invite_code'],
        'invite_count' => $member['invite_count'],
        'total_recharge' => $member['total_recharge'],
        'total_withdraw' => $member['total_withdraw'],
        'total_commission' => $member['total_commission'],
        'status' => $member['status'],
        'is_email_verified' => $member['is_email_verified'],
        'last_login_time' => $member['last_login_time'],
        'login_count' => $member['login_count'],
        'created_at' => $member['created_at']
    ]);

} catch (Exception $e) {
    error('系统错误：' . $e->getMessage());
}
