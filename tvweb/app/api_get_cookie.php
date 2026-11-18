<?php
/**
 * Cookie 查询接口
 * 用于获取 app_config 表中的 cookie 信息
 */

header('Content-Type: application/json; charset=utf-8');

// 定义常量以允许加载配置文件
define('IN_CRONLITE', true);

// 引入数据库配置
require_once __DIR__ . '/../confing/config.php';

// 创建数据库连接
$DB = new DB($host, $user, $pwd, $dbname, $port);

try {
    $row = $DB->get_row("SELECT cookie, updated_at, created_at FROM app_config WHERE id = 1");
    
    if ($row) {
        echo json_encode([
            'code' => 200,
            'msg' => '获取成功',
            'data' => [
                'cookie' => $row['cookie'],
                'updated_at' => $row['updated_at'],
                'created_at' => $row['created_at']
            ]
        ], JSON_UNESCAPED_UNICODE);
    } else {
        echo json_encode([
            'code' => 404,
            'msg' => '配置不存在',
            'data' => null
        ], JSON_UNESCAPED_UNICODE);
    }
} catch (Exception $e) {
    echo json_encode([
        'code' => 500,
        'msg' => '系统错误: ' . $e->getMessage(),
        'data' => null
    ], JSON_UNESCAPED_UNICODE);
}
?>
