-- 添加会员管理菜单
INSERT INTO `app_menu` (`parent_id`, `name`, `path`, `icon`, `sort`, `permission`, `status`, `created_at`, `updated_at`)
VALUES (0, '会员管理', '', 'el-icon-s-custom', 30, 'admin', '1', NOW(), NOW());

-- 获取刚插入的会员管理菜单ID（假设为最新的ID）
SET @member_menu_id = LAST_INSERT_ID();

-- 添加会员管理的子菜单
INSERT INTO `app_menu` (`parent_id`, `name`, `path`, `icon`, `sort`, `permission`, `status`, `created_at`, `updated_at`) VALUES
(@member_menu_id, '会员列表', 'member_list', 'el-icon-user', 1, 'admin', '1', NOW(), NOW()),
(@member_menu_id, '积分记录', 'points_logs', 'el-icon-money', 2, 'admin', '1', NOW(), NOW()),
(@member_menu_id, '积分记录', 'balance_logs', 'el-icon-wallet', 3, 'admin', '1', NOW(), NOW()),
(@member_menu_id, '登录日志', 'login_logs', 'el-icon-document', 4, 'admin', '1', NOW(), NOW()),
(@member_menu_id, '邀请记录', 'invite_logs', 'el-icon-share', 5, 'admin', '1', NOW(), NOW());

-- 添加系统设置菜单
INSERT INTO `app_menu` (`parent_id`, `name`, `path`, `icon`, `sort`, `permission`, `status`, `created_at`, `updated_at`)
VALUES (0, '系统设置', 'system_settings', 'el-icon-setting', 100, 'admin', '1', NOW(), NOW());
