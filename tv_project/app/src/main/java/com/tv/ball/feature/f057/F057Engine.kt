package com.tv.ball.feature.f057

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * 缓冲档位。
 *
 * [label] 只用于引擎生成的参数说明文本（代码注释里标出当前档位），
 * 界面卡片标题仍走 `strings_f057.xml` 的 `f057_preset_*`，两处文案保持一致。
 */
enum class Preset {
    /** 省流：攒得少、只开一条连接，弱网下先别把带宽抢满。 */
    SAVER,

    /** 均衡：接近播放器现有默认，日常够用。 */
    BALANCED,

    /** 激进：攒得多、并发高，宽带给力时把卡顿彻底压掉。 */
    AGGRESSIVE;

    val label: String
        get() = when (this) {
            SAVER -> "省流"
            BALANCED -> "均衡"
            AGGRESSIVE -> "激进"
        }
}

/**
 * 一组缓冲参数。
 *
 * @param bufferMs            目标缓冲时长：起播之后想攒到的量，用来折算起播与断流恢复阈值
 * @param minBufferMs         DefaultLoadControl 的 minBufferMs：水位低于它立刻继续加载
 * @param maxBufferMs         DefaultLoadControl 的 maxBufferMs：攒到这里停止加载（内存上限的实质）
 * @param parallelConnections 并发连接数：音轨 / 视频轨 / 合并源合计最多同时跑几条
 */
data class BufferConfig(
    val bufferMs: Int,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val parallelConnections: Int
) {

    /** 起播阈值：目标缓冲的十分之一，夹在 0.5 ~ 6 秒（ExoPlayer 的 bufferForPlaybackMs）。 */
    val bufferForPlaybackMs: Int
        get() = (bufferMs / 10).coerceIn(500, 6_000)

    /** 断流恢复阈值：目标缓冲的五分之一，夹在 1 ~ 10 秒（bufferForPlaybackAfterRebufferMs）。 */
    val bufferForPlaybackAfterRebufferMs: Int
        get() = (bufferMs / 5).coerceIn(1_000, 10_000)

    /** 一行摘要，卡片与状态行都用它，避免各处自己拼数字。 */
    val summary: String
        get() = "min ${seconds(minBufferMs)}s · max ${seconds(maxBufferMs)}s · 起播 " +
            "${seconds(bufferForPlaybackMs)}s · 并发 $parallelConnections 条"
}

/** 毫秒转「12.5s」这种人话，固定用 Locale.US 免得跟随系统语言变成逗号小数点。 */
private fun seconds(ms: Int): String = String.format(Locale.US, "%.1f", ms / 1000.0)

/**
 * 缓冲与并发策略引擎。
 *
 * 解决的是「弱网下默认缓冲容易卡」：ExoPlayer 的 LoadControl 参数写死在播放器里，
 * 换网络环境只能重装。这里把四档参数抽出来做成可配 + 可持久化，并负责三件事：
 *
 * 1. 预设：省流 / 均衡 / 激进各一组整数，[of] 直接给出；
 * 2. 校验与纠偏：[validate] 只报问题不改值，[sanitize] 把越界与交叉冲突压回合法区间，
 *    调用方可以「先报再存」，也可以「存纠正后的值并说明改了什么」；
 * 3. 落地文本：[applyTo] 把一组参数拼成能直接粘进播放器的 `DefaultLoadControl.Builder` 片段，
 *    并发数单独说明 —— ExoPlayer 的 LoadControl 没有并发参数，它落在数据源与源编排上。
 *
 * 引擎本身无状态、纯函数，持久化走 [load] / [save] / [reset]，键名带 f057 前缀，不与其他功能抢。
 */
object F057Engine {

    /** 目标缓冲的上界下界（毫秒）。 */
    val BUFFER_RANGE: IntRange = 1_000..120_000

    /** minBufferMs 的上下界。 */
    val MIN_RANGE: IntRange = 1_000..60_000

    /** maxBufferMs 的上下界。 */
    val MAX_RANGE: IntRange = 2_000..180_000

    /** 并发连接数上下界：1 条是底线，8 条以上电视盒子先热起来。 */
    val PARALLEL_RANGE: IntRange = 1..8

    /** 三档预设，界面按这个顺序排卡片。 */
    val presets: List<Preset> = listOf(Preset.SAVER, Preset.BALANCED, Preset.AGGRESSIVE)

    // ===== 预设 =====

