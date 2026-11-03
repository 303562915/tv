<?php
require_once 'config.php';

$db = Database::getInstance()->getConnection();
$method = $_SERVER['REQUEST_METHOD'];

switch ($method) {
    case 'GET':
        if (isset($_GET['key'])) {
            // 获取单个配置
            $stmt = $db->prepare("SELECT * FROM app_config WHERE config_key = ?");
            $stmt->execute([$_GET['key']]);
            $config = $stmt->fetch();
            success($config);
        } else {
            // 获取配置列表
            $group = $_GET['group'] ?? '';

            // 检查表结构，判断是否有group_name字段
            $columns = $db->query("SHOW COLUMNS FROM app_config")->fetchAll(PDO::FETCH_COLUMN);
            $hasGroupName = in_array('group_name', $columns);

            if ($hasGroupName) {
                $where = $group ? "WHERE group_name = ?" : '';
                $params = $group ? [$group] : [];
                $stmt = $db->prepare("SELECT * FROM app_config $where ORDER BY group_name, sort_order");
            } else {
                $where = '';
                $params = [];
                $stmt = $db->prepare("SELECT * FROM app_config ORDER BY sort_order");
            }

            $stmt->execute($params);
            $list = $stmt->fetchAll();

            success(['list' => $list]);
        }
        break;

    case 'POST':
        $data = getInput();
        if (empty($data['config_key'])) {
            error('配置键不能为空');
        }

        $stmt = $db->prepare("INSERT INTO app_config (config_key, config_value, config_type, description, group_name, sort_order) VALUES (?, ?, ?, ?, ?, ?)");
        try {
            $stmt->execute([
                $data['config_key'],
                $data['config_value'] ?? '',
                $data['config_type'] ?? 'text',
                $data['description'] ?? '',
                $data['group_name'] ?? 'default',
                $data['sort_order'] ?? 0
            ]);
            success(['id' => $db->lastInsertId()], '添加成功');
        } catch (PDOException $e) {
            error('配置键已存在');
        }
        break;

    case 'PUT':
        $data = getInput();
        if (empty($data['id'])) {
            error('ID不能为空');
        }

        $stmt = $db->prepare("UPDATE app_config SET config_value = ?, config_type = ?, description = ?, group_name = ?, sort_order = ? WHERE id = ?");
        $stmt->execute([
            $data['config_value'] ?? '',
            $data['config_type'] ?? 'text',
            $data['description'] ?? '',
            $data['group_name'] ?? 'default',
            $data['sort_order'] ?? 0,
            $data['id']
        ]);
        success([], '更新成功');
        break;

    case 'DELETE':
        $id = $_GET['id'] ?? 0;
        if (!$id) {
            error('ID不能为空');
        }

        $stmt = $db->prepare("DELETE FROM app_config WHERE id = ?");
        $stmt->execute([$id]);
        success([], '删除成功');
        break;

    default:
        error('不支持的请求方法', 405);
}
