<?php
$mod = 'menu.php';
$title = '菜单管理';
require_once('pages/header.php');
?>

<div class="page-header">
    <div class="page-title">菜单管理</div>
</div>

<div class="content-box">
    <div class="toolbar">
        <el-button type="success" icon="el-icon-plus" @click="handleAdd">添加菜单</el-button>
    </div>

    <el-table :data="menuTree" row-key="id" border stripe :tree-props="{children: 'children'}" :default-expand-all="false" v-loading="loading">
        <el-table-column prop="name" label="菜单名称" min-width="150">
            <template slot-scope="scope">
                <i :class="scope.row.icon" style="margin-right: 8px;"></i>
                <span>{{ scope.row.name }}</span>
            </template>
        </el-table-column>
        <el-table-column prop="path" label="路径" width="150"></el-table-column>
        <el-table-column prop="sort" label="排序" width="80"></el-table-column>
        <el-table-column prop="permission" label="权限" width="100">
            <template slot-scope="scope">
                <el-tag :type="scope.row.permission === 'admin' ? 'warning' : 'success'" size="small">
                    {{ scope.row.permission === 'admin' ? '管理员' : '所有人' }}
                </el-tag>
            </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="80">
            <template slot-scope="scope">
                <el-switch v-model="scope.row.status" :active-value="'1'" :inactive-value="'0'" @change="handleStatusChange(scope.row)"></el-switch>
            </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
            <template slot-scope="scope">
                <el-button-group>
                    <el-button type="primary" size="mini" @click="handleEdit(scope.row)">编辑</el-button>
                    <el-button type="success" size="mini" @click="handleAdd(scope.row)">添加</el-button>
                    <el-button type="danger" size="mini" @click="handleDelete(scope.row)">删除</el-button>
                </el-button-group>
            </template>
        </el-table-column>
    </el-table>
</div>

<!-- 菜单表单对话框 -->
<el-dialog :title="dialogTitle" :visible.sync="dialogVisible" width="500px">
    <el-form :model="form" ref="form" label-width="100px">
        <el-form-item label="上级菜单">
            <el-select v-model="form.parent_id" placeholder="顶级菜单" clearable style="width: 100%;">
                <el-option label="顶级菜单" :value="0"></el-option>
                <el-option v-for="menu in menuList" :key="menu.id" :label="menu.name" :value="parseInt(menu.id)"></el-option>
            </el-select>
        </el-form-item>
        <el-form-item label="菜单名称" required>
            <el-input v-model="form.name" placeholder="例如: 用户管理"></el-input>
        </el-form-item>
        <el-form-item label="图标">
            <el-input v-model="form.icon" placeholder="点击右侧按钮选择图标">
                <template slot="prepend">
                    <i :class="form.icon || 'el-icon-menu'" style="width: 16px;"></i>
                </template>
                <el-button slot="append" icon="el-icon-search" @click="iconSelectorVisible = true">选择图标</el-button>
            </el-input>
        </el-form-item>
        <el-form-item label="路径">
            <el-input v-model="form.path" placeholder="例如: users (不需要.php后缀)"></el-input>
        </el-form-item>
        <el-form-item label="排序">
            <el-input-number v-model="form.sort" :min="0" :max="999"></el-input-number>
        </el-form-item>
        <el-form-item label="权限">
            <el-select v-model="form.permission" placeholder="请选择权限" style="width: 100%;">
                <el-option label="所有人" value=""></el-option>
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

<!-- 图标选择器对话框 -->
<el-dialog title="选择图标" :visible.sync="iconSelectorVisible" width="600px">
    <div style="display: grid; grid-template-columns: repeat(6, 1fr); gap: 10px; max-height: 400px; overflow-y: auto;">
        <div v-for="icon in iconList" :key="icon"
             @click="selectIcon(icon)"
             style="display: flex; flex-direction: column; align-items: center; padding: 15px; border: 1px solid #ddd; border-radius: 4px; cursor: pointer; transition: all 0.3s;"
             :style="form.icon === icon ? 'background: #409EFF; color: white; border-color: #409EFF;' : ''"
             @mouseenter="$event.currentTarget.style.background = '#f5f7fa'"
             @mouseleave="$event.currentTarget.style.background = form.icon === icon ? '#409EFF' : 'white'">
            <i :class="icon" style="font-size: 24px; margin-bottom: 5px;"></i>
            <span style="font-size: 12px; text-align: center; word-break: break-all;">{{ icon.replace('el-icon-', '') }}</span>
        </div>
    </div>
</el-dialog>

<script>
// 扩展Vue实例数据
Object.assign(app.$data, {
    loading: false,
    submitLoading: false,
    menuTree: [],
    dialogVisible: false,
    iconSelectorVisible: false,
    dialogTitle: '添加菜单',
    form: {
        id: null,
        parent_id: 0,
        name: '',
        icon: '',
        path: '',
        sort: 0,
        permission: '',
        status: 1
    },
    iconList: [
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
    ]
});

// 扩展Vue实例方法
Object.assign(app, {
    loadMenuTree() {
        this.loading = true;
        axios.get('menu.php?tree=1').then(res => {
            if (res.code === 200) {
                this.menuTree = res.data.list;
            }
        }).finally(() => {
            this.loading = false;
        });
    },

    handleAdd(row) {
        this.dialogTitle = '添加菜单';
        this.form = {
            id: null,
            parent_id: row ? parseInt(row.id) : 0,
            name: '',
            icon: '',
            path: '',
            sort: 0,
            permission: '',
            status: 1
        };
        this.dialogVisible = true;
    },

    handleEdit(row) {
        this.dialogTitle = '编辑菜单';
        this.form = {
            id: parseInt(row.id),
            parent_id: parseInt(row.parent_id),
            name: row.name,
            icon: row.icon,
            path: row.path,
            sort: parseInt(row.sort),
            permission: row.permission,
            status: parseInt(row.status)
        };
        this.dialogVisible = true;
    },

    submitForm() {
        if (!this.form.name) {
            this.$message.error('请输入菜单名称');
            return;
        }

        this.submitLoading = true;
        const method = this.form.id ? 'put' : 'post';
        axios[method]('menu.php', this.form).then(res => {
            if (res.code === 200) {
                this.$message.success(res.msg);
                this.dialogVisible = false;
                this.loadMenuTree();
                this.loadMenuList();
            } else {
                this.$message.error(res.msg);
            }
        }).finally(() => {
            this.submitLoading = false;
        });
    },

    handleDelete(row) {
        this.$confirm('确定要删除这个菜单吗？', '提示', {
            type: 'warning'
        }).then(() => {
            axios.delete(`menu.php?id=${row.id}`).then(res => {
                if (res.code === 200) {
                    this.$message.success(res.msg);
                    this.loadMenuTree();
                    this.loadMenuList();
                } else {
                    this.$message.error(res.msg);
                }
            });
        }).catch(() => {});
    },

    handleStatusChange(row) {
        axios.put('menu.php', {
            id: row.id,
            name: row.name,
            status: row.status,
            parent_id: row.parent_id,
            icon: row.icon,
            path: row.path,
            sort: row.sort,
            permission: row.permission
        }).then(res => {
            if (res.code === 200) {
                this.$message.success('状态更新成功');
                this.loadMenuList();
            } else {
                this.$message.error(res.msg);
                row.status = row.status === '1' ? '0' : '1';
            }
        });
    },

    selectIcon(icon) {
        this.form.icon = icon;
        this.iconSelectorVisible = false;
    }
});

// 加载数据
app.loadMenuTree();
</script>

<?php require_once('pages/footer.php'); ?>
