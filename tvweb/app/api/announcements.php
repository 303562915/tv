<?php
require_once 'config.php';

$db = Database::getInstance()->getConnection();
$method = $_SERVER['REQUEST_METHOD'];

switch ($method) {
    case 'GET':
        if (isset($_GET['id'])) {
            // 获取单个公告
            $stmt = $db->prepare("SELECT * FROM app_announcements WHERE id = ?");
            $stmt->execute([$_GET['id']]);
            $announcement = $stmt->fetch();

            // 增加查看次数
            $stmt = $db->prepare("UPDATE app_announcements SET view_count = view_count + 1 WHERE id = ?");
            $stmt->execute([$_GET['id']]);

            success($announcement);
        } else {
            // 获取公告列表
            $page = $_GET['page'] ?? 1;
            $pageSize = $_GET['pageSize'] ?? 20;
            $status = $_GET['status'] ?? '';
            $offset = ($page - 1) * $pageSize;

            $where = '';
            $params = [];
            if ($status !== '') {
                $where = "WHERE status = ?";
                $params = [$status];
            }

            $stmt = $db->prepare("SELECT COUNT(*) as total FROM app_announcements $where");
            $stmt->execute($params);
            $total = $stmt->fetch()['total'];

            $stmt = $db->prepare("SELECT * FROM app_announcements $where ORDER BY priority DESC, id DESC LIMIT $offset, $pageSize");
            $stmt->execute($params);
            $list = $stmt->fetchAll();

            success(['list' => $list, 'total' => $total, 'page' => (int)$page, 'pageSize' => (int)$pageSize]);
        }
        break;

    case 'POST':
        $data = getInput();
        if (empty($data['title']) || empty($data['content'])) {
            error('标题和内容不能为空');
        }

        $stmt = $db->prepare("INSERT INTO app_announcements (title, content, type, priority, status, start_time, end_time) VALUES (?, ?, ?, ?, ?, ?, ?)");
        $stmt->execute([
            $data['title'],
            $data['content'],
            $data['type'] ?? 'info',
            $data['priority'] ?? 0,
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

        $stmt = $db->prepare("UPDATE app_announcements SET title = ?, content = ?, type = ?, priority = ?, status = ?, start_time = ?, end_time = ? WHERE id = ?");
        $stmt->execute([
            $data['title'],
            $data['content'],
            $data['type'] ?? 'info',
            $data['priority'] ?? 0,
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

        $stmt = $db->prepare("DELETE FROM app_announcements WHERE id = ?");
        $stmt->execute([$id]);
        success([], '删除成功');
        break;

    default:
        error('不支持的请求方法', 405);
}
