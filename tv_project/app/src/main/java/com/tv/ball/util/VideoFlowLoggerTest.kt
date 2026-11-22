package com.tv.ball.util

/**
 * 视频流程日志测试工具
 *
 * 用于生成测试日志，验证日志系统是否正常工作。
 *
 * ## 这不是测试类
 *
 * 它在 `src/main` 里，没有任何 `@Test` 注解、没有 `main` 函数，也不会被任何测试任务扫到；
 * 它只是给日志系统造样例数据的**手动演示工具**（被页面或调试入口调一下，生成一份看起来像
 * 真实播放流程的日志）。
 *
 * ## 里面的 `Thread.sleep` 是刻意保留的
 *
 * 日志是按时间戳排列的，相邻两条必须拉开一点间隔才能看出"先初始化、再嗅探、再解析"的顺序，
 * 所以这里按 100/200/300/500 ms 的节奏打点。作为演示工具，调用方自己选择在哪个线程跑它；
 * 真要接到主线程入口，调用方应把它丢到后台线程。因此这里整块抑制
 * `BlockingMethodInNonBlockingContext` —— 保留阻塞写法是需求本身，不是漏改的同步等待。
 * （若要接进单元测试，请改成 `runTest` + `advanceTimeBy` 的虚拟时间，那时的 sleep 才该消失。）
 */
@Suppress("BlockingMethodInNonBlockingContext")
object VideoFlowLoggerTest {
    
    /**
     * 创建一个测试日志会话
     *
     * 每两条日志之间的 `Thread.sleep` 是刻意阻塞（拉开时间戳间隔），由上方的
     * `@Suppress("BlockingMethodInNonBlockingContext")` 统一说明，不在每一处重复。
     */
    fun createTestSession() {
        VideoFlowLogger.startSession(
            videoTitle = "测试视频 - 示例影片",
            videoUrl = "https://example.com/test/video.m3u8"
        )
        
        // 模拟初始化
        VideoFlowLogger.info(
            VideoFlowLogger.FlowStage.INIT,
            "初始化播放器",
            "视频ID: 12345\n视频标题: 测试视频"
        )
        
        Thread.sleep(100)
        
        // 模拟嗅探
        VideoFlowLogger.success(
            VideoFlowLogger.FlowStage.SNIFF,
            "嗅探到视频资源",
            "URL: https://example.com/video1.m3u8\nType: m3u8"
        )
        
        Thread.sleep(100)
        
        VideoFlowLogger.success(
            VideoFlowLogger.FlowStage.SNIFF,
            "嗅探到视频资源",
            "URL: https://example.com/video2.mp4\nType: mp4"
        )
        
        Thread.sleep(100)
        
        // 模拟解析
        VideoFlowLogger.info(
            VideoFlowLogger.FlowStage.PARSE,
            "开始解析视频链接",
            "Parser: M3u8Parser\nURL: https://example.com/video1.m3u8"
        )
        
        Thread.sleep(200)
        
        VideoFlowLogger.success(
            VideoFlowLogger.FlowStage.PARSE,
            "解析成功",
            "找到 1 个播放链接\n类型: HLS"
        )
        
        Thread.sleep(100)
        
        // 模拟网络请求
        VideoFlowLogger.debug(
            VideoFlowLogger.FlowStage.NETWORK,
            "发起网络请求",
            "URL: https://example.com/video1.m3u8\nMethod: GET"
        )
        
        Thread.sleep(300)
        
        VideoFlowLogger.success(
            VideoFlowLogger.FlowStage.NETWORK,
            "网络请求成功",
            "Status: 200\nSize: 1024 bytes"
        )
        
        Thread.sleep(100)
        
        // 模拟播放器初始化
        VideoFlowLogger.info(
            VideoFlowLogger.FlowStage.PLAYER_INIT,
            "准备播放器",
            "URL: https://example.com/video1.m3u8"
        )
        
        Thread.sleep(200)
        
        VideoFlowLogger.success(
            VideoFlowLogger.FlowStage.PLAYER_INIT,
            "播放器准备完成"
        )
        
        Thread.sleep(100)
        
        // 模拟加载
        VideoFlowLogger.debug(
            VideoFlowLogger.FlowStage.PLAYER_LOAD,
            "正在缓冲",
            "Position: 0ms"
        )
        
        Thread.sleep(500)
        
        VideoFlowLogger.success(
            VideoFlowLogger.FlowStage.PLAYER_LOAD,
            "缓冲完成，准备播放",
            "Duration: 120000ms\nPosition: 0ms"
        )
        
        Thread.sleep(100)
        
        // 模拟播放
        VideoFlowLogger.success(
            VideoFlowLogger.FlowStage.PLAYER_PLAY,
            "开始播放",
            "Position: 0ms\nSpeed: 1.0x"
        )
        
        Thread.sleep(100)
        
        VideoFlowLogger.info(
            VideoFlowLogger.FlowStage.OTHER,
            "测试会话结束"
        )
        
        VideoFlowLogger.endSession()
    }
    
    /**
     * 创建一个包含错误的测试会话
     */
    fun createErrorTestSession() {
        VideoFlowLogger.startSession(
            videoTitle = "错误测试 - 播放失败示例",
            videoUrl = "https://example.com/error/video.m3u8"
        )
        
        VideoFlowLogger.info(
            VideoFlowLogger.FlowStage.INIT,
            "初始化播放器"
        )
        
        Thread.sleep(100)
        
        VideoFlowLogger.warning(
            VideoFlowLogger.FlowStage.SNIFF,
            "嗅探超时",
            "等待时间: 5000ms"
        )
        
        Thread.sleep(100)
        
        VideoFlowLogger.error(
            VideoFlowLogger.FlowStage.NETWORK,
            "网络请求失败",
            "URL: https://example.com/error/video.m3u8",
            Exception("Connection timeout")
        )
        
        Thread.sleep(100)
        
        VideoFlowLogger.error(
            VideoFlowLogger.FlowStage.PLAYER_ERROR,
            "播放失败",
            "Error code: 404",
            Exception("Video not found")
        )
        
        VideoFlowLogger.endSession()
    }
    
    /**
     * 创建多个测试会话
     */
    fun createMultipleTestSessions(count: Int = 3) {
        repeat(count) { index ->
            VideoFlowLogger.startSession(
                videoTitle = "测试视频 #${index + 1}",
                videoUrl = "https://example.com/test/video${index + 1}.m3u8"
            )
            
            VideoFlowLogger.info(
                VideoFlowLogger.FlowStage.INIT,
                "初始化播放器 #${index + 1}"
            )
            
            Thread.sleep(50)
            
            VideoFlowLogger.success(
                VideoFlowLogger.FlowStage.PLAYER_PLAY,
                "开始播放 #${index + 1}"
            )
            
            VideoFlowLogger.endSession()
            
            Thread.sleep(100)
        }
    }
}
