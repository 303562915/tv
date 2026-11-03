/**
 * Cloudflare Worker 视频代理
 * 用于转发视频请求并添加自定义请求头
 *
 * 部署步骤：
 * 1. 访问 https://dash.cloudflare.com/
 * 2. 进入 Workers & Pages
 * 3. 创建新的 Worker
 * 4. 复制此代码粘贴进去
 * 5. 点击 Deploy
 *
 * 使用方式：
 * https://your-worker.workers.dev/?url=视频地址&referer=来源&ua=UA
 */

addEventListener('fetch', event => {
  event.respondWith(handleRequest(event.request))
})

async function handleRequest(request) {
  // 处理 CORS 预检请求
  if (request.method === 'OPTIONS') {
    return new Response(null, {
      headers: {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET, HEAD, POST, OPTIONS',
        'Access-Control-Allow-Headers': '*',
        'Access-Control-Max-Age': '86400',
      }
    })
  }

  try {
    const url = new URL(request.url)

    // 获取参数
    const targetUrl = url.searchParams.get('url')
    const referer = url.searchParams.get('referer') || ''
    const userAgent = url.searchParams.get('ua') || 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'

    if (!targetUrl) {
      return new Response('缺少 url 参数', {
        status: 400,
        headers: { 'Content-Type': 'text/plain; charset=utf-8' }
      })
    }

    // 构建请求头
    const headers = {
      'User-Agent': userAgent,
      'Accept': '*/*',
      'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
      'Accept-Encoding': 'gzip, deflate, br',
      'Connection': 'keep-alive',
    }

    // 添加 Referer（如果提供）
    if (referer) {
      headers['Referer'] = referer
    }

    // 转发请求
    const response = await fetch(targetUrl, {
      method: request.method,
      headers: headers,
      redirect: 'follow'
    })

    // 构建响应头
    const responseHeaders = new Headers(response.headers)
    responseHeaders.set('Access-Control-Allow-Origin', '*')
    responseHeaders.set('Access-Control-Allow-Methods', 'GET, HEAD, POST, OPTIONS')
    responseHeaders.set('Access-Control-Allow-Headers', '*')
    responseHeaders.set('Access-Control-Expose-Headers', '*')

    // 删除可能导致问题的头
    responseHeaders.delete('Content-Security-Policy')
    responseHeaders.delete('X-Frame-Options')

    // 返回响应
    return new Response(response.body, {
      status: response.status,
      statusText: response.statusText,
      headers: responseHeaders
    })

  } catch (error) {
    return new Response(`代理错误: ${error.message}`, {
      status: 500,
      headers: {
        'Content-Type': 'text/plain; charset=utf-8',
        'Access-Control-Allow-Origin': '*'
      }
    })
  }
}
