package com.tv.ball.feature.f072

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * F072「备份恢复与校验」的真实能力层。
 *
 * 备份包就是一个 zip，根目录放 manifest.json：
 *
 * ```json
 * {
 *   "schema": 1,
 *   "version": "1.2.0",
 *   "createdAt": "2026-11-20T10:00:00Z",
 *   "entries": [
 *     { "path": "shared_prefs/settings.xml", "size": 1234, "sha256": "…" }
 *   ]
 * }
 * ```
 *
 * 两段职责分得很开：
 * - [inspect] 只读：读清单 + 逐条比对 sha256，任何一条对不上就把 [InspectResult.checksumOk] 置 false，
 *   并把原因写进 [InspectResult.problems]；
 * - [restore] 先 inspect 再动手：清单读不出、校验不过、版本不兼容、目标目录不可用，一律一个字节都不写。
 *
 * 恢复的安全边界有两条：
 * 1. 条目路径必须落在目标目录内部（拒绝绝对路径、盘符与 `..`，防 zip slip）；
 * 2. 覆盖前把原文件原样复制到 `rollback-<时间>` 目录；中途任何异常都把快照复制回去，
 *    并把这次新建的文件删掉，让目标目录回到恢复前的状态。
 *
 * 对外方法都是挂起函数，内部切 [Dispatchers.IO]，返回时已经回到调用方线程。
 * 版本闸门只有一个入口 [isCompatible]：主版本必须相同，次版本不高于本机支持上限。
 */
object F072Engine {

    /** 清单文件名，固定在 zip 根目录。 */
    const val MANIFEST_NAME = "manifest.json"

    private const val TAG = "F072Engine"

    /** 本机认的数据格式：主版本相等，次版本不超过上限。 */
    private const val SUPPORTED_MAJOR = 1
    private const val MIN_SUPPORTED_MINOR = 0
    private const val MAX_SUPPORTED_MINOR = 3

    private const val BUFFER_SIZE = 64 * 1024

    /** 临时后缀：先写整份再改名，避免断电/异常留下半个文件。 */
    private const val TEMP_SUFFIX = ".f072-tmp"

    private val HEX = "0123456789abcdef".toCharArray()

    /** 清单里声明的一个条目，以及它在 zip 里核对出来的结果。 */
    data class EntryInfo(
        val path: String,
        val size: Long,
        val sha256: String,
        val verified: Boolean,
        val note: String? = null
    )

    /** 一次校验的完整结论：清单元信息 + 逐条结果 + 是否通过 + 问题列表。 */
    data class InspectResult(
        val version: String?,
        val createdAt: String?,
        val entries: List<EntryInfo>,
        val checksumOk: Boolean,
        val problems: List<String>
    ) {
        /** 清单读出来了、条目非空、哈希全对，才允许往下走。 */
        val restorable: Boolean get() = checksumOk && entries.isNotEmpty()
    }

    /** 一次恢复的统计：写了几个、跳过几个、回滚目录在哪。 */
    data class RestoreResult(
        val restored: Int,
        val skipped: Int,
        val rollbackDir: String?,
        val dryRun: Boolean = false,
        val rolledBack: Boolean = false,
        val problems: List<String> = emptyList()
    )

    // ===== 版本闸门 =====

    /**
     * 数据格式是否被本机接受。
     *
     * 规则故意保守：只看主次版本，补丁号与后缀（`-rc1`、`+build7`）一律忽略；
     * 解析不出数字（null、空串、"beta"）当作不兼容——宁可不恢复，也不要把半坏数据写进去。
     */
    fun isCompatible(version: String?): Boolean {
        val parsed = parseVersion(version) ?: return false
        val (major, minor) = parsed
        return major == SUPPORTED_MAJOR && minor in MIN_SUPPORTED_MINOR..MAX_SUPPORTED_MINOR
    }

    /** 界面上「本机支持 1.0 ~ 1.3」这句里的区间，集中在这里拼，避免 UI 写死数字。 */
    fun supportedRangeLabel(): String =
        "$SUPPORTED_MAJOR.$MIN_SUPPORTED_MINOR ~ $SUPPORTED_MAJOR.$MAX_SUPPORTED_MINOR"

    // ===== 对外入口 =====

    /** 读清单并逐条校验 sha256；不修改任何文件。 */
    suspend fun inspect(zipFile: File): InspectResult =
        withContext(Dispatchers.IO) { inspectBlocking(zipFile) }

