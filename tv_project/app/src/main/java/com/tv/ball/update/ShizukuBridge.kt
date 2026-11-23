package com.tv.ball.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.tv.ball.update.shizuku.ShizukuShell
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku / Stellar 安装通道的薄封装。
 *
 * 真正的机制在 [ShizukuShell] / [com.tv.ball.update.shizuku.ShellUserService]：
 * Shizuku 13.1.1 起 `Shizuku.newProcess` 已私有，必须走 UserService 才能执行 `pm install`。
 *
 * Stellar 是 Shizuku 的分支，自带 `rikka.shizuku` 兼容层（binder 从它自己的 provider 推过来），
 * 所以这两条来源用的是**同一套 API**，区别只在「谁在提供 binder」与展示名。
 */
object ShizukuBridge {

    private const val UPDATE_CODE = 4211

    /** Shizuku 本体的包名。注意不是 `rikka.shizuku`（那是 API 库），写错会永远判成「没装」。 */
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** Stellar（Shizuku 分支）本体的包名。 */
    const val STELLAR_PACKAGE = "roro.stellar.manager"

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 正在等授权结果的那一次请求码；0 表示没有等待中的请求。 */
    @Volatile
    private var awaitedRequestCode = 0

    private val shizukuClass: Class<*>? by lazy {
        runCatching { Class.forName("rikka.shizuku.Shizuku") }.getOrNull()
    }

    fun isAvailable(): Boolean {
        val clazz = shizukuClass ?: return false
        return runCatching {
            clazz.getMethod("pingBinder").invoke(null) as? Boolean ?: false
        }.getOrDefault(false)
    }

    fun hasPermission(): Boolean {
        val clazz = shizukuClass ?: return false
        val granted = runCatching {
            clazz.getMethod("checkSelfPermission").invoke(null) as? Int
        }.getOrNull() ?: return false
        return granted == PackageManager.PERMISSION_GRANTED
    }

    fun requestPermission(requestCode: Int = UPDATE_CODE) {
        val clazz = shizukuClass ?: return
        runCatching {
            clazz.getMethod("requestPermission", Int::class.javaPrimitiveType)
                .invoke(null, requestCode)
        }
    }

    /**
     * 申请授权并**等结果**（安装链路在 IO 线程上调用）。
     *
     * `Shizuku.requestPermission` 必须在主线程发，所以这里 post 到主线程，再在调用线程上等
     * [timeoutMs]。用户点了「允许」返回 true；拒绝、超时、根本没拉起弹窗都返回 false，
     * 安装链路据此继续降级，绝不卡在这一步。
     */
    fun requestPermissionBlocking(
        requestCode: Int = UPDATE_CODE,
        timeoutMs: Long = 20_000L
    ): Boolean {
        if (hasPermission()) return true
        if (!isAvailable()) return false

        val latch = CountDownLatch(1)
        awaitedRequestCode = requestCode
        val listener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { code, _ ->
            // 允许和拒绝都放开等待；最终以 hasPermission() 为准
            if (code == awaitedRequestCode) latch.countDown()
        }
        // 注册与申请都在主线程做（Shizuku 的 API 要求主线程），等待留在调用线程上；
        // 万一调用方本来就是主线程，就直接就地注册，避免自己把自己 post 死
        var registered = false
        val onMainThread = Looper.myLooper() == Looper.getMainLooper()
        val register = Runnable {
            registered = runCatching {
                rikka.shizuku.Shizuku.addRequestPermissionResultListener(listener)
                requestPermission(requestCode)
                true
            }.getOrDefault(false)
        }
        if (onMainThread) {
            register.run()
        } else {
            val posted = CountDownLatch(1)
            mainHandler.post {
                register.run()
                posted.countDown()
            }
            posted.await(2, TimeUnit.SECONDS)
        }
        try {
            if (registered && !onMainThread) latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            if (registered) {
                val unregister = Runnable {
                    runCatching {
                        rikka.shizuku.Shizuku.removeRequestPermissionResultListener(listener)
                    }
                }
                if (onMainThread) unregister.run() else mainHandler.post(unregister)
            }
            awaitedRequestCode = 0
        }
        return hasPermission()
    }

    /**
     * 设备上装没装 Shizuku / Stellar 本体。
     *
     * 与 [isAvailable] 不是一回事：[isAvailable] 问的是「服务活着且能用」，装了但没启动、
     * 没授权同样是 false。安装链路要把「没装」和「装了没授权」分开说，所以这里按包名单独查一次。
     */
    fun isInstalled(context: Context): Boolean =
        isPackageInstalled(context, SHIZUKU_PACKAGE) || isPackageInstalled(context, STELLAR_PACKAGE)

    fun isPackageInstalled(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /**
     * 跳到 Shizuku / Stellar 应用，让用户在那里允许本应用。
     *
     * 只发 `package:<pkg>` 的 `ACTION_VIEW`；包被卸载、没有入口、系统拦了，
     * 全部静默忽略——提示文案已经说过该去哪儿点，跳不过去也不能崩。
     */
    fun openShizukuApp(context: Context) {
        for (pkg in listOf(SHIZUKU_PACKAGE, STELLAR_PACKAGE)) {
            if (!isPackageInstalled(context, pkg)) continue
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }

    /** 装 apk；成功返回 true。失败原因在 [ShizukuInstaller.install] 里。 */
    fun install(context: Context, apk: File): Boolean =
        ShizukuInstaller.install(context, apk) == null

    /** 兼容旧签名（无 Context），不建议用于安装。 */
    fun install(apk: File): Boolean = false
}
