package com.tv.ball.feature.f093

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.tv.ball.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 期望结构里的一列：类型与可空性逐字对齐 Room 由 `@Entity` 生成的 DDL。 */
data class ColumnSpec(
    val name: String,
    val type: String,
    val notNull: Boolean,
    val primaryKey: Boolean = false,
    val autoIncrement: Boolean = false,
    val default: String? = null
)

/**
 * 期望结构里的一张表。
 *
 * 建表 DDL 由列清单生成，不在别处再抄一遍：这张清单同时喂「校验」和「建表修复 SQL」，
 * 两处用的是同一份数据，不会出现「补出来的表」和「校验认的表」各写一套然后互相打脸。
 */
data class TableSpec(val name: String, val columns: List<ColumnSpec>) {

    /** 建表语句；[target] 给修复脚本里「先建 `<表>__new`」那一步用。 */
    fun createSql(target: String = name): String {
        val body = columns.joinToString(",\n    ") { defOf(it) }
        return "CREATE TABLE IF NOT EXISTS `$target` (\n    $body\n);"
    }

    private fun defOf(col: ColumnSpec): String = buildString {
        append('`').append(col.name).append("` ").append(col.type)
        if (col.primaryKey) {
            append(" PRIMARY KEY")
            if (col.autoIncrement) append(" AUTOINCREMENT")
        }
        if (col.notNull) append(" NOT NULL")
        col.default?.let { append(" DEFAULT ").append(it) }
    }
}

/** 问题分类：只有「没有」和「有但不对」两种，页面按它决定用错误色还是警告色。 */
enum class Kind {
    /** 表或列在库里根本不存在。 */
    MISSING,

    /** 表/列在，但库版本、声明类型或可空性落后于当前代码。 */
    LOW_VERSION
}

/** 差异的具体原因；文案在资源里，导出报告和页面共用同一套 label。 */
enum class Detail {
    TABLE_MISSING,
    COLUMN_MISSING,
    VERSION_LOW,
    VERSION_HIGH,
    TYPE_CHANGED,
    NULLABILITY_CHANGED
}

/**
 * 一处差异。
 *
 * 库版本不对时 [table] 为空、[column] 固定 `user_version`——它不是某张表的问题，
 * 而是整个库的水位问题，修复办法也不同（PRAGMA 而不是 ALTER TABLE）。
 */
data class Issue(
    val table: String,
    val column: String,
    val kind: Kind,
    val detail: Detail,
    val expected: String = "",
    val actual: String = ""
)

/** 一次校验的总体状态。 */
enum class State {
    /** 库文件在，且读得动。 */
    OK,

    /** 库文件不在：应用还没写过 Room 数据。 */
    ABSENT,

    /** 库文件在，但打开/读取失败。 */
    ERROR
}

/** 一次校验的完整结果；页面和导出都用它，不再各自重新扫一遍。 */
data class Report(
    val path: String,
    val state: State,
    val dbVersion: Int,
    val expectedVersion: Int,
    val tableCount: Int,
    val columnCount: Int,
    val issues: List<Issue>,
    val error: String = ""
)

/**
 * F093「数据库迁移校验」的能力层。
 *
 * 只做一件事：**拿代码里的期望结构和设备上那份 Room 库的实际结构对一遍**，
 * 把差异列出来，并给出能照着执行的修复 SQL。
 *
 * 为什么要单独读库、而不是问 Room：
 * 结构不一致的库，Room 自己根本打不开——`RoomOpenHelper` 在 onOpen 里对不上 identity hash
 * 就抛 `IllegalStateException: Migration didn't properly handle ...`，异常发生在
 * `databaseBuilder(...).build()` 之后的第一次访问，堆栈里看不到是哪张表哪一列。
 * 所以这里绕过 Room，用 [SQLiteDatabase.openDatabase] 以**只读**方式打开同一个文件，
 * 直接读 `sqlite_master` / `PRAGMA table_info` —— 只读打开不会改库，也不会触发迁移。
 *
 * 三条纪律：
 * 1. **只读**：一律 `OPEN_READONLY`。这个页面出现在「已经出问题」的时候，任何写操作都可能把
 *    能救的数据变成不能救的数据；修复 SQL 只显示、不执行，由用户自己决定。
 * 2. **不崩**：库不存在（新装、刚清过数据）返回 [State.ABSENT]，打不开返回 [State.ERROR]，
 *    页面据此显示「库未创建」或错误原文，而不是让功能中心跟着一起挂。
 * 3. **和代码一致**：期望结构逐列抄自 `data/model` 下的实体文件 的实体与 `data/local/AppDatabase.kt`
 *    的迁移脚本，库名抄自 `VideoPlayerApplication` 的 `Room.databaseBuilder`。
 */
