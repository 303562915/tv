<?php
require_once 'config.php';

$db = Database::getInstance()->getConnection();
$method = $_SERVER['REQUEST_METHOD'];

switch ($method) {
    case 'GET':
        if (isset($_GET['id'])) {
            // 获取单个用户
            $stmt = $db->prepare("SELECT id, username, email, phone, role, status, last_login_time, last_login_ip, created_at FROM app_users WHERE id = ?");
            $stmt->execute([$_GET['id']]);
            $user = $stmt->fetch();
            success($user);
        } else {
            // 获取用户列表
            $page = $_GET['page'] ?? 1;
            $pageSize = $_GET['pageSize'] ?? 20;
            $keyword = $_GET['keyword'] ?? '';
            $offset = ($page - 1) * $pageSize;

            $where = '';
            $params = [];
            if ($keyword) {
                $where = "WHERE username LIKE ? OR email LIKE ? OR phone LIKE ?";
                $params = ["%$keyword%", "%$keyword%", "%$keyword%"];
            }

            $stmt = $db->prepare("SELECT COUNT(*) as total FROM app_users $where");
            $stmt->execute($params);
            $total = $stmt->fetch()['total'];

            $stmt = $db->prepare("SELECT id, username, email, phone, role, status, last_login_time, last_login_ip, created_at FROM app_users $where ORDER BY id DESC LIMIT $offset, $pageSize");
            $stmt->execute($params);
            $list = $stmt->fetchAll();

            success(['list' => $list, 'total' => $total, 'page' => (int)$page, 'pageSize' => (int)$pageSize]);
        }
        break;

    case 'POST':
        $data = getInput();
        if (empty($data['username']) || empty($data['password'])) {
            error('用户名和密码不能为空');
        }

        $stmt = $db->prepare("INSERT INTO app_users (username, password, email, phone, role, status) VALUES (?, ?, ?, ?, ?, ?)");
        try {
            $stmt->execute([
                $data['username'],
                md5($data['password']),
                $data['email'] ?? '',
                $data['phone'] ?? '',
                $data['role'] ?? 'user',
                $data['status'] ?? 1
            ]);
            success(['id' => $db->lastInsertId()], '添加成功');
        } catch (PDOException $e) {
            error('用户名已存在');
        }
        break;

    case 'PUT':
        $data = getInput();
        if (empty($data['id'])) {
            error('ID不能为空');
        }

        $fields = [];
        $params = [];

        if (isset($data['username'])) {
            $fields[] = "username = ?";
            $params[] = $data['username'];
        }
        if (isset($data['password']) && !empty($data['password'])) {
            $fields[] = "password = ?";
            $params[] = md5($data['password']);
        }
        if (isset($data['email'])) {
            $fields[] = "email = ?";
            $params[] = $data['email'];
        }
        if (isset($data['phone'])) {
            $fields[] = "phone = ?";
            $params[] = $data['phone'];
        }
        if (isset($data['role'])) {
            $fields[] = "role = ?";
            $params[] = $data['role'];
        }
        if (isset($data['status'])) {
            $fields[] = "status = ?";
            $params[] = $data['status'];
        }

        $params[] = $data['id'];
        $sql = "UPDATE app_users SET " . implode(', ', $fields) . " WHERE id = ?";
        $stmt = $db->prepare($sql);
        $stmt->execute($params);
        success([], '更新成功');
        break;

    case 'DELETE':
        $id = $_GET['id'] ?? 0;
        if (!$id) {
            error('ID不能为空');
        }

        $stmt = $db->prepare("DELETE FROM app_users WHERE id = ?");
        $stmt->execute([$id]);
        success([], '删除成功');
        break;

    default:
        error('不支持的请求方法', 405);
}
