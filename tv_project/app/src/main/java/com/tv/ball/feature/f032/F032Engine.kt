package com.tv.ball.feature.f032

import com.google.gson.Gson

/**
 * F032「播放偏好学习」的能力层。
 *
 * 只干两件事：按 (类别, 取值) 累加选择次数；从这些次数里算出「最该默认选哪一项」。
 * 刻意写成纯 Kotlin：不引用任何 Android API，持久化只交出一段 JSON 字符串，
 * 真正落盘到 SharedPreferences 的活由 [F032Activity] 做，这样逻辑可以脱离页面单测。
 */
class F032Engine(private val clock: () -> Long = { System.currentTimeMillis() }) {

    /** 学习类别：线路与清晰度各自独立计数，互不影响。 */
    enum class Kind { LINE, QUALITY }

    /**
     * 一条学习记录。
     *
     * @param count 这个取值被选中的次数
     * @param lastUsedMs 最后一次选中的时刻；次数打平时靠它决定谁更新
     */
    data class Choice(
        val kind: Kind,
        val value: String,
        val count: Int,
        val lastUsedMs: Long
    )

    /**
     * 按类别分桶。
     *
     * 每个桶最多个位数条（线路 3 条、清晰度 4 条），线性查找比维护散列更快也更省内存，
     * 顺带保住插入顺序，让「次数与时间都打平」时取到稳定的那一条。
     */
    private val buckets = LinkedHashMap<Kind, MutableList<Choice>>()

    /** 某类别下的全部记录（未排序，保持插入顺序）。 */
    fun choicesOf(kind: Kind): List<Choice> = buckets[kind]?.toList().orEmpty()

    /** 某类别下某个取值的记录，没选过就是 null。 */
    fun find(kind: Kind, value: String): Choice? =
        buckets[kind]?.firstOrNull { it.value == value }

    /**
     * 记一次选择：同类别同取值的次数 +1，并刷新最近使用时间。
     * 空取值直接忽略，免得脏数据把推荐带偏。
     */
    fun record(kind: Kind, value: String) {
        val key = value.trim()
        if (key.isEmpty()) return
        val list = buckets.getOrPut(kind) { mutableListOf() }
        val now = clock()
        val index = list.indexOfFirst { it.value == key }
        if (index >= 0) {
            val old = list[index]
            list[index] = old.copy(count = old.count + 1, lastUsedMs = now)
        } else {
            list.add(Choice(kind, key, 1, now))
        }
    }

    /**
     * 该类别该默认选哪一项：次数最多；次数相同时取最近用过的那一项。
     *
     * 两项也都打平时按插入顺序取最早出现的那条，保证同一份数据每次算出来的结果一致，
     * 页面不会因为比较顺序抖动而自己改默认项。
     */
    fun preferred(kind: Kind): Choice? =
        buckets[kind]
            ?.filter { it.count > 0 }
            ?.maxWithOrNull(compareBy<Choice> { it.count }.thenBy { it.lastUsedMs })

    /** 清掉全部学习结果，两类一起清。 */
    fun clear() {
        buckets.clear()
    }

    /** 全部记录的只读快照，按类别、次数、最近使用倒序排好，界面直接贴。 */
    fun snapshot(): List<Choice> = buckets.values
        .flatten()
        .sortedWith(
            compareBy<Choice> { it.kind.ordinal }
                .thenByDescending { it.count }
                .thenByDescending { it.lastUsedMs }
        )

    /** 累计选择次数（线路 + 清晰度）。 */
    fun totalCount(): Int = buckets.values.sumOf { list -> list.sumOf { it.count } }

    // region JSON 持久化

    /** 序列化成一段 JSON，交给调用方写进 SharedPreferences。 */
    fun exportJson(): String = gson.toJson(snapshot().map { it.toDto() })

    /**
     * 从 JSON 恢复，先清空当前内存状态。
     *
     * 坏数据一律按「没有学习结果」处理：学习结果只是默认项的偏好提示，
     * 读失败时退回列表第一项，比让整个页面抛异常更合适。
     * 字段全可空的 DTO 保证旧版本或缺字段的 JSON 也不会崩。
     */
    fun importJson(json: String?) {
        buckets.clear()
        if (json.isNullOrBlank()) return
        val restored = runCatching { gson.fromJson(json, Array<F032ChoiceDto>::class.java) }
            .getOrNull()
            ?: return
        restored.forEach { dto ->
            if (dto == null) return@forEach
            val kind = Kind.values().firstOrNull { it.name == dto.kind } ?: return@forEach
            val value = dto.value?.trim().orEmpty()
            val count = dto.count ?: 0
            if (value.isEmpty() || count <= 0) return@forEach
            val lastUsedMs = dto.lastUsedMs ?: 0L
            val list = buckets.getOrPut(kind) { mutableListOf() }
            val index = list.indexOfFirst { it.value == value }
            if (index >= 0) {
                // 同一个取值重复出现（历史脏数据）时合并次数，时间取更近的一次。
                val old = list[index]
                list[index] = old.copy(
                    count = old.count + count,
                    lastUsedMs = maxOf(old.lastUsedMs, lastUsedMs)
                )
            } else {
                list.add(Choice(kind, value, count, lastUsedMs))
            }
        }
    }

    private fun Choice.toDto(): F032ChoiceDto =
        F032ChoiceDto(kind = kind.name, value = value, count = count, lastUsedMs = lastUsedMs)

    private companion object {
        /** Gson 无状态，整个进程共用一份。 */
        val gson = Gson()
    }

    // endregion
}

/**
 * 落盘用的扁平结构。
 *
 * 字段全部可空：Gson 反序列化不依赖构造器，缺字段时给 null，
 * 由 [F032Engine.importJson] 统一判脏，比在 DTO 上写一堆默认值更直接。
 */
internal data class F032ChoiceDto(
    val kind: String?,
    val value: String?,
    val count: Int?,
    val lastUsedMs: Long?
)