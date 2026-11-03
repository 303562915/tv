#!/bin/bash

# 测试M3U8 URL
URL="https://vod1.maowushi.com/20260221/UnbjP6RG/index.m3u8"

echo "测试 M3U8 URL: $URL"
echo "================================"

# 测试1: 基本请求
echo -e "\n1. 基本请求（无请求头）:"
curl -I "$URL" 2>&1 | head -20

# 测试2: 带User-Agent
echo -e "\n2. 带User-Agent:"
curl -I -H "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" "$URL" 2>&1 | head -20

# 测试3: 带完整请求头
echo -e "\n3. 带完整请求头:"
curl -I \
  -H "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" \
  -H "Accept: */*" \
  -H "Accept-Encoding: identity" \
  -H "Accept-Language: zh-CN,zh;q=0.9,en;q=0.8" \
  -H "Connection: keep-alive" \
  -H "Referer: https://www.maowushi.com" \
  "$URL" 2>&1 | head -20

# 测试4: 下载M3U8内容
echo -e "\n4. M3U8文件内容:"
curl -s \
  -H "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" \
  -H "Referer: https://www.maowushi.com" \
  "$URL" 2>&1 | head -30
