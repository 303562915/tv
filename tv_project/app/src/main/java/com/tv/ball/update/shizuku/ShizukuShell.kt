package com.tv.ball.update.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 以 Shizuku 的身份跑 shell 命令：绑定 [ShellUserService]，每条命令一次 binder 事务。
 *
 * 对照参考项目 Canta 的 `ShizukuShell`。Shizuku 13.1.1 之后 `Shizuku.newProcess` 已私有，
 * UserService 是官方支持的、能跑 `pm install` 的通道。
 */
class ShizukuShell(private val context: Context) {

    companion object {
        private const val TAG = "TVPlayerShell"
        private const val SERVICE_VERSION = 1
        private const val BIND_TIMEOUT_MS = 10_000L
        private const val RUN_TIMEOUT_MS = 120_000L
    }

    data class Run(val ok: Boolean, val exitCode: Int, val output: String)

    private val args: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShellUserService::class.java.name)
        )
            .daemon(false)
            .version(SERVICE_VERSION)
            .processNameSuffix("shell")
            .tag(TAG)
    }

    @Volatile
    private var binder: IBinder? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    /** 绑定一次，多条命令复用；失败返回 null。 */
    private fun bind(): IBinder? {
        binder?.let { if (it.isBinderAlive) return it }
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
            }
        }
        return runCatching {
            Shizuku.bindUserService(args, conn)
            if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
            binder?.takeIf { it.isBinderAlive }
        }.getOrNull()
    }

    fun run(script: String): Run {
        val target = bind() ?: return Run(false, -1, "绑定 Shizuku 服务失败")
        return runCatching {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ShellUserService.DESCRIPTOR)
                data.writeString(script)
                val handled = target.transact(ShellUserService.TRANSACTION_RUN, data, reply, 0)
                if (!handled) return Run(false, -1, "服务没有处理这条命令")
                reply.readException()
                val exitCode = reply.readInt()
                val output = reply.readString().orEmpty()
                Run(exitCode == 0, exitCode, output)
            } finally {
                reply.recycle()
                data.recycle()
            }
        }.getOrElse { Run(false, -1, "执行命令失败：$it") }
    }
}