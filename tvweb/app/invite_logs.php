<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>邀请记录 - TV管理后台</title>
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
            <div class="page-title">邀请记录</div>
        </div>
        <div class="content-box">
            <div class="toolbar">
                <el-input v-model="searchKeyword" placeholder="搜索邀请人/被邀请人" style="width: 300px;" clearable>
                    <el-button slot="append" icon="el-icon-search" @click="loadLogs"></el-button>
                </el-input>
                <el-select v-model="searchStatus" placeholder="奖励状态" clearable style="width: 150px; margin-left: 10px;" @change="loadLogs">
                    <el-option label="待发放" value="pending"></el-option>
                    <el-option label="已发放" value="completed"></el-option>
                </el-select>
            </div>
            <el-table :data="logList" border stripe v-loading="loading">
                <el-table-column prop="id" label="ID" width="80"></el-table-column>
                <el-table-column prop="inviter_id" label="邀请人ID" width="100"></el-table-column>
                <el-table-column prop="inviter_username" label="邀请人" width="120"></el-table-column>
                <el-table-column prop="invitee_id" label="被邀请人ID" width="120"></el-table-column>
                <el-table-column prop="invitee_username" label="被邀请人" width="120"></el-table-column>
                <el-table-column prop="reward_points" label="奖励积分" width="100">
                    <template slot-scope="scope">
                        <span style="color: #67C23A;">+{{ scope.row.reward_points }}</span>
                    </template>
                </el-table-column>
                <el-table-column prop="reward_status" label="奖励状态" width="100">
                    <template slot-scope="scope">
                        <el-tag :type="scope.row.reward_status === 'completed' ? 'success' : 'warning'" size="small">
                            {{ scope.row.reward_status === 'completed' ? '已发放' : '待发放' }}
                        </el-tag>
                    </template>
                </el-table-column>
                <el-table-column prop="reward_time" label="发放时间" width="160"></el-table-column>
                <el-table-column prop="created_at" label="邀请时间" width="160"></el-table-column>
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
        axios.defaults.baseURL = "./";
        axios.interceptors.response.use(response => response.data, error => Promise.reject(error));
    </script>
    <script>
        new Vue({
            el: '#app',
            data: {
                logList: [],
                loading: false,
                searchKeyword: '',
                searchStatus: '',
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
                    axios.get('api/admin/invite_logs.php', {
                        params: {
                            page: this.pagination.page,
                            pageSize: this.pagination.pageSize,
                            keyword: this.searchKeyword,
                            status: this.searchStatus
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
                }
            }
        });
    </script>
</body>
</html>
