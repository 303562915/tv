-- 管理后台数据库设计
-- 数据库名: tv_admin

CREATE DATABASE IF NOT EXISTS tv_admin DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE tv_admin;

-- 用户表
CREATE TABLE IF NOT EXISTS app_users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    password VARCHAR(255) NOT NULL COMMENT '密码(MD5加密)',
    email VARCHAR(100) COMMENT '邮箱',
    phone VARCHAR(20) COMMENT '手机号',
    role ENUM('admin', 'user') DEFAULT 'user' COMMENT '角色',
    status TINYINT DEFAULT 1 COMMENT '状态 1启用 0禁用',
    last_login_time DATETIME COMMENT '最后登录时间',
    last_login_ip VARCHAR(50) COMMENT '最后登录IP',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_username (username),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 配置表
CREATE TABLE IF NOT EXISTS app_config (
    id INT AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(100) NOT NULL UNIQUE COMMENT '配置键',
    config_value TEXT COMMENT '配置值',
    config_type ENUM('text', 'number', 'json', 'boolean') DEFAULT 'text' COMMENT '配置类型',
    description VARCHAR(255) COMMENT '配置描述',
    group_name VARCHAR(50) DEFAULT 'default' COMMENT '配置分组',
    sort_order INT DEFAULT 0 COMMENT '排序',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_group (group_name),
    INDEX idx_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配置表';

-- 公告表
CREATE TABLE IF NOT EXISTS app_announcements (
    id INT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL COMMENT '公告标题',
    content TEXT NOT NULL COMMENT '公告内容',
    type ENUM('info', 'warning', 'success', 'error') DEFAULT 'info' COMMENT '公告类型',
    priority INT DEFAULT 0 COMMENT '优先级 数字越大越靠前',
    status TINYINT DEFAULT 1 COMMENT '状态 1显示 0隐藏',
    start_time DATETIME COMMENT '开始时间',
    end_time DATETIME COMMENT '结束时间',
    view_count INT DEFAULT 0 COMMENT '查看次数',
    created_by INT COMMENT '创建人ID',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_status (status),
    INDEX idx_priority (priority),
    INDEX idx_time (start_time, end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公告表';

-- 轮播图表
CREATE TABLE IF NOT EXISTS app_banners (
    id INT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL COMMENT '轮播图标题',
    image_url VARCHAR(500) NOT NULL COMMENT '图片URL',
    link_url VARCHAR(500) COMMENT '跳转链接',
    link_type ENUM('none', 'internal', 'external', 'video') DEFAULT 'none' COMMENT '链接类型',
    position VARCHAR(50) DEFAULT 'home' COMMENT '显示位置 home首页 detail详情页',
    sort_order INT DEFAULT 0 COMMENT '排序 数字越小越靠前',
    status TINYINT DEFAULT 1 COMMENT '状态 1显示 0隐藏',
    start_time DATETIME COMMENT '开始时间',
    end_time DATETIME COMMENT '结束时间',
    click_count INT DEFAULT 0 COMMENT '点击次数',
    created_by INT COMMENT '创建人ID',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_position (position),
    INDEX idx_status (status),
    INDEX idx_sort (sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='轮播图表';

-- 操作日志表
CREATE TABLE IF NOT EXISTS app_logs (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT COMMENT '操作用户ID',
    action VARCHAR(100) NOT NULL COMMENT '操作动作',
    module VARCHAR(50) NOT NULL COMMENT '操作模块',
    content TEXT COMMENT '操作内容',
    ip VARCHAR(50) COMMENT '操作IP',
    user_agent VARCHAR(500) COMMENT '用户代理',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_user (user_id),
    INDEX idx_module (module),
    INDEX idx_time (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志表';

-- 插入默认管理员账号 (密码: admin123)
INSERT INTO app_users (username, password, email, role, status)
VALUES ('admin', MD5('admin123'), 'admin@example.com', 'admin', 1);

-- 插入默认配置
INSERT INTO app_config (config_key, config_value, config_type, description, group_name) VALUES
('app_name', 'TV管理后台', 'text', '应用名称', 'basic'),
('app_version', '1.0.0', 'text', '应用版本', 'basic'),
('api_base_url', 'http://your-server-domain.com/app/', 'text', 'API基础地址', 'api'),
('page_size', '20', 'number', '每页显示数量', 'basic'),
('upload_max_size', '10', 'number', '上传文件最大大小(MB)', 'upload'),
('announcement_enabled', '1', 'boolean', '是否启用公告', 'feature'),
('banner_enabled', '1', 'boolean', '是否启用轮播', 'feature');

-- 插入示例公告
INSERT INTO app_announcements (title, content, type, priority, status) VALUES
('欢迎使用TV管理后台', '这是一个示例公告，您可以在公告管理中编辑或删除。', 'info', 1, 1),
('系统维护通知', '系统将于本周六凌晨2:00-4:00进行维护升级。', 'warning', 2, 1);

-- 插入示例轮播图
INSERT INTO app_banners (title, image_url, link_url, link_type, position, sort_order, status) VALUES
('首页轮播1', 'https://via.placeholder.com/800x400?text=Banner1', '', 'none', 'home', 1, 1),
('首页轮播2', 'https://via.placeholder.com/800x400?text=Banner2', '', 'none', 'home', 2, 1);
