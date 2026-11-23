package com.tv.ball.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import java.io.File

/**
 * Dhizuku 通道：把「设备所有者（DeviceOwner / ProfileOwner）」权限借给本应用，
 * 零 root、零 adb 也能装应用（对应 [https://github.com/iamr0s/Dhizuku]）。
 *
 * 全程反射接入 `com.rosan.dhizuku.api.Dhizuku`，**不引入额外依赖**：设备上没有 Dhizuku 时
 * `Class.forName` 直接失败，链路继续往下走，不会因为缺库崩在启动上。
 *
 * 三件事：
 *  1. [isConnected]：`Dhizuku.init(context)` 返回 true，说明装着、已激活、拿到服务器 binder；
 *  2. [requestPermission]：Dhizuku 没提供「静默授权」，只能拉起它自己的授权页
 *     （`com.rosan.dhizuku.action.request.permission` + 一个请求结果回调 Binder）。
 *     回调的 AIDL 在这里手写 transact，省掉整个 AAR；
 *  3. [install]：Dhizuku 服务器持有设备所有者权限，用它起的 remote process 跑 `pm install`。
 *     服务器是 root / shell 拉起时就是静默装；不是的话把原因交回上层继续降级。
 */
object DhizukuBridge {

    const val PACKAGE = "com.rosan.dhizuku"

    private const val API_ENTRY = "com.rosan.dhizuku.api.Dhizuku"
    private const val ACTION_REQUEST_PERMISSION = "$PACKAGE.action.request.permission"
    private const val EXTRA_UID = "uid"
    private const val EXTRA_REQUEST_BINDER = "request_permission_binder"

    private val apiClass: Class<*>? by lazy {
        runCatching { Class.forName(API_ENTRY) }.getOrNull()
    }

    /** init 一次就够；binder 掉了（Dhizuku 被杀）由 `isPermissionGranted` 抛错兜住。 */
    @Volatile
    private var connected = false

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    /** Dhizuku 装着、已激活为设备所有者、能拿到服务器 binder。 */
    fun isConnected(context: Context): Boolean {
        val clazz = apiClass ?: return false
        if (connected) return true
        val ok = runCatching {
            clazz.getMethod("init", Context::class.java).invoke(null, context) as? Boolean ?: false
        }.getOrDefault(false)
        connected = ok
        return ok
    }

    /** 本应用有没有被 Dhizuku 授权。binder 掉了会抛 RuntimeException，一律当没授权。 */
    fun isPermissionGranted(context: Context): Boolean {
        val clazz = apiClass ?: return false
        if (!isConnected(context)) return false
        val granted = runCatching {
            clazz.getMethod("isPermissionGranted").invoke(null) as? Boolean
        }.getOrNull() ?: false
        return granted
    }

    /** Dhizuku 服务器版本名，只用于「权限来源」那行的说明。 */
    fun versionName(context: Context): String? {
        val clazz = apiClass ?: return null
        if (!isConnected(context)) return null
        return runCatching { clazz.getMethod("getVersionName").invoke(null) as? String }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * 拉起 Dhizuku 的授权页。成功发出 Intent 返回 true。
     *
     * [onResult] 会拿到用户的选择（true = 已允许）；不走回调也没有关系，
     * 设置页回来时会重新探一次状态。
     */
    fun requestPermission(context: Context, onResult: ((Boolean) -> Unit)? = null): Boolean {
        if (!isConnected(context)) return false
        val bundle = Bundle().apply {
            putInt(EXTRA_UID, context.applicationInfo.uid)
            putBinder(EXTRA_REQUEST_BINDER, GrantBinder(onResult))
        }
        val intent = Intent(ACTION_REQUEST_PERMISSION)
            .setPackage(PACKAGE)
            .putExtras(bundle)
            // 旧版 Dhizuku 读的是这一个；两个都塞，新老都能认
            .putExtra("bundle", bundle)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /**
     * 用 Dhizuku 装 apk；成功返回 null，失败返回可展示的原因（上层据此继续降级）。
     *
     * 路径优先用公共下载目录的真实路径：Dhizuku 服务器跑在它自己的 uid 下，
     * 本应用的私有缓存它读不到。
     */
    fun install(context: Context, apk: File): String? {
        if (!isInstalled(context)) return "未安装 Dhizuku"
        if (!isConnected(context)) return "Dhizuku 未激活（没有设备所有者权限）"
        if (!isPermissionGranted(context)) {
            requestPermission(context)
            return "Dhizuku 未授权，已在 Dhizuku 里申请"
        }
        if (!apk.isFile || apk.length() <= 0) return "APK 文件不存在"

        val path = ShizukuInstaller.stageInDownloads(context, apk) ?: apk.absolutePath
        val quoted = path.replace("'", "'\\''")
        val output = runRemote("pm install -r -d '$quoted'")
            ?: return "Dhizuku 通道跑不起命令"
        if (output.contains("Success")) return null
        return if (output.isBlank()) "Dhizuku：pm install 没有输出" else "Dhizuku：${output.trim().take(160)}"
    }

    /** 让 Dhizuku 服务器起一个进程跑命令，返回 stdout + stderr。 */
    private fun runRemote(command: String): String? {
        val clazz = apiClass ?: return null
        return runCatching {
            val method = clazz.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                File::class.java
            )
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
                ?: return null
            val out = process.inputStream.bufferedReader().use { it.readText() }
            val err = runCatching {
                process.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            runCatching { process.waitFor() }
            out + err
        }.getOrNull()
    }

    /**
     * 授权结果回调：AIDL `IDhizukuRequestPermissionListener.onRequestPermission(int)`。
     *
     * 手写一次 transact 就够——先吃掉接口名，再读授权结果（0 = 允许）。
     */
    private class GrantBinder(
        private val onResult: ((Boolean) -> Unit)?
    ) : Binder() {

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val granted = runCatching {
                data.readString() // 接口描述符：读掉就行，不校验，避免 Dhizuku 改名后失效
                data.readInt() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            runCatching { reply?.writeNoException() }
            runCatching { onResult?.invoke(granted) }
            return true
        }
    }
}
