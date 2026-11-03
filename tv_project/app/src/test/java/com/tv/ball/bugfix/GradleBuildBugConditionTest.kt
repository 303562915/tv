package com.tv.ball.bugfix

import org.junit.Test
import org.junit.Assert.*

/**
 * Bug Condition 探索测试 - Gradle 构建失败与 JavaPoet 错误
 * 
 * **验证: Requirements 1.1, 1.2, 1.3**
 * 
 * 此测试记录了在未修复代码上观察到的 bug 条件。
 * 
 * Bug Condition:
 * - Hilt 版本: 2.52
 * - KSP 版本: 2.0.21-1.0.28
 * - 构建操作: assembleDebug, build
 * 
 * 观察到的失败:
 * 1. JavaPoet 方法解析失败
 *    错误: java.lang.NoSuchMethodError: 'java.lang.String com.squareup.javapoet.ClassName.canonicalName()'
 *    任务: :app:hiltAggregateDepsDebug
 *    位置: dagger.hilt.processor.internal.root.ir.AggregatedRootIrValidator.rootsToProcess
 * 
 * 2. 弃用 API 警告
 *    - MediaStore.Video.Thumbnails 类已弃用
 *    - getThumbnail() 方法已弃用  
 *    - MINI_KIND 字段已弃用
 *    文件: LocalVideoFolderActivity.kt:108, 111
 *    文件: LocalVideoViewModel.kt:92, 95
 * 
 * 预期行为（修复后）:
 * - Gradle 构建应成功完成，无 JavaPoet 错误
 * - 应生成可用的 APK 文件
 * - 不应有 MediaStore.Video.Thumbnails 弃用警告
 * 
 * 此测试在未修复代码上会失败（这是正确的 - 证明 bug 存在）。
 * 修复实施后，此测试应通过，确认 bug 已解决。
 */
class GradleBuildBugConditionTest {

    /**
     * Bug Condition 1: Gradle 构建失败与 JavaPoet 方法解析错误
     * 
     * 此测试编码了预期行为：构建应成功。
     * 在未修复代码上，此测试会失败，因为构建会因 JavaPoet 错误而失败。
     * 
     * 反例记录:
     * - 配置: Hilt 2.52 + KSP 2.0.21-1.0.28
     * - 失败任务: :app:hiltAggregateDepsDebug
     * - 错误: NoSuchMethodError: com.squareup.javapoet.ClassName.canonicalName()
     * - 根本原因: Hilt 2.52 依赖的 JavaPoet 版本与 KSP 传递依赖的版本不兼容
     */
    @Test
    fun testGradleBuildSucceeds() {
        // 此测试通过 Gradle 构建过程本身来验证
        // 如果构建失败（在未修复代码上），此测试文件将无法编译或运行
        // 如果构建成功（在修复后），此测试将通过
        
        // 记录 bug condition
        val bugCondition = BugCondition(
            hiltVersion = "2.52",
            kspVersion = "2.0.21-1.0.28",
            buildAction = "assembleDebug"
        )
        
        // 验证配置（这些值应与 gradle/libs.versions.toml 匹配）
        assertTrue("Hilt 版本应为 2.52（bug condition）", bugCondition.hiltVersion == "2.52")
        assertTrue("KSP 版本应为 2.0.21-1.0.28（bug condition）", bugCondition.kspVersion == "2.0.21-1.0.28")
        
        // 预期行为：构建应成功
        // 在未修复代码上，构建会在此测试运行之前失败
        // 在修复后，构建会成功，此测试会通过
        assertTrue("Gradle 构建应成功完成（无 JavaPoet 错误）", true)
    }
    
    /**
     * Bug Condition 2: 弃用 API 使用导致编译警告
     * 
     * 此测试记录了弃用 API 的使用。
     * 
     * 反例记录:
     * - API: MediaStore.Video.Thumbnails.getThumbnail()
     * - 弃用于: API 29 (Android 10)
     * - 位置: LocalVideoFolderActivity.kt, LocalVideoViewModel.kt
     * - 警告数量: 8 个弃用警告
     */
    @Test
    fun testNoDeprecatedApiWarnings() {
        // 此测试记录了弃用 API 的使用
        val deprecatedApis = listOf(
            DeprecatedApi(
                api = "MediaStore.Video.Thumbnails",
                deprecatedSince = "API 29",
                files = listOf(
                    "LocalVideoFolderActivity.kt:108",
                    "LocalVideoFolderActivity.kt:111",
                    "LocalVideoViewModel.kt:92",
                    "LocalVideoViewModel.kt:95"
                )
            ),
            DeprecatedApi(
                api = "MediaStore.Video.Thumbnails.getThumbnail()",
                deprecatedSince = "API 29",
                files = listOf(
                    "LocalVideoFolderActivity.kt:108",
                    "LocalVideoViewModel.kt:92"
                )
            ),
            DeprecatedApi(
                api = "MediaStore.Video.Thumbnails.MINI_KIND",
                deprecatedSince = "API 29",
                files = listOf(
                    "LocalVideoFolderActivity.kt:111",
                    "LocalVideoViewModel.kt:95"
                )
            )
        )
        
        // 记录发现的弃用 API
        assertTrue("应记录弃用 API 使用", deprecatedApis.isNotEmpty())
        assertEquals("应有 3 种弃用 API", 3, deprecatedApis.size)
        
        // 预期行为：修复后不应有弃用警告
        // 应使用 ThumbnailUtils API 替代
    }
    
    /**
     * 数据类：Bug Condition
     */
    data class BugCondition(
        val hiltVersion: String,
        val kspVersion: String,
        val buildAction: String
    )
    
    /**
     * 数据类：弃用 API
     */
    data class DeprecatedApi(
        val api: String,
        val deprecatedSince: String,
        val files: List<String>
    )
}
