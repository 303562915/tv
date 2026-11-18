<?php
/**
 * 邮件日志记录辅助函数
 * 在发送邮件时调用此函数记录日志
 */

require_once __DIR__ . '/api/config.php';

/**
 * 记录邮件发送日志
 *
 * @param string $recipientEmail 收件人邮箱
 * @param string $emailType 邮件类型：register(注册验证), reset_password(密码重置), notification(通知), vip_expire(VIP到期提醒)
 * @param string $subject 邮件主题
 * @param string $content 邮件内容
 * @param bool $status 发送状态：true=成功, false=失败
 * @param int|null $userId 用户ID（可选）
 * @param string $errorMessage 错误信息（失败时）
 * @return int 日志ID
 */
function logEmail($recipientEmail, $emailType, $subject, $content, $status, $userId = null, $errorMessage = '') {
    try {
        $db = Database::getInstance()->getConnection();

        $stmt = $db->prepare("INSERT INTO app_email_logs
            (user_id, recipient_email, email_type, subject, content, status, error_message)
            VALUES (?, ?, ?, ?, ?, ?, ?)");

        $stmt->execute([
            $userId,
            $recipientEmail,
            $emailType,
            $subject,
            $content,
            $status ? 1 : 0,
            $errorMessage
        ]);

        return $db->lastInsertId();
    } catch (Exception $e) {
        // 记录日志失败不应该影响主流程
        error_log("Failed to log email: " . $e->getMessage());
        return 0;
    }
}

/**
 * 邮件类型常量
 */
class EmailType {
    const REGISTER = 'register';              // 注册验证
    const RESET_PASSWORD = 'reset_password';  // 密码重置
    const NOTIFICATION = 'notification';      // 通知
    const VIP_EXPIRE = 'vip_expire';         // VIP到期提醒
    const INVITE = 'invite';                 // 邀请
    const SYSTEM = 'system';                 // 系统通知
}

/**
 * 获取邮件类型的中文名称
 */
function getEmailTypeName($type) {
    $types = [
        'register' => '注册验证',
        'reset_password' => '密码重置',
        'notification' => '通知',
        'vip_expire' => 'VIP到期提醒',
        'invite' => '邀请',
        'system' => '系统通知'
    ];
    return $types[$type] ?? $type;
}
