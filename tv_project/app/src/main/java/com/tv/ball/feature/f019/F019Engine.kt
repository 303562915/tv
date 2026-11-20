package com.tv.ball.feature.f019

/**
 * F019 媒体库增量索引 · 纯 Kotlin 引擎。
 *
 * 不依赖 Android / 协程 / IO：入参出参都是普通数据类，方便单测与复用。
 * 变更判定：同一 path 下 size 或 mtimeMs 任一不同即视为 updated。
 */
object F019Engine {

    /** 索引中的一条媒体条目。 */
    data class Entry(
        val path: String,
        val size: Long,
        val mtimeMs: Long
    )

    /** 一次增量扫描的结果，按 path 归入四类。 */
    data class Diff(
        val added: List<Entry>,
        val updated: List<Entry>,
        val removed: List<Entry>,
        val unchanged: List<Entry>
    ) {
        val addedCount: Int get() = added.size
        val updatedCount: Int get() = updated.size
        val removedCount: Int get() = removed.size
        val unchangedCount: Int get() = unchanged.size
        val totalCount: Int get() = addedCount + updatedCount + removedCount + unchangedCount
    }

    /**
     * 计算旧索引（path -> Entry）与新快照之间的差异。
     *
     * - added：新快照里出现、旧索引没有的 path
     * - updated：两侧都有，但 size 或 mtimeMs 不同
     * - removed：旧索引有、新快照里消失的 path
     * - unchanged：两侧都有且 size、mtimeMs 完全一致
     *
     * 输出顺序：added / updated / unchanged 按新快照顺序，removed 按旧索引顺序，
     * 保证同一输入多次调用结果稳定。新快照中重复 path 只取首次出现（按 path 去重）。
     */
    fun diff(old: Map<String, Entry>, new: List<Entry>): Diff {
        val added = ArrayList<Entry>()
        val updated = ArrayList<Entry>()
        val unchanged = ArrayList<Entry>()
        val seen = HashSet<String>(new.size * 2)

        for (entry in new) {
            if (!seen.add(entry.path)) continue
            val previous = old[entry.path]
            when {
                previous == null -> added.add(entry)
                isChanged(previous, entry) -> updated.add(entry)
                else -> unchanged.add(entry)
            }
        }

        val removed = ArrayList<Entry>()
        for ((path, previous) in old) {
            if (!seen.contains(path)) removed.add(previous)
        }

        return Diff(added, updated, removed, unchanged)
    }

    /** 变更判定：大小或修改时间任一不同即为变更。 */
    fun isChanged(previous: Entry, current: Entry): Boolean =
        previous.size != current.size || previous.mtimeMs != current.mtimeMs

    /** 把一份 Diff 应用到旧索引上，返回新的索引（旧对象不被修改）。 */
    fun applyTo(old: Map<String, Entry>, diff: Diff): Map<String, Entry> {
        val result = LinkedHashMap<String, Entry>(old.size + diff.added.size + diff.updated.size)
        result.putAll(old)
        for (entry in diff.added) result[entry.path] = entry
        for (entry in diff.updated) result[entry.path] = entry
        for (entry in diff.removed) result.remove(entry.path)
        return result
    }
}
