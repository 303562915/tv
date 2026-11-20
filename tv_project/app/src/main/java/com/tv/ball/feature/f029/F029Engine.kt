package com.tv.ball.feature.f029

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 一条收藏。
 *
 * 标签是自由文本集合，同一条收藏上天然不会重复；顺序在渲染时现排，
 * 所以这里用 [Set] 而不是 List —— 语义就是「挂没挂这个标签」。
 */
data class Favorite(
    val id: String,
    val title: String,
    val tags: Set<String> = emptySet()
)

/** 标签 ＋ 它挂了几个收藏，[F029Engine.allTags] 的输出单元。 */
data class TagCount(val name: String, val count: Int)

/**
 * F029「收藏标签与分组」· 领域逻辑。
 *
 * 标签不单独存一张表：它只活在各条收藏的 [Favorite.tags] 上，
 * 所以「有哪些标签」永远是 [allTags] 现算出来的，不会出现挂了 0 条收藏的空标签。
 * 所有变更入口都是纯函数（旧 List 进、新 List 出，不改入参），
 * Activity 只负责把结果整体换进去、落盘、重绘。
 *
 * 持久化见 [save] / [load]：一个 SharedPreferences 键 [KEY_TAGS]，值是 [encode] 出的 JSON。
 * 示例收藏集本身写在代码里，存储只承载用户打过的标签。
 */
object F029Engine {

    /** 需求约定的 SharedPreferences 文件名。 */
    const val PREFS_NAME = "f029_prefs"

    /** 需求约定的存储键：标签 JSON。 */
    const val KEY_TAGS = "f029_tags"

    /** 标签名长度上限，超出的按码点截断，避免一条标签把筛选条撑爆。 */
    const val MAX_TAG_LENGTH = 12

    private const val VERSION = 1
    private const val FIELD_VERSION = "version"
    private const val FIELD_ITEMS = "items"
    private const val FIELD_ID = "id"
    private const val FIELD_TITLE = "title"
    private const val FIELD_TAGS = "tags"

    private val WHITESPACE = Regex("\\s+")

    // region 标签名

    /**
     * 标签名规范化：去首尾空白、连续空白压成一个空格、超长按码点截断。
     *
     * 返回 null 表示这段输入不构成标签（空串 / 全是空白），调用方据此给提示。
     */
    fun normalizeTag(raw: String?): String? {
        if (raw == null) return null
        val collapsed = raw.trim().replace(WHITESPACE, " ")
        if (collapsed.isEmpty()) return null
        if (collapsed.codePointCount(0, collapsed.length) <= MAX_TAG_LENGTH) return collapsed
        // 按码点截断，别把 emoji 的代理对劈成半个
        return collapsed.substring(0, collapsed.offsetByCodePoints(0, MAX_TAG_LENGTH))
    }

    // endregion

    // region 查询

    /**
     * 全部标签及计数：条数从多到少，条数相同按名字排。
     * 界面直接照这个顺序渲染筛选条和标签管理列表，长出来的顺序也稳定。
     */
    fun allTags(items: List<Favorite>): List<TagCount> {
        val counter = HashMap<String, Int>()
        items.forEach { item ->
            item.tags.forEach { tag -> counter[tag] = (counter[tag] ?: 0) + 1 }
        }
        return counter.map { TagCount(it.key, it.value) }
            .sortedWith(compareByDescending<TagCount> { it.count }.thenBy { it.name })
    }

    /** 某个标签挂了几条收藏。 */
    fun countOf(items: List<Favorite>, tag: String?): Int {
        val target = normalizeTag(tag) ?: return 0
        return items.count { target in it.tags }
    }

    /** 按标签筛选；tag 为 null / 空白时原样返回，等价于「全部」。 */
    fun filterByTag(items: List<Favorite>, tag: String?): List<Favorite> {
        val target = normalizeTag(tag) ?: return items
        return items.filter { target in it.tags }
    }

    // endregion

    // region 变更

