package com.tv.ball.feature.f020

import java.text.Collator
import java.util.Locale

/**
 * F020 文件夹多维排序 · 纯 Kotlin 引擎。
 *
 * 不依赖 Android / 协程 / IO：入参出参都是普通数据类，方便单测与复用。
 *
 * 名称排序走 `java.text.Collator.getInstance(Locale.CHINA)`（中文按拼音，不是 UTF-16 码位），
 * 且内嵌数字按自然序比较：「第9集」排在「第10集」前面，而不是「第10集」因为 '1' < '9' 排到前面。
 */
object F020Engine {

    /** 一个可参与排序的文件夹。 */
    data class Folder(
        val name: String,
        val videoCount: Int,
        val totalDurationMs: Long,
        val modifiedMs: Long
    )

    /** 排序维度：名称 / 视频数 / 总时长 / 最近修改。 */
    enum class SortKey { NAME, COUNT, DURATION, MODIFIED }

    /**
     * 按 [key] 排序 [list]，[ascending] 为 false 时反序。
     *
     * 返回新列表，入参不被修改；同值时按名称自然序兜底，保证同一输入多次调用结果一致。
     */
    fun sort(list: List<Folder>, key: SortKey, ascending: Boolean): List<Folder> {
        val collator = newCollator()
        return list.sortedWith { a, b -> compare(a, b, key, ascending, collator) }
    }

    /** 把持久化里读回来的字符串还原成 [SortKey]，非法值退回 NAME。 */
    fun keyOf(raw: String?): SortKey =
        SortKey.values().firstOrNull { it.name == raw } ?: SortKey.NAME

    /** 单次比较：先比主键（带方向），再比名称自然序。 */
    fun compare(a: Folder, b: Folder, key: SortKey, ascending: Boolean, collator: Collator): Int {
        val primary = when (key) {
            SortKey.NAME -> naturalCompare(a.name, b.name, collator)
            SortKey.COUNT -> a.videoCount.compareTo(b.videoCount)
            SortKey.DURATION -> a.totalDurationMs.compareTo(b.totalDurationMs)
            SortKey.MODIFIED -> a.modifiedMs.compareTo(b.modifiedMs)
        }
        if (primary != 0) return if (ascending) primary else -primary
        return naturalCompare(a.name, b.name, collator)
    }

    /**
     * 自然序比较：文本段交给 [collator]，连续数字段按数值大小比。
     *
     * 「第9集」<「第10集」：数字段 9 vs 10 按数值比，得出 9 < 10；
     * 数值相同时前导零少的排前面（「第9集」<「第09集」）。
     */
    fun naturalCompare(a: String, b: String, collator: Collator): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val da = a[i].isDigit()
            val db = b[j].isDigit()
            if (da && db) {
                val startA = i
                while (i < a.length && a[i].isDigit()) i++
                val startB = j
                while (j < b.length && b[j].isDigit()) j++
                val digitsA = a.substring(startA, i).trimStart('0')
                val digitsB = b.substring(startB, j).trimStart('0')
                if (digitsA.length != digitsB.length) return digitsA.length - digitsB.length
                val byValue = digitsA.compareTo(digitsB)
                if (byValue != 0) return byValue
                // 数值相同：前导零多的排后面
                val widthA = i - startA
                val widthB = j - startB
                if (widthA != widthB) return widthA - widthB
            } else if (da != db) {
                // 数字段与文本段相邻：数字先、文本后，保证全序且不会原地打转
                return if (da) -1 else 1
            } else {
                val startA = i
                while (i < a.length && !a[i].isDigit()) i++
                val startB = j
                while (j < b.length && !b[j].isDigit()) j++
                val byText = collator.compare(a.substring(startA, i), b.substring(startB, j))
                if (byText != 0) return byText
            }
        }
        // 前缀关系：短的在前面
        return (a.length - i).compareTo(b.length - j)
    }

    /** Collator 不是线程安全的，每次排序新建一份。 */
    private fun newCollator(): Collator =
        Collator.getInstance(Locale.CHINA).apply { strength = Collator.TERTIARY }
}