<?php
/**
 * 会员登录API
 * POST /api/member/login.php
 */

require_once __DIR__ . '/../config.php';
require_once __DIR__ . '/../MemberHelper.php';

header('Content-Type: application/json; charset=utf-8');

$db = Database::getInstance()->getConnection();

try {
    $input = json_decode(file_get_contents('php://input'), true);

    $type = $input['type'] ?? ''; // email, username
    $account = $input['account'] ?? '';
    $password = $input['password'] ?? '';
    $code = $input['code'] ?? ''; // 验证码登录

    // 验证登录类型
    if (!in_array($type, ['email', 'username'])) {
        error('请选择登录方式');
    }

    if (empty($account)) {
        error('请输入账号');
    }

    // 判断是密码登录还是验证码登录
    $isCodeLogin = !empty($code);

    if (!$isCodeLogin && empty($password)) {
        error('请输入密码');
    }

    // 查询会员
    $member = null;
    if ($type === 'email') {
        $stmt = $db->prepare("SELECT * FROM app_members WHERE email = ?");
        $stmt->execute([$account]);
        $member = $stmt->fetch();
    } else {
        $stmt = $db->prepare("SELECT * FROM app_members WHERE username = ?");
        $stmt->execute([$account]);
        $member = $stmt->fetch();
    }

    if (!$member) {
        error('账号不存在');
    }

    // 检查账号状态
    if ($member['status'] === 'frozen') {
        error('账号已被冻结');
    }

    if ($member['status'] === 'banned') {
        error('账号已被封禁');
    }

    $ip = MemberHelper::getClientIp();
    $deviceId = $input['device_id'] ?? '';
    $deviceType = $input['device_type'] ?? '';
    $appVersion = $input['app_version'] ?? '';

    // 验证码登录
    if ($isCodeLogin) {
        if ($type !== 'email') {
            error('验证码登录仅支持邮箱');
        }

        // 验证邮箱验证码
        $stmt = $db->prepare("
            SELECT * FROM app_email_codes
            WHERE email = ? AND code = ? AND type = 'login' AND is_used = 0 AND expire_time > NOW()
            ORDER BY id DESC LIMIT 1
        ");
        $stmt->execute([$account, $code]);
        $codeRecord = $stmt->fetch();

        if (!$codeRecord) {
            // 记录失败日志
            $stmt = $db->prepare("
                INSERT INTO app_login_logs (
                    member_id, login_type, login_account, ip, device_id, device_type, app_version,
                    status, fail_reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'failed', '验证码错误')
            ");
            $stmt->execute([$member['id'], $type, $account, $ip, $deviceId, $deviceType, $appVersion]);

            error('验证码错误或已过期');
        }

        // 标记验证码已使用
        $stmt = $db->prepare("UPDATE app_email_codes SET is_used = 1 WHERE id = ?");
        $stmt->execute([$codeRecord['id']]);

    } else {
        // 密码登录
        if (!MemberHelper::verifyPassword($password, $member['password'])) {
            // 记录失败日志
            $stmt = $db->prepare("
                INSERT INTO app_login_logs (
                    member_id, login_type, login_account, ip, device_id, device_type, app_version,
                    status, fail_reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'failed', '密码错误')
            ");
            $stmt->execute([$member['id'], $type, $account, $ip, $deviceId, $deviceType, $appVersion]);

            error('密码错误');
        }
    }

    // 更新会员登录信息
    $stmt = $db->prepare("
        UPDATE app_members
        SET last_login_time = NOW(), last_login_ip = ?, login_count = login_count + 1,
            device_id = ?, device_type = ?, app_version = ?
        WHERE id = ?
    ");
    $stmt->execute([$ip, $deviceId, $deviceType, $appVersion, $member['id']]);

    // 记录成功日志
    $stmt = $db->prepare("
        INSERT INTO app_login_logs (
            member_id, login_type, login_account, ip, device_id, device_type, app_version, status
        ) VALUES (?, ?, ?, ?, ?, ?, ?, 'success')
    ");
    $stmt->execute([$member['id'], $type, $account, $ip, $deviceId, $deviceType, $appVersion]);

    // 生成Token
    $token = MemberHelper::generateToken($member['id'], $member['username']);

    // 返回会员信息
    success([
        'member_id' => $member['id'],
        'username' => $member['username'],
        'email' => $member['email'],
        'nickname' => $member['nickname'],
        'avatar' => $member['avatar'],
        'level' => $member['level'],
        'vip_expire_time' => $member['vip_expire_time'],
        'points' => $member['points'],
        'balance' => $member['balance'],
        'invite_code' => $member['invite_code'],
        'token' => $token
    ], '登录成功');

} catch (Exception $e) {
    error('系统错误：' . $e->getMessage());
}
