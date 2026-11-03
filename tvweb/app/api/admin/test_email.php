<?php
/**
 * 测试邮件发送API
 * POST /api/admin/test_email.php
 */

require_once __DIR__ . '/../config.php';
require_once __DIR__ . '/../MemberHelper.php';

header('Content-Type: application/json; charset=utf-8');

try {
    $input = json_decode(file_get_contents('php://input'), true);
    $email = $input['email'] ?? '';

    if (empty($email)) {
        error('请输入邮箱地址');
    }

    if (!filter_var($email, FILTER_VALIDATE_EMAIL)) {
        error('邮箱格式不正确');
    }

    $testCode = '888888';
    $result = MemberHelper::sendEmailCode($email, $testCode, 'register');

    if ($result) {
        success(null, '测试邮件已发送');
    } else {
        error('邮件发送失败');
    }

} catch (Exception $e) {
    error('系统错误：' . $e->getMessage());
}
