<?php
/**
 * 发送邮箱验证码API
 * POST /api/member/send_email_code.php
 */

require_once __DIR__ . '/../config.php';
require_once __DIR__ . '/../MemberHelper.php';

header('Content-Type: application/json; charset=utf-8');

$db = Database::getInstance()->getConnection();

try {
    $input = json_decode(file_get_contents('php://input'), true);

    $email = $input['email'] ?? '';
    $type = $input['type'] ?? 'register'; // register, login, reset_password

    // 验证邮箱
    if (empty($email)) {
        error('请输入邮箱');
    }

    if (!MemberHelper::validateEmail($email)) {
        error('邮箱格式不正确');
    }

    // 验证类型
    if (!in_array($type, ['register', 'login', 'reset_password'])) {
        error('验证码类型错误');
    }

    // 注册时检查邮箱是否已存在
    if ($type === 'register') {
        $stmt = $db->prepare("SELECT id FROM app_members WHERE email = ?");
        $stmt->execute([$email]);
        if ($stmt->fetch()) {
            error('该邮箱已被注册');
        }
    }

    // 登录或重置密码时检查邮箱是否存在
    if (in_array($type, ['login', 'reset_password'])) {
        $stmt = $db->prepare("SELECT id FROM app_members WHERE email = ?");
        $stmt->execute([$email]);
        if (!$stmt->fetch()) {
            error('该邮箱未注册');
        }
    }

    $ip = MemberHelper::getClientIp();

    // 检查60秒内是否已发送
    $stmt = $db->prepare("
        SELECT id FROM app_email_codes
        WHERE email = ? AND created_at > DATE_SUB(NOW(), INTERVAL 60 SECOND)
        ORDER BY id DESC LIMIT 1
    ");
    $stmt->execute([$email]);
    if ($stmt->fetch()) {
        error('请60秒后再试');
    }

    // 从配置中获取每日发送次数限制
    $stmt = $db->prepare("SELECT config_value FROM app_config WHERE config_key = 'email_daily_limit'");
    $stmt->execute();
    $limitConfig = $stmt->fetch();
    $dailyLimit = $limitConfig ? intval($limitConfig['config_value']) : 10; // 默认10次

    // 检查是否启用次数限制
    $stmt = $db->prepare("SELECT config_value FROM app_config WHERE config_key = 'email_limit_enabled'");
    $stmt->execute();
    $limitEnabledConfig = $stmt->fetch();
    $limitEnabled = $limitEnabledConfig ? intval($limitEnabledConfig['config_value']) : 1; // 默认启用

    // 如果启用了次数限制，则检查今天发送次数
    if ($limitEnabled) {
        $stmt = $db->prepare("
            SELECT COUNT(*) as count FROM app_email_codes
            WHERE email = ? AND DATE(created_at) = CURDATE()
        ");
        $stmt->execute([$email]);
        $result = $stmt->fetch();
        if ($result['count'] >= $dailyLimit) {
            error("今日发送次数已达上限（{$dailyLimit}次）");
        }
    }

    // 生成验证码
    $code = MemberHelper::generateEmailCode();
    $expireTime = date('Y-m-d H:i:s', time() + 300); // 5分钟后过期

    // 保存验证码
    $stmt = $db->prepare("
        INSERT INTO app_email_codes (type, email, code, expire_time, ip)
        VALUES (?, ?, ?, ?, ?)
    ");
    $stmt->execute([$type, $email, $code, $expireTime, $ip]);

    // 发送邮件
    $sent = MemberHelper::sendEmailCode($email, $code, $type);

    if ($sent) {
        success([
            'expire_time' => $expireTime
        ], '验证码已发送');
    } else {
        error('验证码发送失败，请稍后重试');
    }

} catch (Exception $e) {
    error('系统错误：' . $e->getMessage());
}
