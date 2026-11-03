<?php
require_once 'config.php';

$db = Database::getInstance()->getConnection();
$method = $_SERVER['REQUEST_METHOD'];

switch ($method) {
    case 'GET':
        if (isset($_GET['tree'])) {
            // 获取树形菜单列表
            $stmt = $db->query("SELECT * FROM app_menu ORDER BY sort ASC, id ASC");
            $list = $stmt->fetchAll();

            // 构建树形结构
            $tree = buildMenuTree($list);
            success(['list' => $tree]);
        } else {
            // 获取所有菜单（用于侧边栏显示）
            $stmt = $db->query("SELECT * FROM app_menu ORDER BY sort ASC, id ASC");
            $list = $stmt->fetchAll();
            success(['list' => $list]);
        }
        break;

    case 'POST':
        $data = getInput();
        if (empty($data['name'])) {
            error('菜单名称不能为空');
        }

        $stmt = $db->prepare("INSERT INTO app_menu (parent_id, name, icon, path, sort, permission, status) VALUES (?, ?, ?, ?, ?, ?, ?)");
        $stmt->execute([
            $data['parent_id'] ?? 0,
            $data['name'],
            $data['icon'] ?? '',
            $data['path'] ?? '',
            $data['sort'] ?? 0,
            $data['permission'] ?? '',
            $data['status'] ?? 1
        ]);
        success(['id' => $db->lastInsertId()], '添加成功');
        break;

    case 'PUT':
        $data = getInput();
        if (empty($data['id'])) {
            error('ID不能为空');
        }

        $stmt = $db->prepare("UPDATE app_menu SET parent_id = ?, name = ?, icon = ?, path = ?, sort = ?, permission = ?, status = ? WHERE id = ?");
        $stmt->execute([
            $data['parent_id'] ?? 0,
            $data['name'],
            $data['icon'] ?? '',
            $data['path'] ?? '',
            $data['sort'] ?? 0,
            $data['permission'] ?? '',
            $data['status'] ?? 1,
            $data['id']
        ]);
        success([], '更新成功');
        break;

    case 'DELETE':
        $id = $_GET['id'] ?? 0;
        if (!$id) {
            error('ID不能为空');
        }

        // 检查是否有子菜单
        $stmt = $db->prepare("SELECT COUNT(*) FROM app_menu WHERE parent_id = ?");
        $stmt->execute([$id]);
        if ($stmt->fetchColumn() > 0) {
            error('请先删除子菜单');
        }

        $stmt = $db->prepare("DELETE FROM app_menu WHERE id = ?");
        $stmt->execute([$id]);
        success([], '删除成功');
        break;

    default:
        error('不支持的请求方法', 405);
}

// 构建树形结构
function buildMenuTree($list, $parentId = 0) {
    $tree = [];
    foreach ($list as $item) {
        // 确保类型一致，转换为整数比较
        if (intval($item['parent_id']) == intval($parentId)) {
            $children = buildMenuTree($list, $item['id']);
            if ($children) {
                $item['children'] = $children;
            }
            $tree[] = $item;
        }
    }
    return $tree;
}
