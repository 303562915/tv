package com.tv.ball.feature.f004

import android.content.Context
import kotlin.math.abs
import kotlin.math.round
import java.util.Locale

/**
 * 「倍速曲线与长按倍速」的唯一入口。
 *
 * 播放页接线方式（主智能体负责接线，这里把契约写清楚）：
 * 1. 长按开始：`applyLongPress(engine.longPressPreset)`，长按结束：`releaseLongPress()`；
 * 2. 每次生效倍速变化都读 `engine.effectiveSpeed`，直接丢给播放器的 setPlaybackSpeed；
 * 3. 常驻倍速（`currentSpeed`）变更时同步 `ConfigRepository.setPlaybackSpeed`，
 *    播放页自己的倍速入口也写同一份，两条路径看到的值一致；
 * 4. 档位集合与常驻倍速都落 SharedPreferences，切集、旋转、进程重建后自动读回。
 *
 * 判定与档位曲线的逻辑全在 [F004SpeedCurve] 里，纯 Kotlin、不碰 Android 类型，可脱离运行时单测。
 */
class F004Engine private constructor(private val curve: F004SpeedCurve) {

    /** 当前档位集合，已去重、按升序排好，且一定包含 1.0x。 */
    val speeds: List<Float>
        get() = curve.speeds

    /** 常驻倍速：长按松手后回到的那个值。 */
    val currentSpeed: Float
        get() = curve.currentSpeed

    /** 真正生效的倍速：长按期间是临时倍速，否则等于常驻倍速。 */
    val effectiveSpeed: Float
        get() = curve.effectiveSpeed

    /** 是否正处在长按态。 */
    val isLongPressing: Boolean
        get() = curve.isLongPressing

    /** 长按倍速预设档（1.5x / 2.0x / 3.0x 之一，用户改过就按改过的算）。 */
    val longPressPreset: Float
        get() = curve.longPressPreset

    /** 这个倍速是否已经在档位集合里。 */
    fun hasSpeed(value: Float): Boolean = curve.hasSpeed(value)

    /** 新增档位：越界、重复、超出数量上限都返回带原因的失败。 */
    fun addSpeed(value: Float): F004Result = curve.addSpeed(value)

    /** 删除档位：1.0x 基准档与「最后一个档位」不允许删。 */
    fun removeSpeed(value: Float): F004Result = curve.removeSpeed(value)

    /** 直接指定常驻倍速（范围内即可，不要求落在档位上）。 */
    fun selectSpeed(value: Float): F004Result = curve.selectSpeed(value)

    /** 与播放页/配置里的倍速对齐：值相同就不重复写盘。 */
    fun syncCurrent(value: Float): F004Result = curve.syncCurrent(value)

    /** 在档位曲线上前进 / 后退一档，到头回卷。 */
    fun cycleNext(forward: Boolean = true): F004Result = curve.cycleNext(forward)

    /** 按下长按：把临时倍速压栈，返回压栈后的生效倍速。 */
    fun applyLongPress(multiplier: Float): F004Result = curve.applyLongPress(multiplier)

    /** 松开长按：出栈，返回恢复后的生效倍速；没按过就返回失败原因。 */
    fun releaseLongPress(): F004Result = curve.releaseLongPress()

    /** 改长按倍速预设，持久化，下次长按直接用。 */
    fun setLongPressPreset(value: Float): F004Result = curve.setLongPressPreset(value)

    /** 恢复出厂档位：默认曲线 + 1.0x 常驻 + 2.0x 长按。 */
    fun resetDefault(): F004Result = curve.resetDefault()

    companion object {
        /** 进程级单例：播放页与设置页拿到的是同一份档位曲线与长按状态。 */
        @Volatile
        private var instance: F004Engine? = null

        fun get(context: Context): F004Engine =
            instance ?: synchronized(this) {
                instance ?: F004Engine(
                    F004SpeedCurve(F004PrefsStore(context.applicationContext))
                ).also { instance = it }
            }

        /** 单测入口：自己塞 store，不依赖 Android 运行时。 */
        fun create(store: F004SpeedStore): F004Engine = F004Engine(F004SpeedCurve(store))
    }
}

/**
 * 倍速相关的取值域与格式化。
 *
 * 单独抽一个 object：Activity 只是展示与调参，真正的上下限、步进、默认曲线只有这一处定义，
 * 免得界面与引擎各写一套数字后对不上。
 */
