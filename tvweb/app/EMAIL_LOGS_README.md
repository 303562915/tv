# 邮箱记录功能使用说明

## 功能概述

邮箱记录功能用于记录系统发送的所有邮件，包括：
- 邮件类型
- 收件人
- 关联用户
- 发送状态
- 发送时间
- 错误信息（如果失败）

## 数据库表结构

### app_email_logs 表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | INT | 主键 |
| user_id | INT | 用户ID（可为空） |
| recipient_email | VARCHAR(255) | 收件人邮箱 |
| email_type | VARCHAR(50) | 邮件类型 |
| subject | VARCHAR(255) | 邮件主题 |
| content | TEXT | 邮件内容 |
| status | TINYINT | 发送状态（0=失败，1=成功） |
| error_message | TEXT | 错误信息 |
| created_at | TIMESTAMP | 发送时间 |

### 邮件类型

- `register` - 注册验证
- `reset_password` - 密码重置
- `notification` - 通知
- `vip_expire` - VIP到期提醒
- `invite` - 邀请
- `system` - 系统通知

## 使用方法

### 1. 在代码中记录邮件发送

```php
<?php
require_once 'email_logger.php';

// 发送邮件后记录日志
$emailSent = sendEmail($to, $subject, $content); // 你的发送邮件函数

// 记录日志
logEmail(
    $to,                          // 收件人邮箱
    EmailType::REGISTER,          // 邮件类型
    $subject,                     // 邮件主题
    $content,                     // 邮件内容
    $emailSent,                   // 发送状态（true/false）
    $userId,                      // 用户ID（可选）
    $emailSent ? '' : $errorMsg   // 错误信息
);
?>
```

### 2. 使用邮件类型常量

```php
<?php
// 推荐使用常量
EmailType::REGISTER         // 注册验证
EmailType::RESET_PASSWORD   // 密码重置
EmailType::NOTIFICATION     // 通知
EmailType::VIP_EXPIRE       // VIP到期提醒
EmailType::INVITE           // 邀请
EmailType::SYSTEM           // 系统通知
?>
```

### 3. 后台查看邮件记录

1. 登录后台管理系统
2. 点击左侧菜单 "邮箱记录"
3. 可以按以下条件筛选：
   - 关键词（邮箱或用户名）
   - 邮件类型
   - 发送状态（成功/失败）

### 4. 查看邮件详情

点击列表中的 "查看详情" 按钮，可以看到：
- 完整的邮件内容
- 错误信息（如果失败）
- 关联的用户信息

## API接口

### 获取邮件记录列表

```
GET /api/email_logs.php
```

参数：
- `page` - 页码（默认1）
- `pageSize` - 每页数量（默认20）
- `keyword` - 搜索关键词（邮箱或用户名）
- `email_type` - 邮件类型
- `status` - 状态（0=失败，1=成功）

响应：
```json
{
  "code": 0,
  "msg": "操作成功",
  "data": {
    "list": [...],
    "total": 100
  }
}
```

### 添加邮件记录

```
POST /api/email_logs.php
```

请求体：
```json
{
  "user_id": 1,
  "recipient_email": "user@example.com",
  "email_type": "register",
  "subject": "欢迎注册",
  "content": "邮件内容",
  "status": 1,
  "error_message": ""
}
```

### 删除邮件记录

```
DELETE /api/email_logs.php?id=1
```

## 集成到现有邮件发送代码

### 示例1: 注册验证邮件

```php
<?php
require_once 'email_logger.php';

function sendRegisterEmail($userId, $email, $code) {
    $subject = '欢迎注册TV平台';
    $content = "您的验证码是: {$code}";
    
    try {
        // 发送邮件
        $result = mail($email, $subject, $content);
        
        // 记录日志
        logEmail(
            $email,
            EmailType::REGISTER,
            $subject,
            $content,
            $result,
            $userId,
            $result ? '' : 'Mail function failed'
        );
        
        return $result;
    } catch (Exception $e) {
        // 记录失败日志
        logEmail(
            $email,
            EmailType::REGISTER,
            $subject,
            $content,
            false,
            $userId,
            $e->getMessage()
        );
        return false;
    }
}
?>
```

### 示例2: 使用PHPMailer

```php
<?php
require_once 'email_logger.php';
require_once 'PHPMailer/PHPMailer.php';

use PHPMailer\PHPMailer\PHPMailer;

function sendEmailWithPHPMailer($userId, $to, $subject, $content, $type) {
    $mail = new PHPMailer(true);
    
    try {
        // SMTP配置
        $mail->isSMTP();
        $mail->Host = 'smtp.example.com';
        $mail->SMTPAuth = true;
        $mail->Username = 'your@email.com';
        $mail->Password = 'password';
        
        // 邮件内容
        $mail->setFrom('your@email.com', 'TV平台');
        $mail->addAddress($to);
        $mail->Subject = $subject;
        $mail->Body = $content;
        
        // 发送
        $mail->send();
        
        // 记录成功日志
        logEmail($to, $type, $subject, $content, true, $userId, '');
        
        return true;
    } catch (Exception $e) {
        // 记录失败日志
        logEmail($to, $type, $subject, $content, false, $userId, $e->getMessage());
        
        return false;
    }
}
?>
```

## 维护建议

1. **定期清理旧记录**
   - 建议保留最近3-6个月的记录
   - 可以创建定时任务清理旧数据

2. **监控失败率**
   - 定期检查失败的邮件记录
   - 分析失败原因并优化

3. **性能优化**
   - 表已添加索引（user_id, email_type, status, created_at）
   - 大量数据时考虑分表或归档

## 故障排除

### 问题1: 菜单不显示

解决方案：
```bash
# 执行添加菜单脚本
curl https://your-server-domain.com/app/add_email_logs_menu.php
```

### 问题2: 表不存在

解决方案：
```bash
# 执行数据库修复脚本
curl https://your-server-domain.com/app/fix_database.php
```

### 问题3: 记录日志失败

检查：
1. 数据库连接是否正常
2. app_email_logs 表是否存在
3. 检查PHP错误日志

## 测试

运行测试脚本插入示例数据：
```bash
curl https://your-server-domain.com/app/test_email_logs.php
```

这将插入6条测试记录，包括成功和失败的案例。
