<?php
require_once 'config.php';

$db = Database::getInstance()->getConnection();
$method = $_SERVER['REQUEST_METHOD'];

switch ($method) {
    case 'GET':
        if (isset($_GET['id'])) {
            // 获取单个轮播图
            $stmt = $db->prepare("SELECT * FROM app_banners WHERE id = ?");
            $stmt->execute([$_GET['id']]);
            $banner = $stmt->fetch();
            success($banner);
        } else {
            // 获取轮播图列表
            $page = $_GET['page'] ?? 1;
            $pageSize = $_GET['pageSize'] ?? 20;
            $position = $_GET['position'] ?? '';
            $status = $_GET['status'] ?? '';
            $offset = ($page - 1) * $pageSize;

            $where = [];
            $params = [];
            if ($position) {
                $where[] = "position = ?";
                $params[] = $position;
            }
            if ($status !== '') {
                $where[] = "status = ?";
                $params[] = $status;
            }
            $whereStr = $where ? 'WHERE ' . implode(' AND ', $where) : '';

            $stmt = $db->prepare("SELECT COUNT(*) as total FROM app_banners $whereStr");
            $stmt->execute($params);
            $total = $stmt->fetch()['total'];

            $stmt = $db->prepare("SELECT * FROM app_banners $whereStr ORDER BY sort_order ASC, id DESC LIMIT $offset, $pageSize");
            $stmt->execute($params);
            $list = $stmt->fetchAll();

            success(['list' => $list, 'total' => $total, 'page' => (int)$page, 'pageSize' => (int)$pageSize]);
        }
        break;

    case 'POST':
        $data = getInput();
        if (empty($data['title']) || empty($data['image_url'])) {
            error('标题和图片不能为空');
        }

        $stmt = $db->prepare("INSERT INTO app_banners (title, image_url, link_url, link_type, position, sort_order, status, start_time, end_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
        $stmt->execute([
            $data['title'],
            $data['image_url'],
            $data['link_url'] ?? '',
            $data['link_type'] ?? 'none',
            $data['position'] ?? 'home',
            $data['sort_order'] ?? 0,
            $data['status'] ?? 1,
            $data['start_time'] ?? null,
            $data['end_time'] ?? null
        ]);
        success(['id' => $db->lastInsertId()], '添加成功');
        break;

    case 'PUT':
        $data = getInput();
        if (empty($data['id'])) {
            error('ID不能为空');
        }

        $stmt = $db->prepare("UPDATE app_banners SET title = ?, image_url = ?, link_url = ?, link_type = ?, position = ?, sort_order = ?, status = ?, start_time = ?, end_time = ? WHERE id = ?");
        $stmt->execute([
            $data['title'],
            $data['image_url'],
            $data['link_url'] ?? '',
            $data['link_type'] ?? 'none',
            $data['position'] ?? 'home',
            $data['sort_order'] ?? 0,
            $data['status'] ?? 1,
            $data['start_time'] ?? null,
            $data['end_time'] ?? null,
            $data['id']
        ]);
        success([], '更新成功');
        break;

    case 'DELETE':
        $id = $_GET['id'] ?? 0;
        if (!$id) {
            error('ID不能为空');
        }

        $stmt = $db->prepare("DELETE FROM app_banners WHERE id = ?");
        $stmt->execute([$id]);
        success([], '删除成功');
        break;

    default:
        error('不支持的请求方法', 405);
}
