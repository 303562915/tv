                </el-main>
            </el-container>
        </el-container>
    </div>

    <script src="assets/js/vue.js"></script>
    <script src="assets/js/element-ui.js"></script>
    <script src="assets/js/axios.min.js"></script>
    <script>
    // Axios 配置
    axios.defaults.baseURL = './api/';
    axios.interceptors.response.use(
        response => response.data,
        error => {
            if (error.response && error.response.status === 401) {
                window.location.href = 'index.html';
            }
            return Promise.reject(error);
        }
    );

    // Vue 实例
    window.app = new Vue({
        el: '#app',
        data() {
            return {
                menuList: [],
                currentPath: '<?php echo $mod; ?>',
                userInfo: <?php echo json_encode($userInfo); ?>
            };
        },
        mounted() {
            this.loadMenuList();
        },
        methods: {
            // 加载菜单列表
            loadMenuList() {
                axios.get('menu.php').then(res => {
                    if (res.code === 200) {
                        this.menuList = res.data.list.filter(item => {
                            if (item.permission === 'admin' && this.userInfo.role !== 'admin') {
                                return false;
                            }
                            return item.status == 1;
                        });
                    }
                });
            },

            // 跳转页面
            goToPage(path) {
                console.log('跳转到:', path);
                if (!path) {
                    console.warn('路径为空，跳转到仪表盘');
                    path = 'dashboard.php';
                }
                window.location.href = path;
            },

            // 退出登录
            handleLogout() {
                this.$confirm('确定要退出登录吗？', '提示', {
                    type: 'warning'
                }).then(() => {
                    document.cookie = 'token=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/;';
                    document.cookie = 'userInfo=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/;';
                    localStorage.clear();
                    window.location.href = 'index.html';
                });
            }
        }
    });
    </script>
</body>
</html>
