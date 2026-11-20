package com.tv.ball.feature.f088

import android.content.Context
import android.view.KeyEvent
import com.tv.ball.R

/**
 * 可以被重映射的播放动作。
 *
 * 只挑播放器里真正用得着、且各家遥控器上叫法不一的那几个：
 * 运营商盒子的遥控器往往只有「上一集 / 下一集 / 菜单 / 快进 / 快退」中的一部分，
 * 电视自带遥控器又可能一个媒体键都没有。所以动作清单固定，键由用户自己认领。
 *
 * [storageName] 是落盘用的稳定名字，不跟着 Kotlin 枚举改名而变；
 * [labelRes] 让页面、toast、映射表三处文案共用一个来源。
 */
enum class Action(val storageName: String, val labelRes: Int) {
    /** 播放 / 暂停。 */
    PLAY_PAUSE("play_pause", R.string.f088_action_play_pause),

    /** 下一集。 */
    NEXT("next", R.string.f088_action_next),

    /** 上一集。 */
    PREV("prev", R.string.f088_action_prev),

    /** 呼出菜单 / 信息层。 */
    MENU("menu", R.string.f088_action_menu),

    /** 快进。 */
    SEEK_FORWARD("seek_forward", R.string.f088_action_seek_forward),

    /** 快退。 */
    SEEK_BACKWARD("seek_backward", R.string.f088_action_seek_backward);

    companion object {
        /** 从落盘名字还原动作；认不出来的老数据直接丢掉，不让一条坏记录毁掉整张表。 */
        fun fromStorage(name: String): Action? = values().firstOrNull { it.storageName == name }
    }
}

/**
 * 一张「动作 → 键值」表。
 *
 * 刻意做成不可变对象：每次改绑都产生一张新表再整体落盘，
 * 页面拿到的永远是某一时刻的完整快照，不会出现"读了一半的表"。
 *
 * 键值用 [KeyEvent] 的 int（也就是 `getKeyCode()` 的返回值），而不是字符：
 * 电视遥控器上大量键根本没有字符（返回、菜单、媒体键），键值才是唯一稳定的身份。
 */
data class KeyMap(val bindings: Map<Action, Int> = emptyMap()) {

    /** 这个动作当前绑到哪个键上；没绑返回 null。 */
    fun keyOf(action: Action): Int? = bindings[action]

    /** 这个键值当前归哪个动作；没绑返回 null。 */
    fun actionOf(keyCode: Int): Action? =
        bindings.entries.firstOrNull { it.value == keyCode }?.key

    /**
     * 把 [action] 绑到 [keyCode] 上，返回新表。
     *
     * 关键语义：一个键只能服务一个动作。所以先把**占用该键的其它动作**整条删掉
     * （这就是"自动解绑"），再写入新绑定；[action] 原来绑在别的键上的旧记录也一并被覆盖。
     * 调用方想知道被挤掉的是谁，用 [displacedBy] 先问一遍。
     */
    fun withBinding(action: Action, keyCode: Int): KeyMap {
        val kept = bindings.filterValues { it != keyCode }
        return KeyMap(LinkedHashMap(kept).apply { put(action, keyCode) })
    }

    /** 解绑单个动作，其它动作不受影响。 */
    fun without(action: Action): KeyMap = KeyMap(LinkedHashMap(bindings).apply { remove(action) })

    /**
     * 这次改绑会把哪个动作挤掉。
     *
     * 自己占着自己这个键（同一个动作重复学习同一个键）不算冲突，返回 null；
     * 这样页面只在真的"抢走了别人的键"时才提示冲突。
     */
    fun displacedBy(action: Action, keyCode: Int): Action? =
        actionOf(keyCode)?.takeIf { it != action }

    companion object {
        /** 空表：一个动作都没绑，任何键都不触发。 */
        fun empty(): KeyMap = KeyMap(emptyMap())
    }
}

/**
 * F088「遥控器按键自定义」的能力层。
 *
 * 这一层只回答两件事：**这个键是谁的**、**这个动作绑在哪个键上**。
 * 捕获、绑定、解绑、恢复默认都走这里，页面只负责画表和收按键。
 *
 * 为什么必须有这一层：不同遥控器同一个功能键发出来的键值差别很大
 * （运营商盒子把"下一集"发成 87 / 176，电视自带遥控器可能发来 22 / 23 这种方向键复用值，
 * 甚至是厂商私有的 200+ 键值）。把逻辑写进页面，就只能靠改代码适配每一台盒子；
 * 放在引擎里，改绑只是往 SharedPreferences 里写一条记录。
 *
 * 纯查询部分（[actionOf] / [keyName] / [withBinding]）不碰存储，可以脱离设备单测；
 * [load] / [save] 负责持久化，格式见 [encode] / [parse]。
 */
object F088Engine {

    /** 存储文件名；单独一份，不跟别的功能挤同一个 prefs。 */
    const val PREFS_NAME: String = "f088_key_map"

    private const val KEY_MAP = "map"
    private const val SEP_ENTRY = ";"
    private const val SEP_PAIR = ":"
    private const val PREFIX = "KEYCODE_"

