package com.tv.ball.feature.f087

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView

/**
 * 无障碍描述审计引擎。
 *
 * ## 为什么要有这东西
 *
 * 遥控器 + TalkBack 的用户拿不到"我看到了什么"这条信息：屏幕上放一个纯图标的按钮，
 * 视力正常的人看一眼形状就知道，读屏软件却只念一句「按钮」，甚至连「按钮」都不念。
 * 判据其实就一句话——**可点击或可聚焦的控件，要么有 contentDescription，要么有可读文本**。
 * 这条规则写起来比看起来细，所以集中放在这里：扫描、给建议、批量补全共用同一套判断，
 * 免得「扫出来的问题」和「补全后重扫」用的是两套标准，补完了问题数还在原地。
 *
 * ## 扫描范围
 *
 * [audit] 只看传进来的这棵 View 树，不碰全局、不反射、不读别的窗口。
 * 它就是一个**自查工具**：页面自己扫自己，把念不出来的控件列出来、把缺的描述补上，
 * 之后拿遥控器走一遍、开 TalkBack 听一遍，就知道漏没漏。清单里那个「严重度」是本页自评，
 * 不是整机诊断结论。
 *
 * ## 判定细则
 *
 * 1. 只有 `isClickable || isFocusable` 的控件才"要能念出来"；纯展示容器和说明文字不要求描述，
 *    否则会给一堆 `LinearLayout` 报假问题，清单直接没法看。
 * 2. 已经有非空 `contentDescription` 的放过——写得不好是文案评审的事，这里只管「有没有」。
 * 3. `TextView` / `Button` 自带可见文本时读屏会念文本，不必额外补；
 *    文字为空或只有图片的（`drawableLeft` 之类）才算漏。
 * 4. `EditText` 用 `hint` 顶替描述：输入框念提示语本来就是标准做法。
 * 5. 根节点（传进 [audit] 的那一层）不判：页面根容器不是「控件」，它的焦点语义由窗口承担，
 *    报了也是假问题，还会把清单顶到第一位。只从它的子节点开始递归。
 *
 * ## 严重度
 *
 * - [Severity.HIGH]：纯图标可点控件（`ImageButton` / `ImageView`），读屏完全无输出；
 * - [Severity.MEDIUM]：可聚焦但既无文本也无描述（例如文字被清空、只剩背景的按钮）；
 * - [Severity.LOW]：挂过 `tag` 线索、但仍缺正式描述的，属于可改进项。
 *
 * 全程只读 View 树；唯一会改动控件的是显式的 [applyFix] / [applyAll]。
 */
object F087Engine {

    /** 问题严重度。声明顺序即轻重顺序。 */
    enum class Severity(val label: String) {
        /** 读屏完全无输出：纯图标可点控件。 */
        HIGH("高"),

        /** 能聚焦但念不出名字。 */
        MEDIUM("中"),

        /** 有线索但不规范，可改进。 */
        LOW("低")
    }

    /**
     * 一条审计结果。
     *
     * @param viewClassName 控件类名（`simpleName`，匿名类回退完整类名），清单里用来区分控件类型。
     * @param idName 资源 id 可读名（`getResourceEntryName`），取不到时给占位。
     * @param tag 控件原本的 `tag` 字符串，空则为空串；给 [suggestDesc] 当兜底线索。
     * @param suggestedDesc 建议补上的 contentDescription，人话、可直接用。
     * @param severity 见 [Severity]。
     */
    data class Issue(
        val viewClassName: String,
        val idName: String,
        val tag: String,
        val suggestedDesc: String,
        val severity: Severity
    )

    private const val NO_ID = "no_id"
    private const val UNKNOWN_ID = "未命名控件"

    // ===== 扫描 =====

