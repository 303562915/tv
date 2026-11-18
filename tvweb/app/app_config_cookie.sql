-- MyApp 应用数据库表结构
-- 所有表使用 app_ 前缀，避免与主项目冲突

-- 配置表
CREATE TABLE IF NOT EXISTS `app_config` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '配置ID',
  `cookie` text COMMENT 'Cookie信息',
  `updated_at` datetime DEFAULT NULL COMMENT '更新时间',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用配置表';

-- 插入默认配置记录
INSERT INTO `app_config` (`id`, `cookie`, `updated_at`) VALUES (1, '', NULL);
