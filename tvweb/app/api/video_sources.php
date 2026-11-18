<?php
require_once 'config.php';

$db = Database::getInstance()->getConnection();
$method = $_SERVER['REQUEST_METHOD'];

switch ($method) {
    case 'GET':
        if (isset($_GET['id'])) {
            // 获取单个视频源
            $stmt = $db->prepare("SELECT * FROM app_video_sources WHERE id = ?");
            $stmt->execute([$_GET['id']]);
            $source = $stmt->fetch();
            success($source);
        } else {
            // 获取视频源列表
            $page = $_GET['page'] ?? 1;
            $pageSize = $_GET['pageSize'] ?? 100;
            $status = $_GET['status'] ?? '';
            $offset = ($page - 1) * $pageSize;

            $where = '';
            $params = [];
            if ($status !== '') {
                $where = "WHERE status = ?";
                $params = [$status];
            }

            $stmt = $db->prepare("SELECT COUNT(*) as total FROM app_video_sources $where");
            $stmt->execute($params);
            $total = $stmt->fetch()['total'];

            $stmt = $db->prepare("SELECT * FROM app_video_sources $where ORDER BY sort_order ASC, id ASC LIMIT $offset, $pageSize");
            $stmt->execute($params);
            $list = $stmt->fetchAll();

            success(['list' => $list, 'total' => $total, 'page' => (int)$page, 'pageSize' => (int)$pageSize]);
        }
        break;

    case 'POST':
        $data = getInput();
        if (empty($data['source_key']) || empty($data['source_name']) || empty($data['base_url'])) {
            error('源标识、源名称和基础URL不能为空');
        }

        $stmt = $db->prepare("INSERT INTO app_video_sources (source_key, source_name, source_type, base_url, status, sort_order, description) VALUES (?, ?, ?, ?, ?, ?, ?)");
        try {
            $stmt->execute([
                $data['source_key'],
                $data['source_name'],
                $data['source_type'] ?? 'API',
                $data['base_url'],
                $data['status'] ?? 1,
                $data['sort_order'] ?? 0,
                $data['description'] ?? ''
            ]);
            success(['id' => $db->lastInsertId()], '添加成功');
        } catch (PDOException $e) {
            error('源标识已存在');
        }
        break;

    case 'PUT':
        $data = getInput();
        if (empty($data['id'])) {
            error('ID不能为空');
        }

        $stmt = $db->prepare("UPDATE app_video_sources SET source_name = ?, source_type = ?, base_url = ?, status = ?, sort_order = ?, description = ? WHERE id = ?");
        $stmt->execute([
            $data['source_name'],
            $data['source_type'] ?? 'API',
            $data['base_url'],
            $data['status'] ?? 1,
            $data['sort_order'] ?? 0,
            $data['description'] ?? '',
            $data['id']
        ]);
        success([], '更新成功');
        break;

    case 'DELETE':
        $id = $_GET['id'] ?? 0;
        if (!$id) {
            error('ID不能为空');
        }

        $stmt = $db->prepare("DELETE FROM app_video_sources WHERE id = ?");
        $stmt->execute([$id]);
        success([], '删除成功');
        break;

    default:
        error('不支持的请求方法', 405);
}