    /**
     * 把备份恢复到 [targetDirs] 的第一个目录。
     *
     * @param dryRun true 只算计划、不落盘，返回的 [RestoreResult.restored] 是「将写入」的条数；
     * @param rollbackRoot 回滚目录的父目录，默认放在目标目录的同级，方便一眼看到。
     */
    suspend fun restore(
        zipFile: File,
        targetDirs: List<File>,
        dryRun: Boolean,
        rollbackRoot: File? = null
    ): RestoreResult = withContext(Dispatchers.IO) {
        restoreBlocking(zipFile, targetDirs, dryRun, rollbackRoot)
    }

    // ===== 校验 =====

    private fun inspectBlocking(zipFile: File): InspectResult {
        if (!zipFile.isFile) {
            return InspectResult(null, null, emptyList(), false, listOf("备份文件不存在：" + zipFile.name))
        }
        val archive = try {
            ZipFile(zipFile)
        } catch (e: IOException) {
            Log.w(TAG, "打不开 zip", e)
            return InspectResult(
                null, null, emptyList(), false,
                listOf("不是可读取的 zip：" + (e.message ?: e.javaClass.simpleName))
            )
        }

        val problems = ArrayList<String>()
        val entries = ArrayList<EntryInfo>()
        var version: String? = null
        var createdAt: String? = null

        archive.use { zip ->
            val manifestEntry = zip.getEntry(MANIFEST_NAME)
            if (manifestEntry == null) {
                problems += "缺少 " + MANIFEST_NAME
            } else {
                val root = readManifest(zip, manifestEntry, problems)
                if (root != null) {
                    version = root.optString("version").trim().takeIf { it.isNotEmpty() }
                    createdAt = root.optString("createdAt").trim().takeIf { it.isNotEmpty() }
                    if (version == null) problems += "清单里没有 version"
                    if (createdAt == null) problems += "清单里没有 createdAt"
                    readEntries(zip, root, entries, problems)
                }
            }
            // 清单外条目：不致命（恢复时按清单走），但要让用户看见包里多了东西
            val declared = entries.map { it.path }.toHashSet()
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name != MANIFEST_NAME && it.name !in declared }
                .forEach { problems += "zip 里有清单外条目：" + it.name }
        }