    /**
     * 出厂映射：按 Android 标准媒体键来配。
     *
     * 这是"大多数盒子能用"的一组默认值，不是承诺——正因为存在发不出这些键值的遥控器，
     * 才有这个页面。用户改过一次之后，[load] 就以用户表为准，默认表只负责首次打开。
     */
    fun defaultMap(): KeyMap = KeyMap(
        LinkedHashMap<Action, Int>().apply {
            put(Action.PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            put(Action.NEXT, KeyEvent.KEYCODE_MEDIA_NEXT)
            put(Action.PREV, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            put(Action.MENU, KeyEvent.KEYCODE_MENU)
            put(Action.SEEK_FORWARD, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            put(Action.SEEK_BACKWARD, KeyEvent.KEYCODE_MEDIA_REWIND)
        }
    )

    // ===== 纯查询 =====

    /**
     * 键值的人话名字：`KeyEvent.keyCodeToString(87)` → `"KEYCODE_MEDIA_NEXT"` → `"MEDIA_NEXT"`。
     *
     * 去掉 `KEYCODE_` 前缀是为了在电视上少占几列宽度；数字键值仍由页面另起一段显示，
     * 因为用户跟客服/论坛描述问题时，"键值 87"比"MEDIA_NEXT"更好对照。
     */
    fun keyName(keyCode: Int): String {
        val raw = KeyEvent.keyCodeToString(keyCode)
        val stripped = raw.removePrefix(PREFIX)
        return if (stripped.isEmpty()) keyCode.toString() else stripped
    }

    /** 这张表里有多少个动作已经绑上了键。 */
    fun assignedCount(map: KeyMap): Int = map.bindings.count { it.value > 0 }

    /** 这张表跟出厂映射是不是完全一样（用来在页面上标"当前就是默认"）。 */
    fun isDefault(map: KeyMap): Boolean = map.bindings == defaultMap().bindings

    // ===== 改绑 =====

    /**
     * 把 [action] 绑到 [keyCode]，落盘，并返回**被挤掉的动作**（没有冲突就返回 null）。
     *
     * 冲突时旧动作是"解绑"而不是"跟着换到别的键"：用户按下一个键就是想让它做这件事，
     * 系统偷偷给旧动作另找一个键，会让后续按键行为变得不可预期。
     */
    fun bind(context: Context, action: Action, keyCode: Int): Action? {
        val map = load(context)
        val displaced = map.displacedBy(action, keyCode)
        save(context, map.withBinding(action, keyCode))
        return displaced
    }

    /** 解绑一个动作，返回它原来占的键值（本来就没绑返回 null）。 */
    fun unbind(context: Context, action: Action): Int? {
        val map = load(context)
        val old = map.keyOf(action) ?: return null
        save(context, map.without(action))
        return old
    }

    /** 一键恢复出厂映射，返回恢复后的表。 */
    fun reset(context: Context): KeyMap {
        val map = defaultMap()
        save(context, map)
        return map
    }

    // ===== 查询入口（页面与播放器共用） =====

    /** 这个键值现在归哪个动作；播放器收到按键时问这一句就够了。 */
    fun actionOf(context: Context, keyCode: Int): Action? = load(context).actionOf(keyCode)

    /** 这个动作现在绑在哪个键上。 */
    fun keyOf(context: Context, action: Action): Int? = load(context).keyOf(action)

    // ===== 持久化 =====

    /**
     * 读表。
     *
     * 从来没存过（首次打开）→ 出厂映射；
     * 存过但是空串 → 用户确实把所有绑定都解掉了，返回空表，**不能**回退到默认，
     * 否则用户解绑完重进页面发现又全回来了。这个区别靠"有没有这个 key"来区分，不靠内容。
     */
    fun load(context: Context): KeyMap {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_MAP, null) ?: return defaultMap()
        return parse(raw)
    }

    /** 写表；apply() 异步落盘，学习模式里连按几个键不会因为磁盘 IO 卡住遥控器。 */
    fun save(context: Context, map: KeyMap) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MAP, encode(map))
            .apply()
    }

    /** 序列化成 `play_pause:85;next:87;…` 这样一行；空表就是空串。 */
    fun encode(map: KeyMap): String =
        map.bindings.entries.joinToString(SEP_ENTRY) { (action, code) ->
            action.storageName + SEP_PAIR + code
        }

    /**
     * 反序列化。
     *
     * 单条坏记录（老版本残留、手工改坏、键值不是数字）只丢这一条，其余照收：
     * 一张表里坏一项就整张作废的话，用户会莫名其妙丢掉全部自定义。
     */
    fun parse(raw: String): KeyMap {
        val map = LinkedHashMap<Action, Int>()
        raw.split(SEP_ENTRY).forEach { entry ->
            if (entry.isBlank()) return@forEach
            val parts = entry.split(SEP_PAIR, limit = 2)
            if (parts.size != 2) return@forEach
            val action = Action.fromStorage(parts[0].trim()) ?: return@forEach
            val keyCode = parts[1].trim().toIntOrNull() ?: return@forEach
            map[action] = keyCode
        }
        return KeyMap(map)
    }
}