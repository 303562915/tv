<?php
// 检查登录状态
session_start();
$token = $_COOKIE['token'] ?? '';
$userInfo = $_COOKIE['userInfo'] ?? '';

if (empty($token) || empty($userInfo)) {
    header('Location: index.html');
    exit;
}

$userInfo = json_decode($userInfo, true);
$mod = $mod ?? 'default';
$title = $title ?? 'TV管理后台';
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title><?php echo $title; ?> - TV管理后台</title>
    <link rel="stylesheet" href="assets/css/element-ui.css">
    <link rel="stylesheet" href="../style.css?v=20261026.1">
    <style>
        body, h1, h2, h3, h4, h5, h6, p, ul, ol, li, dl, dt, dd, form, fieldset, legend { margin: 0; padding: 0; }
        body { font-family: 'Microsoft YaHei', Arial, sans-serif; overflow: hidden; width: 100vw; }
        #app { height: 100vh; overflow: hidden; width: 100%; }
        .el-container { height: 100%; display: flex; width: 100%; }
        .el-container.is-vertical { flex-direction: column; }
        .el-header { background: #545c64 !important; color: white !important; display: flex; justify-content: space-between; align-items: center; padding: 0 20px !important; height: 60px !important; flex-shrink: 0; }
        .el-aside { background: #304156 !important; height: 100%; overflow-y: auto; flex-shrink: 0; width: 200px !important; }
        .el-aside::-webkit-scrollbar { width: 6px; }
        .el-aside::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.2); border-radius: 3px; }
        .el-aside::-webkit-scrollbar-track { background: transparent; }
        .el-menu { border: none !important; height: 100%; }
        .el-main { background: #f0f2f5 !important; padding: 20px !important; flex: 1; overflow-y: auto; }
        .page-header { background: white; padding: 20px; margin-bottom: 20px; border-radius: 4px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .page-title { font-size: 20px; font-weight: bold; color: #333; }
        .content-box { background: white; padding: 20px; border-radius: 4px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .toolbar { margin-bottom: 20px; }
        .el-table { margin-top: 20px; }
        .pagination { margin-top: 20px; text-align: right; }
        .status-tag { cursor: pointer; }
    </style>
</head>
<body>
    <div id="app">
        <el-container>
            <el-aside width="200px">
                <div style="height: 60px; display: flex; align-items: center; justify-content: center; color: white; font-size: 18px; font-weight: bold; background: #263445;">
                    TV后台
                </div>
                <el-menu :default-active="currentPath" background-color="#304156" text-color="#bfcbd9" active-text-color="#409EFF">
                    <el-menu-item v-for="menu in menuList" :key="menu.id" :index="menu.path" @click.native="goToPage(menu.path)">
                        <i :class="menu.icon"></i>
                        <span slot="title">{{ menu.name }}</span>
                    </el-menu-item>
                </el-menu>
            </el-aside>

            <el-container>
                <el-header>
                    <div style="font-size: 16px;">TV管理后台系统</div>
                    <div>
                        <span style="margin-right: 20px;">欢迎, <?php echo htmlspecialchars($userInfo['username']); ?></span>
                        <el-button type="danger" size="small" @click="handleLogout">退出</el-button>
                    </div>
                </el-header>

                <el-main>
