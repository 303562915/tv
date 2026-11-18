<?php
/**
 * 导入原始轮播数据到数据库
 */

// 数据库配置
$config = [
    'host' => 'localhost',
    'dbname' => 'yughplvt',
    'username' => 'yughplvt',
    'password' => getenv('DB_PASS') ?: '',
    'charset' => 'utf8mb4'
];

header('Content-Type: text/html; charset=utf-8');

echo '<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <title>导入轮播数据</title>
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
        <h1>导入轮播数据</h1>
        <div class="log">';

try {
    $dsn = "mysql:host={$config['host']};dbname={$config['dbname']};charset={$config['charset']}";
    $pdo = new PDO($dsn, $config['username'], $config['password']);
    $pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);

    echo '<div class="success">✓ 数据库连接成功</div>';

    // 原始轮播数据
    $banners = [
        [
            'title' => '如果喜欢可以帮我安利给别人哦',
            'image_url' => 'https://vres.bdwtjw.com/vod1/vod/upload/202603/06/15/ec1b30b672e0faaa2e5552f6a0caa904.jpg',
            'link_url' => 'sd1',
            'link_type' => 'internal',
            'position' => 'home',
            'sort_order' => 1,
            'status' => 1
        ],
        [
            'title' => '正规手机卡',
            'image_url' => 'https://tc-new.z.wiki/autoupload/u94X23qgYepuz56apULahLKXl_QqVl-bpSwqP4fJO68/20260419/Jjjo/3063X1368/IMG_20260419_015715_compressed_1776535100497.png/webp',
            'link_url' => 'https://172.lot-ml.com/ProductEn/Index/fff7dc002429b54f',
            'link_type' => 'external',
            'position' => 'home',
            'sort_order' => 2,
            'status' => 1
        ],
        [
            'title' => '我的朋友安德烈',
            'image_url' => 'https://vres.bdwtjw.com/vod1/vod/upload/202603/18/00/ca683238d064a30be8596eff9bc6bc9f.jpg',
            'link_url' => 'sd5',
            'link_type' => 'internal',
            'position' => 'home',
            'sort_order' => 3,
            'status' => 1
        ]
    ];

    echo '<div class="info">正在清空旧的示例数据...</div>';
    $pdo->exec("DELETE FROM app_banners WHERE title LIKE '%轮播%' OR image_url LIKE '%placeholder%'");
    echo '<div class="success">✓ 旧数据已清空</div>';

    echo '<div class="info">正在导入真实轮播数据...</div>';

    $stmt = $pdo->prepare("
        INSERT INTO app_banners (title, image_url, link_url, link_type, position, sort_order, status)
        VALUES (?, ?, ?, ?, ?, ?, ?)
    ");

    foreach ($banners as $banner) {
        $stmt->execute([
            $banner['title'],
            $banner['image_url'],
            $banner['link_url'],
            $banner['link_type'],
            $banner['position'],
            $banner['sort_order'],
            $banner['status']
        ]);
        echo '<div class="success">✓ 导入轮播: ' . htmlspecialchars($banner['title']) . '</div>';
    }

    echo '<div class="success" style="margin-top: 20px; font-size: 16px; font-weight: bold;">
        ✓ 轮播数据导入完成！共导入 ' . count($banners) . ' 个轮播图
    </div>';

    echo '<div class="info" style="margin-top: 15px;">
        <strong>提示：</strong><br>
        1. 现在访问 API 接口会返回真实数据<br>
        2. 可以在管理后台继续添加或编辑轮播图<br>
        3. 软件会自动从接口获取最新数据
    </div>';

    echo '</div>
        <div style="text-align: center;">
            <a href="index.html" class="btn">进入管理后台</a>
            <a href="api/banner_json.php" class="btn" target="_blank" style="background: #67C23A; margin-left: 10px;">查看接口数据</a>
        </div>
    </div>
</body>
</html>';

} catch (PDOException $e) {
    echo '<div class="error">✗ 错误: ' . htmlspecialchars($e->getMessage()) . '</div>';
    echo '</div></div></body></html>';
}
