<?php
/**
 * 会员注册API
 * POST /api/member/register.php
 */

require_once __DIR__ . '/../config.php';
require_once __DIR__ . '/../MemberHelper.php';

header('Content-Type: application/json; charset=utf-8');

$db = Database::getInstance()->getConnection();

try {
    // 获取POST数据
    $input = json_decode(file_get_contents('php://input'), true);

    $type = $input['type'] ?? ''; // email 或 username
    $email = $input['email'] ?? '';
    $username = $input['username'] ?? '';
    $code = $input['code'] ?? '';
    $password = $input['password'] ?? '';
    $inviteCode = $input['invite_code'] ?? '';

    // 验证注册类型
    if (!in_array($type, ['email', 'username'])) {
        error('请选择注册方式');
    }

    // 验证密码
    if (empty($password)) {
        error('请输入密码');
    }

    if (!MemberHelper::validatePassword($password)) {
        error('密码至少6位，需包含字母和数字');
    }

    $finalUsername = '';
    $finalEmail = null;

    // 邮箱注册
    if ($type === 'email') {
        if (empty($email)) {
            error('请输入邮箱');
        }

        if (!MemberHelper::validateEmail($email)) {
            error('邮箱格式不正确');
        }

        if (empty($code)) {
            error('请输入验证码');
        }

        // 验证邮箱验证码
        $stmt = $db->prepare("
            SELECT * FROM app_email_codes
            WHERE email = ? AND code = ? AND type = 'register' AND is_used = 0 AND expire_time > NOW()
            ORDER BY id DESC LIMIT 1
        ");
        $stmt->execute([$email, $code]);
        $codeRecord = $stmt->fetch();

        if (!$codeRecord) {
            error('验证码错误或已过期');
        }

        // 标记验证码已使用
        $stmt = $db->prepare("UPDATE app_email_codes SET is_used = 1 WHERE id = ?");
        $stmt->execute([$codeRecord['id']]);

        // 检查邮箱是否已注册
        $stmt = $db->prepare("SELECT id FROM app_members WHERE email = ?");
        $stmt->execute([$email]);
        if ($stmt->fetch()) {
            error('该邮箱已被注册');
        }

        // 使用邮箱前缀作为用户名
        $finalUsername = explode('@', $email)[0] . rand(1000, 9999);
        $finalEmail = $email;

    } else {
        // 用户名注册
        if (empty($username)) {
            error('请输入用户名');
        }

        if (!MemberHelper::validateUsername($username)) {
            error('用户名4-20位，只能包含字母、数字和下划线');
        }

        // 检查用户名是否已存在
        $stmt = $db->prepare("SELECT id FROM app_members WHERE username = ?");
        $stmt->execute([$username]);
        if ($stmt->fetch()) {
            error('该用户名已被注册');
        }

        $finalUsername = $username;
    }

    // 验证推荐码（如果提供）
    $parentId = 0;
    $parentInviteCode = null;
    if (!empty($inviteCode)) {
        $stmt = $db->prepare("SELECT id, invite_code FROM app_members WHERE invite_code = ?");
        $stmt->execute([$inviteCode]);
        $parent = $stmt->fetch();

        if (!$parent) {
            error('推荐码不存在');
        }

        $parentId = $parent['id'];
        $parentInviteCode = $parent['invite_code'];
    }

    // 生成唯一推荐码
    $myInviteCode = MemberHelper::generateUniqueInviteCode($db);

    // 加密密码
    $hashedPassword = MemberHelper::hashPassword($password);

    // 获取IP和设备信息
    $ip = MemberHelper::getClientIp();
    $deviceId = $input['device_id'] ?? '';
    $deviceType = $input['device_type'] ?? '';
    $appVersion = $input['app_version'] ?? '';

    // 开始事务
    $db->beginTransaction();

    try {
        // 插入会员记录
        $stmt = $db->prepare("
            INSERT INTO app_members (
                username, password, email, invite_code, parent_invite_code, parent_id,
                last_login_ip, device_id, device_type, app_version, is_email_verified
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ");
        $stmt->execute([
            $finalUsername,
            $hashedPassword,
            $finalEmail,
            $myInviteCode,
            $parentInviteCode,
            $parentId,
            $ip,
            $deviceId,
            $deviceType,
            $appVersion,
            $type === 'email' ? 1 : 0
        ]);

        $memberId = $db->lastInsertId();

        // 如果有推荐人，处理邀请奖励
        if ($parentId > 0) {
            // 创建邀请记录
            $stmt = $db->prepare("
                INSERT INTO app_invitations (
                    inviter_id, inviter_code, invitee_id, invitee_username, invitee_email,
                    reward_status, reward_time
                ) VALUES (?, ?, ?, ?, ?, 'completed', NOW())
            ");
            $stmt->execute([
                $parentId,
                $parentInviteCode,
                $memberId,
                $finalUsername,
                $finalEmail
            ]);

            // 给邀请人增加20积分
            MemberHelper::addPoints($db, $parentId, 20, 'invite', "邀请用户 {$finalUsername} 注册");

            // 给被邀请人增加20积分
            MemberHelper::addPoints($db, $memberId, 20, 'invite', "使用推荐码 {$parentInviteCode} 注册");

            // 更新邀请人的邀请数量
            $stmt = $db->prepare("UPDATE app_members SET invite_count = invite_count + 1 WHERE id = ?");
            $stmt->execute([$parentId]);
        }

        // 记录登录日志
        $stmt = $db->prepare("
            INSERT INTO app_login_logs (
                member_id, login_type, login_account, ip, device_id, device_type, app_version, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'success')
        ");
        $stmt->execute([
            $memberId,
            $type,
            $type === 'email' ? $email : $username,
            $ip,
            $deviceId,
            $deviceType,
            $appVersion
        ]);

        $db->commit();

        // 生成Token
        $token = MemberHelper::generateToken($memberId, $finalUsername);

        // 返回成功
        success([
            'member_id' => $memberId,
            'username' => $finalUsername,
            'email' => $finalEmail,
            'invite_code' => $myInviteCode,
            'points' => $parentId > 0 ? 20 : 0,
            'token' => $token
        ], '注册成功');

    } catch (Exception $e) {
        $db->rollBack();
        error('注册失败：' . $e->getMessage());
    }

} catch (Exception $e) {
    error('系统错误：' . $e->getMessage());
}
