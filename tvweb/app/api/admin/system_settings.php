<?php
/**
 * 系统设置API
 * GET: 获取设置
 * POST: 保存设置
 */

require_once __DIR__ . '/../config.php';

header('Content-Type: application/json; charset=utf-8');

$db = Database::getInstance()->getConnection();

try {
    if ($_SERVER['REQUEST_METHOD'] === 'GET') {
        // 获取所有设置
        $stmt = $db->query("SELECT config_key, config_value, config_type FROM app_config");
        $configs = $stmt->fetchAll();

        $settings = [];
        foreach ($configs as $config) {
            $key = $config['config_key'];
            $value = $config['config_value'];
            $type = $config['config_type'];

            // 根据类型转换值
            if ($type === 'number') {
                $value = floatval($value);
            } elseif ($type === 'boolean') {
                $value = $value === '1' || $value === 'true';
            } elseif ($type === 'json') {
                $value = json_decode($value, true);
            }

            $settings[$key] = $value;
        }

        success($settings);

    } elseif ($_SERVER['REQUEST_METHOD'] === 'POST') {
        // 保存设置
        $input = json_decode(file_get_contents('php://input'), true);
        $type = $input['type'] ?? '';
        $settings = $input['settings'] ?? [];

        if (empty($settings)) {
            error('设置数据不能为空');
        }

        $db->beginTransaction();

        foreach ($settings as $key => $value) {
            // 确定配置类型
            $configType = 'text';
            if (is_bool($value)) {
                $configType = 'boolean';
                $value = $value ? '1' : '0';
            } elseif (is_numeric($value)) {
                $configType = 'number';
            } elseif (is_array($value)) {
                $configType = 'json';
                $value = json_encode($value);
            }

            // 检查配置是否存在
            $stmt = $db->prepare("SELECT id FROM app_config WHERE config_key = ?");
            $stmt->execute([$key]);
            $exists = $stmt->fetch();

            if ($exists) {
                // 更新
                $stmt = $db->prepare("
                    UPDATE app_config
                    SET config_value = ?, config_type = ?, updated_at = NOW()
                    WHERE config_key = ?
                ");
                $stmt->execute([$value, $configType, $key]);
            } else {
                // 插入
                $stmt = $db->prepare("
                    INSERT INTO app_config (config_key, config_value, config_type, group_name)
                    VALUES (?, ?, ?, ?)
                ");
                $stmt->execute([$key, $value, $configType, $type]);
            }
        }

        $db->commit();
        success(null, '保存成功');
    }

} catch (Exception $e) {
    if (isset($db) && $db->inTransaction()) {
        $db->rollBack();
    }
    error('系统错误：' . $e->getMessage());
}
