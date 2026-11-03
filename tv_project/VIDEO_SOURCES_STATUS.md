# 视频源状态说明

## 测试时间：2026-04-11

### 可用的视频源 ✅

1. **豆瓣资源 (dbzy)** - 正常
   - URL: `https://caiji.dbzy5.com/api.php/provide/vod/from/dbm3u8/at/json/`
   - 状态: HTTP 200
   
2. **量子资源 (lzizy8)** - 正常
   - URL: `http://23.224.101.30/api.php/provide/vod/?ac=list`
   - 状态: HTTP 200
   - 注意: 部分视频可能无封面图

3. **360资源 (360zy)** - 正常
   - URL: `https://360zy5.com/api.php/provide/vod/`
   - 状态: HTTP 200
   - 有完整的封面图

### 有问题的视频源 ⚠️

1. **爱奇艺资源 (iqiyi)** - 可能有连接问题
   - URL: `https://iqiyizyapi.com/api.php/provide/vod/`
   - 问题: 部分用户报告443错误
   - 建议: 检查网络连接或切换其他源

2. **UU资源 (uuzy)** - 参数问题
   - URL: `https://uuzy.me/api.php/provide/vod/?ac=list`
   - 问题: 某些分类返回404
   - 状态: 基础API可用，但部分分类不可用
   - 注意: 无封面图

3. **非凡资源 (ffzy5)** - 端口问题
   - URL: `http://ffzy5.tv/api.php/provide/vod/`
   - 问题: 80端口连接问题
   - 建议: 暂时使用其他源

4. **极速资源 (jisuzy)** - 服务不可用
   - URL: `http://jisuziyuan999.com/api.php/provide/vod/`
   - 状态: HTTP 503 Service Unavailable
   - 建议: 等待服务恢复或使用其他源

5. **牛牛资源 (niuniuzy3)** - 未测试
   - URL: `http://niuniuzy3.com/api.php/provide/vod/`

## 推荐使用顺序

1. 360资源 (有图片，稳定)
2. 豆瓣资源 (稳定)
3. 量子资源 (稳定但无图片)
4. 其他源作为备用

## 用户操作建议

1. 如果当前视频源无法加载，请切换到其他源
2. 在"本地"标签页可以看到所有视频源列表
3. 点击视频源即可切换
4. 切换后返回主页刷新即可

## 技术说明

- 视频源服务器由第三方提供，稳定性不受应用控制
- 部分源可能因为维护、网络问题等原因暂时不可用
- 应用已经实现了错误处理，不会因为源不可用而崩溃
- 建议收藏多个可用的视频源以备切换
