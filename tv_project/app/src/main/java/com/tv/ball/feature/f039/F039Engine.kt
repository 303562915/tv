package com.tv.ball.feature.f039

/**
 * F039 播放列表回收站：纯 Kotlin 领域模型，不依赖 Android。
 *
 * 语义：
 * - softDelete：把列表移入回收站，在 nowMs 基础上保留 [RETENTION_MS]（7 天）。
 * - restore：从回收站恢复成正常列表。
 * - purge：彻底清除指定条目。
 * - purgeExpired：清理所有 expiresAtMs <= nowMs 的条目，返回被清除的条目。
 */
data class TrashEntry(
    val playlistId: String,
    val name: String,
    val itemCount: Int,
    val deletedAtMs: Long,
    val expiresAtMs: Long
)

class F039Engine(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        /** 7 天保留期。 */
        const val RETENTION_MS: Long = 7L * 24L * 60L * 60L * 1000L
        const val DAY_MS: Long = 24L * 60L * 60L * 1000L
    }

    val active: MutableList<TrashEntry> = mutableListOf()
    val trash: MutableList<TrashEntry> = mutableListOf()

    private val deletedIndex = mutableSetOf<String>()

    fun seedActive(entries: List<TrashEntry>) {
        active.clear()
        entries.forEach { addActive(it) }
    }

    private fun addActive(entry: TrashEntry) {
        active.add(entry)
    }

    fun addPlaylist(
        playlistId: String,
        name: String,
        itemCount: Int
    ): Boolean {
        if (playlistId.isBlank()) return false
        if (deletedIndex.contains(playlistId)) return false
        if (active.any { it.playlistId == playlistId }) return false
        active.add(
            TrashEntry(
                playlistId = playlistId,
                name = name,
                itemCount = itemCount,
                deletedAtMs = 0L,
                expiresAtMs = 0L
            )
        )
        return true
    }

    /** 软删除：移出正常列表，进入回收站并计算过期时间。 */
    fun softDelete(playlistId: String, nowMs: Long = clock()): TrashEntry? {
        val index = active.indexOfFirst { it.playlistId == playlistId }
        if (index < 0) return null
        val source = active.removeAt(index)
        val entry = TrashEntry(
            playlistId = source.playlistId,
            name = source.name,
            itemCount = source.itemCount,
            deletedAtMs = nowMs,
            expiresAtMs = nowMs + RETENTION_MS
        )
        trash.add(entry)
        deletedIndex.add(playlistId)
        return entry
    }

    /** 恢复：从回收站回到正常列表，顺带清理已过期条目。 */
    fun restore(playlistId: String, nowMs: Long = clock()): Boolean {
        purgeExpired(nowMs)
        val index = trash.indexOfFirst { it.playlistId == playlistId }
        if (index < 0) return false
        val entry = trash.removeAt(index)
        deletedIndex.remove(playlistId)
        active.add(
            TrashEntry(
                playlistId = entry.playlistId,
                name = entry.name,
                itemCount = entry.itemCount,
                deletedAtMs = 0L,
                expiresAtMs = 0L
            )
        )
        return true
    }

    /** 彻底清除单条。 */
    fun purge(playlistId: String): Boolean {
        val removed = trash.removeAll { it.playlistId == playlistId }
        return removed
    }

    /** 清理过期条目，返回被清除的条目列表。 */
    fun purgeExpired(nowMs: Long = clock()): List<TrashEntry> {
        val expired = trash.filter { it.expiresAtMs <= nowMs }
        if (expired.isNotEmpty()) {
            trash.removeAll(expired)
        }
        return expired
    }

    /** 剩余保留毫秒数，已过期返回 0。 */
    fun remainingMs(entry: TrashEntry, nowMs: Long = clock()): Long {
        val left = entry.expiresAtMs - nowMs
        return if (left > 0L) left else 0L
    }

    /** 剩余展示天数（向上取整），已过期返回 0。 */
    fun remainingDays(entry: TrashEntry, nowMs: Long = clock()): Int {
        val left = remainingMs(entry, nowMs)
        if (left <= 0L) return 0
        return ((left + DAY_MS - 1L) / DAY_MS).toInt()
    }

    fun isExpired(entry: TrashEntry, nowMs: Long = clock()): Boolean =
        remainingMs(entry, nowMs) <= 0L

    fun findTrash(playlistId: String): TrashEntry? =
        trash.firstOrNull { it.playlistId == playlistId }

    fun snapshotActive(): List<TrashEntry> = active.toList()

    fun snapshotTrash(): List<TrashEntry> = trash.sortedByDescending { it.deletedAtMs }
}