    /** 取预设对应的一组参数。 */
    fun of(preset: Preset): BufferConfig = when (preset) {
        // 省流：低水位、单连接，弱网先把首帧放出来
        Preset.SAVER -> BufferConfig(
            bufferMs = 8_000,
            minBufferMs = 5_000,
            maxBufferMs = 15_000,
            parallelConnections = 1
        )
        // 均衡：与播放器现状接近，起播与流畅折中
        Preset.BALANCED -> BufferConfig(
            bufferMs = 18_000,
            minBufferMs = 15_000,
            maxBufferMs = 50_000,
            parallelConnections = 2
        )
        // 激进：高水位 + 4 条并发，宽带够时几乎看不到转圈
        Preset.AGGRESSIVE -> BufferConfig(
            bufferMs = 45_000,
            minBufferMs = 30_000,
            maxBufferMs = 120_000,
            parallelConnections = 4
        )
    }

    /** 这组参数正好等于哪一档；都不等就是自定义。 */
    fun activePreset(config: BufferConfig): Preset? =
        presets.firstOrNull { of(it) == config }

    // ===== 校验 =====

    /**
     * 越界与交叉冲突的具体说明；空列表表示这组参数可以直接存。
     *
     * 只报不改：界面要把「原本填了什么」和「最终存了什么」都讲清楚，
     * 所以纠偏交给 [sanitize]，两者分开。
     */
    fun validate(config: BufferConfig): List<String> {
        val issues = ArrayList<String>(6)

        if (config.bufferMs !in BUFFER_RANGE) {
            issues.add(
                "缓冲时长要在 ${BUFFER_RANGE.first}~${BUFFER_RANGE.last}ms，现在是 ${config.bufferMs}ms"
            )
        }
        if (config.minBufferMs !in MIN_RANGE) {
            issues.add(
                "最小缓冲要在 ${MIN_RANGE.first}~${MIN_RANGE.last}ms，现在是 ${config.minBufferMs}ms"
            )
        }
        if (config.maxBufferMs !in MAX_RANGE) {
            issues.add(
                "最大缓冲要在 ${MAX_RANGE.first}~${MAX_RANGE.last}ms，现在是 ${config.maxBufferMs}ms"
            )
        }
        if (config.parallelConnections !in PARALLEL_RANGE) {
            issues.add(
                "并发连接数要在 ${PARALLEL_RANGE.first}~${PARALLEL_RANGE.last}，现在是 ${config.parallelConnections}"
            )
        }

        // 交叉约束：ExoPlayer 要求 max > min，且目标缓冲得落在两者之间
        if (config.maxBufferMs <= config.minBufferMs) {
            issues.add(
                "最大缓冲必须大于最小缓冲（现在 ${config.maxBufferMs}ms ≤ ${config.minBufferMs}ms）"
            )
        } else if (config.bufferMs < config.minBufferMs || config.bufferMs > config.maxBufferMs) {
            issues.add(
                "缓冲时长要落在最小~最大缓冲之间（" +
                    "${config.minBufferMs}~${config.maxBufferMs}ms，现在 ${config.bufferMs}ms）"
            )
        }
        return issues
    }

    /**
     * 把一组参数压回合法区间。
     *
     * 顺序有讲究：先夹 min / max 并保证 max > min，再把目标缓冲夹到 [min, max] 里，
     * 这样四档数字永远不会互相打架，存进 SharedPreferences 的也永远是能直接用的值。
     */
    fun sanitize(config: BufferConfig): BufferConfig {
        val min = config.minBufferMs.coerceIn(MIN_RANGE)
        val max = config.maxBufferMs.coerceIn(MAX_RANGE).let { if (it <= min) min + 5_000 else it }
        val buffer = config.bufferMs.coerceIn(BUFFER_RANGE).coerceIn(min, max)
        val parallel = config.parallelConnections.coerceIn(PARALLEL_RANGE)
        return BufferConfig(buffer, min, max, parallel)
    }

    // ===== 落地文本 =====

