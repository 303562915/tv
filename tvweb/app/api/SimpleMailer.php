<?php
/**
 * PHPMailer 简化版
 * 用于发送邮件
 */

class SimpleMailer {
    private $host = 'smtp.qq.com';
    private $port = 587; // 使用TLS的587端口，更稳定
    private $username = 'REDACTED_MAILBOX';
    private $password = 'REDACTED_SMTP_AUTH_CODE';
    private $fromEmail = 'REDACTED_MAILBOX';
    private $fromName = 'TV会员系统';

    public function send($to, $subject, $body) {
        // 使用PHP内置的mail函数配合stream_socket_client
        $boundary = md5(time());

        $headers = "From: {$this->fromName} <{$this->fromEmail}>\r\n";
        $headers .= "Reply-To: {$this->fromEmail}\r\n";
        $headers .= "MIME-Version: 1.0\r\n";
        $headers .= "Content-Type: text/html; charset=UTF-8\r\n";
        $headers .= "X-Mailer: PHP/" . phpversion() . "\r\n";

        try {
            // 尝试使用stream_socket_client
            $smtp = stream_socket_client(
                "tcp://{$this->host}:{$this->port}",
                $errno,
                $errstr,
                30,
                STREAM_CLIENT_CONNECT
            );

            if (!$smtp) {
                error_log("SMTP连接失败: {$errno} - {$errstr}");
                return false;
            }

            // 启用TLS加密
            stream_set_timeout($smtp, 30);

            // 读取欢迎消息
            $this->getResponse($smtp);

            // EHLO
            $this->sendCommand($smtp, "EHLO {$this->host}\r\n");

            // STARTTLS
            $this->sendCommand($smtp, "STARTTLS\r\n");

            // 启用加密
            stream_socket_enable_crypto($smtp, true, STREAM_CRYPTO_METHOD_TLS_CLIENT);

            // 再次EHLO
            $this->sendCommand($smtp, "EHLO {$this->host}\r\n");

            // AUTH LOGIN
            $this->sendCommand($smtp, "AUTH LOGIN\r\n");
            $this->sendCommand($smtp, base64_encode($this->username) . "\r\n");
            $this->sendCommand($smtp, base64_encode($this->password) . "\r\n");

            // MAIL FROM
            $this->sendCommand($smtp, "MAIL FROM: <{$this->fromEmail}>\r\n");

            // RCPT TO
            $this->sendCommand($smtp, "RCPT TO: <{$to}>\r\n");

            // DATA
            $this->sendCommand($smtp, "DATA\r\n");

            // 发送邮件内容
            $message = "From: {$this->fromName} <{$this->fromEmail}>\r\n";
            $message .= "To: <{$to}>\r\n";
            $message .= "Subject: =?UTF-8?B?" . base64_encode($subject) . "?=\r\n";
            $message .= "MIME-Version: 1.0\r\n";
            $message .= "Content-Type: text/html; charset=UTF-8\r\n";
            $message .= "\r\n";
            $message .= $body . "\r\n";
            $message .= ".\r\n";

            fputs($smtp, $message);
            $this->getResponse($smtp);

            // QUIT
            $this->sendCommand($smtp, "QUIT\r\n");
            fclose($smtp);

            return true;

        } catch (Exception $e) {
            error_log("邮件发送异常: " . $e->getMessage());
            return false;
        }
    }

    private function sendCommand($smtp, $command) {
        fputs($smtp, $command);
        return $this->getResponse($smtp);
    }

    private function getResponse($smtp) {
        $response = '';
        while ($line = fgets($smtp, 515)) {
            $response .= $line;
            if (substr($line, 3, 1) == ' ') {
                break;
            }
        }
        return $response;
    }
}