object F093Engine {

    /**
     * 主库文件名。
     *
     * 必须与 `VideoPlayerApplication` 里 `Room.databaseBuilder(context, AppDatabase::class.java, ...)`
     * 的第三个参数逐字一致（Room 不会自动补 `.db`，文件名就是这一串）。
     */
    const val DB_NAME: String = "video_player_database"

    /**
     * 兜底候选库名。
     *
     * 工程里 F071 备份、F076 存储清理两处的库名清单与上面不一致（`tv_player.db` 等），
     * 老版本装过的机器上真有可能躺着这种名字的库文件。主名找不到时按顺序再看一遍，
     * 谁的 `getDatabasePath` 指向一个真实文件就用谁——校验的是「设备上那份库」，
     * 不是「我以为它叫什么」。
     */
    val DB_CANDIDATES: List<String> = listOf(
        DB_NAME,
        "$DB_NAME.db",
        "tv_player.db",
        "tvplayer.db",
        "app_database"
    )

    /** 代码期望的库版本 = `AppDatabase` 的 `version`。迁移脚本补到 10→11（收藏分类）时为 11。 */
    const val EXPECTED_VERSION: Int = 11

    /** 导出报告的目录名（放应用外部私有目录，用户能用文件管理器找到）。 */
    const val REPORT_DIR: String = "report"

    // ===== 期望结构 =====
    // 逐列抄自 data/model/VideoEntity.kt / HistoryEntity.kt / FavoriteEntity.kt /
    // PlaylistEntity.kt / DownloadTaskEntity.kt。注意两处真实名字与直觉不同：
    // 播放列表实体写的是 @Entity(tableName = "playlist")（单数），下载任务是 download_tasks。

    private val VIDEOS = TableSpec(
        "videos",
        listOf(
            ColumnSpec("id", "INTEGER", notNull = true, primaryKey = true, autoIncrement = true),
            ColumnSpec("title", "TEXT", notNull = true, default = "''"),
            ColumnSpec("url", "TEXT", notNull = true, default = "''"),
            ColumnSpec("thumbnailUrl", "TEXT", notNull = false),
            ColumnSpec("description", "TEXT", notNull = false),
            ColumnSpec("duration", "INTEGER", notNull = true, default = "0"),
            ColumnSpec("sourceType", "TEXT", notNull = true, default = "''"),
            ColumnSpec("createdAt", "INTEGER", notNull = true, default = "0")
        )
    )

    private val HISTORY = TableSpec(
        "history",
        listOf(
            ColumnSpec("id", "INTEGER", notNull = true, primaryKey = true, autoIncrement = true),
            ColumnSpec("videoTitle", "TEXT", notNull = true, default = "''"),
            ColumnSpec("videoUrl", "TEXT", notNull = true, default = "''"),
            ColumnSpec("parsedVideoUrl", "TEXT", notNull = false),
            ColumnSpec("videoHeaders", "TEXT", notNull = false),
            ColumnSpec("thumbnailUrl", "TEXT", notNull = false),
            ColumnSpec("watchedPosition", "INTEGER", notNull = true, default = "0"),
            ColumnSpec("duration", "INTEGER", notNull = true, default = "0"),
            ColumnSpec("lastWatchedAt", "INTEGER", notNull = true, default = "0"),
            ColumnSpec("detailUrl", "TEXT", notNull = false),
            ColumnSpec("episodesJson", "TEXT", notNull = false),
            ColumnSpec("currentEpisode", "TEXT", notNull = false),
            ColumnSpec("videoSource", "TEXT", notNull = false)
        )
    )

    private val FAVORITES = TableSpec(
        "favorites",
        listOf(
            ColumnSpec("id", "INTEGER", notNull = true, primaryKey = true, autoIncrement = true),
            ColumnSpec("videoId", "INTEGER", notNull = true, default = "0"),
            ColumnSpec("title", "TEXT", notNull = true, default = "''"),
            ColumnSpec("thumbnailUrl", "TEXT", notNull = false),
            ColumnSpec("detailUrl", "TEXT", notNull = false),
            ColumnSpec("episodesJson", "TEXT", notNull = false),
            ColumnSpec("parsedVideoUrl", "TEXT", notNull = false),
            ColumnSpec("videoHeaders", "TEXT", notNull = false),
            ColumnSpec("addedAt", "INTEGER", notNull = true, default = "0"),
            // 10→11 新增：用户自定义分类名，可空（NULL/空串都算「未分类」）
            ColumnSpec("category", "TEXT", notNull = false)
        )
    )

