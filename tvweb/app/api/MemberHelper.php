<?php
/**
 * 会员系统工具类
 */

// 引入邮件日志记录工具
require_once __DIR__ . '/../email_logger.php';

class MemberHelper {

    /**
     * 生成推荐码
     * @return string 8位推荐码
     */
    public static function generateInviteCode() {
        $characters = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // 去除易混淆字符
        $code = '';
        for ($i = 0; $i < 8; $i++) {
            $code .= $characters[rand(0, strlen($characters) - 1)];
        }
        return $code;
    }

    /**
     * 生成唯一推荐码
     * @param PDO $db 数据库连接
     * @return string 唯一推荐码
     */
    public static function generateUniqueInviteCode($db) {
        $maxAttempts = 10;
        for ($i = 0; $i < $maxAttempts; $i++) {
            $code = self::generateInviteCode();

            // 检查是否已存在
            $stmt = $db->prepare("SELECT id FROM app_members WHERE invite_code = ?");
            $stmt->execute([$code]);

            if (!$stmt->fetch()) {
                return $code;
            }
        }

        // 如果10次都重复，使用时间戳确保唯一
        return strtoupper(substr(md5(microtime()), 0, 8));
    }

    /**
     * 生成6位数字验证码
     * @return string
     */
    public static function generateEmailCode() {
        return str_pad(rand(0, 999999), 6, '0', STR_PAD_LEFT);
    }

    /**
     * 验证邮箱格式
     * @param string $email
     * @return bool
     */
    public static function validateEmail($email) {
        return filter_var($email, FILTER_VALIDATE_EMAIL) !== false;
    }

    /**
     * 验证用户名格式
     * @param string $username
     * @return bool
     */
    public static function validateUsername($username) {
        // 4-20位，字母数字下划线
        return preg_match('/^[a-zA-Z0-9_]{4,20}$/', $username);
    }

    /**
     * 验证密码强度
     * @param string $password
     * @return bool
     */
    public static function validatePassword($password) {
        // 至少6位，包含字母和数字
        return strlen($password) >= 6 &&
               preg_match('/[a-zA-Z]/', $password) &&
               preg_match('/[0-9]/', $password);
    }

    /**
     * 加密密码
     * @param string $password
     * @return string
     */
    public static function hashPassword($password) {
        return password_hash($password, PASSWORD_BCRYPT);
    }

    /**
     * 验证密码
     * @param string $password
     * @param string $hash
     * @return bool
     */
    public static function verifyPassword($password, $hash) {
        return password_verify($password, $hash);
    }

    /**
     * 生成JWT Token
     * @param int $memberId
     * @param string $username
     * @return string
     */
    public static function generateToken($memberId, $username) {
        $header = json_encode(['typ' => 'JWT', 'alg' => 'HS256']);
        $payload = json_encode([
            'member_id' => $memberId,
            'username' => $username,
            'exp' => time() + (7 * 24 * 60 * 60) // 7天有效期
        ]);

        $base64UrlHeader = str_replace(['+', '/', '='], ['-', '_', ''], base64_encode($header));
        $base64UrlPayload = str_replace(['+', '/', '='], ['-', '_', ''], base64_encode($payload));

        $signature = hash_hmac('sha256', $base64UrlHeader . "." . $base64UrlPayload, 'your-secret-key-change-this', true);
        $base64UrlSignature = str_replace(['+', '/', '='], ['-', '_', ''], base64_encode($signature));

        return $base64UrlHeader . "." . $base64UrlPayload . "." . $base64UrlSignature;
    }

    /**
     * 验证JWT Token
     * @param string $token
     * @return array|false
     */
    public static function verifyToken($token) {
        $parts = explode('.', $token);
        if (count($parts) !== 3) {
            return false;
        }

        list($base64UrlHeader, $base64UrlPayload, $base64UrlSignature) = $parts;

        // 验证签名
        $signature = hash_hmac('sha256', $base64UrlHeader . "." . $base64UrlPayload, 'your-secret-key-change-this', true);
        $base64UrlSignatureCheck = str_replace(['+', '/', '='], ['-', '_', ''], base64_encode($signature));

        if ($base64UrlSignature !== $base64UrlSignatureCheck) {
            return false;
        }

        // 解析payload
        $payload = json_decode(base64_decode(str_replace(['-', '_'], ['+', '/'], $base64UrlPayload)), true);

        // 检查是否过期
        if (isset($payload['exp']) && $payload['exp'] < time()) {
            return false;
        }

        return $payload;
    }

