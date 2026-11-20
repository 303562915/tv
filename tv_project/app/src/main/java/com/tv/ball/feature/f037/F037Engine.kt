package com.tv.ball.feature.f037

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * 「播放列表封面与描述」的数据与持久化入口。
 *
 * 一个播放列表在这里只有三样东西需要落盘：[PlaylistMeta.name]、[PlaylistMeta.description]、
 * [PlaylistMeta.coverUri]，正好对应列表卡片上要显示的四块内容里的三块（条目数另行统计）。
 *
 * 存储用 SharedPreferences 存一段 JSON，而不是三个散键：
 * - 三个字段永远一起读写，一条 JSON 保证「名字改了描述没改」这种半截状态不会出现；
 * - 以后加字段（排序方式、是否置顶）只改 [encode] / [decode]，不用再动存储键；
 * - 整条记录只有一个键 [KEY_META]，[PlaylistMetaStore.clear] 就是删掉这一个键，没有残留。
 *
 * 封面存的是 `content://` 字符串，不是图片本体：图片由系统文档提供方托管，
 * 应用只保存 URI，读权限由 Activity 在用户选图时调 `takePersistableUriPermission` 长期持有。
 * 因此 [PlaylistMeta.coverUri] 可能因为权限失效而加载不出来，界面要把这种情况说清楚，
 * 而不是把空图默默当成「没有封面」。
 *
 * 这个 engine 不依赖 Android 之外的任何东西：`decode(encode(x)) == x.normalized()` 可以直接单测。
 */
object F037Engine {

    /** 元数据落在哪个 SharedPreferences 文件里。 */
    const val PREFS_NAME: String = "f037_playlist_meta"

    /** 整条 [PlaylistMeta] 序列化后存在这个键下面。 */
    const val KEY_META: String = "meta_json"

    /** 列表名称长度上限，与布局里 `android:maxLength` 保持一致。 */
    const val MAX_NAME_LENGTH: Int = 40

    /** 描述长度上限，与布局里 `android:maxLength` 保持一致。 */
    const val MAX_DESCRIPTION_LENGTH: Int = 200

    /** 没有自己起名字时的默认名，空名字一律回落到它。 */
    const val DEFAULT_NAME: String = "我的播放列表"

    /**
     * 列表卡片预览用的样例条目。
     *
     * 真实条目要等接上播放队列的数据源（Room 的 playlist 表）才能拿到；
     * 这之前预览卡用这组固定条目把「条目数」这一块先画出来，
     * 接线时把 [entryCount] 换成一次真实查询即可，界面代码不用动。
     */
    val DEMO_ENTRIES: List<String> = listOf(
        "第 01 集 · 序章",
        "第 02 集 · 出发",
        "第 03 集 · 夜航",
        "第 04 集 · 归途"
    )

    /**
     * 一个播放列表的展示元数据。
     *
     * @param name 列表名称，界面上的主标题。
     * @param description 列表简介，显示在名称下面，可以留空。
     * @param coverUri 封面图的 `content://` URI；null 表示没选封面，界面上给默认底色。
     */
    data class PlaylistMeta(
        val name: String = DEFAULT_NAME,
        val description: String = "",
        val coverUri: String? = null
    ) {

        /** 是否已经有封面。 */
        val hasCover: Boolean
            get() = !coverUri.isNullOrBlank()

        /** 实际显示的名字：名字被清空过就回落到 [DEFAULT_NAME]，列表卡片不会变成空标题。 */
        val displayName: String
            get() = name.ifBlank { DEFAULT_NAME }

        /** 实际显示的描述：没写就是空串，由界面决定换成占位文案。 */
        val displayDescription: String
            get() = description.trim()

        /**
         * 洗一遍再存：去首尾空白、按上限截断、空 URI 归一成 null。
         *
         * 读回来和写下去走的是同一个形状，`decode(encode(meta))` 才能等于 `meta.normalized()`。
         */
        fun normalized(): PlaylistMeta = copy(
            name = name.trim().take(MAX_NAME_LENGTH).ifBlank { DEFAULT_NAME },
            description = description.trim().take(MAX_DESCRIPTION_LENGTH),
            coverUri = coverUri?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    /**
     * [PlaylistMeta] 的读写封装。
     *
     * 只暴露这三个方法：读、写、清。没有「部分更新」的接口——
     * 谁改元数据谁就交出完整的一条 [PlaylistMeta]，避免两处各改一半。
     */
    class PlaylistMetaStore(private val prefs: SharedPreferences) {

        /** 读出已保存的元数据；没有存过、或存的内容坏了，都回落到默认值。 */
        fun load(): PlaylistMeta = decode(prefs.getString(KEY_META, null))

        /** 写入元数据（内部会先 [PlaylistMeta.normalized] 一遍）。 */
        fun save(meta: PlaylistMeta) {
            prefs.edit().putString(KEY_META, encode(meta.normalized())).apply()
        }

        /** 清掉这条记录，回到「从没设置过」的状态。 */
        fun clear() {
            prefs.edit().remove(KEY_META).apply()
        }

        /** 当前这份记录是否存过东西（用来区分「首次进入」和「读到了旧值」）。 */
        fun exists(): Boolean = prefs.contains(KEY_META)
    }

    /** 取一个绑定到应用 Context 的存储实例。 */
    fun store(context: Context): PlaylistMetaStore = PlaylistMetaStore(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    /** 元数据序列化成 JSON，字段名与 [PlaylistMeta] 一一对应。 */
    fun encode(meta: PlaylistMeta): String {
        val json = JSONObject()
        json.put(FIELD_NAME, meta.name)
        json.put(FIELD_DESCRIPTION, meta.description)
        // 显式写 JSONObject.NULL，读的时候才能靠 isNull 区分「没封面」和「封面是空串」
        json.put(FIELD_COVER_URI, meta.coverUri ?: JSONObject.NULL)
        return json.toString()
    }

    /**
     * JSON 反序列化。
     *
     * 容错策略是「只降级出错的那一项」，不整条丢掉：
     * 描述太长、字段类型不对，都还有名字和封面可用，不该因为一个脏字段让用户重新填一遍。
     * 整段 JSON 都解析不出来时才回落到默认 [PlaylistMeta]。
     */
    fun decode(raw: String?): PlaylistMeta {
        if (raw.isNullOrBlank()) return PlaylistMeta()
        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            // 旧版本 / 手改过 / 存储被写坏：整条作废，回到默认值
            return PlaylistMeta()
        }
        return PlaylistMeta(
            name = json.optString(FIELD_NAME, DEFAULT_NAME).ifBlank { DEFAULT_NAME },
            description = json.optString(FIELD_DESCRIPTION, ""),
            coverUri = if (json.isNull(FIELD_COVER_URI)) {
                null
            } else {
                json.optString(FIELD_COVER_URI, "").trim().takeIf { it.isNotEmpty() }
            }
        ).normalized()
    }

    /** 列表卡片的条目数；接上真实队列之前就是样例条目的数量。 */
    fun entryCount(): Int = DEMO_ENTRIES.size

    /** 样例条目拼成一行，页面底部拿它说明「这些是样例」。 */
    fun entriesSummary(): String = DEMO_ENTRIES.joinToString(" · ")

    /** 描述为空时要显示的占位文案由界面给，这里只回答「有没有描述」。 */
    fun hasDescription(meta: PlaylistMeta): Boolean = meta.displayDescription.isNotEmpty()

    private const val FIELD_NAME: String = "name"
    private const val FIELD_DESCRIPTION: String = "description"
    private const val FIELD_COVER_URI: String = "coverUri"
}