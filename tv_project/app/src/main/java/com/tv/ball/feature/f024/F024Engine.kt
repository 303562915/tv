package com.tv.ball.feature.f024

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

/**
 * F024 SAF 目录授权的真实能力层。
 *
 * 只做三件事：把 OpenDocumentTree 拿到的 tree Uri 转成持久授权
 * （[ContentResolver.takePersistableUriPermission]）、撤销持久授权
 * （[ContentResolver.releasePersistableUriPermission]），以及列出当前
 * 已被系统记住的授权（[ContentResolver.persistedUriPermissions]）。
 *
 * 持久授权写在 system_server 侧，和进程无关，所以 App 重启、设备重启后
 * 只要没被撤销就仍然有效。
 */
object F024Engine {

    /** 只用读权限申请，写权限需要的话在同一串 flags 里追加。 */
    private const val READ_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION
    private const val WRITE_FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** 一条已持久化的目录授权。 */
    data class Grant(
        val uri: Uri,
        val isRead: Boolean,
        val isWrite: Boolean
    ) {
        val uriString: String get() = uri.toString()
    }

    /** 操作结果：成功为 ok=true；失败时 [reason] 带上系统给的原因。 */
    data class Outcome(val ok: Boolean, val reason: String = "") {
        val isSuccess: Boolean get() = ok
    }

    /**
     * 申请持久读授权。
     *
     * `takePersistableUriPermission` 在 Uri 来自普通 ACTION_OPEN_DOCUMENT /
     * 缺少 FLAG_GRANT_PERSISTABLE_URI_PERMISSION 时会抛 SecurityException，
     * 所以必须接住并回传原因，不能让它冒到 UI。
     */
    fun persist(resolver: ContentResolver, uri: Uri): Outcome =
        try {
            resolver.takePersistableUriPermission(uri, READ_FLAGS)
            Outcome(true)
        } catch (e: SecurityException) {
            Outcome(false, e.message ?: "SecurityException: 该 Uri 不支持持久化授权")
        }

    /**
     * 撤销持久授权。
     *
     * 系统对「没持有过的 flag」同样抛 SecurityException，因此先按读权限撤销，
     * 失败再按读写撤销兜底，两次都失败才返回原因。
     */
    fun release(resolver: ContentResolver, uri: Uri): Outcome {
        try {
            resolver.releasePersistableUriPermission(uri, READ_FLAGS)
            return Outcome(true)
        } catch (e: SecurityException) {
            val firstReason = e.message ?: "SecurityException"
            return try {
                resolver.releasePersistableUriPermission(uri, WRITE_FLAGS)
                Outcome(true)
            } catch (second: SecurityException) {
                Outcome(false, second.message ?: firstReason)
            }
        }
    }

    /** 列出系统当前记住的所有目录授权（只保留有读或写权限的条目）。 */
    fun persistedGrants(resolver: ContentResolver): List<Grant> =
        resolver.persistedUriPermissions
            .asSequence()
            .filter { it.isReadPermission || it.isWritePermission }
            .map { Grant(it.uri, it.isReadPermission, it.isWritePermission) }
            .sortedBy { it.uri.toString() }
            .toList()

    /** 已持久化授权的数量。 */
    fun count(resolver: ContentResolver): Int = persistedGrants(resolver).size

    /** 某个 Uri 是否已经在持久授权列表里。 */
    fun isGranted(resolver: ContentResolver, uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == uri && (it.isReadPermission || it.isWritePermission) }
}