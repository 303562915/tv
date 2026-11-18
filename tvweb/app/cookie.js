// Cookie 管理页面独立脚本
new Vue({
    el: '.container',
    data() {
        return {
            currentCookie: '加载中...',
            updatedAt: '-',
            cookieInput: '',
            loading: false
        };
    },
    mounted() {
        this.loadCookie();
    },
    methods: {
        // 加载 Cookie
        loadCookie() {
            fetch('./api/api_get_cookie.php')
                .then(res => res.json())
                .then(data => {
                    if (data.code === 200) {
                        this.currentCookie = data.data.cookie || '暂无配置';
                        this.updatedAt = data.data.updated_at || '从未更新';
                    } else {
                        this.showMessage('加载失败', 'error');
                    }
                })
                .catch(() => {
                    this.showMessage('加载失败', 'error');
                });
        },

        // 更新 Cookie
        updateCookie() {
            if (!this.cookieInput.trim()) {
                this.showMessage('请输入 Cookie 值', 'error');
                return;
            }

            this.loading = true;
            fetch('./api/api_update_cookie.php', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    cookie: this.cookieInput
                })
            })
            .then(res => res.json())
            .then(data => {
                if (data.code === 200) {
                    this.showMessage('更新成功', 'success');
                    this.cookieInput = '';
                    this.loadCookie();
                } else {
                    this.showMessage('更新失败: ' + data.msg, 'error');
                }
            })
            .catch(() => {
                this.showMessage('更新失败', 'error');
            })
            .finally(() => {
                this.loading = false;
            });
        },

        // 显示消息
        showMessage(text, type) {
            const msgDiv = document.getElementById('message');
            msgDiv.textContent = text;
            msgDiv.className = 'message ' + (type === 'success' ? 'success' : 'error');
            msgDiv.style.display = 'block';
            setTimeout(() => {
                msgDiv.style.display = 'none';
            }, 3000);
        }
    }
});