    /**
     * 递归扫描 [root] 之下的所有控件，返回缺描述的清单。
     *
     * 先序遍历（父在前、子在后）：和用户在页面上从上往下走的顺序基本一致。
     * 只读——可以随便重复调用，不会改动任何 `contentDescription`。
     */
    fun audit(root: View): List<Issue> {
        val out = ArrayList<Issue>()
        forEachControl(root) { view ->
            if (needsDescription(view)) out.add(issueOf(view))
        }
        return out
    }

    /** 列出所有缺描述的控件对象本身；一键补全和高亮定位都要用，所以单独开一个口子。 */
    fun pendingViews(root: View): List<View> {
        val out = ArrayList<View>()
        forEachControl(root) { view ->
            if (needsDescription(view)) out.add(view)
        }
        return out
    }

    /**
     * 遍历 root 之下的每一层控件（不含 root 自己）。
     *
     * 递归而不是自建栈：View 树深度有限，递归和「父 → 子」语义一一对应，改规则时不容易漏分支。
     */
    private fun forEachControl(root: View, action: (View) -> Unit) {
        if (root !is ViewGroup) return
        for (i in 0 until root.childCount) {
            val child: View = root.getChildAt(i)
            action(child)
            if (child is ViewGroup) forEachControl(child, action)
        }
    }

    /**
     * 单控件判定：该不该有描述、现在有没有。
     *
     * `isFocusable` 会受 `descendantFocusability` 影响——典型的 `RecyclerView` 自身不可聚焦，
     * 那就不该给它报问题：焦点落进去的是子项，不是容器。
     */
    fun needsDescription(view: View): Boolean {
        if (!(view.isClickable || view.isFocusable)) return false

        // 已有描述：不管写得好不好，不算「缺」。
        if (!view.contentDescription.isNullOrBlank()) return false

        // 自带可读文本的，读屏会念文本，不必再补。
        if (view is EditText && !view.hint.isNullOrBlank()) return false
        if (view is TextView && !view.text.isNullOrBlank()) return false
        if (view is CompoundButton && !view.text.isNullOrBlank()) return false

        return true
    }

    /** 纯图标可点 → 高；有 tag 线索 → 低；其余 → 中。 */
    private fun severityOf(view: View): Severity = when {
        isPureIcon(view) -> Severity.HIGH
        !view.tag?.toString().isNullOrBlank() -> Severity.LOW
        else -> Severity.MEDIUM
    }

    /** 纯图标 = 图像控件；这类读屏出来是真正的「无声」。 */
    private fun isPureIcon(view: View): Boolean = view is ImageButton || view is ImageView

    private fun issueOf(view: View): Issue = Issue(
        viewClassName = classNameOf(view),
        idName = idNameOf(view),
        tag = view.tag?.toString().orEmpty(),
        suggestedDesc = suggestDesc(view),
        severity = severityOf(view)
    )

    // ===== 建议文案 =====

    /**
     * 给控件生成一条建议描述。优先级从最具体到最兜底：
     *
     * 1. **资源 id 里的动作词**：`f087IconSearch` → `search` → 「搜索」。人工埋 id 时写下的语义最可靠，
     *    再配一张小词典把英文动作词翻成人话，建议文案就能直接用；
     * 2. **控件 tag**：开发者把「这个图标是干嘛的」塞 tag 里时直接用；
     * 3. **邻近文本**：同一条父链上的第一个非空 `TextView`——图标配文字是常见布局，那行字就是它的名字；
     * 4. **控件类型兜底**：`ImageButton` → 「按钮」。至少让用户知道这里能按。
     *
     * 结果保证非空：补一个「按钮」总比留空强，空描述等于没补。
     */
    fun suggestDesc(view: View): String {
        val fromId = descFromId(view)
        if (fromId.isNotBlank()) return fromId

        val tag = view.tag?.toString()?.trim().orEmpty()
        if (tag.isNotBlank() && !tag.startsWith("android.view")) return tag

        siblingText(view)?.let { return it }

        return fallbackDesc(view)
    }

