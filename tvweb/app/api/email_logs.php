<?php
require_once 'config.php';

$db = Database::getInstance()->getConnection();
$method = $_SERVER['REQUEST_METHOD'];

switch ($method) {
    case 'GET':
        // 获取邮箱记录列表
        $page = isset($_GET['page']) ? intval($_GET['page']) : 1;
        $pageSize = isset($_GET['pageSize']) ? intval($_GET['pageSize']) : 20;
        $offset = ($page - 1) * $pageSize;

        $keyword = $_GET['keyword'] ?? '';
        $emailType = $_GET['email_type'] ?? '';
        $status = $_GET['status'] ?? '';

        // 构建查询条件
        $where = [];
        $params = [];

        if ($keyword) {
            $where[] = "(el.recipient_email LIKE ? OR u.username LIKE ?)";
            $params[] = "%{$keyword}%";
            $params[] = "%{$keyword}%";
        }

        if ($emailType) {
            $where[] = "el.email_type = ?";
            $params[] = $emailType;
        }

        if ($status !== '') {
            $where[] = "el.status = ?";
            $params[] = $status;
        }

        $whereSQL = $where ? 'WHERE ' . implode(' AND ', $where) : '';

        // 获取总数
        $countSQL = "SELECT COUNT(*) FROM app_email_logs el
                     LEFT JOIN app_users u ON el.user_id = u.id
                     {$whereSQL}";
        $stmt = $db->prepare($countSQL);
        $stmt->execute($params);
        $total = $stmt->fetchColumn();

        // 获取列表
        $listSQL = "SELECT el.*, u.username
                    FROM app_email_logs el
                    LEFT JOIN app_users u ON el.user_id = u.id
                    {$whereSQL}
                    ORDER BY el.created_at DESC
                    LIMIT {$pageSize} OFFSET {$offset}";
        $stmt = $db->prepare($listSQL);
        $stmt->execute($params);
        $list = $stmt->fetchAll();

        success(['list' => $list, 'total' => $total]);
        break;

    case 'POST':
        // 添加邮箱记录（通常由系统自动调用）
        $data = getInput();

        if (empty($data['recipient_email']) || empty($data['email_type'])) {
            error('收件人邮箱和邮件类型不能为空');
        }

        $stmt = $db->prepare("INSERT INTO app_email_logs
            (user_id, recipient_email, email_type, subject, content, status, error_message)
            VALUES (?, ?, ?, ?, ?, ?, ?)");
        $stmt->execute([
            $data['user_id'] ?? null,
            $data['recipient_email'],
            $data['email_type'],
            $data['subject'] ?? '',
            $data['content'] ?? '',
            $data['status'] ?? 0,
            $data['error_message'] ?? ''
        ]);

        success(['id' => $db->lastInsertId()], '记录添加成功');
        break;

    case 'DELETE':
        // 删除邮箱记录
        $id = $_GET['id'] ?? 0;
        if (!$id) {
            error('ID不能为空');
        }

        $stmt = $db->prepare("DELETE FROM app_email_logs WHERE id = ?");
        $stmt->execute([$id]);
        success([], '删除成功');
        break;

    default:
        error('不支持的请求方法', 405);
}
