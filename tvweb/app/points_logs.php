<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>积分记录 - TV管理后台</title>
    <link rel="stylesheet" href="assets/css/element-ui.css">
    <style>
        body { margin: 0; padding: 0; font-family: 'Microsoft YaHei', Arial, sans-serif; }
        .page-header { background: white; padding: 20px; margin-bottom: 20px; border-radius: 4px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .page-title { font-size: 20px; font-weight: bold; color: #333; }
        .content-box { background: white; padding: 20px; border-radius: 4px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .toolbar { margin-bottom: 20px; }
        .pagination { margin-top: 20px; text-align: right; }
    </style>
</head>
<body>
    <div id="app">
        <div class="page-header">
            <div class="page-title">积分记录</div>
        </div>
        <div class="content-box">
            <div class="toolbar">
                <el-input v-model="searchKeyword" placeholder="搜索会员ID/用户名" style="width: 300px;" clearable>
                    <el-button slot="append" icon="el-icon-search" @click="loadLogs"></el-button>
                </el-input>
                <el-select v-model="searchType" placeholder="类型" clearable style="width: 150px; margin-left: 10px;" @change="loadLogs">
                    <el-option label="注册奖励" value="register"></el-option>
                    <el-option label="邀请奖励" value="invite"></el-option>
                    <el-option label="签到奖励" value="signin"></el-option>
                    <el-option label="管理员调整" value="admin_adjust"></el-option>
                    <el-option label="消费扣除" value="consume"></el-option>
                </el-select>
            </div>
            <el-table :data="logList" border stripe v-loading="loading">
                <el-table-column prop="id" label="ID" width="80"></el-table-column>
                <el-table-column prop="member_id" label="会员ID" width="100"></el-table-column>
                <el-table-column prop="username" label="用户名" width="120"></el-table-column>
                <el-table-column prop="type" label="类型" width="120">
                    <template slot-scope="scope">
                        <el-tag :type="scope.row.points > 0 ? 'success' : 'danger'" size="small">
                            {{ getTypeName(scope.row.type) }}
                        </el-tag>
                    </template>
                </el-table-column>
                <el-table-column prop="points" label="积分变动" width="120">
                    <template slot-scope="scope">
                        <span :style="{color: scope.row.points > 0 ? '#67C23A' : '#F56C6C'}">
                            {{ scope.row.points > 0 ? '+' : '' }}{{ scope.row.points }}
                        </span>
                    </template>
                </el-table-column>
                <el-table-column prop="points_before" label="变动前" width="100"></el-table-column>
                <el-table-column prop="points_after" label="变动后" width="100"></el-table-column>
                <el-table-column prop="remark" label="备注" min-width="200"></el-table-column>
                <el-table-column prop="created_at" label="时间" width="160"></el-table-column>
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
    </div>

    <script src="assets/js/vue.js"></script>
    <script src="assets/js/element-ui.js"></script>
    <script src="assets/js/axios.min.js"></script>
    <script>
        axios.defaults.baseURL = './';
        axios.interceptors.response.use(response => response.data, error => Promise.reject(error));

        new Vue({
            el: '#app',
            data: {
                logList: [],
                loading: false,
                searchKeyword: '',
                searchType: '',
                pagination: {
                    page: 1,
                    pageSize: 20,
                    total: 0
                }
            },
            mounted() {
                this.loadLogs();
            },
            methods: {
                loadLogs() {
                    this.loading = true;
                    axios.get('api/admin/points_logs.php', {
                        params: {
                            page: this.pagination.page,
                            pageSize: this.pagination.pageSize,
                            keyword: this.searchKeyword,
                            type: this.searchType
                        }
                    }).then(res => {
                        if (res.code === 0) {
                            this.logList = res.data.list;
                            this.pagination.total = res.data.total;
                        }
                    }).finally(() => {
                        this.loading = false;
                    });
                },
                handlePageChange(page) {
                    this.pagination.page = page;
                    this.loadLogs();
                },
                getTypeName(type) {
                    const types = {
                        'register': '注册奖励',
                        'invite': '邀请奖励',
                        'signin': '签到奖励',
                        'admin_adjust': '管理员调整',
                        'consume': '消费扣除'
                    };
                    return types[type] || type;
                }
            }
        });
    </script>
</body>
</html>
