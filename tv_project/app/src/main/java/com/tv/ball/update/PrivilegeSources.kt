package com.tv.ball.update

import android.content.Context

/**
 * 应用内更新能借到的「权限来源」——安装链路和关于页的「权限来源」卡片共用这一份定义，
 * 保证「页面上写的顺序」和「实际尝试的顺序」永远一致。
 *
 * 优先级（写死，别再分叉）：
 *  1. [PrivilegeKind.ROOT]      root（面具 / KernelSU / APatch）—— `su -c pm install`
 *  2. [PrivilegeKind.SHIZUKU]   Shizuku —— UserService 里的 `pm install`
 *  3. [PrivilegeKind.STELLAR]   Stellar —— 与 Shizuku 共用 binder，只是来源不同
 *  4. [PrivilegeKind.DHIZUKU]   Dhizuku —— 设备所有者权限，服务器进程里跑 `pm install`
 *  5. [PrivilegeKind.BUILTIN]   自带安装器 —— 系统安装器 + 未知来源权限，永远作最后的保底
 */
enum class PrivilegeKind {
    ROOT,
    SHIZUKU,
    STELLAR,
    DHIZUKU,
    BUILTIN
}

/** 一条来源当前的状态，页面上就是这几种字样。 */
enum class PrivilegeState {
    /** 这次安装正在用的就是它（按 [PrivilegeKind] 顺序第一条「已就绪」）。 */
    IN_USE,

    /** 活着、已授权，随时能静默装。 */
    READY,

    /** 装了但没授权 / 服务没跑 / 没激活——点一下能去申请。 */
    NEED_AUTH,

    /** 没装。 */
    NOT_INSTALLED,

    /** 装了但这条来源用不上（例如 Stellar 和 Shizuku 同时装时 binder 归 Shizuku）。 */
    UNAVAILABLE
}

/**
 * 一条来源的探测结果。
 *
 * [detail] 直接给页面显示，所以这里只说「看到了什么」，不写建议文案。
 */
data class PrivilegeSource(
    val kind: PrivilegeKind,
    val state: PrivilegeState,
    val detail: String
)

object PrivilegeSources {

    /**
     * 探一遍所有来源（[refreshRoot] 为 true 时重跑 `su -c id`）。
     *
     * 会做 binder 事务（Shizuku ping / Dhizuku init），**不要在 UI 线程调**。
     * 返回顺序 = 安装链路的尝试顺序；第一条 READY 被标成 [PrivilegeState.IN_USE]。
     */
    fun detect(context: Context, refreshRoot: Boolean = false): List<PrivilegeSource> {
        val shizukuInstalled = ShizukuBridge.isPackageInstalled(context, ShizukuBridge.SHIZUKU_PACKAGE)
        val stellarInstalled = ShizukuBridge.isPackageInstalled(context, ShizukuBridge.STELLAR_PACKAGE)
        // 两者共用同一个 binder：谁在提供它分不出来，所以只探一次
        val shellAlive = ShizukuBridge.isAvailable()
        val shellGranted = shellAlive && ShizukuBridge.hasPermission()

        val shizuku = when {
            !shizukuInstalled -> PrivilegeSource(
                PrivilegeKind.SHIZUKU,
                PrivilegeState.NOT_INSTALLED,
                "未安装 Shizuku，装上可免弹窗安装更新"
            )
            shellGranted -> PrivilegeSource(
                PrivilegeKind.SHIZUKU,
                PrivilegeState.READY,
                "服务已运行、已授权本应用，可静默安装"
            )
            shellAlive -> PrivilegeSource(
                PrivilegeKind.SHIZUKU,
                PrivilegeState.NEED_AUTH,
                "服务已运行，本应用还没授权"
            )
            else -> PrivilegeSource(
                PrivilegeKind.SHIZUKU,
                PrivilegeState.NEED_AUTH,
                "已安装，服务没在运行（先在 Shizuku 里启动服务）"
            )
        }

        val stellar = when {
            !stellarInstalled -> PrivilegeSource(
                PrivilegeKind.STELLAR,
                PrivilegeState.NOT_INSTALLED,
                "未安装 Stellar（Shizuku 分支，授权方式与 Shizuku 一样）"
            )
            // 两个都装时 binder 归 Shizuku，Stellar 只作备选，不再单列一条可用通道
            shizukuInstalled -> PrivilegeSource(
                PrivilegeKind.STELLAR,
                PrivilegeState.UNAVAILABLE,
                "已安装，与 Shizuku 共用同一套 binder（现在由 Shizuku 提供）"
            )
            shellGranted -> PrivilegeSource(
                PrivilegeKind.STELLAR,
                PrivilegeState.READY,
                "服务已运行、已授权本应用（Stellar 兼容层提供 binder）"
            )
            shellAlive -> PrivilegeSource(
                PrivilegeKind.STELLAR,
                PrivilegeState.NEED_AUTH,
                "服务已运行，本应用还没授权"
            )
            else -> PrivilegeSource(
                PrivilegeKind.STELLAR,
                PrivilegeState.NEED_AUTH,
                "已安装，服务没在运行（先在 Stellar 里激活）"
            )
        }

        val sources = listOf(
            detectRoot(context, refreshRoot),
            shizuku,
            stellar,
            detectDhizuku(context),
            detectBuiltin(context)
        )
        val active = sources.firstOrNull { it.state == PrivilegeState.READY }?.kind
        return sources.map {
            if (it.kind == active) it.copy(state = PrivilegeState.IN_USE) else it
        }
    }

