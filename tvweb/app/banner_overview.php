<?php
require_once 'api/config.php';
header('Content-Type: text/html; charset=utf-8');
$db = Database::getInstance()->getConnection();
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <title>轮播系统总览</title>
    <style>
        body { font-family: Arial, sans-serif; max-width: 1400px; margin: 20px auto; padding: 20px; background: #f5f5f5; }
        .container { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
        .section { background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .section h2 { margin-top: 0; padding-bottom: 10px; border-bottom: 2px solid #007bff; }
        .home-section h2 { border-bottom-color: #28a745; }
        .profile-section h2 { border-bottom-color: #ffc107; }
        table { width: 100%; border-collapse: collapse; margin-top: 15px; }
        table th, table td { padding: 10px; text-align: left; border-bottom: 1px solid #ddd; }
        table th { background: #f8f9fa; font-weight: bold; }
        .banner-img { max-width: 150px; height: auto; border-radius: 4px; }
        .status-active { color: #28a745; font-weight: bold; }
        .status-inactive { color: #dc3545; font-weight: bold; }
        .btn { display: inline-block; padding: 10px 20px; margin: 5px; background: #007bff; color: white; text-decoration: none; border-radius: 4px; }
        .btn:hover { background: #0056b3; }
        .btn-success { background: #28a745; }
        .btn-warning { background: #ffc107; color: #000; }
        .info-box { background: #e7f3ff; padding: 15px; border-left: 4px solid #007bff; margin: 15px 0; }
        .warning-box { background: #fff3cd; padding: 15px; border-left: 4px solid #ffc107; margin: 15px 0; }
        .stats { display: flex; justify-content: space-around; margin: 20px 0; }
        .stat-card { text-align: center; padding: 20px; background: #f8f9fa; border-radius: 8px; }
        .stat-number { font-size: 36px; font-weight: bold; color: #007bff; }
    </style>
</head>
<body>
    <h1 style="text-align: center;">轮播系统总览</h1>

    <?php
    $stmt = $db->prepare("SELECT COUNT(*) as count FROM app_banners WHERE position = 'home' AND status = 1");
    $stmt->execute();
    $homeCount = $stmt->fetch()['count'];

    $stmt = $db->prepare("SELECT COUNT(*) as count FROM app_banners WHERE position = 'profile' AND status = 1");
    $stmt->execute();
    $profileCount = $stmt->fetch()['count'];
    ?>

    <div class="stats">
        <div class="stat-card">
            <div class="stat-number"><?= $homeCount ?></div>
            <div>首页轮播（启用）</div>
        </div>
        <div class="stat-card">
            <div class="stat-number"><?= $profileCount ?></div>
            <div>广告轮播（启用）</div>
        </div>
    </div>

    <div class="info-box">
        <strong>重要说明：</strong>
        <ul>
            <li><strong>首页轮播 (home)</strong>：APP首页展示</li>
            <li><strong>我的页面轮播 (profile)</strong>：广告推广</li>
            <li>两个系统完全独立</li>
        </ul>
    </div>

    <div class="container">
        <div class="section home-section">
            <h2>首页轮播</h2>
            <a href="pages/banners.php" class="btn btn-success">管理</a>
            <a href="api/banner_json.php" class="btn" target="_blank">API</a>
            <?php
            $stmt = $db->prepare("SELECT * FROM app_banners WHERE position = 'home' ORDER BY sort_order ASC LIMIT 5");
            $stmt->execute();
            $homeBanners = $stmt->fetchAll();
            if (empty($homeBanners)) {
                echo '<div class="warning-box">暂无数据</div>';
            } else {
                echo '<table><tr><th>ID</th><th>标题</th><th>状态</th></tr>';
                foreach ($homeBanners as $b) {
                    $status = $b['status'] ? 'status-active' : 'status-inactive';
                    echo "<tr><td>{$b['id']}</td><td>{$b['title']}</td><td class='$status'>" . ($b['status'] ? '启用' : '禁用') . "</td></tr>";
                }
                echo '</table>';
            }
            ?>
        </div>

        <div class="section profile-section">
            <h2>广告轮播</h2>
            <a href="pages/profile_banners.php" class="btn btn-warning">管理</a>
            <a href="api/profile_banners.php" class="btn" target="_blank">API</a>
            <?php
            $stmt = $db->prepare("SELECT * FROM app_banners WHERE position = 'profile' ORDER BY sort_order ASC LIMIT 5");
            $stmt->execute();
            $profileBanners = $stmt->fetchAll();
            if (empty($profileBanners)) {
                echo '<div class="warning-box">暂无数据<br><a href="add_test_profile_banners.php" class="btn">添加测试数据</a></div>';
            } else {
                echo '<table><tr><th>ID</th><th>标题</th><th>状态</th></tr>';
                foreach ($profileBanners as $b) {
                    $status = $b['status'] ? 'status-active' : 'status-inactive';
                    echo "<tr><td>{$b['id']}</td><td>{$b['title']}</td><td class='$status'>" . ($b['status'] ? '启用' : '禁用') . "</td></tr>";
                }
                echo '</table>';
            }
            ?>
        </div>
    </div>
</body>
</html>