object F004Speeds {

    /** 可调下限。 */
    const val MIN_SPEED = 0.25f

    /** 可调上限。 */
    const val MAX_SPEED = 4.0f

    /** 候选倍速的步进。 */
    const val STEP = 0.05f

    /** 基准档：任何曲线都必须有它，且不能删。 */
    const val BASE_SPEED = 1.0f

    /** 档位数量上限，多了列表会顶穿一屏，也没有实际意义。 */
    const val MAX_TIERS = 12

    /** 浮点比较容差：倍速都是两位小数，0.001 足够区分档位又不会因精度抖动误判重复。 */
    const val EPS = 0.001f

    /** 默认曲线。 */
    val DEFAULT_SPEEDS: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

    /** 长按可选档：倍速面板里常用的三档。 */
    val PRESETS: List<Float> = listOf(1.5f, 2.0f, 3.0f)

    /** 默认长按倍速。 */
    const val DEFAULT_PRESET = 2.0f

    fun isInRange(value: Float): Boolean =
        value.isFinite() && value >= MIN_SPEED - EPS && value <= MAX_SPEED + EPS

    /** 统一收口到两位小数：避免 0.05 步进累加出 1.7500001 这种脏值。 */
    fun roundTo2(value: Float): Float =
        if (value.isFinite()) round(value * 100f) / 100f else value

    fun clamp(value: Float): Float = when {
        !value.isFinite() -> BASE_SPEED
        value < MIN_SPEED -> MIN_SPEED
        value > MAX_SPEED -> MAX_SPEED
        else -> roundTo2(value)
    }

    /** 在合法区间内加减一个步长。 */
    fun step(value: Float, delta: Float): Float = clamp(roundTo2(value + delta))

    /** 界面文案：0.75 → "0.75x"，1.0 → "1.0x"。 */
    fun label(value: Float): String {
        val hundredths = round(value * 100f).toLong()
        return if (hundredths % 10L == 0L) "%.1fx".format(Locale.US, value)
        else "%.2fx".format(Locale.US, value)
    }
}

/** 一次倍速操作的结果；失败时 [reject] 带可读原因。 */
data class F004Result(
    val success: Boolean,
    val speed: Float,
    val reject: F004Reject? = null
) {
    /** 失败原因；成功时为空串，界面可直接拼进提示。 */
    val reason: String
        get() = reject?.reason.orEmpty()

    companion object {
        fun ok(speed: Float): F004Result = F004Result(true, speed, null)

        fun fail(reject: F004Reject): F004Result = F004Result(false, 0f, reject)
    }
}

/** 非法值 / 非法操作的原因。中文文案放这里，界面直接取，不用再维护第二份。 */
enum class F004Reject(val reason: String) {
    OUT_OF_RANGE("倍速要落在 0.25x ~ 4.0x 之间"),
    DUPLICATE("这个倍速已经在档位集合里"),
    TOO_MANY("档位数量已达上限，先删掉一个再加"),
    NOT_FOUND("档位集合里没有这个倍速"),
    KEEP_BASE("1.0x 是基准档，不能删除"),
    TOO_FEW("至少要保留一个档位"),
    NOT_PRESSING("当前没有按住长按键")
}

/**
 * 档位曲线的存储抽象。
 *
 * [F004SpeedCurve] 只依赖这层，不碰 Android 类型；线上实现见 [F004PrefsStore]。
 * 读方法返回 null 表示「从未写过」，引擎据此区分「没配过」与「配成了默认值」。
 */
interface F004SpeedStore {

    fun readSpeeds(): List<Float>?
    fun writeSpeeds(speeds: List<Float>)

    fun readCurrent(): Float?
    fun writeCurrent(speed: Float)

    fun readLongPressPreset(): Float?
    fun writeLongPressPreset(speed: Float)
}

/**
 * SharedPreferences 实现。
 *
 * 用 applicationContext 取实例，避免持有 Activity；写用 apply() 异步落盘，不阻塞主线程。
 * 倍速都存字符串：Float.toString 与 toFloatOrNull 能无损往返，也不会像 putFloat 那样混进 0.30000001。
 */
class F004PrefsStore(context: Context) : F004SpeedStore {