        val checksumOk = version != null && entries.isNotEmpty() && entries.all { it.verified }
        return InspectResult(version, createdAt, entries, checksumOk, problems)
    }

    /** 读 manifest.json 文本并解析成 JSONObject；失败时把原因写进 [problems] 并返回 null。 */
    private fun readManifest(zip: ZipFile, entry: ZipEntry, problems: MutableList<String>): JSONObject? {
        val raw = try {
            zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: IOException) {
            Log.w(TAG, "读 manifest 失败", e)
            problems += "读不出 " + MANIFEST_NAME + "：" + (e.message ?: e.javaClass.simpleName)
            return null
        }
        return try {
            JSONObject(raw)
        } catch (e: JSONException) {
            Log.w(TAG, "manifest 不是 JSON", e)
            problems += MANIFEST_NAME + " 不是合法 JSON"
            null
        }
    }

    /** 逐条比对清单里的 entries；每一条都会产出一个 [EntryInfo]，问题同步进 [problems]。 */
    private fun readEntries(
        zip: ZipFile,
        root: JSONObject,
        entries: MutableList<EntryInfo>,
        problems: MutableList<String>
    ) {
        val declared = root.optJSONArray("entries")
        if (declared == null || declared.length() == 0) {
            problems += "清单里没有 entries"
            return
        }
        for (index in 0 until declared.length()) {
            val item = declared.optJSONObject(index) ?: continue
            val path = item.optString("path").trim()
            if (path.isEmpty()) {
                problems += "第 " + (index + 1) + " 条缺少 path"
                continue
            }
            checkEntry(zip, path, item.optLong("size", -1L), item.optString("sha256"), entries, problems)
        }
    }

    private fun checkEntry(
        zip: ZipFile,
        path: String,
        declaredSize: Long,
        declaredSha: String,
        entries: MutableList<EntryInfo>,
        problems: MutableList<String>
    ) {
        val zipEntry = zip.getEntry(path)
        if (zipEntry == null) {
            entries += EntryInfo(path, declaredSize, declaredSha, verified = false, note = "压缩包里没有这个条目")
            problems += "缺条目：" + path
            return
        }
        if (!isSafeRelative(path)) {
            entries += EntryInfo(path, declaredSize, declaredSha, verified = false, note = "路径越界")
            problems += "条目路径越界：" + path
            return
        }
        val actual = try {
            zip.getInputStream(zipEntry).use { sha256(it) }
        } catch (e: IOException) {
            Log.w(TAG, "算 sha256 失败：" + path, e)
            entries += EntryInfo(path, declaredSize, declaredSha, verified = false, note = "读取失败")
            problems += "算不出 " + path + " 的 sha256"
            return
        }
        val matches = normalizeSha(declaredSha) == actual
        val note = when {
            !matches -> "sha256 不匹配"
            declaredSize >= 0 && zipEntry.size >= 0 && declaredSize != zipEntry.size -> "大小与清单不符"
            else -> null
        }
        entries += EntryInfo(path, declaredSize, declaredSha, verified = matches, note = note)
        when {
            !matches -> problems += "校验失败：" + path
            note != null -> problems += path + "：" + note
        }
    }

    // ===== 恢复 =====

    private fun restoreBlocking(
        zipFile: File,
        targetDirs: List<File>,
        dryRun: Boolean,
        rollbackRoot: File?
    ): RestoreResult {
        val inspected = inspectBlocking(zipFile)
        val problems = ArrayList(inspected.problems)
        val total = inspected.entries.size

        if (!inspected.restorable) {
            problems += "校验未通过，未执行恢复"
            return RestoreResult(0, total, null, dryRun, false, problems)
        }
        if (!isCompatible(inspected.version)) {
            problems += "版本不兼容，未执行恢复"
            return RestoreResult(0, total, null, dryRun, false, problems)
        }
        val root = targetDirs.firstOrNull()
        if (root == null) {
            problems += "没有可用的恢复目标目录"
            return RestoreResult(0, total, null, dryRun, false, problems)
        }

        val plan = ArrayList<Pair<EntryInfo, File>>()
        var skipped = 0
        for (entry in inspected.entries) {
            if (!isSafeRelative(entry.path)) {
                skipped++
                problems += "跳过越界路径：" + entry.path
                continue
            }
            val dest = File(root, entry.path)
            if (dest.isDirectory) {
                skipped++
                problems += "目标是目录，跳过：" + entry.path
                continue
            }
            // 目标已经一致就不动它：既省 IO，也让「跳过」这个统计有意义
            if (dest.isFile && sha256Of(dest) == normalizeSha(entry.sha256)) {
                skipped++
                continue
            }
            plan += entry to dest
        }

        if (plan.isEmpty()) {
            return RestoreResult(0, skipped, null, dryRun, false, problems)
        }
        if (dryRun) {
            return RestoreResult(plan.size, skipped, null, true, false, problems)
        }

        val overwrites = plan.filter { it.second.isFile }
        var rollbackDir: File? = null
        if (overwrites.isNotEmpty()) {
            val dir = createRollbackDir(rollbackRoot ?: root.parentFile ?: root, problems)
                ?: return RestoreResult(0, skipped + plan.size, null, false, false, problems)
            rollbackDir = dir
            if (!snapshot(overwrites, dir, problems)) {
                // 快照没做全都还没写，直接放弃；回滚目录留着给人看是哪些文件读不出来
                return RestoreResult(0, skipped + plan.size, dir.absolutePath, false, false, problems)
            }
        }

        var restored = 0
        val created = ArrayList<File>()
        try {
            ZipFile(zipFile).use { zip ->
                for ((entry, dest) in plan) {
                    val zipEntry = zip.getEntry(entry.path)
                        ?: throw IOException("压缩包里没有条目 " + entry.path)
                    val existed = dest.exists()
                    dest.parentFile?.mkdirs()
                    writeEntry(zip, zipEntry, dest)
                    if (!existed) created += dest
                    restored++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "恢复中断，开始回滚", e)
            problems += "恢复中断：" + (e.message ?: e.javaClass.simpleName)
            val reverted = revert(rollbackDir, plan, created, problems)
            if (!reverted) problems += "回滚没有完全成功，请按回滚目录手工核对"
            return RestoreResult(0, skipped + (plan.size - restored), rollbackDir?.absolutePath, false, true, problems)
        }

        return RestoreResult(restored, skipped, rollbackDir?.absolutePath, false, false, problems)
    }

    /**
     * 建 `rollback-<yyyyMMdd-HHmmss>` 目录；同一秒内重复恢复时补 `-2`、`-3`，
     * 绝不把两次恢复的快照混在同一个目录里。
     */
    private fun createRollbackDir(parent: File, problems: MutableList<String>): File? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var candidate = File(parent, "rollback-" + stamp)
        var index = 2
        while (candidate.exists()) {
            candidate = File(parent, "rollback-" + stamp + "-" + index)
            index++
        }
        val made = candidate.isDirectory || candidate.mkdirs()
        if (!made) {
            problems += "建不出回滚目录：" + candidate.absolutePath
            return null
        }
        return candidate
    }

    /** 把将要被覆盖的原文件按相对路径复制进回滚目录；任何一个失败就返回 false。 */
    private fun snapshot(
        overwrites: List<Pair<EntryInfo, File>>,
        rollbackDir: File,
        problems: MutableList<String>
    ): Boolean {
        for ((entry, dest) in overwrites) {
            val backup = File(rollbackDir, entry.path)
            try {
                backup.parentFile?.mkdirs()
                dest.copyTo(backup, overwrite = true)
            } catch (e: IOException) {
                Log.w(TAG, "快照失败：" + entry.path, e)
                problems += "快照失败：" + entry.path + "（" + (e.message ?: e.javaClass.simpleName) + "）"
                return false
            }
        }
        return true
    }

    /** 回滚：有快照的复制回去，这次新建的删掉；返回是否全部还原成功。 */
    private fun revert(
        rollbackDir: File?,
        plan: List<Pair<EntryInfo, File>>,
        created: List<File>,
        problems: MutableList<String>
    ): Boolean {
        var ok = true
        for ((entry, dest) in plan) {
            val backup = rollbackDir?.let { File(it, entry.path) }
            try {
                if (backup != null && backup.isFile) {
                    dest.parentFile?.mkdirs()
                    backup.copyTo(dest, overwrite = true)
                } else if (created.contains(dest) && dest.exists() && !dest.delete()) {
                    ok = false
                    problems += "回滚时删不掉新写入的文件：" + entry.path
                }
            } catch (e: IOException) {
                Log.w(TAG, "回滚失败：" + entry.path, e)
                ok = false
                problems += "回滚失败：" + entry.path + "（" + (e.message ?: e.javaClass.simpleName) + "）"
            }
        }
        return ok
    }

    /**
     * 写一个条目：先落 `.f072-tmp`，再改名到目标位置。
     *
     * 改名在同一个目录内是原子的，失败时退化成「删旧 + 再改名」/「直接覆盖复制」，
     * 目标文件永远不会处于写了一半的状态。
     */
    private fun writeEntry(zip: ZipFile, zipEntry: ZipEntry, dest: File): Long {
        val temp = File(dest.parentFile, dest.name + TEMP_SUFFIX)
        var total = 0L
        zip.getInputStream(zipEntry).use { input ->
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    total += read
                }
                output.flush()
            }
        }
        if (!temp.renameTo(dest)) {
            // 个别文件系统不允许 rename 覆盖已存在文件，退一步再来
            if (dest.exists()) dest.delete()
            if (!temp.renameTo(dest)) {
                temp.copyTo(dest, overwrite = true)
                temp.delete()
            }
        }
        return total
    }

    // ===== 小工具 =====

    /** 只接受相对路径：拒绝绝对路径、盘符、`.` 与 `..`。 */
    private fun isSafeRelative(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        if (normalized.startsWith("/") || normalized.contains(":")) return false
        return normalized.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    private fun normalizeSha(raw: String): String = raw.trim().lowercase(Locale.US)

    private fun parseVersion(raw: String?): Pair<Int, Int>? {
        val text = raw?.trim()?.removePrefix("v")?.removePrefix("V") ?: return null
        if (text.isEmpty()) return null
        val segments = text.split('.', '-', '+')
        val major = segments.getOrNull(0)?.toIntOrNull() ?: return null
        val minor = segments.getOrNull(1)?.toIntOrNull() ?: 0
        return major to minor
    }

    private fun sha256Of(file: File): String? = try {
        file.inputStream().use { sha256(it) }
    } catch (e: IOException) {
        Log.w(TAG, "算不出文件 sha256：" + file.name, e)
        null
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }
}
