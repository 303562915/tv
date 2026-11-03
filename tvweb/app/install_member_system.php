<?php
/**
 * 会员系统数据库安装程序
 * 访问此页面自动创建所有数据库表
 */

require_once 'api/config.php';

header('Content-Type: text/html; charset=utf-8');

$db = Database::getInstance()->getConnection();

// 检查是否已安装
$checkInstalled = false;
try {
    $stmt = $db->query("SELECT COUNT(*) FROM app_members");
    $checkInstalled = true;
} catch (Exception $e) {
    $checkInstalled = false;
}

?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>会员系统安装</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: Arial, sans-serif; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); min-height: 100vh; padding: 20px; }
        .container { max-width: 800px; margin: 0 auto; background: white; border-radius: 10px; box-shadow: 0 10px 40px rgba(0,0,0,0.2); overflow: hidden; }
        .header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 30px; text-align: center; }
        .header h1 { font-size: 32px; margin-bottom: 10px; }
        .content { padding: 30px; }
        .status { padding: 15px; margin-bottom: 20px; border-radius: 5px; }
        .status.success { background: #d4edda; color: #155724; border: 1px solid #c3e6cb; }
        .status.error { background: #f8d7da; color: #721c24; border: 1px solid #f5c6cb; }
        .status.warning { background: #fff3cd; color: #856404; border: 1px solid #ffeaa7; }
        .status.info { background: #d1ecf1; color: #0c5460; border: 1px solid #bee5eb; }
        .btn { display: inline-block; padding: 12px 30px; background: #667eea; color: white; text-decoration: none; border-radius: 5px; border: none; cursor: pointer; font-size: 16px; }
        .btn:hover { background: #5568d3; }
        .btn-danger { background: #dc3545; }
        .btn-danger:hover { background: #c82333; }
        .log { background: #f8f9fa; padding: 15px; border-radius: 5px; margin-top: 20px; max-height: 400px; overflow-y: auto; }
        .log-item { padding: 8px; margin-bottom: 5px; border-left: 3px solid #667eea; background: white; }
        .log-item.success { border-left-color: #28a745; }
        .log-item.error { border-left-color: #dc3545; }
        table { width: 100%; border-collapse: collapse; margin-top: 20px; }
        table th, table td { padding: 12px; text-align: left; border-bottom: 1px solid #ddd; }
        table th { background: #f8f9fa; font-weight: bold; }
        .center { text-align: center; }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>🔐 会员系统安装程序</h1>
            <p>自动创建数据库表和初始数据</p>
        </div>

        <div class="content">
            <?php if ($checkInstalled): ?>
                <div class="status warning">
                    <strong>⚠️ 警告：</strong> 检测到会员系统已安装！
                    <p style="margin-top: 10px;">如果重新安装，将会删除所有现有数据。请谨慎操作！</p>
                </div>

                <div class="center">
                    <form method="POST" onsubmit="return confirm('确定要重新安装吗？这将删除所有现有数据！');">
                        <input type="hidden" name="action" value="reinstall">
                        <button type="submit" class="btn btn-danger">重新安装（删除所有数据）</button>
                        <a href="test_member_api.html" class="btn">前往测试页面</a>
                    </form>
                </div>

            <?php else: ?>
                <div class="status info">
                    <strong>ℹ️ 提示：</strong> 会员系统尚未安装，点击下方按钮开始安装。
                </div>

                <div class="center">
                    <form method="POST">
                        <input type="hidden" name="action" value="install">
                        <button type="submit" class="btn">开始安装</button>
                    </form>
                </div>
            <?php endif; ?>

            <?php
            // 处理安装
            if ($_SERVER['REQUEST_METHOD'] === 'POST' && isset($_POST['action'])) {
                $action = $_POST['action'];

                echo '<div class="log">';
                echo '<h3>安装日志：</h3>';

                try {
                    // 如果是重新安装，先删除旧表
                    if ($action === 'reinstall') {
                        $tables = ['app_points_logs', 'app_balance_logs', 'app_email_codes', 'app_login_logs', 'app_invitations', 'app_members'];
                        foreach ($tables as $table) {
                            try {
                                $db->exec("DROP TABLE IF EXISTS $table");
                                echo "<div class='log-item success'>✓ 删除表: $table</div>";
                            } catch (Exception $e) {
                                echo "<div class='log-item error'>✗ 删除表失败: $table - " . $e->getMessage() . "</div>";
                            }
                        }
                    }

                    // 创建会员表
                    $sql = "CREATE TABLE IF NOT EXISTS app_members (
                        id INT AUTO_INCREMENT PRIMARY KEY COMMENT '会员ID',
                        username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
                        password VARCHAR(255) NOT NULL COMMENT '密码',
                        email VARCHAR(100) UNIQUE COMMENT '邮箱',
                        nickname VARCHAR(50) COMMENT '昵称',
                        avatar VARCHAR(500) COMMENT '头像URL',
                        gender ENUM('male', 'female', 'unknown') DEFAULT 'unknown' COMMENT '性别',
                        birthday DATE COMMENT '生日',
                        invite_code VARCHAR(20) NOT NULL UNIQUE COMMENT '我的推荐码',
                        parent_invite_code VARCHAR(20) COMMENT '上级推荐码',
                        parent_id INT DEFAULT 0 COMMENT '上级会员ID',
                        invite_count INT DEFAULT 0 COMMENT '邀请人数',
                        level ENUM('normal', 'vip', 'svip') DEFAULT 'normal' COMMENT '会员等级',
                        vip_expire_time DATETIME COMMENT 'VIP到期时间',
                        points INT DEFAULT 0 COMMENT '当前积分',
                        total_points INT DEFAULT 0 COMMENT '累计获得积分',
                        balance DECIMAL(10,2) DEFAULT 0.00 COMMENT '积分',
                        frozen_balance DECIMAL(10,2) DEFAULT 0.00 COMMENT '冻结积分',
                        total_recharge DECIMAL(10,2) DEFAULT 0.00 COMMENT '累计充值',
                        total_withdraw DECIMAL(10,2) DEFAULT 0.00 COMMENT '累计提现',
                        total_commission DECIMAL(10,2) DEFAULT 0.00 COMMENT '累计佣金',
                        status ENUM('normal', 'frozen', 'banned') DEFAULT 'normal' COMMENT '账号状态',
                        is_email_verified TINYINT DEFAULT 0 COMMENT '邮箱是否验证',
                        last_login_time DATETIME COMMENT '最后登录时间',
                        last_login_ip VARCHAR(50) COMMENT '最后登录IP',
                        login_count INT DEFAULT 0 COMMENT '登录次数',
                        device_id VARCHAR(100) COMMENT '设备ID',
                        device_type VARCHAR(50) COMMENT '设备类型',
                        app_version VARCHAR(20) COMMENT 'APP版本',
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                        INDEX idx_email (email),
                        INDEX idx_invite_code (invite_code),
                        INDEX idx_parent_id (parent_id),
                        INDEX idx_level (level),
                        INDEX idx_status (status),
                        INDEX idx_created_at (created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员表'";
                    $db->exec($sql);
                    echo "<div class='log-item success'>✓ 创建表: app_members</div>";

                    // 创建邀请记录表
                    $sql = "CREATE TABLE IF NOT EXISTS app_invitations (
                        id INT AUTO_INCREMENT PRIMARY KEY COMMENT '记录ID',
                        inviter_id INT NOT NULL COMMENT '邀请人ID',
                        inviter_code VARCHAR(20) NOT NULL COMMENT '邀请人推荐码',
                        invitee_id INT NOT NULL COMMENT '被邀请人ID',
                        invitee_username VARCHAR(50) COMMENT '被邀请人用户名',
                        invitee_email VARCHAR(100) COMMENT '被邀请人邮箱',
                        reward_points INT DEFAULT 20 COMMENT '奖励积分',
                        reward_status ENUM('pending', 'completed', 'cancelled') DEFAULT 'pending' COMMENT '奖励状态',
                        reward_time DATETIME COMMENT '奖励发放时间',
                        invitee_total_recharge DECIMAL(10,2) DEFAULT 0.00 COMMENT '被邀请人累计充值',
                        invitee_is_active TINYINT DEFAULT 0 COMMENT '被邀请人是否活跃',
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '邀请时间',
                        INDEX idx_inviter_id (inviter_id),
                        INDEX idx_invitee_id (invitee_id),
                        INDEX idx_reward_status (reward_status),
                        INDEX idx_created_at (created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邀请记录表'";
                    $db->exec($sql);
                    echo "<div class='log-item success'>✓ 创建表: app_invitations</div>";

                    // 创建登录日志表
                    $sql = "CREATE TABLE IF NOT EXISTS app_login_logs (
                        id INT AUTO_INCREMENT PRIMARY KEY COMMENT '日志ID',
                        member_id INT NOT NULL COMMENT '会员ID',
                        login_type ENUM('email', 'username') COMMENT '登录方式',
                        login_account VARCHAR(100) COMMENT '登录账号',
                        ip VARCHAR(50) COMMENT '登录IP',
                        location VARCHAR(100) COMMENT '登录地点',
                        device_id VARCHAR(100) COMMENT '设备ID',
                        device_type VARCHAR(50) COMMENT '设备类型',
                        device_model VARCHAR(100) COMMENT '设备型号',
                        os_version VARCHAR(50) COMMENT '系统版本',
                        app_version VARCHAR(20) COMMENT 'APP版本',
                        status ENUM('success', 'failed') DEFAULT 'success' COMMENT '登录状态',
                        fail_reason VARCHAR(255) COMMENT '失败原因',
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
                        INDEX idx_member_id (member_id),
                        INDEX idx_created_at (created_at),
                        INDEX idx_ip (ip)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录日志表'";
                    $db->exec($sql);
                    echo "<div class='log-item success'>✓ 创建表: app_login_logs</div>";

                    // 创建邮箱验证码表
                    $sql = "CREATE TABLE IF NOT EXISTS app_email_codes (
                        id INT AUTO_INCREMENT PRIMARY KEY COMMENT '验证码ID',
                        type ENUM('register', 'login', 'reset_password', 'bind_email') COMMENT '验证码类型',
                        email VARCHAR(100) NOT NULL COMMENT '邮箱',
                        code VARCHAR(6) NOT NULL COMMENT '验证码',
                        is_used TINYINT DEFAULT 0 COMMENT '是否已使用',
                        expire_time DATETIME NOT NULL COMMENT '过期时间',
                        ip VARCHAR(50) COMMENT '请求IP',
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                        INDEX idx_email (email),
                        INDEX idx_code (code),
                        INDEX idx_expire_time (expire_time)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邮箱验证码表'";
                    $db->exec($sql);
                    echo "<div class='log-item success'>✓ 创建表: app_email_codes</div>";

                    // 创建积分变动记录表
                    $sql = "CREATE TABLE IF NOT EXISTS app_balance_logs (
                        id INT AUTO_INCREMENT PRIMARY KEY COMMENT '记录ID',
                        member_id INT NOT NULL COMMENT '会员ID',
                        type ENUM('recharge', 'withdraw', 'commission', 'consume', 'refund', 'reward', 'admin') COMMENT '变动类型',
                        amount DECIMAL(10,2) NOT NULL COMMENT '变动金额',
                        balance_before DECIMAL(10,2) NOT NULL COMMENT '变动前积分',
                        balance_after DECIMAL(10,2) NOT NULL COMMENT '变动后积分',
                        related_id INT COMMENT '关联ID',
                        related_type VARCHAR(50) COMMENT '关联类型',
                        remark VARCHAR(255) COMMENT '备注',
                        operator_id INT COMMENT '操作人ID',
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                        INDEX idx_member_id (member_id),
                        INDEX idx_type (type),
                        INDEX idx_created_at (created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分变动记录表'";
                    $db->exec($sql);
                    echo "<div class='log-item success'>✓ 创建表: app_balance_logs</div>";

                    // 创建积分记录表
                    $sql = "CREATE TABLE IF NOT EXISTS app_points_logs (
                        id INT AUTO_INCREMENT PRIMARY KEY COMMENT '记录ID',
                        member_id INT NOT NULL COMMENT '会员ID',
                        type ENUM('invite', 'sign_in', 'task', 'consume', 'exchange', 'expire', 'admin') COMMENT '积分类型',
                        points INT NOT NULL COMMENT '积分变动',
                        points_before INT NOT NULL COMMENT '变动前积分',
                        points_after INT NOT NULL COMMENT '变动后积分',
                        related_id INT COMMENT '关联ID',
                        related_type VARCHAR(50) COMMENT '关联类型',
                        remark VARCHAR(255) COMMENT '备注',
                        operator_id INT COMMENT '操作人ID',
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                        INDEX idx_member_id (member_id),
                        INDEX idx_type (type),
                        INDEX idx_created_at (created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分记录表'";
                    $db->exec($sql);
                    echo "<div class='log-item success'>✓ 创建表: app_points_logs</div>";

                    // 插入测试数据
                    $password = password_hash('password', PASSWORD_BCRYPT);

                    $sql = "INSERT INTO app_members (username, password, email, nickname, invite_code, level, points, balance, vip_expire_time)
                            VALUES ('testuser1', ?, 'test1@example.com', '测试用户1', 'TEST0001', 'vip', 100, 500.00, DATE_ADD(NOW(), INTERVAL 30 DAY))";
                    $stmt = $db->prepare($sql);
                    $stmt->execute([$password]);
                    echo "<div class='log-item success'>✓ 创建测试账号: testuser1</div>";

                    $sql = "INSERT INTO app_members (username, password, email, nickname, invite_code, parent_invite_code, parent_id, level, points, balance)
                            VALUES ('testuser2', ?, 'test2@example.com', '测试用户2', 'TEST0002', 'TEST0001', 1, 'normal', 20, 0.00)";
                    $stmt = $db->prepare($sql);
                    $stmt->execute([$password]);
                    echo "<div class='log-item success'>✓ 创建测试账号: testuser2</div>";

                    // 更新邀请人数
                    $db->exec("UPDATE app_members SET invite_count = 1 WHERE id = 1");

                    // 插入邀请记录
                    $sql = "INSERT INTO app_invitations (inviter_id, inviter_code, invitee_id, invitee_username, invitee_email, reward_status, reward_time)
                            VALUES (1, 'TEST0001', 2, 'testuser2', 'test2@example.com', 'completed', NOW())";
                    $db->exec($sql);
                    echo "<div class='log-item success'>✓ 创建邀请记录</div>";

                    echo '</div>';

                    echo '<div class="status success" style="margin-top: 20px;">';
                    echo '<strong>✅ 安装成功！</strong>';
                    echo '<p style="margin-top: 10px;">会员系统已成功安装，您现在可以开始使用了。</p>';
                    echo '</div>';

                    echo '<h3 style="margin-top: 30px;">测试账号信息：</h3>';
                    echo '<table>';
                    echo '<tr><th>用户名</th><th>密码</th><th>邮箱</th><th>推荐码</th><th>等级</th><th>积分</th><th>积分</th></tr>';
                    echo '<tr><td>testuser1</td><td>password</td><td>test1@example.com</td><td>TEST0001</td><td>VIP</td><td>100</td><td>500.00</td></tr>';
                    echo '<tr><td>testuser2</td><td>password</td><td>test2@example.com</td><td>TEST0002</td><td>普通</td><td>20</td><td>0.00</td></tr>';
                    echo '</table>';

                    echo '<div class="center" style="margin-top: 30px;">';
                    echo '<a href="test_member_api.html" class="btn">前往测试页面</a>';
                    echo '<a href="banner_overview.php" class="btn">返回首页</a>';
                    echo '</div>';

                } catch (Exception $e) {
                    echo "<div class='log-item error'>✗ 安装失败: " . $e->getMessage() . "</div>";
                    echo '</div>';

                    echo '<div class="status error" style="margin-top: 20px;">';
                    echo '<strong>❌ 安装失败！</strong>';
                    echo '<p style="margin-top: 10px;">错误信息：' . $e->getMessage() . '</p>';
                    echo '</div>';
                }
            }
            ?>
        </div>
    </div>
</body>
</html>