    private val PLAYLIST = TableSpec(
        "playlist",
        listOf(
            ColumnSpec("id", "INTEGER", notNull = true, primaryKey = true, autoIncrement = true),
            ColumnSpec("videoId", "INTEGER", notNull = true, default = "0"),
            ColumnSpec("videoTitle", "TEXT", notNull = true, default = "''"),
            ColumnSpec("videoUrl", "TEXT", notNull = true, default = "''"),
            ColumnSpec("thumbnailUrl", "TEXT", notNull = false),
            ColumnSpec("position", "INTEGER", notNull = true, default = "0"),
            ColumnSpec("addedAt", "INTEGER", notNull = true, default = "0")
        )
    )

    private val DOWNLOAD_TASKS = TableSpec(
        "download_tasks",
        listOf(
            ColumnSpec("id", "INTEGER", notNull = true, primaryKey = true),
            ColumnSpec("url", "TEXT", notNull = true, default = "''"),
            ColumnSpec("title", "TEXT", notNull = true, default = "''"),
            ColumnSpec("fileName", "TEXT", notNull = true, default = "''"),
            ColumnSpec("savePath", "TEXT", notNull = true, default = "''"),
            ColumnSpec("totalSize", "INTEGER", notNull = true, default = "0"),
            ColumnSpec("downloadedSize", "INTEGER", notNull = true, default = "0"),
            ColumnSpec("status", "TEXT", notNull = true, default = "'PENDING'"),
            ColumnSpec("progress", "INTEGER", notNull = true, default = "0"),
            ColumnSpec("createTime", "INTEGER", notNull = true, default = "0"),
            ColumnSpec("updateTime", "INTEGER", notNull = true, default = "0"),
            ColumnSpec("isM3u8", "INTEGER", notNull = true, default = "0"),
            ColumnSpec("m3u8SegmentsJson", "TEXT", notNull = true, default = "'[]'"),
            ColumnSpec("errorMessage", "TEXT", notNull = false),
            ColumnSpec("customHeadersJson", "TEXT", notNull = true, default = "'{}'"),
            ColumnSpec("threadCount", "INTEGER", notNull = false),
            ColumnSpec("partProgressesJson", "TEXT", notNull = false)
        )
    )

    /** 五张表就是 `AppDatabase.entities` 里的五个实体，顺序也照它排。 */
    fun expectedSchema(): List<TableSpec> = listOf(
        VIDEOS, HISTORY, FAVORITES, PLAYLIST, DOWNLOAD_TASKS
    )

    // ===== 打开与定位 =====

    /**
     * 主库文件路径（不保证存在）。
     *
     * 走 [Context.getDatabasePath] 而不是拼 `/data/data/<包名>/databases/...`：
     * 部分 ROM 会重定向数据库目录，硬编码路径在那类机器上一定读不到。
     */
    fun primaryPath(context: Context): String =
        runCatching { context.getDatabasePath(DB_NAME) }.getOrNull()?.path ?: ""

    /** 真实存在的库文件；一个候选都没有就是 null（新装机器上这是正常状态）。 */
    fun resolve(context: Context): File? {
        for (name in DB_CANDIDATES) {
            val file = runCatching { context.getDatabasePath(name) }.getOrNull() ?: continue
            if (runCatching { file.isFile }.getOrDefault(false)) return file
        }
        return null
    }

    /** 页面首屏用：库还没建时也要显示「它本该在哪」。 */
    fun displayPath(context: Context): String = resolve(context)?.path ?: primaryPath(context)

    // ===== 校验 =====