    /**
     * 从资源 id 反推动作词并翻成中文。
     *
     * `f087IconSearch` → 拆 `_` 与驼峰 → 去掉 `f087` / `icon` / `btn` 这类结构词 → 剩下 `Search`
     * → 查 [ACTION_WORDS] → 「搜索」。
     * 全部是结构词（id 就叫 `imageView3`）时返回空串，交给后面的兜底，
     * 免得建议文案变成「ImageView3」这种念了也白念的东西。
     */
    private fun descFromId(view: View): String {
        val entry = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
        if (entry.isNullOrBlank()) return ""

        val tokens = ArrayList<String>()
        for (part in entry.split('_', '-')) {
            if (part.isBlank()) continue
            for (word in camelToWords(part)) {
                if (!isStructuralWord(word)) tokens.add(word.lowercase())
            }
        }
        if (tokens.isEmpty()) return ""

        val mapped = tokens.map { ACTION_WORDS[it] }
        // 有一个词没翻译出来就说明这条 id 语义不够清楚，退回下一档兜底，
        // 免得生成「Search Foo」这种中英混排的怪文案。
        if (mapped.any { it == null }) return ""
        return mapped.filterNotNull().distinct().joinToString("")
    }

    /** `demoIconSearch` → [`demo`, `Icon`, `Search`]。 */
    private fun camelToWords(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val spaced = raw.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        return spaced.split(' ').filter { it.isNotBlank() }
    }

    /** 结构词：出现在 id 里但不表达含义的片段。 */
    private fun isStructuralWord(word: String): Boolean {
        val w = word.lowercase()
        if (w.matches(Regex("f\\d{3}"))) return true                 // 功能前缀 f087
        if (w.matches(Regex("[a-z]\\d+")) || w.matches(Regex("\\d+"))) return true // imageView3 / 2
        return w in STRUCTURAL_WORDS
    }

    private val STRUCTURAL_WORDS = setOf(
        "iv", "img", "image", "icon", "btn", "button", "layout", "view", "root",
        "container", "wrapper", "item", "row", "cell", "widget", "control", "action",
        "tv", "text", "label", "group", "demo", "page", "panel", "area", "box"
    )

    /**
     * 动作词典：id 里常见的英文动作词 → 读屏能念的中文。
     *
     * 只收「图标按钮上真的会出现」的词，不追求全——认不出来的词会让整条建议退回兜底，
     * 半翻半不翻的文案比不翻更糟。
     */
    private val ACTION_WORDS: Map<String, String> = mapOf(
        "search" to "搜索",
        "refresh" to "刷新",
        "reload" to "重新加载",
        "share" to "分享",
        "favorite" to "收藏",
        "like" to "点赞",
        "star" to "收藏",
        "play" to "播放",
        "pause" to "暂停",
        "stop" to "停止",
        "next" to "下一个",
        "prev" to "上一个",
        "previous" to "上一个",
        "back" to "返回",
        "close" to "关闭",
        "cancel" to "取消",
        "confirm" to "确认",
        "ok" to "确定",
        "save" to "保存",
        "delete" to "删除",
        "remove" to "移除",
        "edit" to "编辑",
        "add" to "新增",
        "create" to "新建",
        "copy" to "复制",
        "download" to "下载",
        "upload" to "上传",
        "more" to "更多",
        "menu" to "菜单",
        "setting" to "设置",
        "settings" to "设置",
        "home" to "首页",
        "history" to "历史",
        "profile" to "我的",
        "user" to "账号",
        "filter" to "筛选",
        "sort" to "排序",
        "scan" to "扫描",
        "cast" to "投屏",
        "fullscreen" to "全屏",
        "volume" to "音量",
        "mute" to "静音",
        "mic" to "麦克风",
        "brightness" to "亮度",
        "theme" to "主题",
        "clean" to "清理",
        "about" to "关于",
        "info" to "信息",
        "help" to "帮助",
        "link" to "链接",
        "open" to "打开",
        "browser" to "浏览器",
        "web" to "网页",
        "video" to "视频",
        "film" to "影片",
        "device" to "设备",
        "cloud" to "云端",
        "phone" to "手机",
        "computer" to "电脑",
        "file" to "文件",
        "folder" to "文件夹",
        "lock" to "锁定",
        "unlock" to "解锁",
        "check" to "勾选",
        "thumb" to "点赞",
        "arrow" to "箭头",
        "expand" to "展开",
        "collapse" to "收起",
        "night" to "夜间",
        "light" to "明亮"
    )

