<?php
// 系统设置页面 - 紧凑布局
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>系统设置 - TV管理后台</title>
    <link rel="stylesheet" href="assets/css/element-ui.css">
    <style>
        body { margin: 0; padding: 0; font-family: 'Microsoft YaHei', Arial, sans-serif; background: #f0f2f5; }
        .page-header { background: white; padding: 15px 20px; margin-bottom: 15px; border-radius: 4px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .page-title { font-size: 18px; font-weight: bold; color: #333; }
        .content-box { background: white; padding: 20px; border-radius: 4px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); margin-bottom: 15px; }
        .section-title { font-size: 14px; font-weight: bold; color: #333; margin-bottom: 15px; padding-bottom: 8px; border-bottom: 2px solid #409EFF; }
        .el-form-item { margin-bottom: 15px; }
        .el-form-item__label { font-size: 13px; }
        .hint { color: #999; font-size: 12px; margin-left: 5px; }
    </style>
</head>
<body>
    <div id="app">
        <div class="page-header">
            <div class="page-title">系统设置</div>
        </div>

        <!-- 基础设置 -->
        <div class="content-box">
            <div class="section-title">基础设置</div>
            <el-form :model="basicSettings" label-width="100px" size="small">
                <el-row :gutter="15">
                    <el-col :span="12">
                        <el-form-item label="网站名称">
                            <el-input v-model="basicSettings.site_name"></el-input>
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item label="网站标题">
                            <el-input v-model="basicSettings.site_title"></el-input>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-row :gutter="15">
                    <el-col :span="12">
                        <el-form-item label="网站Logo">
                            <el-input v-model="basicSettings.site_logo"></el-input>
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item label="ICP备案号">
                            <el-input v-model="basicSettings.icp_number"></el-input>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-form-item label="网站关键词">
                    <el-input v-model="basicSettings.site_keywords" type="textarea" :rows="2"></el-input>
                </el-form-item>
                <el-form-item label="网站描述">
                    <el-input v-model="basicSettings.site_description" type="textarea" :rows="2"></el-input>
                </el-form-item>
                <el-form-item>
                    <el-button type="primary" size="small" @click="saveSettings('basic')">保存</el-button>
                </el-form-item>
            </el-form>
        </div>

        <!-- 会员设置 -->
        <div class="content-box">
            <div class="section-title">会员设置</div>
            <el-form :model="memberSettings" label-width="100px" size="small">
                <el-row :gutter="15">
                    <el-col :span="8">
                        <el-form-item label="注册积分">
                            <el-input-number v-model="memberSettings.register_points" :min="0" controls-position="right" style="width:100%;"></el-input-number>
                        </el-form-item>
                    </el-col>
                    <el-col :span="8">
                        <el-form-item label="邀请积分">
                            <el-input-number v-model="memberSettings.invite_points" :min="0" controls-position="right" style="width:100%;"></el-input-number>
                        </el-form-item>
                    </el-col>
                    <el-col :span="8">
                        <el-form-item label="签到积分">
                            <el-input-number v-model="memberSettings.signin_points" :min="0" controls-position="right" style="width:100%;"></el-input-number>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-row :gutter="15">
                    <el-col :span="6">
                        <el-form-item label="VIP月费">
                            <el-input-number v-model="memberSettings.vip_month_price" :min="0" :precision="2" controls-position="right" style="width:100%;"></el-input-number>
                        </el-form-item>
                    </el-col>
                    <el-col :span="6">
                        <el-form-item label="VIP年费">
                            <el-input-number v-model="memberSettings.vip_year_price" :min="0" :precision="2" controls-position="right" style="width:100%;"></el-input-number>
                        </el-form-item>
                    </el-col>
                    <el-col :span="6">
                        <el-form-item label="SVIP月费">
                            <el-input-number v-model="memberSettings.svip_month_price" :min="0" :precision="2" controls-position="right" style="width:100%;"></el-input-number>
                        </el-form-item>
                    </el-col>
                    <el-col :span="6">
                        <el-form-item label="SVIP年费">
                            <el-input-number v-model="memberSettings.svip_year_price" :min="0" :precision="2" controls-position="right" style="width:100%;"></el-input-number>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-row :gutter="15">
                    <el-col :span="12">
                        <el-form-item label="邮箱注册">
                            <el-switch v-model="memberSettings.allow_email_register"></el-switch>
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item label="用户名注册">
                            <el-switch v-model="memberSettings.allow_username_register"></el-switch>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-form-item>
                    <el-button type="primary" size="small" @click="saveSettings('member')">保存</el-button>
                </el-form-item>
            </el-form>
        </div>

        <!-- 邮件设置 -->
        <div class="content-box">
            <div class="section-title">邮件设置</div>
            <el-form :model="emailSettings" label-width="100px" size="small">
                <el-row :gutter="15">
                    <el-col :span="12">
                        <el-form-item label="SMTP服务器">
                            <el-input v-model="emailSettings.smtp_host"></el-input>
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item label="SMTP端口">
                            <el-input-number v-model="emailSettings.smtp_port" :min="1" :max="65535" controls-position="right" style="width:100%;"></el-input-number>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-row :gutter="15">
                    <el-col :span="12">
                        <el-form-item label="发件人邮箱">
                            <el-input v-model="emailSettings.smtp_user"></el-input>
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item label="邮箱授权码">
                            <el-input v-model="emailSettings.smtp_pass" type="password" show-password></el-input>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-row :gutter="15">
                    <el-col :span="12">
                        <el-form-item label="发件人名称">
                            <el-input v-model="emailSettings.smtp_from_name"></el-input>
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item label="测试邮件">
                            <el-input v-model="testEmail">
                                <el-button slot="append" @click="sendTestEmail" :loading="testEmailLoading">发送</el-button>
                            </el-input>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-row :gutter="15">
                    <el-col :span="12">
                        <el-form-item label="次数限制">
                            <el-switch v-model="emailSettings.email_limit_enabled"></el-switch>
                            <span class="hint">开启后限制每个邮箱每日发送次数</span>
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item label="每日限制">
                            <el-input-number v-model="emailSettings.email_daily_limit" :min="1" :max="1000" controls-position="right" style="width:100%;"></el-input-number>
                            <span class="hint">次/天</span>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-form-item>
                    <el-button type="primary" size="small" @click="saveSettings('email')">保存</el-button>
                </el-form-item>
            </el-form>
        </div>

        <!-- 安全设置 -->
        <div class="content-box">
            <div class="section-title">安全设置</div>
            <el-form :model="securitySettings" label-width="120px" size="small">
                <el-row :gutter="15">
                    <el-col :span="8">
                        <el-form-item label="验证码有效期">
                            <el-input-number v-model="securitySettings.code_expire_minutes" :min="1" :max="60" controls-position="right" style="width:100%;"></el-input-number>
                            <span class="hint">分钟</span>
                        </el-form-item>
                    </el-col>
                    <el-col :span="8">
                        <el-form-item label="发送间隔">
                            <el-input-number v-model="securitySettings.code_send_interval" :min="30" :max="300" controls-position="right" style="width:100%;"></el-input-number>
                            <span class="hint">秒</span>
                        </el-form-item>
                    </el-col>
                    <el-col :span="8">
                        <el-form-item label="每日上限">
                            <el-input-number v-model="securitySettings.code_daily_limit" :min="1" :max="100" controls-position="right" style="width:100%;"></el-input-number>
                            <span class="hint">次</span>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-row :gutter="15">
                    <el-col :span="8">
                        <el-form-item label="Token有效期">
                            <el-input-number v-model="securitySettings.token_expire_days" :min="1" :max="365" controls-position="right" style="width:100%;"></el-input-number>
                            <span class="hint">天</span>
                        </el-form-item>
                    </el-col>
                    <el-col :span="8">
                        <el-form-item label="密码最小长度">
                            <el-input-number v-model="securitySettings.password_min_length" :min="6" :max="20" controls-position="right" style="width:100%;"></el-input-number>
                            <span class="hint">位</span>
                        </el-form-item>
                    </el-col>
                    <el-col :span="8">
                        <el-form-item label="登录日志">
                            <el-switch v-model="securitySettings.enable_login_log"></el-switch>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-form-item>
                    <el-button type="primary" size="small" @click="saveSettings('security')">保存</el-button>
                </el-form-item>
            </el-form>
        </div>

        <!-- 其他设置 -->
        <div class="content-box">
            <div class="section-title">其他设置</div>
            <el-form :model="otherSettings" label-width="100px" size="small">
                <el-row :gutter="15">
                    <el-col :span="24">
                        <el-form-item label="维护模式">
                            <el-switch v-model="otherSettings.maintenance_mode"></el-switch>
                            <span class="hint">开启后前台显示维护提示</span>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-form-item label="维护提示">
                    <el-input v-model="otherSettings.maintenance_message" type="textarea" :rows="2"></el-input>
                </el-form-item>
                <el-row :gutter="15">
                    <el-col :span="8">
                        <el-form-item label="客服QQ">
                            <el-input v-model="otherSettings.service_qq"></el-input>
                        </el-form-item>
                    </el-col>
                    <el-col :span="8">
                        <el-form-item label="客服微信">
                            <el-input v-model="otherSettings.service_wechat"></el-input>
                        </el-form-item>
                    </el-col>
                    <el-col :span="8">
                        <el-form-item label="客服邮箱">
                            <el-input v-model="otherSettings.service_email"></el-input>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-form-item>
                    <el-button type="primary" size="small" @click="saveSettings('other')">保存</el-button>
                </el-form-item>
            </el-form>
        </div>
    </div>

    <script src="assets/js/vue.js"></script>
    <script src="assets/js/element-ui.js"></script>
    <script src="assets/js/axios.min.js"></script>
    <script>
        axios.defaults.baseURL = "./";
        axios.interceptors.response.use(response => response.data, error => Promise.reject(error));
    </script>
    <script>
        new Vue({
            el: '#app',
            data: {
                basicSettings: {
                    site_name: '',
                    site_title: '',
                    site_keywords: '',
                    site_description: '',
                    site_logo: '',
                    icp_number: ''
                },
                memberSettings: {
                    register_points: 10,
                    invite_points: 20,
                    signin_points: 5,
                    vip_month_price: 15.00,
                    vip_year_price: 150.00,
                    svip_month_price: 30.00,
                    svip_year_price: 300.00,
                    allow_email_register: true,
                    allow_username_register: true
                },
                emailSettings: {
                    smtp_host: 'smtp.qq.com',
                    smtp_port: 465,
                    smtp_user: '',
                    smtp_pass: '',
                    smtp_from_name: 'TV会员系统',
                    email_limit_enabled: true,
                    email_daily_limit: 10
                },
                securitySettings: {
                    code_expire_minutes: 5,
                    code_send_interval: 60,
                    code_daily_limit: 10,
                    token_expire_days: 7,
                    password_min_length: 6,
                    enable_login_log: true
                },
                otherSettings: {
                    maintenance_mode: false,
                    maintenance_message: '系统维护中，请稍后访问',
                    service_qq: '',
                    service_wechat: '',
                    service_email: ''
                },
                testEmail: '',
                testEmailLoading: false
            },
            mounted() {
                this.loadSettings();
            },
            methods: {
                loadSettings() {
                    axios.get('api/admin/system_settings.php').then(res => {
                        if (res.code === 0) {
                            const settings = res.data;
                            Object.keys(settings).forEach(key => {
                                const value = settings[key];
                                if (this.basicSettings.hasOwnProperty(key)) {
                                    this.basicSettings[key] = value;
                                } else if (this.memberSettings.hasOwnProperty(key)) {
                                    this.memberSettings[key] = value;
                                } else if (this.emailSettings.hasOwnProperty(key)) {
                                    this.emailSettings[key] = value;
                                } else if (this.securitySettings.hasOwnProperty(key)) {
                                    this.securitySettings[key] = value;
                                } else if (this.otherSettings.hasOwnProperty(key)) {
                                    this.otherSettings[key] = value;
                                }
                            });
                        }
                    });
                },
                saveSettings(type) {
                    let settings = {};
                    if (type === 'basic') {
                        settings = this.basicSettings;
                    } else if (type === 'member') {
                        settings = this.memberSettings;
                    } else if (type === 'email') {
                        settings = this.emailSettings;
                    } else if (type === 'security') {
                        settings = this.securitySettings;
                    } else if (type === 'other') {
                        settings = this.otherSettings;
                    }

                    axios.post('api/admin/system_settings.php', {
                        type: type,
                        settings: settings
                    }).then(res => {
                        if (res.code === 0) {
                            this.$message.success('保存成功');
                        } else {
                            this.$message.error(res.msg);
                        }
                    });
                },
                sendTestEmail() {
                    if (!this.testEmail) {
                        this.$message.warning('请输入测试邮箱地址');
                        return;
                    }
                    this.testEmailLoading = true;
                    axios.post('api/admin/test_email.php', {
                        email: this.testEmail
                    }).then(res => {
                        if (res.code === 0) {
                            this.$message.success('测试邮件已发送');
                        } else {
                            this.$message.error(res.msg);
                        }
                    }).finally(() => {
                        this.testEmailLoading = false;
                    });
                }
            }
        });
    </script>
</body>
</html>