    /**
     * 对着一份已打开的库列差异。
     *
     * 检查顺序：库版本 → 整表缺失 → 逐列（缺失 → 类型 → 可空性）。
     * 表缺失时**不再往下报列缺**：一张没有的表报 8 条「列不存在」只会把真正的结论埋掉。
     *
     * 主键列不做可空性比对：`INTEGER PRIMARY KEY` 是 rowid 别名，SQLite 里天然不可空，
     * `PRAGMA table_info` 却可能给 notnull=0，按字面比对会稳定产出假阳性。
     */
    fun verify(db: SQLiteDatabase): List<Issue> {
        val issues = ArrayList<Issue>()

        val version = runCatching { db.version }.getOrDefault(-1)
        if (version >= 0 && version != EXPECTED_VERSION) {
            issues.add(
                Issue(
                    table = "",
                    column = "user_version",
                    kind = Kind.LOW_VERSION,
                    detail = if (version < EXPECTED_VERSION) Detail.VERSION_LOW else Detail.VERSION_HIGH,
                    expected = EXPECTED_VERSION.toString(),
                    actual = version.toString()
                )
            )
        }

        val existing = tableNames(db)
        for (spec in expectedSchema()) {
            if (spec.name !in existing) {
                issues.add(
                    Issue(
                        table = spec.name,
                        column = "",
                        kind = Kind.MISSING,
                        detail = Detail.TABLE_MISSING,
                        expected = spec.columns.size.toString(),
                        actual = "0"
                    )
                )
                continue
            }

            val found = tableInfo(db, spec.name)
            for (col in spec.columns) {
                val actual = found[col.name]
                if (actual == null) {
                    issues.add(
                        Issue(
                            table = spec.name,
                            column = col.name,
                            kind = Kind.MISSING,
                            detail = Detail.COLUMN_MISSING,
                            expected = col.type,
                            actual = ""
                        )
                    )
                    continue
                }
                if (!actual.type.equals(col.type, ignoreCase = true)) {
                    issues.add(
                        Issue(
                            table = spec.name,
                            column = col.name,
                            kind = Kind.LOW_VERSION,
                            detail = Detail.TYPE_CHANGED,
                            expected = col.type,
                            // 没写类型的列在 SQLite 里按 BLOB 亲和性处理，照实显示
                            actual = actual.type.ifBlank { "BLOB" }
                        )
                    )
                } else if (!col.primaryKey && col.notNull && !actual.notNull) {
                    issues.add(
                        Issue(
                            table = spec.name,
                            column = col.name,
                            kind = Kind.LOW_VERSION,
                            detail = Detail.NULLABILITY_CHANGED,
                            expected = "NOT NULL",
                            actual = "NULL"
                        )
                    )
                }
            }
        }
        return issues
    }

