<?php
require_once 'api/config.php';

$db = Database::getInstance()->getConnection();

echo "<h3>📊 数据库表检查</h3>";

// 检查 app_email_logs 表是否存在
$stmt = $db->query("SHOW TABLES LIKE 'app_email_logs'");
$tableExists = $stmt->rowCount() > 0;

if ($tableExists) {
    echo "<div class='result success'>✓ app_email_logs 表存在</div>";

    // 检查表结构
    $stmt = $db->query("SHOW COLUMNS FROM app_email_logs");
    $columns = $stmt->fetchAll(PDO::FETCH_COLUMN);

    echo "<div class='result info'><strong>表字段：</strong><br>" . implode(', ', $columns) . "</div>";

    // 检查记录数
    $stmt = $db->query("SELECT COUNT(*) FROM app_email_logs");
    $count = $stmt->fetchColumn();

    echo "<div class='result info'><strong>记录数：</strong>{$count} 条</div>";

    if ($count > 0) {
        // 显示最近5条记录
        $stmt = $db->query("SELECT * FROM app_email_logs ORDER BY created_at DESC LIMIT 5");
        $logs = $stmt->fetchAll();

        echo "<h4>最近的记录：</h4>";
        echo "<table>";
        echo "<tr><th>ID</th><th>邮箱</th><th>类型</th><th>状态</th><th>时间</th></tr>";

        foreach ($logs as $log) {
            $status = $log['status'] == 1 ? '<span style="color:#67C23A">成功</span>' : '<span style="color:#F56C6C">失败</span>';
            echo "<tr>";
            echo "<td>{$log['id']}</td>";
            echo "<td>{$log['recipient_email']}</td>";
            echo "<td>{$log['email_type']}</td>";
            echo "<td>{$status}</td>";
            echo "<td>{$log['created_at']}</td>";
            echo "</tr>";
        }

        echo "</table>";
    }
} else {
    echo "<div class='result error'>✗ app_email_logs 表不存在</div>";
    echo "<div class='result warning'>请运行数据库修复脚本：<a href='fix_database.php'>fix_database.php</a></div>";
}

// 检查 email_logger.php 是否存在
if (file_exists(__DIR__ . '/email_logger.php')) {
    echo "<div class='result success'>✓ email_logger.php 文件存在</div>";
} else {
    echo "<div class='result error'>✗ email_logger.php 文件不存在</div>";
}

// 检查 MemberHelper.php 是否引入了 email_logger
$memberHelperContent = file_get_contents(__DIR__ . '/api/MemberHelper.php');
if (strpos($memberHelperContent, 'email_logger.php') !== false) {
    echo "<div class='result success'>✓ MemberHelper.php 已引入 email_logger.php</div>";
} else {
    echo "<div class='result error'>✗ MemberHelper.php 未引入 email_logger.php</div>";
}

if (strpos($memberHelperContent, 'logEmail(') !== false) {
    echo "<div class='result success'>✓ MemberHelper.php 已调用 logEmail() 函数</div>";
} else {
    echo "<div class='result error'>✗ MemberHelper.php 未调用 logEmail() 函数</div>";
}