    /** 这次自动安装会用哪条通道；全都没授权时是 [PrivilegeKind.BUILTIN]。 */
    fun activeKind(context: Context): PrivilegeKind =
        detect(context).firstOrNull { it.state == PrivilegeState.IN_USE }?.kind ?: PrivilegeKind.BUILTIN

    private fun detectRoot(context: Context, refresh: Boolean): PrivilegeSource {
        if (!RootBridge.isAvailable(refresh)) {
            return PrivilegeSource(
                PrivilegeKind.ROOT,
                PrivilegeState.NOT_INSTALLED,
                "没有拿到 root（su 不存在或未授权）"
            )
        }
        val manager = RootBridge.managerLabel(context) ?: "su"
        return PrivilegeSource(PrivilegeKind.ROOT, PrivilegeState.READY, "$manager 已授权，su 里直接装")
    }

    private fun detectDhizuku(context: Context): PrivilegeSource {
        if (!DhizukuBridge.isInstalled(context)) {
            return PrivilegeSource(
                PrivilegeKind.DHIZUKU,
                PrivilegeState.NOT_INSTALLED,
                "未安装 Dhizuku（无 root 时靠设备所有者权限装应用）"
            )
        }
        if (!DhizukuBridge.isConnected(context)) {
            return PrivilegeSource(
                PrivilegeKind.DHIZUKU,
                PrivilegeState.NEED_AUTH,
                "已安装，但没激活成设备所有者"
            )
        }
        if (!DhizukuBridge.isPermissionGranted(context)) {
            return PrivilegeSource(
                PrivilegeKind.DHIZUKU,
                PrivilegeState.NEED_AUTH,
                "已激活设备所有者，本应用还没授权"
            )
        }
        val version = DhizukuBridge.versionName(context)
        return PrivilegeSource(
            PrivilegeKind.DHIZUKU,
            PrivilegeState.READY,
            if (version.isNullOrBlank()) "已授权，设备所有者权限可用" else "已授权，服务器 $version"
        )
    }

    private fun detectBuiltin(context: Context): PrivilegeSource {
        val allowed = ApkInstaller.canRequestInstall(context)
        return PrivilegeSource(
            PrivilegeKind.BUILTIN,
            if (allowed) PrivilegeState.READY else PrivilegeState.NEED_AUTH,
            if (allowed) "系统安装器，弹一次确认就能装" else "要在「安装未知应用」里允许本应用"
        )
    }
}
