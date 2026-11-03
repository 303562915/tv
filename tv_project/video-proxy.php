<?php
/**
 * 视频代理脚本 - PHP版本（增强版）
 * 用于虚拟主机部署，支持m3u8路径重写
 *
 * 使用方式：
 * https://你的域名/video-proxy.php?url=视频地址&referer=来源&ua=UA
 */

// 允许跨域
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
header('Access-Control-Allow-Headers: *');
header('Access-Control-Expose-Headers: *');

// 处理 OPTIONS 预检请求
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(200);
    exit;
}

// 获取参数
$targetUrl = isset($_GET['url']) ? $_GET['url'] : '';
$referer = isset($_GET['referer']) ? $_GET['referer'] : '';
$userAgent = isset($_GET['ua']) ? $_GET['ua'] : 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';

// 验证URL
if (empty($targetUrl)) {
    http_response_code(400);
    echo '错误: 缺少 url 参数';
    exit;
}

// 验证URL格式
if (!filter_var($targetUrl, FILTER_VALIDATE_URL)) {
    http_response_code(400);
    echo '错误: 无效的 URL 格式';
    exit;
}

// 使用 cURL 转发请求（带重试机制）
if (function_exists('curl_init')) {
    $maxRetries = 3;
    $retryDelay = 1; // 秒
    $content = false;
    $httpCode = 0;
    $contentType = '';
    $lastError = '';

    for ($attempt = 1; $attempt <= $maxRetries; $attempt++) {
        $ch = curl_init();

        // 设置 cURL 选项
        curl_setopt($ch, CURLOPT_URL, $targetUrl);
        curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
        curl_setopt($ch, CURLOPT_FOLLOWLOCATION, true);
        curl_setopt($ch, CURLOPT_MAXREDIRS, 5);
        curl_setopt($ch, CURLOPT_TIMEOUT, 60);  // 增加到60秒
        curl_setopt($ch, CURLOPT_CONNECTTIMEOUT, 15);  // 连接超时15秒
        curl_setopt($ch, CURLOPT_SSL_VERIFYPEER, false);
        curl_setopt($ch, CURLOPT_SSL_VERIFYHOST, false);
        curl_setopt($ch, CURLOPT_ENCODING, '');  // 支持gzip压缩

        // 设置请求头
        $headers = array(
            'User-Agent: ' . $userAgent,
            'Accept: */*',
            'Accept-Language: zh-CN,zh;q=0.9,en;q=0.8',
            'Connection: keep-alive',
            'Cache-Control: no-cache'
        );

        if (!empty($referer)) {
            $headers[] = 'Referer: ' . $referer;
        }

        curl_setopt($ch, CURLOPT_HTTPHEADER, $headers);

        // 获取响应
        $content = curl_exec($ch);
        $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
        $contentType = curl_getinfo($ch, CURLINFO_CONTENT_TYPE);
        $lastError = curl_error($ch);

        curl_close($ch);

        // 成功则跳出循环
        if ($content !== false && $httpCode >= 200 && $httpCode < 400) {
            break;
        }

        // 失败则记录并重试
        error_log("代理请求失败 (尝试 $attempt/$maxRetries): $targetUrl - HTTP $httpCode - $lastError");

        if ($attempt < $maxRetries) {
            sleep($retryDelay);
        }
    }

    // 所有重试都失败
    if ($content === false || $httpCode >= 400) {
        http_response_code(500);
        echo json_encode([
            'error' => '代理请求失败',
            'url' => $targetUrl,
            'httpCode' => $httpCode,
            'message' => $lastError,
            'retries' => $maxRetries
        ]);
        exit;
    }

    // 如果是m3u8文件，重写里面的相对路径
    if (strpos($contentType, 'application/vnd.apple.mpegurl') !== false ||
        strpos($contentType, 'application/x-mpegURL') !== false ||
        strpos($targetUrl, '.m3u8') !== false) {

        // 解析目标URL
        $parsedUrl = parse_url($targetUrl);
        $scheme = $parsedUrl['scheme'];
        $host = $parsedUrl['host'];
        $baseUrl = dirname($parsedUrl['path']) . '/';

        // 重写m3u8中的相对路径
        $lines = explode("\n", $content);
        $newLines = array();

        foreach ($lines as $line) {
            $line = trim($line);

            // 跳过空行
            if (empty($line)) {
                $newLines[] = $line;
                continue;
            }

            // 处理 #EXT-X-KEY 行（加密密钥）
            if (strpos($line, '#EXT-X-KEY') === 0) {
                // 提取 URI
                if (preg_match('/URI="([^"]+)"/', $line, $matches)) {
                    $keyUri = $matches[1];

                    // 解析 key URI 为完整 URL
                    if (preg_match('/^https?:\/\//', $keyUri)) {
                        $fullUrl = $keyUri;
                    } elseif ($keyUri[0] === '/') {
                        $fullUrl = $scheme . '://' . $host . $keyUri;
                    } else {
                        $fullUrl = $scheme . '://' . $host . $baseUrl . $keyUri;
                    }

                    // 构建代理URL
                    $proxyUrl = $_SERVER['REQUEST_SCHEME'] . '://' . $_SERVER['HTTP_HOST'] . $_SERVER['SCRIPT_NAME'] .
                               '?url=' . urlencode($fullUrl);
                    if (!empty($referer)) {
                        $proxyUrl .= '&referer=' . urlencode($referer);
                    }
                    if (!empty($userAgent)) {
                        $proxyUrl .= '&ua=' . urlencode($userAgent);
                    }

                    // 替换 URI
                    $line = preg_replace('/URI="[^"]+"/', 'URI="' . $proxyUrl . '"', $line);
                }
                $newLines[] = $line;
                continue;
            }

            // 跳过其他注释行
            if ($line[0] === '#') {
                $newLines[] = $line;
                continue;
            }

            // 处理分片文件路径
            // 如果是完整URL（http/https开头）
            if (preg_match('/^https?:\/\//', $line)) {
                $fullUrl = $line;
            }
            // 如果是绝对路径（/开头），从域名根开始
            elseif ($line[0] === '/') {
                $fullUrl = $scheme . '://' . $host . $line;
            }
            // 否则是相对路径，相对于当前m3u8文件所在目录
            else {
                $fullUrl = $scheme . '://' . $host . $baseUrl . $line;
            }

            // 构建代理URL
            $proxyUrl = $_SERVER['REQUEST_SCHEME'] . '://' . $_SERVER['HTTP_HOST'] . $_SERVER['SCRIPT_NAME'] .
                       '?url=' . urlencode($fullUrl);
            if (!empty($referer)) {
                $proxyUrl .= '&referer=' . urlencode($referer);
            }
            if (!empty($userAgent)) {
                $proxyUrl .= '&ua=' . urlencode($userAgent);
            }
            $newLines[] = $proxyUrl;
        }

        $content = implode("\n", $newLines);
    }

    // 设置响应头
    http_response_code($httpCode);
    header('Content-Type: ' . ($contentType ?: 'application/octet-stream'));
    header('Access-Control-Allow-Origin: *');

    // 输出内容
    echo $content;

} else {
    http_response_code(500);
    echo '错误: cURL 扩展未安装';
}
?>
