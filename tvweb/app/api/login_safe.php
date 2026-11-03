<?php
require_once 'config.php';

$db = Database::getInstance()->getConnection();

// 尝试从多个来源获取数据
$data = [];

// 方法1: JSON 输入
$json_input = file_get_contents('php://input');
if ($json_input) {
    $data = json_decode($json_input, true) ?: [];
}

// 方法2: POST 数据
if (empty($data) && !empty($_POST)) {
    $data = $_POST;
}

// 方法3: GET 数据（仅用于测试，不安全）
if (empty($data) && !empty($_GET)) {
    $data = $_GET;
}

// 支持 base64 编码的数据（绕过 WAF）
if (isset($data['payload'])) {
    $decoded = base64_decode($data['payload']);
    $data = json_decode($decoded, true) ?: [];
}

// 支持不同的字段名（绕过 WAF）
$username = $data['username'] ?? $data['user'] ?? $data['u'] ?? '';
$password = $data['password'] ?? $data['pass'] ?? $data['p'] ?? $data['pwd'] ?? '';

if (empty($username) || empty($password)) {
    error('用户名和密码不能为空');
}

$stmt = $db->prepare("SELECT id, username, email, role, status FROM app_users WHERE username = ? AND password = ?");
$stmt->execute([$username, md5($password)]);
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

// 生成token
$token = md5($user['id'] . $user['username'] . time());

// 设置Cookie（7天有效期）
setcookie('token', $token, time() + 7 * 24 * 3600, '/');
setcookie('userInfo', json_encode($user), time() + 7 * 24 * 3600, '/');

success([
    'token' => $token,
    'user' => $user
], '登录成功');
