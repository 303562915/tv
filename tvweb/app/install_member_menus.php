<?php
/**
 * 添加会员管理菜单
 */

require_once 'api/config.php';

header('Content-Type: text/html; charset=utf-8');

$db = Database::getInstance()->getConnection();

?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>添加会员管理菜单</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: 'Microsoft YaHei', Arial, sans-serif; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); min-height: 100vh; display: flex; align-items: center; justify-content: center; padding: 20px; }
        .container { background: white; border-radius: 10px; box-shadow: 0 10px 40px rgba(0,0,0,0.2); max-width: 800px; width: 100%; padding: 40px; }
        h1 { color: #333; margin-bottom: 30px; text-align: center; font-size: 28px; }
        .step { background: #f5f7fa; padding: 20px; border-radius: 8px; margin-bottom: 20px; border-left: 4px solid #409EFF; }
        .step-title { font-size: 18px; font-weight: bold; color: #333; margin-bottom: 10px; }
        .step-content { color: #666; line-height: 1.8; }
        .success { background: #f0f9ff; border-left-color: #67C23A; }
        .success .step-title { color: #67C23A; }
        .error { background: #fef0f0; border-left-color: #F56C6C; }
        .error .step-title { color: #F56C6C; }
        .info { background: #f4f4f5; border-left-color: #909399; }
        .info .step-title { color: #909399; }
        .btn { display: inline-block; padding: 12px 30px; background: #409EFF; color: white; text-decoration: none; border-radius: 5px; margin-top: 20px; transition: all 0.3s; border: none; cursor: pointer; font-size: 16px; }
        .btn:hover { background: #66b1ff; transform: translateY(-2px); box-shadow: 0 4px 12px rgba(64, 158, 255, 0.4); }
        .btn-success { background: #67C23A; }
        .btn-success:hover { background: #85ce61; box-shadow: 0 4px 12px rgba(103, 194, 58, 0.4); }
        .menu-list { list-style: none; padding-left: 20px; }
        .menu-list li { padding: 5px 0; color: #666; }
        .menu-list li:before { content: "✓ "; color: #67C23A; font-weight: bold; margin-right: 5px; }
        .actions { text-align: center; margin-top: 30px; }
    </style>
</head>
<body>
    <div class="container">
        <h1>🎯 添加会员管理菜单</h1>

        <?php
        try {
            // 检查是否已经安装过
            $stmt = $db->query("SELECT COUNT(*) as count FROM app_menu WHERE name = '会员管理'");
            $result = $stmt->fetch();

            if ($result['count'] > 0) {
                echo '<div class="step info">';
                echo '<div class="step-title">⚠️ 菜单已存在</div>';
                echo '<div class="step-content">会员管理菜单已经存在，无需重复添加。</div>';
                echo '</div>';
            } else {
                // 开始安装
                $db->beginTransaction();

                // 1. 添加会员管理父菜单
                echo '<div class="step">';
                echo '<div class="step-title">步骤 1: 添加会员管理父菜单</div>';
                echo '<div class="step-content">';

                $stmt = $db->prepare("
                    INSERT INTO app_menu (parent_id, name, path, icon, sort, permission, status, created_at, updated_at)
                    VALUES (0, '会员管理', '', 'el-icon-s-custom', 30, 'admin', '1', NOW(), NOW())
                ");
                $stmt->execute();
                $memberMenuId = $db->lastInsertId();

                echo "✓ 会员管理父菜单添加成功 (ID: {$memberMenuId})";
                echo '</div></div>';

                // 2. 添加会员管理子菜单
                echo '<div class="step">';
                echo '<div class="step-title">步骤 2: 添加会员管理子菜单</div>';
                echo '<div class="step-content">';

                $subMenus = [
                    ['会员列表', 'member_list.php', 'el-icon-user', 1],
                    ['积分记录', 'points_logs.php', 'el-icon-money', 2],
                    ['积分记录', 'balance_logs.php', 'el-icon-wallet', 3],
                    ['登录日志', 'login_logs.php', 'el-icon-document', 4],
                    ['邀请记录', 'invite_logs.php', 'el-icon-share', 5]
                ];

                echo '<ul class="menu-list">';
                foreach ($subMenus as $menu) {
                    $stmt = $db->prepare("
                        INSERT INTO app_menu (parent_id, name, path, icon, sort, permission, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, 'admin', '1', NOW(), NOW())
                    ");
                    $stmt->execute([$memberMenuId, $menu[0], $menu[1], $menu[2], $menu[3]]);
                    echo "<li>{$menu[0]} ({$menu[1]})</li>";
                }
                echo '</ul>';

                echo '</div></div>';

                // 3. 添加系统设置菜单
                echo '<div class="step">';
                echo '<div class="step-title">步骤 3: 添加系统设置菜单</div>';
                echo '<div class="step-content">';

                // 检查系统设置菜单是否已存在
                $stmt = $db->query("SELECT COUNT(*) as count FROM app_menu WHERE name = '系统设置'");
                $result = $stmt->fetch();

                if ($result['count'] > 0) {
                    echo "⚠️ 系统设置菜单已存在，跳过";
                } else {
                    $stmt = $db->prepare("
                        INSERT INTO app_menu (parent_id, name, path, icon, sort, permission, status, created_at, updated_at)
                        VALUES (0, '系统设置', 'system_settings.php', 'el-icon-setting', 100, 'admin', '1', NOW(), NOW())
                    ");
                    $stmt->execute();
                    $settingsMenuId = $db->lastInsertId();
                    echo "✓ 系统设置菜单添加成功 (ID: {$settingsMenuId})";
                }

                echo '</div></div>';

                $db->commit();

                // 成功提示
                echo '<div class="step success">';
                echo '<div class="step-title">✅ 安装完成</div>';
                echo '<div class="step-content">';
                echo '所有菜单已成功添加到数据库！<br><br>';
                echo '<strong>新增菜单：</strong><br>';
                echo '<ul class="menu-list">';
                echo '<li>会员管理（父菜单）</li>';
                echo '<li style="padding-left: 20px;">会员列表</li>';
                echo '<li style="padding-left: 20px;">积分记录</li>';
                echo '<li style="padding-left: 20px;">积分记录</li>';
                echo '<li style="padding-left: 20px;">登录日志</li>';
                echo '<li style="padding-left: 20px;">邀请记录</li>';
                echo '<li>系统设置</li>';
                echo '</ul>';
                echo '</div></div>';
            }

            // 显示当前所有菜单
            echo '<div class="step info">';
            echo '<div class="step-title">📋 当前菜单列表</div>';
            echo '<div class="step-content">';

            $stmt = $db->query("
                SELECT m1.id, m1.name, m1.path, m1.icon, m1.sort, m1.parent_id,
                       m2.name as parent_name
                FROM app_menu m1
                LEFT JOIN app_menu m2 ON m1.parent_id = m2.id
                ORDER BY m1.parent_id, m1.sort
            ");
            $menus = $stmt->fetchAll();

            echo '<ul class="menu-list">';
            $currentParent = null;
            foreach ($menus as $menu) {
                if ($menu['parent_id'] == 0) {
                    if ($currentParent !== null) {
                        echo '</ul></li>';
                    }
                    $pathText = $menu['path'] ? $menu['path'] : '父菜单';
                    echo "<li><strong>{$menu['name']}</strong> ({$pathText})";
                    $currentParent = $menu['id'];

                    // 检查是否有子菜单
                    $hasChildren = false;
                    foreach ($menus as $m) {
                        if ($m['parent_id'] == $menu['id']) {
                            $hasChildren = true;
                            break;
                        }
                    }
                    if ($hasChildren) {
                        echo '<ul class="menu-list" style="margin-top: 5px;">';
                    }
                } else if ($menu['parent_id'] == $currentParent) {
                    echo "<li>{$menu['name']} ({$menu['path']})</li>";
                }
            }
            if ($currentParent !== null) {
                echo '</ul></li>';
            }
            echo '</ul>';

            echo '</div></div>';

        } catch (Exception $e) {
            if ($db->inTransaction()) {
                $db->rollBack();
            }

            echo '<div class="step error">';
            echo '<div class="step-title">❌ 安装失败</div>';
            echo '<div class="step-content">';
            echo '错误信息：' . htmlspecialchars($e->getMessage());
            echo '</div></div>';
        }
        ?>

        <div class="actions">
            <a href="index.html" class="btn btn-success">进入管理后台</a>
            <a href="javascript:location.reload()" class="btn">刷新页面</a>
        </div>
    </div>
</body>
</html>
