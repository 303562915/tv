<div class="page-header">
    <div class="page-title">Cookie 配置管理</div>
</div>

<div class="content-box">
    <el-card shadow="hover" style="margin-bottom: 20px;">
        <div slot="header">
            <span style="font-weight: bold;">当前 Cookie 配置</span>
        </div>
        <el-descriptions :column="1" border v-loading="loading">
            <el-descriptions-item label="Cookie 内容">
                <div style="word-break: break-all; font-family: monospace; font-size: 12px;">
                    {{ currentCookie || '(未设置)' }}
                </div>
            </el-descriptions-item>
            <el-descriptions-item label="最后更新时间">
                {{ cookieUpdatedAt || '从未更新' }}
            </el-descriptions-item>
        </el-descriptions>
    </el-card>

    <el-card shadow="hover">
        <div slot="header">
            <span style="font-weight: bold;">更新 Cookie</span>
        </div>
        <el-form label-width="100px">
            <el-form-item label="Cookie 值">
                <el-input
                    v-model="cookieInput"
                    type="textarea"
                    :rows="6"
                    placeholder="请输入 Cookie 字符串"
                    style="font-family: monospace;">
                </el-input>
            </el-form-item>
            <el-form-item>
                <el-button type="primary" @click="updateCookieConfig" :loading="cookieLoading">
                    更新 Cookie
                </el-button>
                <el-button @click="loadCookieData">刷新</el-button>
            </el-form-item>
        </el-form>
    </el-card>
</div>

<script>
// 扩展Vue实例数据
Object.assign(app.$data, {
    loading: false,
    cookieLoading: false,
    currentCookie: '',
    cookieUpdatedAt: '',
    cookieInput: ''
});

// 扩展Vue实例方法
Object.assign(app, {
    // 加载 Cookie 配置
    loadCookieData() {
        this.loading = true;
        axios.get('api_get_cookie.php').then(res => {
            if (res.code === 200) {
                this.currentCookie = res.data.cookie || '';
                this.cookieUpdatedAt = res.data.updated_at || '从未更新';
            } else {
                this.$message.error('加载 Cookie 配置失败');
            }
        }).catch(() => {
            this.$message.error('加载 Cookie 配置失败');
        }).finally(() => {
            this.loading = false;
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
            if (res.code === 200) {
                this.$message.success('Cookie 更新成功');
                this.cookieInput = '';
                this.loadCookieData();
            } else {
                this.$message.error('更新失败: ' + res.msg);
            }
        }).catch(() => {
            this.$message.error('更新失败');
        }).finally(() => {
            this.cookieLoading = false;
        });
    }
});

// 页面加载时获取数据
app.loadCookieData();
</script>
