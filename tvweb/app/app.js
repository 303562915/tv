// 常量定义
const STATUS = {
    ACTIVE: 1,
    INACTIVE: 0
};

const PAGINATION_CONFIG = {
    DEFAULT_PAGE: 1,
    DEFAULT_PAGE_SIZE: 20
};

const API_ENDPOINTS = {
    LOGIN: 'login_safe.php',
    USERS: 'users.php',
    ANNOUNCEMENTS: 'announcements.php',
    BANNERS: 'banners.php',
    CONFIGS: 'config_api.php',
    VIDEO_SOURCES: 'video_sources.php',
    MENU: 'menu.php',
    EMAIL_LOGS: 'email_logs.php',
    COOKIE_GET: 'api_get_cookie.php',
    COOKIE_UPDATE: 'api_update_cookie.php'
};

const RESPONSE_CODE = {
    SUCCESS: 0,
    ERROR: 1,
    UNAUTHORIZED: 401
};

// API 基础地址
const API_BASE = './api/';

// Axios 配置（仅在管理后台页面使用）
if (typeof axios !== 'undefined') {
    axios.defaults.baseURL = API_BASE;

    axios.interceptors.request.use(config => {
        const token = localStorage.getItem('token');
        if (token) {
            config.headers.Authorization = token;
        }

        // 添加CSRF token保护
        const csrfToken = document.querySelector('meta[name="csrf-token"]')?.content;
        if (csrfToken && ['post', 'put', 'delete', 'patch'].includes(config.method?.toLowerCase())) {
            config.headers['X-CSRF-Token'] = csrfToken;
        }

        return config;
    }, error => {
        return Promise.reject(error);
    });

    axios.interceptors.response.use(
        response => response.data,
        error => {
            if (error.response && error.response.status === 401) {
                localStorage.removeItem('token');
                localStorage.removeItem('userInfo');
                window.location.reload();
            }
            return Promise.reject(error);
        }
    );
}

// 全局Vue错误处理
if (typeof Vue !== 'undefined') {
    Vue.config.errorHandler = function (err, vm, info) {
        console.error('Vue Error:', err);
        console.error('Error Info:', info);

        // 显示用户友好的错误消息
        if (vm && vm.$message) {
            vm.$message.error('操作失败，请稍后重试');
        }

        // TODO: 上报错误到监控系统
        // reportError({ error: err, info: info });
    };

    Vue.config.warnHandler = function (msg, vm, trace) {
        console.warn('Vue Warning:', msg);
        console.warn('Trace:', trace);
    };
}

