<?php
require_once '../api/config.php';
$db = Database::getInstance()->getConnection();

// 处理表单提交
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $action = $_POST['action'] ?? '';
    
    if ($action === 'add') {
        $stmt = $db->prepare("INSERT INTO app_banners (title, image_url, link_url, position, sort_order, status) VALUES (?, ?, ?, 'profile', ?, 1)");
        $stmt->execute([
            $_POST['title'],
            $_POST['image_url'],
            $_POST['link_url'] ?? '',
            $_POST['sort_order'] ?? 0
        ]);
        $message = '添加成功';
    } elseif ($action === 'edit') {
        $stmt = $db->prepare("UPDATE app_banners SET title = ?, image_url = ?, link_url = ?, sort_order = ?, status = ? WHERE id = ?");
        $stmt->execute([
            $_POST['title'],
            $_POST['image_url'],
            $_POST['link_url'] ?? '',
            $_POST['sort_order'] ?? 0,
            $_POST['status'] ?? 1,
            $_POST['id']
        ]);
        $message = '更新成功';
    } elseif ($action === 'delete') {
        $stmt = $db->prepare("DELETE FROM app_banners WHERE id = ?");
        $stmt->execute([$_POST['id']]);
        $message = '删除成功';
    }
}

// 获取轮播图列表
$stmt = $db->prepare("SELECT * FROM app_banners WHERE position = 'profile' ORDER BY sort_order ASC, id DESC");
$stmt->execute();
$banners = $stmt->fetchAll();
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>我的页面轮播管理</title>
    <link rel="stylesheet" href="../style.css">
    <style>
        .container { max-width: 1200px; margin: 20px auto; padding: 20px; }
        .form-group { margin-bottom: 15px; }
        .form-group label { display: block; margin-bottom: 5px; font-weight: bold; }
        .form-group input, .form-group select { width: 100%; padding: 8px; border: 1px solid #ddd; border-radius: 4px; }
        .btn { padding: 10px 20px; border: none; border-radius: 4px; cursor: pointer; margin-right: 10px; }
        .btn-primary { background: #007bff; color: white; }
        .btn-success { background: #28a745; color: white; }
        .btn-danger { background: #dc3545; color: white; }
        .btn-secondary { background: #6c757d; color: white; }
        table { width: 100%; border-collapse: collapse; margin-top: 20px; }
        table th, table td { padding: 12px; text-align: left; border-bottom: 1px solid #ddd; }
        table th { background: #f8f9fa; font-weight: bold; }
        .banner-img { max-width: 100px; height: auto; }
        .message { padding: 10px; margin-bottom: 20px; border-radius: 4px; background: #d4edda; color: #155724; }
        .form-card { background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); margin-bottom: 20px; }
        .status-badge { padding: 4px 8px; border-radius: 4px; font-size: 12px; }
        .status-active { background: #d4edda; color: #155724; }
        .status-inactive { background: #f8d7da; color: #721c24; }
    </style>
</head>
<body>
    <div class="container">
        <h1>我的页面轮播管理</h1>
        <p><a href="../dashboard.php">← 返回首页</a></p>
        
        <?php if (isset($message)): ?>
            <div class="message"><?= htmlspecialchars($message) ?></div>
        <?php endif; ?>

        <div class="form-card">
            <h2>添加新轮播</h2>
            <form method="POST">
                <input type="hidden" name="action" value="add">
                <div class="form-group">
                    <label>标题：</label>
                    <input type="text" name="title" required>
                </div>
                <div class="form-group">
                    <label>图片URL：</label>
                    <input type="text" name="image_url" required placeholder="https://example.com/image.jpg">
                </div>
                <div class="form-group">
                    <label>跳转链接：</label>
                    <input type="text" name="link_url" placeholder="https://example.com">
                </div>
                <div class="form-group">
                    <label>排序（数字越小越靠前）：</label>
                    <input type="number" name="sort_order" value="0">
                </div>
                <button type="submit" class="btn btn-primary">添加轮播</button>
            </form>
        </div>

        <h2>轮播列表</h2>
        <table>
            <thead>
                <tr>
                    <th>ID</th>
                    <th>图片</th>
                    <th>标题</th>
                    <th>跳转链接</th>
                    <th>排序</th>
                    <th>状态</th>
                    <th>点击次数</th>
                    <th>操作</th>
                </tr>
            </thead>
            <tbody>
                <?php foreach ($banners as $banner): ?>
                <tr>
                    <td><?= $banner['id'] ?></td>
                    <td><img src="<?= htmlspecialchars($banner['image_url']) ?>" class="banner-img" alt=""></td>
                    <td><?= htmlspecialchars($banner['title']) ?></td>
                    <td><?= htmlspecialchars($banner['link_url']) ?></td>
                    <td><?= $banner['sort_order'] ?></td>
                    <td>
                        <span class="status-badge <?= $banner['status'] ? 'status-active' : 'status-inactive' ?>">
                            <?= $banner['status'] ? '启用' : '禁用' ?>
                        </span>
                    </td>
                    <td><?= $banner['click_count'] ?></td>
                    <td>
                        <button onclick="editBanner(<?= $banner['id'] ?>)" class="btn btn-success">编辑</button>
                        <form method="POST" style="display:inline;" onsubmit="return confirm('确定删除？')">
                            <input type="hidden" name="action" value="delete">
                            <input type="hidden" name="id" value="<?= $banner['id'] ?>">
                            <button type="submit" class="btn btn-danger">删除</button>
                        </form>
                    </td>
                </tr>
                <?php endforeach; ?>
            </tbody>
        </table>
    </div>

    <script>
    function editBanner(id) {
        // 简单实现：跳转到编辑页面或使用模态框
        alert('编辑功能：ID ' + id);
    }
    </script>
</body>
</html>
