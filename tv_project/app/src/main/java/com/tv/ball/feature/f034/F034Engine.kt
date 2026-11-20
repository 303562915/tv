package com.tv.ball.feature.f034

/**
 * F034「队列另存为播放列表」的能力层。
 *
 * 纯 Kotlin：不碰 Android API，也不读系统时钟——时间一律由调用方传 [nowMs]，
 * 所以「另存 / 重名」的判定完全可复现，方便单测。
 *
 * 分工是刻意的：[saveAs] 只给结论、不落库。重名时返回 [Result.NameConflict]，
 * 界面问完人再用 [Result.NameConflict.overwrite] 或
 * [Result.NameConflict.renameWith] 拿到要存的那条列表，最后由 [commit] 写进去。
 * 判定里直接写库的话，用户点了「取消」就没法回头了。
 */
object F034Engine {

    /** 重名时自动加的后缀；再撞名就依次 -3、-4…，见 [freeSuffix]。 */
    const val AUTO_SUFFIX: String = "-2"

    /** 一条播放列表。[items] 存队列里的标题，顺序就是播放顺序。 */
    data class Playlist(
        val name: String,
        val items: List<String>,
        val createdAtMs: Long
    ) {
        val size: Int get() = items.size
    }

    /** [saveAs] 的三种结论。 */
    sealed interface Result {

        /** 名字没被占用，[playlist] 可以直接交给 [commit]。 */
        data class Saved(val playlist: Playlist) : Result

        /** 名字已存在。两条出路各自给出一条待保存的列表。 */
        data class NameConflict(
            val requestedName: String,
            val existing: Playlist,
            val queuedItems: List<String>,
            val savedAtMs: Long
        ) : Result {

            /**
             * 覆盖：还是原来那条列表（名字、创建时间都不动），只把内容换成当前队列。
             * 创建时间留着，用户靠它认「这是我最早建的那条」。
             */
            fun overwrite(): Playlist = existing.copy(items = queuedItems)

            /**
             * 改名另存：加 [suffix] 当作一条全新的列表。
             *
             * @param suffix 默认加 [AUTO_SUFFIX]；如果 "-2" 也已经被占用，
             *               传 [freeSuffix] 算出来的结果，免得加完又撞名。
             */
            fun renameWith(suffix: String = AUTO_SUFFIX): Playlist = Playlist(
                name = requestedName + suffix,
                items = queuedItems,
                createdAtMs = savedAtMs
            )
        }

        /** 名称是空白，什么都没发生。 */
        object EmptyName : Result
    }

    /** 列表库，按名称去重；LinkedHashMap 保证「先存的排在前面」。 */
    private val store = LinkedHashMap<String, Playlist>()

    /** 已保存的列表名，插入顺序。 */
    val existingNames: List<String> get() = store.keys.toList()

    /** 全部列表（含内容），列表管理区按这个渲染。 */
    fun all(): List<Playlist> = store.values.toList()

    /** 按名字取一条，取不到返回 null。 */
    fun find(name: String): Playlist? = store[name.trim()]

    /**
     * 判定一次「另存」。
     *
     * 内容一模一样也算重名：用户点保存就是想再存一份，界面照样问一次，
     * 省得出现「点了按钮却没反应」。
     *
     * @param queue 当前队列的快照，这里会再复制一层，避免界面后续改动串进来
     * @param nowMs 保存时刻，由调用方给
     */
    fun saveAs(name: String, queue: List<String>, nowMs: Long): Result {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Result.EmptyName

        val snapshot = queue.toList()
        val existing = store[trimmed]
            ?: return Result.Saved(Playlist(name = trimmed, items = snapshot, createdAtMs = nowMs))
        return Result.NameConflict(
            requestedName = trimmed,
            existing = existing,
            queuedItems = snapshot,
            savedAtMs = nowMs
        )
    }

    /** 真正落库：同名的旧列表整条替换，新名字追加到末尾。 */
    fun commit(playlist: Playlist) {
        store[playlist.name] = playlist
    }

    /**
     * 给 [name] 找一个还没被占用的改名后缀（"-2"、"-3"…）。
     *
     * 界面把它传给 [Result.NameConflict.renameWith]，这样连续几次重名也不会卡在
     * 「加完 -2 还是重名」。
     */
    fun freeSuffix(name: String): String {
        val base = name.trim()
        if (!store.containsKey(base + AUTO_SUFFIX)) return AUTO_SUFFIX
        var index = 3
        while (store.containsKey("$base-$index")) index++
        return "-$index"
    }
}