    /**
     * 把一组参数拼成等效参数说明。
     *
     * 前半段是能直接粘进 `ExoPlayerManager.initializePlayer()` 的 builder 链（和现有代码同一套方法名），
     * 后半段解释并发数落在哪：`DefaultLoadControl` 本身没有并发参数，
     * 真正限制同时下载条数的是数据源连接池与音视频轨 / 合并源的编排。
     */
    fun applyTo(config: BufferConfig): String {
        val safe = sanitize(config)
        val tag = activePreset(safe)?.let { "预设 ${it.label}" } ?: "自定义"
        val ratio = safe.bufferMs * 100 / safe.maxBufferMs
        val backBufferMs = (safe.bufferMs / 6).coerceIn(0, 10_000)

        return buildString {
            appendLine("// F057 · $tag")
            appendLine("// 目标缓冲 ${seconds(safe.bufferMs)}s，占最大缓冲 $ratio%")
            appendLine("val loadControl = DefaultLoadControl.Builder()")
            appendLine("    .setBufferDurationsMs(")
            appendLine("        ${safe.minBufferMs},      // minBufferMs：低于这个水位立刻继续加载")
            appendLine("        ${safe.maxBufferMs},     // maxBufferMs：攒到这个水位停止加载")
            appendLine("        ${safe.bufferForPlaybackMs},       // bufferForPlaybackMs：起播前至少要攒这么多")
            appendLine("        ${safe.bufferForPlaybackAfterRebufferMs}      // bufferForPlaybackAfterRebufferMs：断流后重新攒")
            appendLine("    )")
            appendLine("    .setTargetBufferBytes(C.LENGTH_UNSET)")
            appendLine("    .setPrioritizeTimeOverSizeThresholds(true)")
            appendLine("    .setBackBuffer($backBufferMs, true)")
            appendLine("    .build()")
            appendLine("// ExoPlayer.Builder(context).setLoadControl(loadControl).build()")
            appendLine()
            appendLine("// 并发连接数 ${safe.parallelConnections} 条（LoadControl 没有这个参数，下面三处才是它的实际落点）")
            appendLine("//  · OkHttpDataSourceFactory：ConnectionPool(maxIdleConnections = ${safe.parallelConnections})")
            appendLine("//  · 音轨 + 视频轨默认各占 1 条，MergingMediaSource 再合并的源数合计不超过 ${safe.parallelConnections}")
            appendLine("//  · HTTP/2 下同域请求复用同一条 TCP，${safe.parallelConnections} 是保底并发，不是带宽上限")
            if (safe.parallelConnections >= 6) {
                appendLine("//  · 并发 6 条以上对电视盒子收益递减，先撞到内存和发热")
            }
            append(
                "// 播放器现状（ExoPlayerManager 内置）对照：10000 / 60000 / 2500 / 5000，并发由 OkHttp 连接池 16 条兜底"
            )
        }
    }

    // ===== 持久化 =====

    /** 读已保存的配置；没存过或存进过越界值时，返回纠偏后的可用值。 */
    fun load(context: Context): BufferConfig {
        val prefs = prefs(context)
        val fallback = of(Preset.BALANCED)
        return sanitize(
            BufferConfig(
                bufferMs = prefs.getInt(KEY_BUFFER_MS, fallback.bufferMs),
                minBufferMs = prefs.getInt(KEY_MIN_MS, fallback.minBufferMs),
                maxBufferMs = prefs.getInt(KEY_MAX_MS, fallback.maxBufferMs),
                parallelConnections = prefs.getInt(KEY_PARALLEL, fallback.parallelConnections)
            )
        )
    }

    /** 存一组参数：先纠偏再落盘，返回真正写进去的那组值。 */
    fun save(context: Context, config: BufferConfig): BufferConfig {
        val safe = sanitize(config)
        prefs(context).edit()
            .putInt(KEY_BUFFER_MS, safe.bufferMs)
            .putInt(KEY_MIN_MS, safe.minBufferMs)
            .putInt(KEY_MAX_MS, safe.maxBufferMs)
            .putInt(KEY_PARALLEL, safe.parallelConnections)
            .apply()
        return safe
    }

    /** 恢复默认（均衡档）并立即落盘。 */
    fun reset(context: Context): BufferConfig = save(context, of(Preset.BALANCED))

    /**
     * 播放侧读配置的唯一入口：播放器启动时拿这一份去建 LoadControl，不需要依赖界面存在。
     * 名字刻意与 [load] 区分，调用点一眼能看出「这是给播放器用的当前生效参数」。
     */
    fun current(context: Context): BufferConfig = load(context)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "f057_buffer_prefs"
    private const val KEY_BUFFER_MS = "f057_buffer_ms"
    private const val KEY_MIN_MS = "f057_min_buffer_ms"
    private const val KEY_MAX_MS = "f057_max_buffer_ms"
    private const val KEY_PARALLEL = "f057_parallel_connections"
}
