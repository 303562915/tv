# 邮件日志记录功能修复说明

## 问题原因

发送验证码邮件时，`MemberHelper.php` 中的 `sendEmailCode()` 方法**没有调用 `logEmail()` 函数**来记录邮件发送，所以后台看不到邮件记录。

## 已完成的修复

### 1. 修改 `api/MemberHelper.php`

**添加引入：**
```php
require_once __DIR__ . '/../email_logger.php';
```

**在邮件发送的各个位置添加日志记录：**

- ✅ SSL连接失败时记录
- ✅ SMTP认证失败时记录
- ✅ 邮件发送成功时记录
- ✅ 邮件发送失败时记录
- ✅ 异常情况时记录

### 2. 创建测试工具

**测试页面：** `test_email_logging.html`
- 发送测试邮件
- 查看邮件记录
- 检查数据库状态

**数据库检查：** `check_email_logs_table.php`
- 检查表是否存在
- 检查表结构
- 显示最近的记录

## 测试步骤

### 方法1: 使用测试页面（推荐）

访问测试页面：
```
https://your-server-domain.com/app/test_email_logging.html
```

1. 输入邮箱地址
2. 选择验证码类型
3. 点击"发送测试邮件"
4. 查看邮件记录

### 方法2: 实际发送验证码

1. 访问前台登录页面
2. 使用邮箱登录
3. 点击"发送验证码"
4. 登录后台查看"邮箱记录"菜单

### 方法3: 检查数据库

访问：
```
https://your-server-domain.com/app/check_email_logs_table.php
```

查看：
- 表是否存在
- 字段是否完整
- 是否有记录

## 邮件类型映射

| 验证码类型 | 邮件日志类型 | 说明 |
|-----------|-------------|------|
| register | register | 注册验证码 |
| login | notification | 登录验证码 |
| reset_password | reset_password | 重置密码 |

## 记录的信息

每次发送邮件都会记录：
- ✓ 收件人邮箱
- ✓ 邮件类型
- ✓ 邮件主题
- ✓ 邮件内容（HTML）
- ✓ 发送状态（成功/失败）
- ✓ 错误信息（如果失败）
- ✓ 发送时间

## 查看记录

### 后台管理界面
1. 登录后台
2. 点击左侧菜单"邮箱记录"
3. 可以按类型、状态筛选
4. 点击"查看详情"查看完整内容

### API接口
```bash
# 获取邮件记录
curl "https://your-server-domain.com/app/api/email_logs.php?page=1&pageSize=10"

# 按类型筛选
curl "https://your-server-domain.com/app/api/email_logs.php?email_type=register"

# 按状态筛选
curl "https://your-server-domain.com/app/api/email_logs.php?status=1"
```

## 故障排除

### 问题1: 仍然看不到记录

**检查清单：**
1. 确认 `app_email_logs` 表存在
   ```bash
   访问：check_email_logs_table.php
   ```

2. 确认 `MemberHelper.php` 已更新
   ```bash
   # 检查是否包含 logEmail 调用
   grep -n "logEmail" api/MemberHelper.php
   ```

3. 确认邮件确实发送了
   ```bash
   # 查看PHP错误日志
   tail -f /var/log/php-fpm/error.log
   ```

### 问题2: 表不存在

运行数据库修复脚本：
```
https://your-server-domain.com/app/fix_database.php
```

### 问题3: 权限错误

检查数据库用户权限：
```sql
SHOW GRANTS FOR 'yughplvt'@'localhost';
```

确保有 INSERT 权限。

## 代码示例

### 手动记录邮件日志

```php
<?php
require_once 'email_logger.php';

// 发送邮件
$sent = sendEmail($to, $subject, $content);

// 记录日志
logEmail(
    $to,                          // 收件人
    EmailType::REGISTER,          // 类型
    $subject,                     // 主题
    $content,                     // 内容
    $sent,                        // 是否成功
    $userId,                      // 用户ID（可选）
    $sent ? '' : $errorMessage    // 错误信息
);
?>
```

### 查询邮件记录

```php
<?php
require_once 'api/config.php';

$db = Database::getInstance()->getConnection();

// 查询最近10条记录
$stmt = $db->query("
    SELECT * FROM app_email_logs 
    ORDER BY created_at DESC 
    LIMIT 10
");

$logs = $stmt->fetchAll();

foreach ($logs as $log) {
    echo "邮箱: {$log['recipient_email']}\n";
    echo "类型: {$log['email_type']}\n";
    echo "状态: " . ($log['status'] ? '成功' : '失败') . "\n";
    echo "时间: {$log['created_at']}\n\n";
}
?>
```

## 性能优化

### 定期清理旧记录

建议保留最近3-6个月的记录：

```sql
-- 删除6个月前的记录
DELETE FROM app_email_logs 
WHERE created_at < DATE_SUB(NOW(), INTERVAL 6 MONTH);
```

### 添加定时任务

```bash
# 编辑crontab
crontab -e

# 每月1号凌晨2点清理旧记录
0 2 1 * * mysql -u用户名 -p密码 数据库名 -e "DELETE FROM app_email_logs WHERE created_at < DATE_SUB(NOW(), INTERVAL 6 MONTH);"
```

## 监控建议

### 监控失败率

```sql
-- 查看今天的发送成功率
SELECT 
    COUNT(*) as total,
    SUM(status = 1) as success,
    SUM(status = 0) as failed,
    ROUND(SUM(status = 1) / COUNT(*) * 100, 2) as success_rate
FROM app_email_logs
WHERE DATE(created_at) = CURDATE();
```

### 查看失败原因

```sql
-- 查看最近的失败记录
SELECT 
    recipient_email,
    email_type,
    error_message,
    created_at
FROM app_email_logs
WHERE status = 0
ORDER BY created_at DESC
LIMIT 10;
```

## 总结

✅ 已修复邮件日志记录功能
✅ 已创建测试工具
✅ 已添加数据库检查脚本

现在每次发送验证码邮件都会自动记录到数据库，可以在后台"邮箱记录"菜单中查看。

**下一步：**
1. 访问 `test_email_logging.html` 测试功能
2. 发送一封验证码邮件
3. 在后台查看邮箱记录
