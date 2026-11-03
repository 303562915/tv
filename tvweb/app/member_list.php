<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>会员列表 - TV管理后台</title>
    <link rel="stylesheet" href="assets/css/element-ui.css">
    <style>
        body { margin: 0; padding: 0; font-family: 'Microsoft YaHei', Arial, sans-serif; }
        .page-header { background: white; padding: 20px; margin-bottom: 20px; border-radius: 4px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .page-title { font-size: 20px; font-weight: bold; color: #333; }
        .content-box { background: white; padding: 20px; border-radius: 4px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .toolbar { margin-bottom: 20px; }
        .pagination { margin-top: 20px; text-align: right; }
        .status-tag { cursor: pointer; }
    </style>
</head>
<body>
    <div id="app">
        <div class="page-header">
            <div class="page-title">会员列表</div>
        </div>
        <div class="content-box">
            <div class="toolbar">
                <el-input v-model="searchKeyword" placeholder="搜索用户名/邮箱" style="width: 300px;" clearable>
                    <el-button slot="append" icon="el-icon-search" @click="loadMembers"></el-button>
                </el-input>
                <el-select v-model="searchLevel" placeholder="会员等级" clearable style="width: 150px; margin-left: 10px;" @change="loadMembers">
                    <el-option label="普通会员" value="normal"></el-option>
                    <el-option label="VIP" value="vip"></el-option>
                    <el-option label="SVIP" value="svip"></el-option>
                </el-select>
                <el-select v-model="searchStatus" placeholder="状态" clearable style="width: 150px; margin-left: 10px;" @change="loadMembers">
                    <el-option label="正常" value="normal"></el-option>
                    <el-option label="冻结" value="frozen"></el-option>
                    <el-option label="封禁" value="banned"></el-option>
                </el-select>
            </div>
            <el-table :data="memberList" border stripe v-loading="loading">
                <el-table-column prop="id" label="ID" width="80"></el-table-column>
                <el-table-column prop="username" label="用户名" width="120"></el-table-column>
                <el-table-column prop="email" label="邮箱" min-width="180"></el-table-column>
                <el-table-column prop="nickname" label="昵称" width="120"></el-table-column>
                <el-table-column prop="level" label="等级" width="100">
                    <template slot-scope="scope">
                        <el-tag :type="scope.row.level === 'svip' ? 'danger' : scope.row.level === 'vip' ? 'warning' : 'info'" size="small">
                            {{ scope.row.level === 'svip' ? 'SVIP' : scope.row.level === 'vip' ? 'VIP' : '普通' }}
                        </el-tag>
                    </template>
                </el-table-column>
                <el-table-column prop="points" label="积分" width="100"></el-table-column>
                <el-table-column prop="balance" label="积分" width="100">
                    <template slot-scope="scope">
                        ¥{{ scope.row.balance }}
                    </template>
                </el-table-column>
                <el-table-column prop="status" label="状态" width="100">
                    <template slot-scope="scope">
                        <el-tag :type="scope.row.status === 'normal' ? 'success' : scope.row.status === 'frozen' ? 'warning' : 'danger'" size="small">
                            {{ scope.row.status === 'normal' ? '正常' : scope.row.status === 'frozen' ? '冻结' : '封禁' }}
                        </el-tag>
                    </template>
                </el-table-column>
                <el-table-column prop="invite_code" label="推荐码" width="100"></el-table-column>
                <el-table-column prop="created_at" label="注册时间" width="160"></el-table-column>
                <el-table-column label="操作" width="200" fixed="right">
                    <template slot-scope="scope">
                        <el-button type="text" size="small" @click="viewDetail(scope.row)">详情</el-button>
                        <el-button type="text" size="small" @click="editMember(scope.row)">编辑</el-button>
                        <el-dropdown @command="handleCommand($event, scope.row)" style="margin-left: 10px;">
                            <span class="el-dropdown-link">
                                更多<i class="el-icon-arrow-down el-icon--right"></i>
                            </span>
                            <el-dropdown-menu slot="dropdown">
                                <el-dropdown-item command="freeze" v-if="scope.row.status === 'normal'">冻结</el-dropdown-item>
                                <el-dropdown-item command="unfreeze" v-if="scope.row.status === 'frozen'">解冻</el-dropdown-item>
                                <el-dropdown-item command="ban" v-if="scope.row.status !== 'banned'">封禁</el-dropdown-item>
                                <el-dropdown-item command="unban" v-if="scope.row.status === 'banned'">解封</el-dropdown-item>
                            </el-dropdown-menu>
                        </el-dropdown>
                    </template>
                </el-table-column>
            </el-table>
            <div class="pagination">
                <el-pagination
                    @current-change="handlePageChange"
                    :current-page="pagination.page"
                    :page-size="pagination.pageSize"
                    layout="total, prev, pager, next"
                    :total="pagination.total">
                </el-pagination>
            </div>
        </div>

        <!-- 会员详情对话框 -->
        <el-dialog title="会员详情" :visible.sync="detailDialogVisible" width="700px">
            <el-descriptions :column="2" border v-if="currentMember">
                <el-descriptions-item label="ID">{{ currentMember.id }}</el-descriptions-item>
                <el-descriptions-item label="用户名">{{ currentMember.username }}</el-descriptions-item>
                <el-descriptions-item label="邮箱">{{ currentMember.email }}</el-descriptions-item>
                <el-descriptions-item label="昵称">{{ currentMember.nickname }}</el-descriptions-item>
                <el-descriptions-item label="会员等级">
                    <el-tag :type="currentMember.level === 'svip' ? 'danger' : currentMember.level === 'vip' ? 'warning' : 'info'">
                        {{ currentMember.level === 'svip' ? 'SVIP' : currentMember.level === 'vip' ? 'VIP' : '普通' }}
                    </el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="VIP到期">{{ currentMember.vip_expire_time || '未开通' }}</el-descriptions-item>
                <el-descriptions-item label="积分">{{ currentMember.points }}</el-descriptions-item>
                <el-descriptions-item label="累计积分">{{ currentMember.total_points }}</el-descriptions-item>
                <el-descriptions-item label="积分">¥{{ currentMember.balance }}</el-descriptions-item>
                <el-descriptions-item label="累计充值">¥{{ currentMember.total_recharge }}</el-descriptions-item>
                <el-descriptions-item label="推荐码">{{ currentMember.invite_code }}</el-descriptions-item>
                <el-descriptions-item label="推荐人">{{ currentMember.parent_invite_code || '无' }}</el-descriptions-item>
                <el-descriptions-item label="推荐人数">{{ currentMember.invite_count }}</el-descriptions-item>
                <el-descriptions-item label="登录次数">{{ currentMember.login_count }}</el-descriptions-item>
                <el-descriptions-item label="注册时间" :span="2">{{ currentMember.created_at }}</el-descriptions-item>
                <el-descriptions-item label="最后登录" :span="2">{{ currentMember.last_login_time || '从未登录' }}</el-descriptions-item>
                <el-descriptions-item label="最后登录IP" :span="2">{{ currentMember.last_login_ip || '-' }}</el-descriptions-item>
            </el-descriptions>
        </el-dialog>

        <!-- 编辑会员对话框 -->
        <el-dialog title="编辑会员" :visible.sync="editDialogVisible" width="500px">
            <el-form :model="editForm" label-width="100px">
                <el-form-item label="昵称">
                    <el-input v-model="editForm.nickname"></el-input>
                </el-form-item>
                <el-form-item label="会员等级">
                    <el-select v-model="editForm.level" style="width: 100%;">
                        <el-option label="普通会员" value="normal"></el-option>
                        <el-option label="VIP" value="vip"></el-option>
                        <el-option label="SVIP" value="svip"></el-option>
                    </el-select>
                </el-form-item>
                <el-form-item label="VIP到期时间">
                    <el-date-picker v-model="editForm.vip_expire_time" type="datetime" placeholder="选择到期时间" style="width: 100%;"></el-date-picker>
                </el-form-item>
                <el-form-item label="积分">
                    <el-input-number v-model="editForm.points" :min="0"></el-input-number>
                </el-form-item>
                <el-form-item label="积分">
                    <el-input-number v-model="editForm.balance" :min="0" :precision="2"></el-input-number>
                </el-form-item>
                <el-form-item label="状态">
                    <el-select v-model="editForm.status" style="width: 100%;">
                        <el-option label="正常" value="normal"></el-option>
                        <el-option label="冻结" value="frozen"></el-option>
                        <el-option label="封禁" value="banned"></el-option>
                    </el-select>
                </el-form-item>
            </el-form>
            <span slot="footer">
                <el-button @click="editDialogVisible = false">取消</el-button>
                <el-button type="primary" @click="submitEdit">确定</el-button>
            </span>
        </el-dialog>
    </div>

    <script src="assets/js/vue.js"></script>
    <script src="assets/js/element-ui.js"></script>
    <script src="assets/js/axios.min.js"></script>
    <script>
        // 配置axios
        axios.defaults.baseURL = './';
        axios.interceptors.response.use(
            response => response.data,
            error => {
                console.error('请求错误:', error);
                return Promise.reject(error);
            }
        );

        new Vue({
            el: '#app',
            data: {
                memberList: [],
                loading: false,
                searchKeyword: '',
                searchLevel: '',
                searchStatus: '',
                pagination: {
                    page: 1,
                    pageSize: 20,
                    total: 0
                },
                detailDialogVisible: false,
                editDialogVisible: false,
                currentMember: null,
                editForm: {}
            },
            mounted() {
                this.loadMembers();
            },
            methods: {
                loadMembers() {
                    this.loading = true;
                    axios.get('api/admin/member_list.php', {
                        params: {
                            page: this.pagination.page,
                            pageSize: this.pagination.pageSize,
                            keyword: this.searchKeyword,
                            level: this.searchLevel,
                            status: this.searchStatus
                        }
                    }).then(res => {
                        if (res.code === 0) {
                            this.memberList = res.data.list;
                            this.pagination.total = res.data.total;
                        } else {
                            this.$message.error(res.msg || '加载失败');
                        }
                    }).catch(err => {
                        console.error('加载会员列表失败:', err);
                        this.$message.error('加载失败');
                    }).finally(() => {
                        this.loading = false;
                    });
                },
                handlePageChange(page) {
                    this.pagination.page = page;
                    this.loadMembers();
                },
                viewDetail(row) {
                    this.currentMember = row;
                    this.detailDialogVisible = true;
                },
                editMember(row) {
                    this.editForm = { ...row };
                    this.editDialogVisible = true;
                },
                submitEdit() {
                    axios.post('api/admin/member_update.php', this.editForm).then(res => {
                        if (res.code === 0) {
                            this.$message.success('更新成功');
                            this.editDialogVisible = false;
                            this.loadMembers();
                        } else {
                            this.$message.error(res.msg);
                        }
                    });
                },
                handleCommand(command, row) {
                    let status = '';
                    let msg = '';
                    if (command === 'freeze') {
                        status = 'frozen';
                        msg = '确定要冻结该会员吗？';
                    } else if (command === 'unfreeze') {
                        status = 'normal';
                        msg = '确定要解冻该会员吗？';
                    } else if (command === 'ban') {
                        status = 'banned';
                        msg = '确定要封禁该会员吗？';
                    } else if (command === 'unban') {
                        status = 'normal';
                        msg = '确定要解封该会员吗？';
                    }

                    this.$confirm(msg, '提示', {
                        confirmButtonText: '确定',
                        cancelButtonText: '取消',
                        type: 'warning'
                    }).then(() => {
                        axios.post('api/admin/member_update.php', {
                            id: row.id,
                            status: status
                        }).then(res => {
                            if (res.code === 0) {
                                this.$message.success('操作成功');
                                this.loadMembers();
                            } else {
                                this.$message.error(res.msg);
                            }
                        });
                    });
                }
            }
        });
    </script>
</body>
</html>
