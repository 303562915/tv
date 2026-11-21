package com.tv.ball.update.shizuku

import android.os.Binder
import android.os.Parcel
import android.util.Log

/**
 * 特权侧：Shizuku 以它自己的身份（shell 或 root）在**独立进程**里启动这个类，
 * 每个 `TRANSACTION_RUN` 跑一条 `sh -c` 脚本并回传 (exitCode, output)。
 *
 * 对照参考项目 Canta 的 `ShellUserService`：Shizuku 13.1.1 起 `newProcess` 已私有，
 * 官方推荐做法就是 UserService。
 */
class ShellUserService(private val context: android.content.Context?) : Binder() {

    /** Shizuku v13 之前会调用这个无参构造。 */
    constructor() : this(null)

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == TRANSACTION_DESTROY) {
            // 服务被替换或宿主已退出：这个进程存在的意义就是给调用方跑命令。
            System.exit(0)
            return true
        }
        if (code != TRANSACTION_RUN) return super.onTransact(code, data, reply, flags)

        data.enforceInterface(DESCRIPTOR)
        val script = data.readString().orEmpty()
        val (exitCode, output) = execute(script)
        reply?.apply {
            writeNoException()
            writeInt(exitCode)
            writeString(output)
        }
        Log.i(TAG, "ran script: exit $exitCode, ${output.length} chars")
        return true
    }

    private fun execute(script: String): Pair<Int, String> = try {
        val builder = ProcessBuilder(SHELL, "-c", script).redirectErrorStream(true)
        // 这个进程由 Shizuku 启动，没有 App 的环境变量；不给 PATH 就找不到 pm / cmd。
        builder.environment()["PATH"] = PATH
        val process = builder.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor() to output
    } catch (e: Exception) {
        Log.w(TAG, "could not run command: $e")
        127 to "shell: could not run command: $e"
    }

    companion object {
        private const val TAG = "ShellUserService"

        const val DESCRIPTOR = "com.tv.ball.SHELL"
        const val TRANSACTION_RUN = 1
        const val TRANSACTION_DESTROY = 16777115

        private const val SHELL = "/system/bin/sh"
        private const val PATH = "/system/bin:/system/xbin:/vendor/bin"
    }
}