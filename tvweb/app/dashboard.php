<div class="page-header">
    <div class="page-title">仪表盘</div>
</div>

<el-row :gutter="20">
    <el-col :span="6">
        <el-card shadow="hover">
            <div style="display: flex; align-items: center;">
                <i class="el-icon-user" style="font-size: 40px; color: #409EFF; margin-right: 20px;"></i>
                <div>
                    <div style="font-size: 24px; font-weight: bold;">{{ dashboardStats.users }}</div>
                    <div style="color: #999; margin-top: 5px;">用户总数</div>
                </div>
            </div>
        </el-card>
    </el-col>
    <el-col :span="6">
        <el-card shadow="hover">
            <div style="display: flex; align-items: center;">
                <i class="el-icon-bell" style="font-size: 40px; color: #67C23A; margin-right: 20px;"></i>
                <div>
                    <div style="font-size: 24px; font-weight: bold;">{{ dashboardStats.announcements }}</div>
                    <div style="color: #999; margin-top: 5px;">公告数量</div>
                </div>
            </div>
        </el-card>
    </el-col>
    <el-col :span="6">
        <el-card shadow="hover">
            <div style="display: flex; align-items: center;">
                <i class="el-icon-picture" style="font-size: 40px; color: #E6A23C; margin-right: 20px;"></i>
                <div>
                    <div style="font-size: 24px; font-weight: bold;">{{ dashboardStats.banners }}</div>
                    <div style="color: #999; margin-top: 5px;">轮播数量</div>
                </div>
            </div>
        </el-card>
    </el-col>
    <el-col :span="6">
        <el-card shadow="hover">
            <div style="display: flex; align-items: center;">
                <i class="el-icon-setting" style="font-size: 40px; color: #F56C6C; margin-right: 20px;"></i>
                <div>
                    <div style="font-size: 24px; font-weight: bold;">{{ dashboardStats.configs }}</div>
                    <div style="color: #999; margin-top: 5px;">配置项数</div>
                </div>
            </div>
        </el-card>
    </el-col>
</el-row>

<el-row :gutter="20" style="margin-top: 20px;">
    <el-col :span="12">
        <el-card shadow="hover">
            <div slot="header">
                <span style="font-weight: bold;">系统信息</span>
            </div>
            <el-descriptions :column="1" border>
                <el-descriptions-item label="系统名称">TV管理后台系统</el-descriptions-item>
                <el-descriptions-item label="系统版本">v1.0.0</el-descriptions-item>
                <el-descriptions-item label="当前用户">{{ userInfo.username }}</el-descriptions-item>
                <el-descriptions-item label="用户角色">
                    <el-tag :type="userInfo.role === 'admin' ? 'danger' : 'info'" size="small">
                        {{ userInfo.role === 'admin' ? '管理员' : '普通用户' }}
                    </el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="登录时间">{{ userInfo.last_login_time || '未知' }}</el-descriptions-item>
            </el-descriptions>
        </el-card>
    </el-col>
    <el-col :span="12">
        <el-card shadow="hover">
            <div slot="header">
                <span style="font-weight: bold;">快捷操作</span>
            </div>
            <div style="display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px;">
                <el-button type="primary" icon="el-icon-user" @click="goToMenu('users')">用户管理</el-button>
                <el-button type="success" icon="el-icon-bell" @click="goToMenu('announcements')">公告管理</el-button>
                <el-button type="warning" icon="el-icon-picture" @click="goToMenu('banners')">轮播管理</el-button>
                <el-button type="danger" icon="el-icon-setting" @click="goToMenu('config')">配置管理</el-button>
                <el-button type="info" icon="el-icon-video-camera" @click="goToMenu('video_sources')">视频源管理</el-button>
                <el-button type="primary" icon="el-icon-menu" @click="goToMenu('menu')">菜单管理</el-button>
            </div>
        </el-card>
    </el-col>
</el-row>

<script>
// 扩展Vue实例数据
Object.assign(app.$data, {
    dashboardStats: {
        users: 0,
        announcements: 0,
        banners: 0,
        configs: 0
    }
});

// 扩展Vue实例方法
Object.assign(app, {
    // 加载仪表盘统计数据
    loadDashboardStats() {
        // 加载用户总数
        axios.get('users.php?page=1&pageSize=1').then(res => {
            if (res.code === 200) {
                this.dashboardStats.users = parseInt(res.data.total) || 0;
            }
        }).catch(err => {
            console.error('加载用户统计失败:', err);
        });

        // 加载公告数量
        axios.get('announcements.php?page=1&pageSize=1').then(res => {
            if (res.code === 200) {
                this.dashboardStats.announcements = parseInt(res.data.total) || 0;
            }
        }).catch(err => {
            console.error('加载公告统计失败:', err);
        });

        // 加载轮播数量
        axios.get('banners.php?page=1&pageSize=1').then(res => {
            if (res.code === 200) {
                this.dashboardStats.banners = parseInt(res.data.total) || 0;
            }
        }).catch(err => {
            console.error('加载轮播统计失败:', err);
        });

        // 加载配置项数
        axios.get('config_api.php').then(res => {
            if (res.code === 200) {
                this.dashboardStats.configs = res.data.list.length;
            }
        }).catch(err => {
            console.error('加载配置统计失败:', err);
        });
    },

    // 跳转到指定菜单
    goToMenu(menuPath) {
        this.handleMenuSelect(menuPath);
    }
});

// 页面加载时获取数据
app.loadDashboardStats();
</script>