    /** 给某条收藏加一个标签；标签不合法或已存在时原样返回。 */
    fun addTag(items: List<Favorite>, id: String, raw: String?): List<Favorite> {
        val tag = normalizeTag(raw) ?: return items
        return items.map { item ->
            if (item.id == id && tag !in item.tags) item.copy(tags = item.tags + tag) else item
        }
    }

    /** 从某条收藏上摘一个标签；标签不存在时原样返回。 */
    fun removeTag(items: List<Favorite>, id: String, raw: String?): List<Favorite> {
        val tag = normalizeTag(raw) ?: return items
        return items.map { item ->
            if (item.id == id && tag in item.tags) item.copy(tags = item.tags - tag) else item
        }
    }

    /**
     * 全局重命名：所有挂了这个标签的收藏一起改成新名字。
     *
     * 新名字已经存在于同一条收藏上时做合并（去重），不会留下两个名字。
     */
    fun renameTag(items: List<Favorite>, from: String?, to: String?): List<Favorite> {
        val source = normalizeTag(from) ?: return items
        val target = normalizeTag(to) ?: return items
        if (source == target) return items
        return items.map { item ->
            if (source in item.tags) item.copy(tags = (item.tags - source) + target) else item
        }
    }

    /** 全局删除：把这个标签从所有收藏上摘掉，收藏本身保留。 */
    fun deleteTag(items: List<Favorite>, raw: String?): List<Favorite> {
        val tag = normalizeTag(raw) ?: return items
        return items.map { item ->
            if (tag in item.tags) item.copy(tags = item.tags - tag) else item
        }
    }

    // endregion

    // region 持久化

    /** 序列化成 JSON：`{"version":1,"items":[{"id","title","tags":[…]}]}`。 */
    fun encode(items: List<Favorite>): String {
        val array = JSONArray()
        items.forEach { item ->
            val tags = JSONArray()
            item.tags.sorted().forEach { tags.put(it) }
            val obj = JSONObject()
            obj.put(FIELD_ID, item.id)
            obj.put(FIELD_TITLE, item.title)
            obj.put(FIELD_TAGS, tags)
            array.put(obj)
        }
        val root = JSONObject()
        root.put(FIELD_VERSION, VERSION)
        root.put(FIELD_ITEMS, array)
        return root.toString()
    }

    /** 反序列化；空值、坏 JSON、解析出 0 条收藏一律返回 null，让调用方回落到示例集。 */
    fun decode(raw: String?): List<Favorite>? {
        if (raw.isNullOrEmpty()) return null
        return try {
            val array = JSONObject(raw).optJSONArray(FIELD_ITEMS) ?: return null
            val out = ArrayList<Favorite>(array.length())
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val id = obj.optString(FIELD_ID).trim()
                if (id.isEmpty()) continue
                val tags = LinkedHashSet<String>()
                val tagArray = obj.optJSONArray(FIELD_TAGS)
                if (tagArray != null) {
                    for (i in 0 until tagArray.length()) {
                        normalizeTag(tagArray.optString(i))?.let { tags.add(it) }
                    }
                }
                out.add(Favorite(id, obj.optString(FIELD_TITLE).ifBlank { id }, tags))
            }
            if (out.isEmpty()) null else out
        } catch (e: JSONException) {
            // 存档被外部改坏时宁可回到默认示例，也不要让页面打不开
            null
        }
    }

    /**
     * 读回收藏。
     *
     * [fallback] 是代码里的示例集：它决定有哪些收藏、标题与顺序，
     * 存储只把标签盖回去 —— 这样以后示例集增删条目，也不会被旧存档带错位。
     */
    fun load(context: Context, fallback: List<Favorite>): List<Favorite> {
        val stored = decode(prefs(context).getString(KEY_TAGS, null)) ?: return fallback
        val byId = HashMap<String, Favorite>(stored.size)
        stored.forEach { byId[it.id] = it }
        return fallback.map { item ->
            val saved = byId[item.id] ?: return@map item
            item.copy(tags = saved.tags)
        }
    }

    /** 落盘；apply() 异步写，主线程不等磁盘。 */
    fun save(context: Context, items: List<Favorite>) {
        prefs(context).edit().putString(KEY_TAGS, encode(items)).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // endregion
}