    private val prefs: android.content.SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun readSpeeds(): List<Float>? {
        val raw = prefs.getString(KEY_TIERS, null) ?: return null
        if (raw.isBlank()) return null
        return raw.split(SEPARATOR).mapNotNull { it.trim().toFloatOrNull() }
    }

    override fun writeSpeeds(speeds: List<Float>) {
        prefs.edit().putString(KEY_TIERS, speeds.joinToString(SEPARATOR)).apply()
    }

    override fun readCurrent(): Float? = prefs.getString(KEY_CURRENT, null)?.toFloatOrNull()

    override fun writeCurrent(speed: Float) {
        prefs.edit().putString(KEY_CURRENT, speed.toString()).apply()
    }

    override fun readLongPressPreset(): Float? = prefs.getString(KEY_PRESET, null)?.toFloatOrNull()

    override fun writeLongPressPreset(speed: Float) {
        prefs.edit().putString(KEY_PRESET, speed.toString()).apply()
    }

    private companion object {
        const val PREFS_NAME = "f004_speed_curve"
        const val KEY_TIERS = "speed_tiers"
        const val KEY_CURRENT = "current_speed"
        const val KEY_PRESET = "long_press_preset"
        const val SEPARATOR = ","
    }
}

/**
 * 档位曲线 + 长按倍速状态机：纯 Kotlin，无 Android 依赖。
 *
 * 长按用栈而不是单个字段：电视遥控器上「按住加速」可能被多层手势同时触发（遥控器长按 + 手势层长按），
 * 栈能保证任何一层松手都只恢复到上一层，不会把别的层设的临时倍速一起清掉。
 */
class F004SpeedCurve(private val store: F004SpeedStore) {

    /** 长按临时倍速栈，栈顶就是当前生效的临时倍速。 */
    private val pressed = ArrayDeque<Float>()

    private var tierList: List<Float> = F004Speeds.DEFAULT_SPEEDS
    private var currentValue: Float = F004Speeds.BASE_SPEED
    private var presetValue: Float = F004Speeds.DEFAULT_PRESET

    init {
        val storedTiers = store.readSpeeds()
        val storedCurrent = store.readCurrent()
        val storedPreset = store.readLongPressPreset()

        // 旧版本的脏数据（重复档、越界档、缺 1.0）在读入时清洗一次，之后就不用每次再算
        tierList = normalize(storedTiers ?: F004Speeds.DEFAULT_SPEEDS)
        if (storedTiers != tierList) store.writeSpeeds(tierList)

        currentValue = F004Speeds.clamp(storedCurrent ?: F004Speeds.BASE_SPEED)
        if (storedCurrent != currentValue) store.writeCurrent(currentValue)

        presetValue = F004Speeds.clamp(storedPreset ?: F004Speeds.DEFAULT_PRESET)
        if (storedPreset != presetValue) store.writeLongPressPreset(presetValue)
    }

    val speeds: List<Float>
        get() = tierList

    val currentSpeed: Float
        get() = currentValue

    val effectiveSpeed: Float
        get() = pressed.lastOrNull() ?: currentValue

    val isLongPressing: Boolean
        get() = pressed.isNotEmpty()

    val longPressPreset: Float
        get() = presetValue

    fun hasSpeed(value: Float): Boolean {
        val v = F004Speeds.roundTo2(value)
        return tierList.any { abs(it - v) < F004Speeds.EPS }
    }

    fun addSpeed(value: Float): F004Result {
        val v = F004Speeds.roundTo2(value)
        if (!F004Speeds.isInRange(v)) return F004Result.fail(F004Reject.OUT_OF_RANGE)
        if (hasSpeed(v)) return F004Result.fail(F004Reject.DUPLICATE)
        if (tierList.size >= F004Speeds.MAX_TIERS) return F004Result.fail(F004Reject.TOO_MANY)

        tierList = (tierList + v).sorted()
        store.writeSpeeds(tierList)
        return F004Result.ok(v)
    }

    fun removeSpeed(value: Float): F004Result {
        val v = F004Speeds.roundTo2(value)
        val target = tierList.firstOrNull { abs(it - v) < F004Speeds.EPS }
            ?: return F004Result.fail(F004Reject.NOT_FOUND)
        if (abs(target - F004Speeds.BASE_SPEED) < F004Speeds.EPS) {
            return F004Result.fail(F004Reject.KEEP_BASE)
        }
        if (tierList.size <= 1) return F004Result.fail(F004Reject.TOO_FEW)

        tierList = tierList.filterNot { abs(it - target) < F004Speeds.EPS }
        store.writeSpeeds(tierList)

        // 常驻倍速正好是被删掉的那一档时回落到基准档，否则播放页会拿到一个不在曲线上的值
        if (abs(currentValue - target) < F004Speeds.EPS) setCurrent(F004Speeds.BASE_SPEED)
        return F004Result.ok(target)
    }

