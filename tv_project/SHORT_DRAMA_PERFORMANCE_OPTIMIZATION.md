# 短剧详情加载性能优化

## 问题
短剧详情页面加载速度慢。

## 优化措施

### 1. 减少网络超时时间
**文件**: `app/src/main/java/com/tv/ball/VideoPlayerApplication.kt`

**优化前**:
- 连接超时: 30秒
- 读取超时: 30秒
- 写入超时: 30秒
- 无总调用超时

**优化后**:
- 连接超时: 10秒 ⬇️ 减少20秒
- 读取超时: 15秒 ⬇️ 减少15秒
- 写入超时: 15秒 ⬇️ 减少15秒
- 总调用超时: 20秒 ✨ 新增

**效果**: 失败请求更快返回，不会长时间等待

### 2. 添加连接池优化
**文件**: `app/src/main/java/com/tv/ball/VideoPlayerApplication.kt`

```kotlin
.connectionPool(okhttp3.ConnectionPool(10, 5, TimeUnit.MINUTES))
.retryOnConnectionFailure(true)
```

**效果**: 
- 复用TCP连接，避免重复建立连接
- 最多保持10个空闲连接
- 连接保活5分钟
- 自动重试失败的连接

### 3. 禁用智能网络切换
**文件**: `app/src/main/java/com/tv/ball/presentation/shortdrama/ShortDramaDetailViewModel.kt`

```kotlin
networkDataSource.get(url, headers, useSmartNetwork = false)
```

**效果**: 
- 直接使用当前网络，不检测和切换网络
- 减少网络检测开销
- 加快请求速度

### 4. 添加内存缓存
**文件**: `app/src/main/java/com/tv/ball/presentation/shortdrama/ShortDramaDetailViewModel.kt`

**实现**:
```kotlin
// 缓存详情数据5分钟
private val detailCache = mutableMapOf<String, Pair<ShortDramaItem, List<EpisodeItem>>>()
private const val CACHE_EXPIRY_MS = 5 * 60 * 1000L
```

**效果**:
- 重复访问同一短剧时，直接从内存读取
- 5分钟内无需重新请求网络
- 几乎瞬时加载（<1ms）

### 5. 添加性能监控日志
**文件**: `app/src/main/java/com/tv/ball/presentation/shortdrama/ShortDramaDetailViewModel.kt`

**日志输出**:
```
⏱️ [开始] 请求短剧详情
⏱️ [网络] 请求完成，耗时: XXXms
⏱️ [解析] 数据解析完成，耗时: XXXms
⏱️ [总计] 加载详情总耗时: XXXms (网络: XXXms, 解析: XXXms)
⚡ 使用缓存数据，缓存时间: X秒前
💾 数据已缓存
```

**效果**: 
- 可以精确定位性能瓶颈
- 区分网络耗时和解析耗时
- 监控缓存命中情况

### 6. 优化HTTP请求头
**文件**: `app/src/main/java/com/tv/ball/presentation/shortdrama/ShortDramaDetailViewModel.kt`

**新增**:
```kotlin
"Connection" to "keep-alive"  // 保持连接
"Cache-Control" to "no-cache" // 不使用过期缓存
```

**效果**: 
- 保持HTTP连接，减少握手开销
- 确保获取最新数据

## 预期性能提升

### 首次加载
- **优化前**: 可能需要30秒（如果网络慢或超时）
- **优化后**: 通常2-5秒（正常网络），最多20秒（超时）
- **提升**: 最坏情况减少10秒，正常情况更快响应

### 重复访问（5分钟内）
- **优化前**: 每次都需要网络请求（2-30秒）
- **优化后**: 使用缓存，几乎瞬时（<1ms）
- **提升**: 接近100%的速度提升

### 连接复用
- **优化前**: 每次请求都建立新连接
- **优化后**: 复用已有连接
- **提升**: 节省TCP握手时间（通常100-300ms）

## 测试建议

1. **首次加载测试**
   - 打开短剧详情页
   - 查看Logcat中的性能日志
   - 记录总耗时和网络耗时

2. **缓存测试**
   - 打开短剧详情页
   - 返回列表
   - 再次打开同一短剧
   - 应该看到"⚡ 使用缓存数据"日志

3. **超时测试**
   - 在弱网环境下测试
   - 确认最多等待20秒后返回错误
   - 不会无限等待

4. **连接复用测试**
   - 连续打开多个不同短剧的详情
   - 第2个及以后的请求应该更快
   - 因为复用了TCP连接

## 进一步优化建议

如果还是觉得慢，可以考虑：

1. **预加载**: 在列表页面预加载热门短剧的详情
2. **数据库缓存**: 将详情数据持久化到数据库，离线也能查看
3. **CDN加速**: 如果API支持，使用CDN加速
4. **分页加载**: 如果剧集很多，分批加载剧集列表
5. **骨架屏**: 添加骨架屏提升感知速度
