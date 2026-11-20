package com.tv.ball.feature.f058

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * F058「流量统计」的能力层。
 *
 * 播放器每解码一段就往这里报一次字节数，用户想知道的其实只有三件事：
 * 这一场看了多少、今天一共花了多少、这几天是不是明显超量。所以这里只做两件事：
 * 1. 内存里累加本次会话的字节数（进程结束即归零，符合「本次会话」的语义）；
 * 2. 按 `yyyy-MM-dd` 分桶累计每日字节数，用一份 JSON 存进 SharedPreferences，
 *    跨进程保留，读的时候再解析。
 *
 * 时间一律按本机时区取日期键；按天滚动用的是固定 24h 步长（本机时区无夏令时，
 * 跨时区/夏令时环境下最近一天的边界最多偏一小时，对统计展示无影响）。
 *
 * 引擎不认识 R 与 Activity：文案、颜色都由页面决定，这里只给数字与分桶结果。
 */
object TrafficMeter {

    /** 一次「+1MB」上报的字节数。 */
    const val DEFAULT_ADD_SMALL: Long = 1L * 1024L * 1024L

    /** 一次「+50MB」上报的字节数。 */
    const val DEFAULT_ADD_LARGE: Long = 50L * 1024L * 1024L

    /** 近 7 天列表的默认跨度。 */
    const val WEEK_DAYS: Int = 7

    /** 日期桶最多保留多少天，超出的在写入时剪掉。 */
    const val KEEP_DAYS: Int = 60

    private const val PREFS_NAME = "f058_traffic_prefs"
    private const val KEY_BUCKETS = "daily_buckets"
    private const val DAY_PATTERN = "yyyy-MM-dd"
    private const val LABEL_PATTERN = "MM-dd"

    private const val DAY_MS: Long = 24L * 60L * 60L * 1000L

    /** 单位表，[formatBytes] 从 B 往上自适应。 */
    private val UNITS = arrayOf("KB", "MB", "GB", "TB", "PB")

    private var prefs: SharedPreferences? = null

    /** 按日期分桶的字节数，键是 `yyyy-MM-dd`。 */
    private val buckets = LinkedHashMap<String, Long>()

    /** 本次会话累计字节数；只活在进程内。 */
    var sessionBytes: Long = 0L
        private set

    /** 本次会话的起点，界面显示「自几点起」。 */
    var sessionStartedAt: Long = System.currentTimeMillis()
        private set

    /** 一个日期桶：[key] 是 `yyyy-MM-dd`，[label] 是 `MM-dd`，[isToday] 供页面高亮。 */
    data class DayBucket(
        val key: String,
        val label: String,
        val bytes: Long,
        val isToday: Boolean
    )

    /**
     * 绑定持久化。幂等：重复调用直接用已有实例，不会重复读盘。
     * 页面 `onCreate` 里调一次即可，之后 [add] / [resetAll] 会自动落盘。
     */
    fun attach(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = store
        load(store.getString(KEY_BUCKETS, "").orEmpty())
        prune()
    }

    /** 解析 JSON 分桶；坏数据整份丢掉，不让一条脏记录卡住页面。 */
    private fun load(raw: String) {
        if (raw.isEmpty()) return
        runCatching {
            val json = JSONObject(raw)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optLong(key, 0L)
                if (value > 0L) buckets[key] = value
            }
        }
    }

    /** 序列化成分桶 JSON，一份就够，不必为一个功能加一张表。 */
    private fun persist() {
        val store = prefs ?: return
        val json = JSONObject()
        buckets.forEach { (key, value) -> json.put(key, value) }
        store.edit().putString(KEY_BUCKETS, json.toString()).apply()
    }

    /** 剪掉超过 [KEEP_DAYS] 的旧桶；`yyyy-MM-dd` 字符串序即时序，直接字符串比较。 */
    private fun prune() {
        if (buckets.isEmpty()) return
        val cutoff = formatKey(System.currentTimeMillis() - (KEEP_DAYS - 1).toLong() * DAY_MS)
        val iterator = buckets.keys.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() < cutoff) iterator.remove()
        }
    }

    private fun formatKey(timeMillis: Long): String =
        SimpleDateFormat(DAY_PATTERN, Locale.US).format(Date(timeMillis))

    /** 某一时刻所属的日期键，默认取当前时间。 */
    fun dayKey(timeMillis: Long = System.currentTimeMillis()): String = formatKey(timeMillis)

    /** 某一天的字节数；键用 [dayKey] 的格式，没有记录就是 0。 */
    fun dailyBytes(day: String): Long = buckets[day] ?: 0L

    /** 今天的字节数。 */
    fun todayBytes(): Long = dailyBytes(dayKey())

    /** 累计字节数：所有保留日期桶之和。 */
    fun totalBytes(): Long = buckets.values.fold(0L) { acc, value -> acc + value }

    /** 有记录的天数，界面显示「共 N 天」。 */
    fun recordedDays(): Int = buckets.count { it.value > 0L }

    /** 近 [days] 天分桶，队首是今天。 */
    fun recentDays(days: Int = WEEK_DAYS): List<DayBucket> {
        val span = days.coerceIn(1, KEEP_DAYS)
        val now = System.currentTimeMillis()
        return (0 until span).map { offset ->
            val timeMillis = now - offset.toLong() * DAY_MS
            val key = formatKey(timeMillis)
            DayBucket(
                key = key,
                label = SimpleDateFormat(LABEL_PATTERN, Locale.US).format(Date(timeMillis)),
                bytes = dailyBytes(key),
                isToday = offset == 0
            )
        }
    }

    /** 近 [days] 天合计，默认 7 天。 */
    fun weeklyBytes(days: Int = WEEK_DAYS): Long =
        recentDays(days).fold(0L) { acc, day -> acc + day.bytes }

    /**
     * 上报一段流量：会话与今日桶同时加。
     *
     * 非正数直接忽略，避免负值把累计冲掉；写入后剪枝并落盘，
     * 页面不必自己关心持久化时机。
     */
    fun add(bytes: Long) {
        if (bytes <= 0L) return
        sessionBytes += bytes
        val key = dayKey()
        buckets[key] = (buckets[key] ?: 0L) + bytes
        prune()
        persist()
    }

    /** 只清本次会话，历史日期桶保留。 */
    fun resetSession() {
        sessionBytes = 0L
        sessionStartedAt = System.currentTimeMillis()
    }

    /** 全清：会话归零、日期桶清空，持久化同步覆盖。 */
    fun resetAll() {
        sessionBytes = 0L
        sessionStartedAt = System.currentTimeMillis()
        buckets.clear()
        persist()
    }

    /** 是否一条记录都没有，页面用它决定空态。 */
    fun isEmpty(): Boolean = buckets.isEmpty() && sessionBytes == 0L

    /**
     * 自适应单位：小于 1KB 直接给字节，往上按 1024 进位。
     * 三位数以上不留小数，避免「1023.6 MB」这种读着别扭的数。
     */
    fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "—"
        if (bytes < 1024L) return "$bytes B"
        var value = bytes.toDouble() / 1024.0
        var index = 0
        while (value >= 1024.0 && index < UNITS.size - 1) {
            value /= 1024.0
            index++
        }
        val text = if (value >= 100.0) {
            String.format(Locale.US, "%.0f", value)
        } else {
            String.format(Locale.US, "%.1f", value)
        }
        return "$text ${UNITS[index]}"
    }
}