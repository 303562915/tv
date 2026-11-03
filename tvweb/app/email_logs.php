<?php
session_start();
if (!isset($_SESSION['user_id'])) {
    header('Location: index.html');
    exit;
}
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>邮箱记录 - TV后台管理</title>
    <link rel="stylesheet" href="https://unpkg.com/element-ui/lib/theme-chalk/index.css">
    <link rel="stylesheet" href="style.css">
</head>
<body>
<div id="app">
    <el-container>
        <!-- 侧边栏 -->
        <el-aside width="200px">
            <div class="logo">TV后台</div>
            <el-menu :default-active="activeMenu" @select="handleMenuSelect">
                <el-menu-item v-for="menu in menuList" :key="menu.id" :index="menu.path">
                    <i :class="menu.icon"></i>
                    <span>{{ menu.name }}</span>
                </el-menu-item>
            </el-menu>
        </el-aside>

        <!-- 主内容区 -->
        <el-container>
            <!-- 顶部导航 -->
            <el-header>
                <div class="header-content">
                    <h2>邮箱记录</h2>
                    <div class="user-info">
                        <span>欢迎, {{ userInfo.username }}</span>
                        <el-button type="text" @click="handleLogout">退出</el-button>
                    </div>
                </div>
            </el-header>

            <!-- 内容区 -->
            <el-main>
                <!-- 搜索和筛选 -->
                <el-card class="search-card">
                    <el-form :inline="true">
                        <el-form-item label="关键词">
                            <el-input v-model="searchKeyword" placeholder="邮箱或用户名" clearable style="width: 200px"></el-input>
                        </el-form-item>
                        <el-form-item label="邮件类型">
                            <el-select v-model="searchEmailType" placeholder="全部" clearable style="width: 150px">
                                <el-option label="注册验证" value="register"></el-option>
                                <el-option label="密码重置" value="reset_password"></el-option>
                                <el-option label="通知" value="notification"></el-option>
                                <el-option label="VIP到期提醒" value="vip_expire"></el-option>
                                <el-option label="邀请" value="invite"></el-option>
                                <el-option label="系统通知" value="system"></el-option>
                            </el-select>
                        </el-form-item>
                        <el-form-item label="状态">
                            <el-select v-model="searchStatus" placeholder="全部" clearable style="width: 120px">
                                <el-option label="成功" value="1"></el-option>
                                <el-option label="失败" value="0"></el-option>
                            </el-select>
                        </el-form-item>
                        <el-form-item>
                            <el-button type="primary" icon="el-icon-search" @click="loadEmailLogs">搜索</el-button>
                            <el-button icon="el-icon-refresh" @click="resetSearch">重置</el-button>
                        </el-form-item>
                    </el-form>
                </el-card>

                <!-- 邮箱记录列表 -->
                <el-card>
                    <el-table :data="emailLogList" border stripe>
                        <el-table-column prop="id" label="ID" width="60"></el-table-column>
                        <el-table-column label="邮件类型" width="120">
                            <template slot-scope="scope">
                                <el-tag :type="getEmailTypeTagType(scope.row.email_type)" size="small">
                                    {{ getEmailTypeName(scope.row.email_type) }}
                                </el-tag>
                            </template>
                        </el-table-column>
                        <el-table-column prop="recipient_email" label="收件人邮箱" width="200"></el-table-column>
                        <el-table-column label="用户" width="120">
                            <template slot-scope="scope">
                                <span v-if="scope.row.username">{{ scope.row.username }}</span>
                                <span v-else style="color: #999;">-</span>
                            </template>
                        </el-table-column>
                        <el-table-column prop="subject" label="主题" show-overflow-tooltip></el-table-column>
                        <el-table-column label="状态" width="80" align="center">
                            <template slot-scope="scope">
                                <el-tag :type="scope.row.status == 1 ? 'success' : 'danger'" size="small">
                                    {{ scope.row.status == 1 ? '成功' : '失败' }}
                                </el-tag>
                            </template>
                        </el-table-column>
                        <el-table-column prop="created_at" label="发送时间" width="160"></el-table-column>
                        <el-table-column label="操作" width="150" fixed="right">
                            <template slot-scope="scope">
                                <el-button type="text" size="small" @click="viewDetail(scope.row)">查看详情</el-button>
                                <el-button type="text" size="small" style="color: #f56c6c" @click="handleDelete(scope.row.id)">删除</el-button>
                            </template>
                        </el-table-column>
                    </el-table>

                    <!-- 分页 -->
                    <el-pagination
                        @current-change="handlePageChange"
                        :current-page="pagination.page"
                        :page-size="pagination.pageSize"
                        :total="pagination.total"
                        layout="total, prev, pager, next"
                        style="margin-top: 20px; text-align: right">
                    </el-pagination>
                </el-card>
            </el-main>
        </el-container>
    </el-container>

    <!-- 详情对话框 -->
    <el-dialog title="邮件详情" :visible.sync="detailDialogVisible" width="600px">
        <el-descriptions :column="1" border v-if="currentDetail">
            <el-descriptions-item label="ID">{{ currentDetail.id }}</el-descriptions-item>
            <el-descriptions-item label="邮件类型">
                <el-tag :type="getEmailTypeTagType(currentDetail.email_type)" size="small">
                    {{ getEmailTypeName(currentDetail.email_type) }}
                </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="收件人邮箱">{{ currentDetail.recipient_email }}</el-descriptions-item>
            <el-descriptions-item label="用户">
                {{ currentDetail.username || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="主题">{{ currentDetail.subject }}</el-descriptions-item>
            <el-descriptions-item label="内容">
                <div style="max-height: 200px; overflow-y: auto; white-space: pre-wrap;">{{ currentDetail.content }}</div>
            </el-descriptions-item>
            <el-descriptions-item label="状态">
                <el-tag :type="currentDetail.status == 1 ? 'success' : 'danger'" size="small">
                    {{ currentDetail.status == 1 ? '成功' : '失败' }}
                </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="错误信息" v-if="currentDetail.error_message">
                <span style="color: #f56c6c;">{{ currentDetail.error_message }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="发送时间">{{ currentDetail.created_at }}</el-descriptions-item>
        </el-descriptions>
    </el-dialog>
</div>

<script src="https://cdn.jsdelivr.net/npm/vue@2.6.14/dist/vue.js"></script>
<script src="https://unpkg.com/element-ui/lib/index.js"></script>
<script src="https://cdn.jsdelivr.net/npm/axios/dist/axios.min.js"></script>
<script src="app.js"></script>
<script>
// 扩展 app.js 的功能
if (window.app) {
    window.app.$data.emailLogList = [];
    window.app.$data.searchEmailType = '';
    window.app.$data.searchStatus = '';
    window.app.$data.detailDialogVisible = false;
    window.app.$data.currentDetail = null;

    // 加载邮箱记录
    window.app.loadEmailLogs = function() {
        const params = {
            page: this.pagination.page,
            pageSize: this.pagination.pageSize,
            keyword: this.searchKeyword,
            email_type: this.searchEmailType,
            status: this.searchStatus
        };

        axios.get('email_logs.php', { params }).then(res => {
            if (res.code === 0) {
                this.emailLogList = res.data.list;
                this.pagination.total = parseInt(res.data.total) || 0;
            }
        });
    };

    // 重置搜索
    window.app.resetSearch = function() {
        this.searchKeyword = '';
        this.searchEmailType = '';
        this.searchStatus = '';
        this.pagination.page = 1;
        this.loadEmailLogs();
    };

    // 查看详情
    window.app.viewDetail = function(row) {
        this.currentDetail = row;
        this.detailDialogVisible = true;
    };

    // 删除记录
    window.app.handleDelete = function(id) {
        this.$confirm('确定要删除这条记录吗?', '提示', {
            confirmButtonText: '确定',
            cancelButtonText: '取消',
            type: 'warning'
        }).then(() => {
            axios.delete(`email_logs.php?id=${id}`).then(res => {
                if (res.code === 0) {
                    this.$message.success(res.msg);
                    this.loadEmailLogs();
                } else {
                    this.$message.error(res.msg);
                }
            });
        }).catch(() => {});
    };

    // 获取邮件类型名称
    window.app.getEmailTypeName = function(type) {
        const types = {
            'register': '注册验证',
            'reset_password': '密码重置',
            'notification': '通知',
            'vip_expire': 'VIP到期提醒',
            'invite': '邀请',
            'system': '系统通知'
        };
        return types[type] || type;
    };

    // 获取邮件类型标签颜色
    window.app.getEmailTypeTagType = function(type) {
        const types = {
            'register': 'success',
            'reset_password': 'warning',
            'notification': 'info',
            'vip_expire': 'danger',
            'invite': 'primary',
            'system': ''
        };
        return types[type] || '';
    };

    // 监听菜单切换
    const originalHandleMenuSelect = window.app.handleMenuSelect;
    window.app.handleMenuSelect = function(index) {
        if (index === 'email_logs') {
            this.loadEmailLogs();
        } else {
            originalHandleMenuSelect.call(this, index);
        }
    };
}
</script>
</body>
</html>