    /**
     * 从请求头获取Token
     * @return string|null
     */
    public static function getTokenFromHeader() {
        $headers = getallheaders();
        if (isset($headers['Authorization'])) {
            $auth = $headers['Authorization'];
            if (preg_match('/Bearer\s+(.*)$/i', $auth, $matches)) {
                return $matches[1];
            }
        }
        return null;
    }

    /**
     * 获取客户端IP
     * @return string
     */
    public static function getClientIp() {
        if (!empty($_SERVER['HTTP_CLIENT_IP'])) {
            return $_SERVER['HTTP_CLIENT_IP'];
        } elseif (!empty($_SERVER['HTTP_X_FORWARDED_FOR'])) {
            return $_SERVER['HTTP_X_FORWARDED_FOR'];
        } else {
            return $_SERVER['REMOTE_ADDR'] ?? '';
        }
    }

    /**
     * 发送邮件验证码
     * @param string $email
     * @param string $code
     * @param string $type
     * @return bool
     */
    public static function sendEmailCode($email, $code, $type = 'register') {
        $subject = '';
        $message = '';

        // 确定邮件类型
        $emailType = EmailType::REGISTER; // 默认
        switch ($type) {
            case 'register':
                $subject = '【TV会员】注册验证码';
                $message = "您正在注册TV会员账号";
                $emailType = EmailType::REGISTER;
                break;
            case 'login':
                $subject = '【TV会员】登录验证码';
                $message = "您正在登录TV会员账号";
                $emailType = EmailType::NOTIFICATION;
                break;
            case 'reset_password':
                $subject = '【TV会员】重置密码验证码';
                $message = "您正在重置密码";
                $emailType = EmailType::RESET_PASSWORD;
                break;
        }

        // 构建HTML邮件内容
        $htmlMessage = "
<!DOCTYPE html>
<html>
<head><meta charset='UTF-8'></head>
<body style='margin:0;padding:20px;font-family:Arial,sans-serif;background:#f5f5f5;'>
    <div style='max-width:600px;margin:0 auto;background:white;border-radius:10px;overflow:hidden;box-shadow:0 2px 10px rgba(0,0,0,0.1);'>
        <div style='background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);color:white;padding:30px;text-align:center;'>
            <h1 style='margin:0;font-size:24px;'>TV会员系统</h1>
            <p style='margin:10px 0 0 0;opacity:0.9;'>{$message}</p>
        </div>
        <div style='padding:40px 30px;'>
            <p style='margin:0 0 20px 0;color:#333;font-size:16px;'>您的验证码是：</p>
            <div style='background:#f8f9fa;border:2px dashed #667eea;border-radius:8px;padding:20px;text-align:center;margin:20px 0;'>
                <span style='font-size:36px;font-weight:bold;color:#667eea;letter-spacing:8px;'>{$code}</span>
            </div>
            <p style='color:#666;font-size:14px;line-height:1.6;margin:20px 0;'>
                • 验证码5分钟内有效<br>
                • 请勿将验证码告诉他人<br>
                • 如非本人操作，请忽略此邮件
            </p>
        </div>
        <div style='background:#f8f9fa;padding:20px;text-align:center;color:#999;font-size:12px;'>
            <p style='margin:0;'>此邮件由系统自动发送，请勿回复</p>
        </div>
    </div>
</body>
</html>";

        // QQ邮箱配置 - 使用SSL加密的465端口
        $smtpHost = 'smtp.qq.com';
        $smtpPort = 465;
        $smtpUser = 'darrenlinfang@foxmail.com';
        $smtpPass = 'sewqgjmnatpucihc';
        $fromEmail = 'darrenlinfang@foxmail.com';
        $fromName = 'TV会员系统';

        try {
            // 使用SSL连接到SMTP服务器
            $socket = @stream_socket_client(
                "ssl://{$smtpHost}:{$smtpPort}",
                $errno,
                $errstr,
                30,
                STREAM_CLIENT_CONNECT,
                stream_context_create([
                    'ssl' => [
                        'verify_peer' => false,
                        'verify_peer_name' => false,
                        'allow_self_signed' => true
                    ]
                ])
            );

            if (!$socket) {
                error_log("SSL连接失败: {$errno} - {$errstr}");

                // 记录邮件发送失败
                logEmail($email, $emailType, $subject, $htmlMessage, false, null, "SSL连接失败: {$errno} - {$errstr}");

                return true; // 验证码已保存，返回true
            }

            stream_set_timeout($socket, 30);

            // 读取欢迎信息
            $response = fgets($socket, 515);
            error_log("SMTP响应: {$response}");

            // EHLO
            fputs($socket, "EHLO {$smtpHost}\r\n");
            $response = '';
            while ($line = fgets($socket, 515)) {
                $response .= $line;
                if (substr($line, 3, 1) == ' ') break;
            }
            error_log("EHLO响应: {$response}");

            // AUTH LOGIN
            fputs($socket, "AUTH LOGIN\r\n");
            $response = fgets($socket, 515);
            error_log("AUTH LOGIN响应: {$response}");

            // 发送用户名
            fputs($socket, base64_encode($smtpUser) . "\r\n");
            $response = fgets($socket, 515);
            error_log("用户名响应: {$response}");

            // 发送密码
            fputs($socket, base64_encode($smtpPass) . "\r\n");
            $response = fgets($socket, 515);
            error_log("密码响应: {$response}");

            if (strpos($response, '235') === false) {
                error_log("SMTP认证失败");

                // 记录邮件发送失败
                logEmail($email, $emailType, $subject, $htmlMessage, false, null, "SMTP认证失败: {$response}");

                fclose($socket);
                return true; // 验证码已保存，返回true
            }

            // MAIL FROM
            fputs($socket, "MAIL FROM: <{$fromEmail}>\r\n");
            $response = fgets($socket, 515);
            error_log("MAIL FROM响应: {$response}");

            // RCPT TO
            fputs($socket, "RCPT TO: <{$email}>\r\n");
            $response = fgets($socket, 515);
            error_log("RCPT TO响应: {$response}");

            // DATA
            fputs($socket, "DATA\r\n");
            $response = fgets($socket, 515);
            error_log("DATA响应: {$response}");

            // 构建邮件内容
            $mailContent = "From: =?UTF-8?B?" . base64_encode($fromName) . "?= <{$fromEmail}>\r\n";
            $mailContent .= "To: <{$email}>\r\n";
            $mailContent .= "Subject: =?UTF-8?B?" . base64_encode($subject) . "?=\r\n";
            $mailContent .= "MIME-Version: 1.0\r\n";
            $mailContent .= "Content-Type: text/html; charset=UTF-8\r\n";
            $mailContent .= "Content-Transfer-Encoding: base64\r\n";
            $mailContent .= "\r\n";
            $mailContent .= chunk_split(base64_encode($htmlMessage));
            $mailContent .= "\r\n.\r\n";

            fputs($socket, $mailContent);
            $response = fgets($socket, 515);
            error_log("发送内容响应: {$response}");

            // QUIT
            fputs($socket, "QUIT\r\n");
            fclose($socket);

            if (strpos($response, '250') !== false) {
                error_log("邮件发送成功到 {$email}: {$code}");

                // 记录邮件发送成功
                logEmail($email, $emailType, $subject, $htmlMessage, true, null, '');

                return true;
            } else {
                error_log("邮件发送可能失败: {$response}");

                // 记录邮件发送失败
                logEmail($email, $emailType, $subject, $htmlMessage, false, null, "邮件发送失败: {$response}");

                return true; // 验证码已保存，返回true
            }

        } catch (Exception $e) {
            error_log("邮件发送异常: " . $e->getMessage());

            // 记录邮件发送异常
            logEmail($email, $emailType, $subject, $htmlMessage, false, null, "异常: " . $e->getMessage());

            return true; // 验证码已保存，返回true
        }
    }

