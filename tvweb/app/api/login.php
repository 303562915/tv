<?php
require_once 'config.php';

$db = Database::getInstance()->getConnection();
$data = getInput();

if (empty($data['username']) || empty($data['password'])) {
    error('用户名和密码不能为空');
}

$stmt = $db->prepare("SELECT id, username, email, role, status FROM app_users WHERE username = ? AND password = ?");
$stmt->execute([$data['username'], md5($data['password'])]);
$user = $stmt->fetch();

if (!$user) {
    error('用户名或密码错误');
}

if ($user['status'] != 1) {
    error('账号已被禁用');
}

// 更新登录信息
$ip = $_SERVER['REMOTE_ADDR'] ?? '';
$stmt = $db->prepare("UPDATE app_users SET last_login_time = NOW(), last_login_ip = ? WHERE id = ?");
$stmt->execute([$ip, $user['id']]);

// 生成token（简单实现）
$token = md5($user['id'] . $user['username'] . time());

success([
    'token' => $token,
    'user' => $user
], '登录成功');
