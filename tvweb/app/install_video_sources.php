<?php
/**
 * 视频源配置在线安装脚本
 * 在现有数据库中添加 app_video_sources 表
 */

// 数据库配置
$config = [
    'host' => 'localhost',
    'dbname' => 'yughplvt',
    'username' => 'yughplvt',
    'password' => 'REDACTED',
    'charset' => 'utf8mb4'
];

header('Content-Type: text/html; charset=utf-8');

echo '<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <title>视频源配置安装</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: "Microsoft YaHei", Arial, sans-serif; background: #f5f5f5; padding: 20px; }
        .container { max-width: 800px; margin: 0 auto; background: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
        h1 { color: #333; margin-bottom: 20px; text-align: center; }
        .log { background: #f8f8f8; padding: 15px; border-radius: 4px; margin: 10px 0; font-family: monospace; font-size: 14px; line-height: 1.6; max-height: 500px; overflow-y: auto; }
        .success { color: #67C23A; }
        .error { color: #F56C6C; }
        .info { color: #409EFF; }
        .btn { display: inline-block; padding: 10px 20px; background: #409EFF; color: white; text-decoration: none; border-radius: 4px; margin-top: 20px; }
    </style>
</head>
<body>
    <div class="container">
        <h1>视频源配置安装</h1>
        <div class="log">';

try {
    $dsn = "mysql:host={$config['host']};dbname={$config['dbname']};charset={$config['charset']}";
    $pdo = new PDO($dsn, $config['username'], $config['password']);
    $pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);

    echo '<div class="success">✓ 数据库连接成功</div>';

    // 创建视频源配置表
    echo '<div class="info">正在创建 app_video_sources 表...</div>';
    $pdo->exec("
        CREATE TABLE IF NOT EXISTS app_video_sources (
            id INT AUTO_INCREMENT PRIMARY KEY,
            source_key VARCHAR(50) NOT NULL UNIQUE COMMENT '源标识',
            source_name VARCHAR(100) NOT NULL COMMENT '源名称',
            source_type ENUM('API', 'WEB') DEFAULT 'API' COMMENT '源类型',
            base_url VARCHAR(500) NOT NULL COMMENT '基础URL',
            status TINYINT DEFAULT 1 COMMENT '状态 1启用 0禁用',
            sort_order INT DEFAULT 0 COMMENT '排序',
            description TEXT COMMENT '描述说明',
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
            INDEX idx_key (source_key),
            INDEX idx_status (status),
            INDEX idx_sort (sort_order)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频源配置表'
    ");
    echo '<div class="success">✓ app_video_sources 表创建成功</div>';

    // 检查是否已有数据
    $stmt = $pdo->query("SELECT COUNT(*) FROM app_video_sources");
    $count = $stmt->fetchColumn();

    if ($count == 0) {
        echo '<div class="info">正在导入默认视频源配置...</div>';

        $sources = [
            ['iqiyi', '爱奇艺', 'API', 'https://iqiyizyapi.com/api.php/provide/vod/?ac=videolist&t={category}&pg={page}', 1, 1, 'JSON API接口'],
            ['360zy', '360源', 'API', 'https://360zy5.com/api.php/provide/vod/?ac=videolist&t={category}&pg={page}&h=&ids=&wd=', 1, 2, 'JSON API接口'],
            ['ffzy5', '非凡通道', 'API', 'http://ffzy5.tv/api.php/provide/vod/?ac=videolist&t={category}&pg={page}', 1, 3, 'JSON API接口'],
            ['dbzy', '豆瓣通道', 'API', 'https://caiji.dbzy5.com/api.php/provide/vod/from/dbm3u8/at/json/?ac=videolist&t={category}&pg={page}', 1, 4, 'JSON API接口'],
            ['niuniuzy3', '牛牛通道', 'API', 'http://niuniuzy3.com/api.php/provide/vod/?ac=videolist&t={category}&pg={page}', 1, 5, 'JSON API接口'],
            ['hongniu', '红牛资源', 'API', 'https://www.hongniuzy2.com/api.php/provide/vod/?ac=videolist&t={category}&pg={page}', 1, 6, 'JSON API接口'],
            ['libvod', 'LiB', 'WEB', 'https://www.libvio.cc/show/{category}-{area}-{year}--{lang}----{page}---{order}.html', 1, 7, 'HTML网页解析'],
            ['keke', '可可', 'WEB', 'https://103.194.185.51:51120/show/{category}-{type}-{area}-{year}-{lang}-{letter}-{order}-{page}-{extra}.html', 1, 8, 'HTML网页解析'],
            ['zxzj', '在线之家', 'WEB', 'https://www.zxzjhd.com/list/{category}-{page}.html', 1, 9, 'HTML网页解析'],
            ['dadaqu', '达达趣', 'WEB', 'https://www.dadaqu.me/show/{category}-{area}-{year}-{lang}-{letter}-{by}-{class}-{page}-{limit}-{order}-{extra}.html', 1, 10, 'HTML网页解析']
        ];

        $stmt = $pdo->prepare("
            INSERT INTO app_video_sources (source_key, source_name, source_type, base_url, status, sort_order, description)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        ");

        foreach ($sources as $source) {
            $stmt->execute($source);
            echo '<div class="success">✓ 导入视频源: ' . $source[1] . '</div>';
        }

        echo '<div class="success" style="margin-top: 20px; font-size: 16px; font-weight: bold;">
            ✓ 视频源配置安装完成！共导入 ' . count($sources) . ' 个视频源
        </div>';
    } else {
        echo '<div class="info">视频源配置已存在，跳过导入</div>';
    }

    echo '</div>
        <div style="text-align: center;">
            <a href="index.html" class="btn">进入管理后台</a>
        </div>
    </div>
</body>
</html>';

} catch (PDOException $e) {
    echo '<div class="error">✗ 错误: ' . htmlspecialchars($e->getMessage()) . '</div>';
    echo '</div></div></body></html>';
}