    /**
     * 找同一行的可读文本：先看兄弟节点，再看父容器下的第一个非空 `TextView`。
     *
     * 兜的是「图标 + 文字」的常见布局——图标本身 id 没语义，但旁边那行「电视剧」就是它的名字。
     * 只在同一条父链上找，不跨层乱搜，否则建议文案会串味。
     */
    private fun siblingText(view: View): String? {
        val parent = view.parent as? ViewGroup ?: return null
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child === view) continue
            val text = (child as? TextView)?.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return null
    }

    /** 最终兜底：按控件类型给一个人话名字。 */
    private fun fallbackDesc(view: View): String = when (view) {
        is ImageButton -> "按钮"
        is Button -> "按钮"
        is CompoundButton -> "开关"
        is EditText -> "输入框"
        is ImageView -> "图片"
        is TextView -> "文字"
        else -> "可操作项"
    }

    /** 控件类名：优先 `simpleName`，匿名类 / 取不到时退回完整类名。 */
    private fun classNameOf(view: View): String {
        val name = view.javaClass.simpleName
        return name.ifBlank { view.javaClass.name }
    }

    /** 资源 id 的可读名；`View.NO_ID` 或取名失败时给占位。 */
    fun idNameOf(view: View): String {
        if (view.id == View.NO_ID) return NO_ID
        val entry = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
        return if (entry.isNullOrBlank()) UNKNOWN_ID else entry
    }

    // ===== 修复 =====

    /**
     * 只补一个控件：把建议文案写进 `contentDescription`。
     *
     * 已有非空描述默认不覆盖——人工写过的描述永远优先于机器生成的；要统一刷成建议文案时传 [force]。
     * 返回是否真的写了，页面上拿它统计「补了几处」。
     */
    fun applyFix(view: View, force: Boolean = false): Boolean {
        if (!force && !view.contentDescription.isNullOrBlank()) return false
        val desc = suggestDesc(view)
        if (desc.isBlank()) return false
        view.contentDescription = desc
        return true
    }

    /**
     * 一键补全：对这棵树上所有缺描述的控件调 [applyFix]。
     *
     * 返回补上的处数。补完必须重跑 [audit] 校验——正常情况下清单应该清空，
     * 页面上「问题数减少」这一步就是靠这个闭环成立的。
     */
    fun applyAll(root: View): Int {
        var fixed = 0
        for (view in pendingViews(root)) {
            if (applyFix(view)) fixed++
        }
        return fixed
    }

    // ===== 汇总 =====

    /**
     * 清单汇总，给页面上那行状态用。
     *
     * 空清单 → 全部合格；否则给总数 + 分档数量，让用户一眼分清「小瑕疵」还是「整页念不出来」。
     * 这里刻意不接 Context：签名只吃 issues，文案在引擎里拼好，页面直接塞进 TextView。
     */
    fun summary(issues: List<Issue>): String {
        if (issues.isEmpty()) return "全部控件都有可读描述"
        val high = issues.count { it.severity == Severity.HIGH }
        val medium = issues.count { it.severity == Severity.MEDIUM }
        val low = issues.count { it.severity == Severity.LOW }
        val parts = ArrayList<String>(3)
        if (high > 0) parts += "高 $high"
        if (medium > 0) parts += "中 $medium"
        if (low > 0) parts += "低 $low"
        return "共 ${issues.size} 处缺描述（${parts.joinToString(" / ")}）"
    }

    /** 一件事的短描述：给列表每一行的抬头用。 */
    fun headline(issue: Issue): String = "${issue.viewClassName} · ${issue.idName}"
}