package com.tv.ball.update

import android.content.Context
import java.io.File

/**
 * root 通道：`su` 里直接 `pm install`，是安装链路里最高优先级的一条（面具 / KernelSU / APatch 都一样）。
 *
 * 两件事必须说清：
 *  1. **探测要缓存**：`su -c id` 在面具上会弹一次授权框，每次打开设置页都弹没人受得了，
 *     所以进程内只探一次，需要重探时显式 [isAvailable] 传 `refresh = true`；
 *  2. **谁给的权限要能报出来**：设置页的「权限来源」要写「面具 / KernelSU / APatch」，
 *     靠已装的管理器包名判断，判断不出来就只说「su 可用」。
 */
object RootBridge {

    /** 常见的 root 管理器：包名 → 展示名。只用于「权限来源」那一行，不影响能不能用。 */
    private val MANAGERS = listOf(
        "com.topjohnwu.magisk" to "Magisk（面具）",
        "me.weishu.kernelsu" to "KernelSU",
        "com.rifsxd.ksunext" to "KernelSU Next",
        "me.bmax.apatch" to "APatch",
        "eu.chainfire.supersu" to "SuperSU",
        "com.kingroot.kinguser" to "KingRoot"
    )

    @Volatile
    private var cached: Boolean? = null

    /**
     * 有没有 root。默认读进程内缓存，[refresh] 时重新探一次。
     *
     * 探测方式仍是 `su -c id`：只认 `uid=0`，其它（命令不存在、被拒、超时）一律当没有，
     * 安装链路因此永远不会卡在这条通道上。
     */
    fun isAvailable(refresh: Boolean = false): Boolean {
        val hit = cached
        if (!refresh && hit != null) return hit
        val ok = probe()
        cached = ok
        return ok
    }

    /** 已装的 root 管理器名；判断不出来返回 null（有 su 但包名不认识）。 */
    fun managerLabel(context: Context): String? = MANAGERS.firstOrNull { (pkg, _) ->
        installed(context, pkg)
    }?.second

    /** 在 root 身份下装 apk；成功返回 true。 */
    fun install(apk: File): Boolean {
        if (!apk.isFile || apk.length() <= 0) return false
        val quoted = apk.absolutePath.replace("'", "'\\''")

        // 先 pm install，再 cmd package install：少数 ROM 上 pm 被裁掉了，cmd 还在
        for (command in listOf(
            "pm install -r -d '$quoted'",
            "cmd package install -r -d '$quoted'"
        )) {
            val output = execSu(command) ?: continue
            if (output.contains("Success")) return true
        }
        return false
    }

    private fun probe(): Boolean {
        val output = execSu("id") ?: return false
        return output.contains("uid=0")
    }

    /** `su -c <命令>` 的输出（stdout + stderr）；命令跑不起来返回 null。 */
    private fun execSu(command: String): String? = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val out = process.inputStream.bufferedReader().use { it.readText() }
        val err = process.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        process.waitFor()
        out + err
    }.getOrNull()

    private fun installed(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
}