    private fun tableNames(db: SQLiteDatabase): Set<String> {
        val names = HashSet<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
            val idx = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                val name = cursor.getString(idx) ?: continue
                if (name.isNotEmpty()) names.add(name)
            }
        }
        return names
    }

    private class ActualColumn(val type: String, val notNull: Boolean)

    private fun tableInfo(db: SQLiteDatabase, table: String): Map<String, ActualColumn> {
        val columns = LinkedHashMap<String, ActualColumn>()
        db.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            val typeIdx = cursor.getColumnIndexOrThrow("type")
            val notNullIdx = cursor.getColumnIndexOrThrow("notnull")
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx) ?: continue
                columns[name] = ActualColumn(
                    type = (cursor.getString(typeIdx) ?: "").trim().uppercase(Locale.US),
                    notNull = cursor.getInt(notNullIdx) != 0
                )
            }
        }
        return columns
    }

    /**
     * 完整扫一遍并开库校验。
     *
     * 一律在 IO 线程调用：开库要 mmap 文件头 + 读 `sqlite_master`，
     * 慢的盒子上几十毫秒，主线程上是可见的一帧掉。
     */
    fun scan(context: Context): Report {
        val app = context.applicationContext ?: context
        val tables = expectedSchema()
        val tableCount = tables.size
        val columnCount = tables.sumOf { it.columns.size }

        val file = resolve(app)
        val path = file?.path ?: primaryPath(app)
        if (file == null) {
            return Report(path, State.ABSENT, -1, EXPECTED_VERSION, tableCount, columnCount, emptyList())
        }

        val db = runCatching {
            SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
        }.getOrElse { e ->
            return Report(
                path, State.ERROR, -1, EXPECTED_VERSION, tableCount, columnCount, emptyList(), readable(e)
            )
        }

        return db.use { open ->
            val version = runCatching { open.version }.getOrDefault(-1)
            val issues = runCatching { verify(open) }.getOrElse { e ->
                return@use Report(
                    path, State.ERROR, version, EXPECTED_VERSION, tableCount, columnCount,
                    emptyList(), readable(e)
                )
            }
            Report(path, State.OK, version, EXPECTED_VERSION, tableCount, columnCount, issues)
        }
    }

    // ===== 修复 SQL =====

    /**
     * 一条问题的修复 SQL（只生成，不执行）。
     *
     * 三种情况分开处理：
     * - 表缺失：整表 CREATE；
     * - 列缺失：ALTER TABLE ADD COLUMN，NOT NULL 的列必须带 DEFAULT，
     *   否则 SQLite 直接报 `Cannot add a NOT NULL column with default value NULL`；
     * - 结构不对（类型/可空性）：SQLite **不支持改列**，只能按 Room 自己的做法整表重建。
     */
    fun fixSql(issue: Issue): String {
        if (issue.table.isBlank()) {
            return buildString {
                appendLine("-- 库版本停在 ${issue.actual}，代码期望 ${issue.expected}（迁移没跑完或库被换过）")
                appendLine("-- 先确认 AppDatabase 里 ${issue.actual} 到 ${issue.expected} 的迁移都执行成功；")
                appendLine("-- 确认结构已对齐后，再把水位推到期望值：")
                append("PRAGMA user_version = $EXPECTED_VERSION;")
            }
        }

        val spec = expectedSchema().firstOrNull { it.name == issue.table }
            ?: return "-- 代码里没有 ${issue.table} 这个实体，属外来表，人工确认是否该删"

        return when (issue.kind) {
            Kind.MISSING ->
                if (issue.column.isBlank()) spec.createSql() else addColumnSql(spec, issue.column)

            Kind.LOW_VERSION -> rebuildSql(spec, issue.column)
        }
    }

    private fun addColumnSql(spec: TableSpec, column: String): String {
        val col = spec.columns.firstOrNull { it.name == column }
            ?: return "-- ${spec.name}.$column 不在期望结构里，无需补列"
        return buildString {
            append("ALTER TABLE `${spec.name}` ADD COLUMN `${col.name}` ${col.type}")
            if (col.notNull) {
                append(" NOT NULL DEFAULT ").append(col.default ?: fallbackDefault(col.type))
            } else if (col.default != null) {
                append(" DEFAULT ").append(col.default)
            }
            append(';')
        }
    }

    /**
     * 整表重建脚本。
     *
     * 只搬**期望结构里存在**的列；旧表若还缺别的列，那几条会各自单独报，
     * 修复时先补列再回来跑这段，或者把缺的那列从 INSERT 列表里去掉。
     */
    private fun rebuildSql(spec: TableSpec, column: String): String {
        val cols = spec.columns.joinToString(", ") { "`${it.name}`" }
        return buildString {
            appendLine("-- ${spec.name}.$column 的声明结构与当前 Room 版本不一致；SQLite 不支持改列，按 Room 的做法整表重建：")
            appendLine("-- 跑之前先把库文件和 -wal / -shm 拷一份出来")
            appendLine("PRAGMA foreign_keys = OFF;")
            appendLine("BEGIN TRANSACTION;")
            appendLine(spec.createSql("${spec.name}__new"))
            appendLine("INSERT INTO `${spec.name}__new` ($cols) SELECT $cols FROM `${spec.name}`;")
            appendLine("DROP TABLE `${spec.name}`;")
            appendLine("ALTER TABLE `${spec.name}__new` RENAME TO `${spec.name}`;")
            appendLine("COMMIT;")
            append("PRAGMA foreign_keys = ON;")
        }
    }

    private fun fallbackDefault(type: String): String =
        if (type.equals("INTEGER", ignoreCase = true) || type.equals("REAL", ignoreCase = true)) "0" else "''"

    // ===== 文案 =====

    fun stateLabel(context: Context, state: State): String = context.getString(
        when (state) {
            State.OK -> R.string.f093_state_ok
            State.ABSENT -> R.string.f093_state_absent
            State.ERROR -> R.string.f093_state_error
        }
    )

    fun kindLabel(context: Context, kind: Kind): String = context.getString(
        when (kind) {
            Kind.MISSING -> R.string.f093_kind_missing
            Kind.LOW_VERSION -> R.string.f093_kind_low_version
        }
    )

    fun detailLabel(context: Context, detail: Detail): String = context.getString(
        when (detail) {
            Detail.TABLE_MISSING -> R.string.f093_detail_table_missing
            Detail.COLUMN_MISSING -> R.string.f093_detail_column_missing
            Detail.VERSION_LOW -> R.string.f093_detail_version_low
            Detail.VERSION_HIGH -> R.string.f093_detail_version_high
            Detail.TYPE_CHANGED -> R.string.f093_detail_type_changed
            Detail.NULLABILITY_CHANGED -> R.string.f093_detail_nullability_changed
        }
    )

    fun tableLabel(context: Context, table: String): String =
        table.ifBlank { context.getString(R.string.f093_table_database) }

    fun columnLabel(context: Context, column: String): String =
        column.ifBlank { context.getString(R.string.f093_column_none) }

    // ===== 导出 =====

    /**
     * 导出成一份纯文本报告。
     *
     * 纯文本而不是 JSON/HTML：这份报告的最后一步通常是「发给别人看」或者「贴到 issue 里」，
     * 文本不需要任何工具就能读。内容固定三段——库信息、差异清单、建议修复 SQL。
     */
    fun exportText(context: Context): String {
        val app = context.applicationContext ?: context
        val report = scan(app)
        val sb = StringBuilder(1024)

        sb.append(context.getString(R.string.f093_report_title)).append('\n')
        sb.append(context.getString(R.string.f093_report_time, stamp("yyyy-MM-dd HH:mm:ss"))).append('\n')
        sb.append(context.getString(R.string.f093_report_path, report.path)).append('\n')
        sb.append(context.getString(R.string.f093_report_state, stateLabel(context, report.state))).append('\n')
        if (report.state == State.OK) {
            sb.append(
                context.getString(R.string.f093_report_version, report.dbVersion, report.expectedVersion)
            ).append('\n')
        }
        sb.append(context.getString(R.string.f093_report_counts, report.tableCount, report.columnCount)).append('\n')

        sb.append('\n').append(context.getString(R.string.f093_report_conclusion)).append('\n')
        sb.append(conclusionText(context, report)).append('\n')

        sb.append('\n').append(context.getString(R.string.f093_report_issues_head)).append('\n')
        if (report.issues.isEmpty()) {
            sb.append(context.getString(R.string.f093_report_empty)).append('\n')
        } else {
            for (issue in report.issues) sb.append(issueText(context, issue)).append('\n')
        }

        sb.append('\n').append(context.getString(R.string.f093_report_sql_head)).append('\n')
        if (report.issues.isEmpty()) {
            sb.append(context.getString(R.string.f093_report_empty)).append('\n')
        } else {
            for (issue in report.issues) sb.append(fixSql(issue)).append("\n\n")
        }

        return sb.toString()
    }

    private fun conclusionText(context: Context, report: Report): String = when (report.state) {
        State.ABSENT -> context.getString(R.string.f093_report_conclusion_absent)
        State.ERROR -> context.getString(R.string.f093_report_conclusion_error, report.error)
        State.OK -> if (report.issues.isEmpty()) {
            context.getString(R.string.f093_report_conclusion_ok)
        } else {
            context.getString(R.string.f093_report_conclusion_issues, report.issues.size)
        }
    }

    private fun issueText(context: Context, issue: Issue): String = context.getString(
        R.string.f093_report_issue_line,
        kindLabel(context, issue.kind),
        tableLabel(context, issue.table),
        columnLabel(context, issue.column),
        issue.expected.ifBlank { context.getString(R.string.f093_report_na) },
        issue.actual.ifBlank { context.getString(R.string.f093_report_na) },
        detailLabel(context, issue.detail)
    )

    /**
     * 落盘一份报告，返回文件；写不出去返回 null（页面给一句提示，不抛）。
     *
     * 目录优先外部私有目录（`Android/data/<包名>/files/report`，文件管理器能翻到），
     * 外置存储没挂上就退回内部 `filesDir/report`：能在内部导出，也好过导不出来。
     */
    fun writeReport(context: Context, text: String): File? {
        val app = context.applicationContext ?: context
        val dir = runCatching { app.getExternalFilesDir(REPORT_DIR) }.getOrNull()
            ?: File(app.filesDir, REPORT_DIR)
        if (!runCatching { dir.mkdirs() || dir.isDirectory }.getOrDefault(false)) return null
        return runCatching {
            val name = "f093-db-report-" + stamp("yyyyMMdd-HHmmss") + ".txt"
            File(dir, name).also { it.writeText(text, Charsets.UTF_8) }
        }.getOrNull()
    }

    private fun stamp(pattern: String): String =
        SimpleDateFormat(pattern, Locale.US).format(Date())

    private fun readable(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
}