    /**
     * 增加积分
     * @param PDO $db
     * @param int $memberId
     * @param int $points
     * @param string $type
     * @param string $remark
     * @return bool
     */
    public static function addPoints($db, $memberId, $points, $type, $remark = '') {
        try {
            $db->beginTransaction();

            // 获取当前积分
            $stmt = $db->prepare("SELECT points, total_points FROM app_members WHERE id = ? FOR UPDATE");
            $stmt->execute([$memberId]);
            $member = $stmt->fetch();

            if (!$member) {
                $db->rollBack();
                return false;
            }

            $pointsBefore = $member['points'];
            $pointsAfter = $pointsBefore + $points;
            $totalPoints = $member['total_points'] + $points;

            // 更新会员积分
            $stmt = $db->prepare("UPDATE app_members SET points = ?, total_points = ? WHERE id = ?");
            $stmt->execute([$pointsAfter, $totalPoints, $memberId]);

            // 记录积分变动
            $stmt = $db->prepare("
                INSERT INTO app_points_logs (member_id, type, points, points_before, points_after, remark)
                VALUES (?, ?, ?, ?, ?, ?)
            ");
            $stmt->execute([$memberId, $type, $points, $pointsBefore, $pointsAfter, $remark]);

            $db->commit();
            return true;

        } catch (Exception $e) {
            $db->rollBack();
            error_log("增加积分失败: " . $e->getMessage());
            return false;
        }
    }
}