// Vue 应用（仅在管理后台页面使用）
if (document.getElementById('app')) {
    window.app = new Vue({
    el: '#app',
    data() {
        return {
            isLogin: false,
            loginLoading: false,
            activeMenu: 'dashboard',
            userInfo: {},
            searchKeyword: '',

            // 登录表单
            loginForm: {
                username: '',
                password: ''
            },

            // 统计数据
            stats: {
                users: 0,
                announcements: 0,
                banners: 0,
                configs: 0
            },

            // 分页
            pagination: {
                page: 1,
                pageSize: 20,
                total: 0
            },

            // 列表数据
            userList: [],
            announcementList: [],
            bannerList: [],
            configList: [],
            videoSourceList: [],
            menuList: [], // 动态菜单列表
            menuTree: [], // 菜单树形数据（用于菜单管理）
            emailLogList: [], // 邮箱记录列表
            iconSelectorVisible: false, // 图标选择器对话框
            iconList: [ // Element UI 图标列表
                'el-icon-s-home', 'el-icon-user', 'el-icon-bell', 'el-icon-picture', 'el-icon-video-camera',
                'el-icon-document', 'el-icon-setting', 'el-icon-menu', 'el-icon-s-tools', 'el-icon-plus',
                'el-icon-money', 'el-icon-s-custom', 'el-icon-connection', 'el-icon-s-platform', 'el-icon-goods',
                'el-icon-tickets', 'el-icon-mobile', 'el-icon-camera', 'el-icon-monitor', 'el-icon-search',
                'el-icon-share', 'el-icon-location', 'el-icon-message', 'el-icon-chat-line-round', 'el-icon-time',
                'el-icon-edit', 'el-icon-delete', 'el-icon-view', 'el-icon-refresh', 'el-icon-download',
                'el-icon-upload', 'el-icon-star-off', 'el-icon-star-on', 'el-icon-check', 'el-icon-close',
                'el-icon-warning', 'el-icon-question', 'el-icon-info', 'el-icon-circle-check', 'el-icon-circle-close',
                'el-icon-folder', 'el-icon-folder-opened', 'el-icon-files', 'el-icon-data-analysis', 'el-icon-pie-chart',
                'el-icon-shopping-cart-1', 'el-icon-shopping-cart-2', 'el-icon-sold-out', 'el-icon-present'
            ],

            // Cookie 管理
            currentCookie: '',
            cookieUpdatedAt: '',
            cookieInput: '',
            cookieLoading: false,

            // 对话框
            dialogTitle: '',
            userDialogVisible: false,
            announcementDialogVisible: false,
            bannerDialogVisible: false,
            configDialogVisible: false,
            videoSourceDialogVisible: false,
            menuDialogVisible: false, // 菜单对话框
            emailDetailDialogVisible: false, // 邮件详情对话框

            // 表单数据
            userForm: {},
            announcementForm: {},
            bannerForm: {},
            configForm: {},
            videoSourceForm: {},
            menuForm: {}, // 菜单表单
            currentEmailDetail: null, // 当前查看的邮件详情

            // 邮箱记录搜索
            searchEmailType: '',
            searchStatus: '',

            // 表单验证规则
            userFormRules: {
                username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
                password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
                email: [{ type: 'email', message: '请输入正确的邮箱', trigger: 'blur' }]
            }
        };
    },

    mounted() {
        this.checkLogin();
    },

    methods: {
        // 检查登录状态
        checkLogin() {
            const token = localStorage.getItem('token');
            const userInfo = localStorage.getItem('userInfo');
            if (token && userInfo) {
                this.isLogin = true;
                this.userInfo = JSON.parse(userInfo);
                this.loadMenuList(); // 加载菜单
                this.loadStats();
            }
        },

        // 加载菜单列表
        loadMenuList() {
            console.log('开始加载菜单...');
            axios.get('menu.php?tree=1').then(res => {
                console.log('菜单API响应:', res);
                console.log('响应code:', res.code);
                console.log('响应data:', res.data);

                if (res.code === 0) {
                    console.log('code匹配成功，开始过滤菜单');
                    // 递归过滤菜单（根据权限和状态）
                    const filterMenu = (items) => {
                        return items.filter(item => {
                            // 根据权限过滤菜单
                            if (item.permission === 'admin' && this.userInfo.role !== 'admin') {
                                return false;
                            }
                            // 过滤状态
                            if (item.status != 1) {
                                return false;
                            }
                            // 递归过滤子菜单
                            if (item.children && item.children.length > 0) {
                                item.children = filterMenu(item.children);
                            }
                            return true;
                        });
                    };

                    this.menuList = filterMenu(res.data.list);
                    console.log('过滤后的菜单列表:', this.menuList);
                } else {
                    console.log('code不匹配，期望0，实际:', res.code);
                }
            }).catch(err => {
                console.error('加载菜单失败:', err);
            });
        },

        // 登录
        handleLogin() {
            // 验证输入
            if (!this.loginForm.username || !this.loginForm.password) {
                this.$message.error('请输入用户名和密码');
                return;
            }

            // 基本格式验证
            if (this.loginForm.username.length < 3 || this.loginForm.username.length > 20) {
                this.$message.error('用户名长度应为3-20个字符');
                return;
            }

            if (this.loginForm.password.length < 6) {
                this.$message.error('密码长度至少6个字符');
                return;
            }

            this.loginLoading = true;

            // 使用标准POST请求
            axios.post(API_ENDPOINTS.LOGIN, {
                username: this.loginForm.username,
                password: this.loginForm.password
            })
            .then(res => {
                if (res.code === RESPONSE_CODE.SUCCESS) {
                    localStorage.setItem('token', res.data.token);
                    localStorage.setItem('userInfo', JSON.stringify(res.data.user));
                    this.$message.success('登录成功');
                    setTimeout(() => {
                        window.location.href = 'dashboard.php';
                    }, 500);
                } else {
                    this.$message.error(res.msg || '登录失败');
                }
            })
            .catch(err => {
                console.error('登录失败:', err);
                // 简化错误消息，不泄露系统信息
                this.$message.error('登录失败，请检查用户名和密码');
            })
            .finally(() => {
                this.loginLoading = false;
            });
        },

        // 退出登录
        handleLogout() {
            this.$confirm('确定要退出登录吗?', '提示', {
                confirmButtonText: '确定',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(() => {
                localStorage.removeItem('token');
                localStorage.removeItem('userInfo');
                this.isLogin = false;
                this.loginForm = { username: '', password: '' };
                this.$message.success('已退出登录');
            });
        },

        // 菜单切换
        handleMenuSelect(index) {
            console.log('菜单切换:', index); // 调试日志

            // 如果路径包含 .php，去掉后缀
            const menuKey = index.replace('.php', '');
            console.log('处理后的菜单键:', menuKey); // 调试日志

            this.activeMenu = menuKey;
            this.pagination.page = 1;
            this.searchKeyword = '';

            if (menuKey === 'users') {
                this.loadUsers();
            } else if (menuKey === 'announcements') {
                this.loadAnnouncements();
            } else if (menuKey === 'banners') {
                this.loadBanners();
            } else if (menuKey === 'config') {
                this.loadConfigs();
            } else if (menuKey === 'video_sources') {
                this.loadVideoSources();
            } else if (menuKey === 'cookies') {
                this.loadCookieConfig();
            } else if (menuKey === 'menu') {
                console.log('加载菜单管理...'); // 调试日志
                this.loadMenus();
            } else if (menuKey === 'email_logs') {
                console.log('加载邮箱记录...'); // 调试日志
                this.loadEmailLogs();
            }
        },

        // 加载统计数据
        async loadStats() {
            try {
                // 并行请求所有统计数据
                const [usersRes, announcementsRes, bannersRes, configsRes] = await Promise.all([
                    axios.get(API_ENDPOINTS.USERS + '?page=1&pageSize=1'),
                    axios.get(API_ENDPOINTS.ANNOUNCEMENTS + '?page=1&pageSize=1'),
                    axios.get(API_ENDPOINTS.BANNERS + '?page=1&pageSize=1'),
                    axios.get(API_ENDPOINTS.CONFIGS)
                ]);

                // 更新统计数据
                if (usersRes.code === RESPONSE_CODE.SUCCESS) {
                    this.stats.users = parseInt(usersRes.data.total) || 0;
                }
                if (announcementsRes.code === RESPONSE_CODE.SUCCESS) {
                    this.stats.announcements = parseInt(announcementsRes.data.total) || 0;
                }
                if (bannersRes.code === RESPONSE_CODE.SUCCESS) {
                    this.stats.banners = parseInt(bannersRes.data.total) || 0;
                }
                if (configsRes.code === RESPONSE_CODE.SUCCESS) {
                    this.stats.configs = configsRes.data.list.length;
                }
            } catch (error) {
                console.error('加载统计数据失败:', error);
                this.$message.warning('部分统计数据加载失败');
            }
        },

        // 通用数据加载方法
        async loadListData(endpoint, listKey, extraParams = {}) {
            try {
                const params = {
                    page: this.pagination.page,
                    pageSize: this.pagination.pageSize,
                    ...extraParams
                };

                const res = await axios.get(endpoint, { params });

                if (res.code === RESPONSE_CODE.SUCCESS) {
                    this[listKey] = res.data.list || [];
                    this.pagination.total = parseInt(res.data.total) || 0;
                } else {
                    this.$message.error(res.msg || '加载数据失败');
                }
            } catch (error) {
                console.error(`加载${listKey}失败:`, error);
                this.$message.error('加载数据失败');
            }
        },

        // 加载用户列表
        loadUsers() {
            this.loadListData(API_ENDPOINTS.USERS, 'userList', {
                keyword: this.searchKeyword
            });
        },

        // 加载公告列表
        loadAnnouncements() {
            this.loadListData(API_ENDPOINTS.ANNOUNCEMENTS, 'announcementList');
        },

        // 加载轮播列表
        loadBanners() {
            this.loadListData(API_ENDPOINTS.BANNERS, 'bannerList');
        },

        // 加载配置列表
        loadConfigs() {
            axios.get(API_ENDPOINTS.CONFIGS).then(res => {
                if (res.code === RESPONSE_CODE.SUCCESS) {
                    this.configList = res.data.list;
                }
            }).catch(error => {
                console.error('加载配置失败:', error);
                this.$message.error('加载配置失败');
            });
        },

        // 加载视频源列表
        loadVideoSources() {
            this.loadListData(API_ENDPOINTS.VIDEO_SOURCES, 'videoSourceList');
        },

        // 加载 Cookie 配置
        loadCookieConfig() {
            axios.get('api_get_cookie.php').then(res => {
                if (res.code === 0) {
                    this.currentCookie = res.data.cookie || '';
                    this.cookieUpdatedAt = res.data.updated_at || '从未更新';
                } else {
                    this.$message.error('加载 Cookie 配置失败');
                }
            }).catch(() => {
                this.$message.error('加载 Cookie 配置失败');
            });
        },

        // 更新 Cookie 配置
        updateCookieConfig() {
            if (!this.cookieInput.trim()) {
                this.$message.error('请输入 Cookie 值');
                return;
            }

            this.cookieLoading = true;
            axios.post('api_update_cookie.php', {
                cookie: this.cookieInput
            }).then(res => {
                if (res.code === 0) {
                    this.$message.success('Cookie 更新成功');
                    this.cookieInput = '';
                    this.loadCookieConfig();
                } else {
                    this.$message.error('更新失败: ' + res.msg);
                }
            }).catch(() => {
                this.$message.error('更新失败');
            }).finally(() => {
                this.cookieLoading = false;
            });
        },

        // 分页切换
        handlePageChange(page) {
            this.pagination.page = page;
            if (this.activeMenu === 'users') {
                this.loadUsers();
            } else if (this.activeMenu === 'announcements') {
                this.loadAnnouncements();
            } else if (this.activeMenu === 'banners') {
                this.loadBanners();
            } else if (this.activeMenu === 'video_sources') {
                this.loadVideoSources();
            } else if (this.activeMenu === 'email_logs') {
                this.loadEmailLogs();
            }
        },

        // 添加
        handleAdd(type) {
            this.dialogTitle = '添加' + this.getTypeName(type);

            if (type === 'user') {
                this.userForm = { role: 'user', status: 1 };
                this.userDialogVisible = true;
            } else if (type === 'announcement') {
                this.announcementForm = { type: 'info', priority: 0, status: 1 };
                this.announcementDialogVisible = true;
            } else if (type === 'banner') {
                this.bannerForm = { link_type: 'none', position: 'home', sort_order: 0, status: 1 };
                this.bannerDialogVisible = true;
            } else if (type === 'config') {
                this.configForm = { config_type: 'text', group_name: 'default', sort_order: 0 };
                this.configDialogVisible = true;
            } else if (type === 'video_source') {
                this.videoSourceForm = { source_type: 'API', sort_order: 0, status: 1 };
                this.videoSourceDialogVisible = true;
            }
        },

        // 编辑
        handleEdit(type, row) {
            this.dialogTitle = '编辑' + this.getTypeName(type);

            if (type === 'user') {
                this.userForm = { ...row, password: '' };
                this.userDialogVisible = true;
            } else if (type === 'announcement') {
                this.announcementForm = { ...row };
                this.announcementDialogVisible = true;
            } else if (type === 'banner') {
                this.bannerForm = { ...row };
                this.bannerDialogVisible = true;
            } else if (type === 'config') {
                this.configForm = { ...row };
                this.configDialogVisible = true;
            } else if (type === 'video_source') {
                this.videoSourceForm = { ...row };
                this.videoSourceDialogVisible = true;
            }
        },

        // 删除
        handleDelete(type, id) {
            this.$confirm('确定要删除吗?', '提示', {
                confirmButtonText: '确定',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(() => {
                let url = '';
                if (type === 'user') url = 'users.php';
                else if (type === 'announcement') url = 'announcements.php';
                else if (type === 'banner') url = 'banners.php';
                else if (type === 'config') url = 'config_api.php';
                else if (type === 'video_source') url = 'video_sources.php';

                axios.delete(url + '?id=' + id).then(res => {
                    if (res.code === 0) {
                        this.$message.success(res.msg);
                        this.reloadCurrentList();
                    } else {
                        this.$message.error(res.msg);
                    }
                });
            });
        },

        // 切换状态
        toggleStatus(type, row) {
            const newStatus = row.status == 1 ? 0 : 1;
            let url = '';
            if (type === 'user') url = 'users.php';
            else if (type === 'announcement') url = 'announcements.php';
            else if (type === 'banner') url = 'banners.php';
            else if (type === 'video_source') url = 'video_sources.php';

            axios.put(url, { id: row.id, status: newStatus }).then(res => {
                if (res.code === 0) {
                    row.status = newStatus;
                    this.$message.success('状态已更新');
                } else {
                    this.$message.error(res.msg);
                }
            });
        },

        // 提交用户表单
        submitUserForm() {
            this.$refs.userForm.validate(valid => {
                if (!valid) return;

                const method = this.userForm.id ? 'put' : 'post';
                axios[method]('users.php', this.userForm).then(res => {
                    if (res.code === 0) {
                        this.$message.success(res.msg);
                        this.userDialogVisible = false;
                        this.loadUsers();
                        this.loadStats();
                    } else {
                        this.$message.error(res.msg);
                    }
                });
            });
        },

        // 提交公告表单
        submitAnnouncementForm() {
            if (!this.announcementForm.title || !this.announcementForm.content) {
                this.$message.error('标题和内容不能为空');
                return;
            }

            const method = this.announcementForm.id ? 'put' : 'post';
            const data = { ...this.announcementForm };
            if (data.start_time) data.start_time = this.formatDateTime(data.start_time);
            if (data.end_time) data.end_time = this.formatDateTime(data.end_time);

            axios[method]('announcements.php', data).then(res => {
                if (res.code === 0) {
                    this.$message.success(res.msg);
                    this.announcementDialogVisible = false;
                    this.loadAnnouncements();
                    this.loadStats();
                } else {
                    this.$message.error(res.msg);
                }
            });
        },

        // 提交轮播表单
        submitBannerForm() {
            if (!this.bannerForm.title || !this.bannerForm.image_url) {
                this.$message.error('标题和图片不能为空');
                return;
            }

            const method = this.bannerForm.id ? 'put' : 'post';
            const data = { ...this.bannerForm };
            if (data.start_time) data.start_time = this.formatDateTime(data.start_time);
            if (data.end_time) data.end_time = this.formatDateTime(data.end_time);

            axios[method]('banners.php', data).then(res => {
                if (res.code === 0) {
                    this.$message.success(res.msg);
                    this.bannerDialogVisible = false;
                    this.loadBanners();
                    this.loadStats();
                } else {
                    this.$message.error(res.msg);
                }
            });
        },

        // 提交配置表单
        submitConfigForm() {
            if (!this.configForm.config_key) {
                this.$message.error('配置键不能为空');
                return;
            }

            const method = this.configForm.id ? 'put' : 'post';
            axios[method]('config_api.php', this.configForm).then(res => {
                if (res.code === 0) {
                    this.$message.success(res.msg);
                    this.configDialogVisible = false;
                    this.loadConfigs();
                    this.loadStats();
                } else {
                    this.$message.error(res.msg);
                }
            });
        },

        // 提交视频源表单
        submitVideoSourceForm() {
            if (!this.videoSourceForm.source_key || !this.videoSourceForm.source_name || !this.videoSourceForm.base_url) {
                this.$message.error('源标识、源名称和基础URL不能为空');
                return;
            }

            const method = this.videoSourceForm.id ? 'put' : 'post';
            axios[method]('video_sources.php', this.videoSourceForm).then(res => {
                if (res.code === 0) {
                    this.$message.success(res.msg);
                    this.videoSourceDialogVisible = false;
                    this.loadVideoSources();
                    this.loadStats();
                } else {
                    this.$message.error(res.msg);
                }
            });
        },

        // 重新加载当前列表
        reloadCurrentList() {
            if (this.activeMenu === 'users') {
                this.loadUsers();
            } else if (this.activeMenu === 'announcements') {
                this.loadAnnouncements();
            } else if (this.activeMenu === 'banners') {
                this.loadBanners();
            } else if (this.activeMenu === 'config') {
                this.loadConfigs();
            } else if (this.activeMenu === 'video_sources') {
                this.loadVideoSources();
            }
            this.loadStats();
        },

        // 获取类型名称
        getTypeName(type) {
            const names = {
                user: '用户',
                announcement: '公告',
                banner: '轮播',
                config: '配置',
                video_source: '视频源'
            };
            return names[type] || '';
        },

        // 打开 Cookie 管理页面
        openCookieManager() {
            window.open('cookie.html', '_blank');
        },
        formatDateTime(date) {
            if (!date) return null;
            const d = new Date(date);
            const year = d.getFullYear();
            const month = String(d.getMonth() + 1).padStart(2, '0');
            const day = String(d.getDate()).padStart(2, '0');
            const hour = String(d.getHours()).padStart(2, '0');
            const minute = String(d.getMinutes()).padStart(2, '0');
            const second = String(d.getSeconds()).padStart(2, '0');
            return `${year}-${month}-${day} ${hour}:${minute}:${second}`;
        },

        // ========== 菜单管理 ==========
        loadMenus() {
            console.log('开始加载菜单数据...'); // 调试日志
            axios.get('menu.php?tree=1').then(res => {
                console.log('菜单数据响应:', res); // 调试日志
                if (res.code === 0) {
                    this.menuTree = res.data.list;
                    console.log('菜单树数据:', this.menuTree); // 调试日志
                    console.log('菜单树JSON:', JSON.stringify(this.menuTree, null, 2)); // 详细查看结构
                } else {
                    console.error('加载菜单失败:', res.msg);
                }
            }).catch(err => {
                console.error('加载菜单出错:', err); // 调试日志
            });
        },

        handleAddMenu(row) {
            this.dialogTitle = '添加菜单';
            this.menuForm = {
                parent_id: row ? row.id : 0,
                name: '',
                icon: '',
                path: '',
                sort: 0,
                permission: '',
                status: 1
            };
            this.menuDialogVisible = true;
        },

        handleEditMenu(row) {
            this.dialogTitle = '编辑菜单';
            this.menuForm = { ...row };
            this.menuDialogVisible = true;
        },

        submitMenuForm() {
            const method = this.menuForm.id ? 'put' : 'post';
            axios[method]('menu.php', this.menuForm).then(res => {
                if (res.code === 0) {
                    this.$message.success(res.msg);
                    this.menuDialogVisible = false;
                    this.loadMenus();
                    this.loadMenuList(); // 重新加载侧边栏菜单
                } else {
                    this.$message.error(res.msg);
                }
            });
        },

        handleDeleteMenu(row) {
            this.$confirm('确定要删除这个菜单吗？', '提示', {
                type: 'warning'
            }).then(() => {
                axios.delete(`menu.php?id=${row.id}`).then(res => {
                    if (res.code === 0) {
                        this.$message.success(res.msg);
                        this.loadMenus();
                        this.loadMenuList(); // 重新加载侧边栏菜单
                    } else {
                        this.$message.error(res.msg);
                    }
                });
            }).catch(() => {});
        },

        handleMenuStatusChange(row) {
            axios.put('menu.php', { id: row.id, status: row.status, name: row.name }).then(res => {
                if (res.code === 0) {
                    this.$message.success('状态更新成功');
                    this.loadMenuList(); // 重新加载侧边栏菜单
                } else {
                    this.$message.error(res.msg);
                    row.status = row.status === '1' ? '0' : '1'; // 恢复原状态
                }
            });
        },

        // 显示图标选择器
        showIconSelector() {
            this.iconSelectorVisible = true;
        },

        // 选择图标
        selectIcon(icon) {
            this.menuForm.icon = icon;
            this.iconSelectorVisible = false;
        },

        // ========== 邮箱记录管理 ==========
        // 加载邮箱记录
        loadEmailLogs() {
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
                } else {
                    this.$message.error('加载邮箱记录失败');
                }
            }).catch(err => {
                console.error('加载邮箱记录失败:', err);
                this.$message.error('加载邮箱记录失败');
            });
        },

        // 重置邮箱记录搜索
        resetEmailSearch() {
            this.searchKeyword = '';
            this.searchEmailType = '';
            this.searchStatus = '';
            this.pagination.page = 1;
            this.loadEmailLogs();
        },

        // 查看邮件详情
        viewEmailDetail(row) {
            this.currentEmailDetail = row;
            this.emailDetailDialogVisible = true;
        },

        // 删除邮件记录
        deleteEmailLog(id) {
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
        },

        // 获取邮件类型名称
        getEmailTypeName(type) {
            const types = {
                'register': '注册验证',
                'reset_password': '密码重置',
                'notification': '通知',
                'vip_expire': 'VIP到期提醒',
                'invite': '邀请',
                'system': '系统通知'
            };
            return types[type] || type;
        },

        // 获取邮件类型标签颜色
        getEmailTypeTagType(type) {
            const types = {
                'register': 'success',
                'reset_password': 'warning',
                'notification': 'info',
                'vip_expire': 'danger',
                'invite': 'primary',
                'system': ''
            };
            return types[type] || '';
        }
    }
    });
}