    fun selectSpeed(value: Float): F004Result {
        val v = F004Speeds.roundTo2(value)
        if (!F004Speeds.isInRange(v)) return F004Result.fail(F004Reject.OUT_OF_RANGE)
        setCurrent(v)
        return F004Result.ok(v)
    }

    fun syncCurrent(value: Float): F004Result {
        val v = F004Speeds.roundTo2(value)
        if (!F004Speeds.isInRange(v)) return F004Result.fail(F004Reject.OUT_OF_RANGE)
        if (abs(v - currentValue) < F004Speeds.EPS) return F004Result.ok(currentValue)
        return selectSpeed(v)
    }

    fun cycleNext(forward: Boolean = true): F004Result {
        if (tierList.isEmpty()) return F004Result.fail(F004Reject.TOO_FEW)
        val size = tierList.size
        val offset = if (forward) 1 else -1
        // 常驻倍速可能不在档位上（例如播放页直接设了 1.1x），先吸附到最近一档再走一步
        val anchor = nearestIndex(currentValue)
        val next = tierList[((anchor + offset) % size + size) % size]
        setCurrent(next)
        return F004Result.ok(next)
    }

    fun applyLongPress(multiplier: Float): F004Result {
        val v = F004Speeds.roundTo2(multiplier)
        if (!F004Speeds.isInRange(v)) return F004Result.fail(F004Reject.OUT_OF_RANGE)
        pressed.addLast(v)
        return F004Result.ok(effectiveSpeed)
    }

    fun releaseLongPress(): F004Result {
        if (pressed.isEmpty()) return F004Result.fail(F004Reject.NOT_PRESSING)
        pressed.removeLast()
        return F004Result.ok(effectiveSpeed)
    }

    fun setLongPressPreset(value: Float): F004Result {
        val v = F004Speeds.roundTo2(value)
        if (!F004Speeds.isInRange(v)) return F004Result.fail(F004Reject.OUT_OF_RANGE)
        presetValue = v
        store.writeLongPressPreset(v)
        return F004Result.ok(v)
    }

    fun resetDefault(): F004Result {
        pressed.clear()
        tierList = F004Speeds.DEFAULT_SPEEDS
        store.writeSpeeds(tierList)
        setCurrent(F004Speeds.BASE_SPEED)
        presetValue = F004Speeds.DEFAULT_PRESET
        store.writeLongPressPreset(presetValue)
        return F004Result.ok(F004Speeds.BASE_SPEED)
    }

    private fun setCurrent(value: Float) {
        currentValue = value
        store.writeCurrent(value)
    }

    private fun nearestIndex(value: Float): Int {
        var best = 0
        var bestDelta = Float.MAX_VALUE
        tierList.forEachIndexed { index, speed ->
            val delta = abs(speed - value)
            if (delta < bestDelta) {
                bestDelta = delta
                best = index
            }
        }
        return best
    }

    private fun normalize(input: List<Float>): List<Float> {
        val cleaned = ArrayList<Float>(input.size)
        input.forEach { raw ->
            val v = F004Speeds.roundTo2(raw)
            if (!F004Speeds.isInRange(v)) return@forEach
            if (cleaned.any { abs(it - v) < F004Speeds.EPS }) return@forEach
            cleaned.add(v)
        }
        cleaned.sort()

        // 空表说明存档坏了，直接回默认曲线；缺 1.0 就补上，保证任何曲线都能回到原速
        if (cleaned.isEmpty()) cleaned.addAll(F004Speeds.DEFAULT_SPEEDS)
        if (cleaned.none { abs(it - F004Speeds.BASE_SPEED) < F004Speeds.EPS }) {
            cleaned.add(F004Speeds.BASE_SPEED)
            cleaned.sort()
        }

        // 超上限时从最高档开始砍：低速档更常用，先保它们
        while (cleaned.size > F004Speeds.MAX_TIERS) {
            cleaned.removeAt(cleaned.size - 1)
        }
        return cleaned
    }
}
