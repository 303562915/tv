package com.tv.ball.feature.f021

import java.io.File
import java.security.MessageDigest

/**
 * F021 缩略图磁盘缓存 · 真实落盘实现。
 *
 * 设计要点：
 * - 条目名 = 源标识（视频路径 / URL）的 SHA-1 十六进制，定长 40 字符且不暴露原始路径；
 * - LRU 的「最近使用」记在文件 mtime 上：命中 [get] 会 touch 一次 mtime，淘汰时按 mtime 升序删；
 * - 一个实例绑定一个目录，命中计数跟着实例走，Activity 里只 new 一次；
 * - 线程安全：公开方法都在 [lock] 内完成，可从任意线程调用（调用方负责放到 IO 线程）。
 */
class F021Engine private constructor(
    /** 该实例的缓存目录，构造时保证存在。 */
    val dir: File
) {

    /** 命中统计快照。 */
    data class Stats(
        /** 目录内当前条目数。 */
        val entryCount: Int,
        /** 目录内所有条目字节数之和。 */
        val totalBytes: Long,
        /** 命中次数。 */
        val hitCount: Long,
        /** 未命中次数。 */
        val missCount: Long
    ) {
        /** 命中率 0..1；一次都没查过时返回 0。 */
        val hitRate: Double
            get() {
                val total = hitCount + missCount
                return if (total == 0L) 0.0 else hitCount.toDouble() / total.toDouble()
            }
    }

    /** 一次淘汰 / 清空的结果。 */
    data class EvictResult(
        /** 被删掉的条目数。 */
        val removedCount: Int,
        /** 被删掉的字节数。 */
        val removedBytes: Long,
        /** 淘汰后剩余字节数。 */
        val remainingBytes: Long
    )

    private val lock = Any()
    private var hitCount: Long = 0L
    private var missCount: Long = 0L

    /**
     * 查缓存。
     *
     * 命中时把 mtime 刷成当前时间（LRU 的「用过了」）并累加命中计数；
     * 未命中累加未命中计数并返回 null —— 调用方据此才去解码缩略图。
     */
    fun get(source: String): File? = synchronized(lock) {
        val file = File(dir, key(source))
        if (file.isFile && file.length() > 0L) {
            file.setLastModified(System.currentTimeMillis())
            hitCount++
            file
        } else {
            missCount++
            null
        }
    }

    /**
     * 写缓存。先写临时文件再 rename，避免读侧读到写了一半的缩略图。
     *
     * 同一 source 重复 put 视为覆盖（键相同），不会产生重复条目。
     */
    fun put(source: String, bytes: ByteArray): File = synchronized(lock) {
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, key(source))
        val temp = File(dir, "${target.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            // 少数文件系统不支持覆盖式 rename，退回直写
            target.writeBytes(bytes)
            temp.delete()
        }
        target.setLastModified(System.currentTimeMillis())
        target
    }

    /**
     * 按 LRU 淘汰到上限。
     *
     * 排序依据 mtime 升序（最久没被 get / put 过的排最前），逐条删到总字节数 <= maxBytes 为止。
     * maxBytes <= 0 视为清空。
     */
    fun evictToLimit(maxBytes: Long): EvictResult = synchronized(lock) {
        val items = listEntries()
        var total = items.sumOf { it.length() }
        if (maxBytes > 0L && total <= maxBytes) return@synchronized EvictResult(0, 0L, total)

        var removed = 0
        var freed = 0L
        for (file in items) {
            if (maxBytes > 0L && total <= maxBytes) break
            val size = file.length()
            if (file.delete()) {
                total -= size
                removed++
                freed += size
            }
        }
        EvictResult(removed, freed, total)
    }

    /** 当前目录状态 + 本实例累计命中统计。 */
    fun stats(): Stats = synchronized(lock) {
        val items = listEntries()
        Stats(
            entryCount = items.size,
            totalBytes = items.sumOf { it.length() },
            hitCount = hitCount,
            missCount = missCount
        )
    }

    /** 目录内所有条目，按 mtime 升序 —— 即 LRU 淘汰顺序。 */
    fun entries(): List<File> = synchronized(lock) { listEntries() }

    /** 该 source 是否已有可用缓存（只探测，不改计数）。 */
    fun peek(source: String): File? = synchronized(lock) {
        val file = File(dir, key(source))
        if (file.isFile && file.length() > 0L) file else null
    }

    /** 清空目录内所有条目，并把命中 / 未命中计数归零。 */
    fun clear(): EvictResult = synchronized(lock) {
        val result = evictToLimit(0L)
        hitCount = 0L
        missCount = 0L
        result
    }

    /** 只列缓存条目（跳过写一半的 .tmp），按 mtime 升序。 */
    private fun listEntries(): List<File> {
        val files = dir.listFiles() ?: return emptyList()
        return files
            .asSequence()
            .filter { it.isFile && !it.name.endsWith(".tmp") }
            .sortedBy { it.lastModified() }
            .toList()
    }

    companion object {
        /** 缓存子目录名；换名字等于让所有老缓存失效。 */
        private const val DIR_NAME = "thumbnail_cache"

        /** 在 [baseDir]（通常是 `context.cacheDir`）下开一个缓存实例。 */
        fun open(baseDir: File): F021Engine {
            val dir = File(baseDir, DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            return F021Engine(dir)
        }

        /**
         * 源标识 → 缓存键：SHA-1 十六进制小写。
         *
         * 同一个 source 永远得到同一个键；换算法会让所有老缓存失效，所以固定 SHA-1 且不掺运行时状态。
         */
        fun key(source: String): String {
            val digest = MessageDigest.getInstance("SHA-1")
            val bytes = digest.digest(source.toByteArray(Charsets.UTF_8))
            val out = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                if (v < 0x10) out.append('0')
                out.append(Integer.toHexString(v))
            }
            return out.toString()
        }
    }
}