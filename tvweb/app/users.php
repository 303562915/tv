<?php
$mod = 'users.php';
$title = '用户管理';
require_once('pages/header.php');
?>

<div class="page-header">
    <div class="page-title">用户管理</div>
</div>

<div class="content-box">
    <div class="toolbar">
        <el-button type="primary" icon="el-icon-plus" @click="handleAdd">添加用户</el-button>
        <el-input v-model="searchKeyword" placeholder="搜索用户名/邮箱/手机号" style="width: 300px; margin-left: 10px;" clearable @clear="loadUsers">
            <el-button slot="append" icon="el-icon-search" @click="loadUsers"></el-button>
        </el-input>
    </div>

    <el-table :data="userList" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="80"></el-table-column>
        <el-table-column prop="username" label="用户名" width="120"></el-table-column>
        <el-table-column prop="email" label="邮箱" width="180"></el-table-column>
        <el-table-column prop="phone" label="手机号" width="120"></el-table-column>
        <el-table-column prop="role" label="角色" width="100">
            <template slot-scope="scope">
                <el-tag :type="scope.row.role === 'admin' ? 'danger' : 'success'" size="small">
                    {{ scope.row.role === 'admin' ? '管理员' : '普通用户' }}
                </el-tag>
            </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
            <template slot-scope="scope">
                <el-tag :type="scope.row.status == 1 ? 'success' : 'danger'" size="small" class="status-tag" @click.native="toggleStatus(scope.row)">
                    {{ scope.row.status == 1 ? '启用' : '禁用' }}
                </el-tag>
            </template>
        </el-table-column>
        <el-table-column prop="last_login_time" label="最后登录" width="160"></el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
            <template slot-scope="scope">
                <el-button type="text" size="small" @click="handleEdit(scope.row)">编辑</el-button>
                <el-button type="text" size="small" style="color: #F56C6C;" @click="handleDelete(scope.row.id)">删除</el-button>
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

<!-- 用户表单对话框 -->
<el-dialog :title="dialogTitle" :visible.sync="dialogVisible" width="500px">
    <el-form :model="form" ref="form" label-width="100px">
        <el-form-item label="用户名" required>
            <el-input v-model="form.username"></el-input>
        </el-form-item>
        <el-form-item label="密码" :required="!form.id">
            <el-input v-model="form.password" type="password" :placeholder="form.id ? '不修改请留空' : '请输入密码'"></el-input>
        </el-form-item>
        <el-form-item label="邮箱">
            <el-input v-model="form.email"></el-input>
        </el-form-item>
        <el-form-item label="手机号">
            <el-input v-model="form.phone"></el-input>
        </el-form-item>
        <el-form-item label="角色">
            <el-select v-model="form.role" style="width: 100%;">
                <el-option label="普通用户" value="user"></el-option>
                <el-option label="管理员" value="admin"></el-option>
            </el-select>
        </el-form-item>
        <el-form-item label="状态">
            <el-switch v-model="form.status" :active-value="1" :inactive-value="0"></el-switch>
        </el-form-item>
    </el-form>
    <span slot="footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitForm" :loading="submitLoading">确定</el-button>
    </span>
</el-dialog>

<script>
// 扩展Vue实例数据
Object.assign(app.$data, {
    loading: false,
    submitLoading: false,
    userList: [],
    searchKeyword: '',
    dialogVisible: false,
    dialogTitle: '添加用户',
    form: {
        id: null,
        username: '',
        password: '',
        email: '',
        phone: '',
        role: 'user',
        status: 1
    },
    pagination: {
        page: 1,
        pageSize: 20,
        total: 0
    }
});

// 扩展Vue实例方法
Object.assign(app, {
    loadUsers() {
        this.loading = true;
        axios.get('users.php', {
            params: {
                page: this.pagination.page,
                pageSize: this.pagination.pageSize,
                keyword: this.searchKeyword
            }
        }).then(res => {
            if (res.code === 200) {
                this.userList = res.data.list;
                this.pagination.total = parseInt(res.data.total) || 0;
            }
        }).finally(() => {
            this.loading = false;
        });
    },

    handlePageChange(page) {
        this.pagination.page = page;
        this.loadUsers();
    },

    handleAdd() {
        this.dialogTitle = '添加用户';
        this.form = {
            id: null,
            username: '',
            password: '',
            email: '',
            phone: '',
            role: 'user',
            status: 1
        };
        this.dialogVisible = true;
    },

    handleEdit(row) {
        this.dialogTitle = '编辑用户';
        this.form = {
            id: row.id,
            username: row.username,
            password: '',
            email: row.email,
            phone: row.phone,
            role: row.role,
            status: parseInt(row.status)
        };
        this.dialogVisible = true;
    },

    submitForm() {
        if (!this.form.username) {
            this.$message.error('请输入用户名');
            return;
        }
        if (!this.form.id && !this.form.password) {
            this.$message.error('请输入密码');
            return;
        }

        this.submitLoading = true;
        const method = this.form.id ? 'put' : 'post';
        axios[method]('users.php', this.form).then(res => {
            if (res.code === 200) {
                this.$message.success(res.msg);
                this.dialogVisible = false;
                this.loadUsers();
            } else {
                this.$message.error(res.msg);
            }
        }).finally(() => {
            this.submitLoading = false;
        });
    },

    handleDelete(id) {
        this.$confirm('确定要删除这个用户吗？', '提示', {
            type: 'warning'
        }).then(() => {
            axios.delete(`users.php?id=${id}`).then(res => {
                if (res.code === 200) {
                    this.$message.success(res.msg);
                    this.loadUsers();
                } else {
                    this.$message.error(res.msg);
                }
            });
        }).catch(() => {});
    },

    toggleStatus(row) {
        axios.put('users.php', {
            id: row.id,
            username: row.username,
            status: row.status == 1 ? 0 : 1
        }).then(res => {
            if (res.code === 200) {
                this.$message.success('状态更新成功');
                this.loadUsers();
            } else {
                this.$message.error(res.msg);
            }
        });
    }
});

// 加载数据
app.loadUsers();
</script>

<?php require_once('pages/footer.php'); ?>
