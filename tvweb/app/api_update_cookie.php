<?php
/**
 * Cookie 更新接口
 * 用于更新 app_config 表中的 cookie 字段
 */

header('Content-Type: application/json; charset=utf-8');

// 定义常量以允许加载配置文件
define('IN_CRONLITE', true);

// 引入数据库配置
require_once __DIR__ . '/../confing/config.php';

// 创建数据库连接
$DB = new DB($host, $user, $pwd, $dbname, $port);

// 只允许 POST 请求
if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    echo json_encode([
        'code' => 405,
        'msg' => '只允许 POST 请求',
        'data' => null
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

// 获取 POST 数据
$input = file_get_contents('php://input');
$data = json_decode($input, true);

// 验证参数
if (!isset($data['cookie'])) {
    echo json_encode([
        'code' => 400,
        'msg' => '缺少必要参数: cookie',
        'data' => null
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

$cookie = $data['cookie'];

try {
    // 转义特殊字符
    $cookieEscaped = $DB->escape($cookie);
    
    // 更新数据库
    $sql = "UPDATE app_config SET cookie = '{$cookieEscaped}', updated_at = NOW() WHERE id = 1";
    
    if ($DB->query($sql)) {
        // 获取更新后的数据
        $row = $DB->get_row("SELECT cookie, updated_at FROM app_config WHERE id = 1");
        
        echo json_encode([
            'code' => 200,
            'msg' => 'Cookie 更新成功',
            'data' => [
                'cookie' => $row['cookie'],
                'updated_at' => $row['updated_at']
            ]
        ], JSON_UNESCAPED_UNICODE);
    } else {
        echo json_encode([
            'code' => 500,
            'msg' => '更新失败: ' . $DB->error(),
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
