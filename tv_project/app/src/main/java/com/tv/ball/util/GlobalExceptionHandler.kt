package com.tv.ball.util

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * 全局异常处理器
 * 捕获未处理的异常，记录日志并提供友好的错误提示
 */
class GlobalExceptionHandler private constructor(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "GlobalExceptionHandler"
        private const val SHOWN_PREFS = "crash_reports"
        private const val KEY_LAST_SHOWN = "last_shown_crash"
        private var instance: GlobalExceptionHandler? = null

        /**
         * 初始化全局异常处理器
         */
        fun init(context: Context) {
            if (instance == null) {
                val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
                instance = GlobalExceptionHandler(context.applicationContext, defaultHandler)
                Thread.setDefaultUncaughtExceptionHandler(instance)
                Log.i(TAG, "全局异常处理器已初始化")
            }
        }

        /**
         * 取「还没给用户看过」的最新崩溃报告；没有新崩溃返回 null。
         *
         * 崩溃后进程直接退出，用户没法翻 Android/data 目录，所以下次启动时由界面层
         * 直接把这份内容显示出来——不然线上定位只能靠盲猜。主线程外调用。
         */
        fun takeUnshownReport(): CrashReport? {
            val ctx = instance?.context ?: return null
            return try {
                val logDir = ctx.getExternalFilesDir("crash_logs") ?: return null
                val newest = logDir.listFiles()?.maxByOrNull { it.lastModified() } ?: return null
                val lastShown = ctx.getSharedPreferences(SHOWN_PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_LAST_SHOWN, null)
                if (newest.name == lastShown) return null
                CrashReport(newest.name, newest.readText())
            } catch (e: Exception) {
                Log.e(TAG, "读取崩溃日志失败", e)
                null
            }
        }

        /** 标记某份报告已经展示过，下次启动不再弹。 */
        fun markReportShown(fileName: String) {
            val ctx = instance?.context ?: return
            try {
                ctx.getSharedPreferences(SHOWN_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_SHOWN, fileName)
                    .apply()
            } catch (e: Exception) {
                Log.e(TAG, "记录崩溃日志展示状态失败", e)
            }
        }
    }

    /** 一份崩溃报告：文件名 + 全文。 */
    data class CrashReport(val fileName: String, val content: String)

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            // 记录异常信息
            logException(throwable)

            // 保存崩溃日志到文件
            saveCrashLog(throwable)

            // 显示错误提示（可选）
            // showErrorDialog(throwable)

        } catch (e: Exception) {
            Log.e(TAG, "处理异常时发生错误", e)
        } finally {
            // 调用系统默认的异常处理器
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 记录异常到 Logcat
     */
    private fun logException(throwable: Throwable) {
        Log.e(TAG, "捕获到未处理的异常", throwable)
        Log.e(TAG, "异常类型: ${throwable.javaClass.name}")
        Log.e(TAG, "异常消息: ${throwable.message}")
        Log.e(TAG, "堆栈跟踪:\n${getStackTraceString(throwable)}")
    }

    /**
     * 保存崩溃日志到文件
     */
    private fun saveCrashLog(throwable: Throwable) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
                .format(Date())
            val fileName = "crash_$timestamp.log"

            val logDir = context.getExternalFilesDir("crash_logs")
            if (logDir != null && !logDir.exists()) {
                logDir.mkdirs()
            }

            val logFile = java.io.File(logDir, fileName)
            val writer = PrintWriter(logFile)

            writer.println("=".repeat(50))
            writer.println("崩溃日志")
            writer.println("=".repeat(50))
            writer.println("时间: $timestamp")
            writer.println("应用版本: ${getAppVersion()}")
            writer.println("Android 版本: ${android.os.Build.VERSION.RELEASE}")
            writer.println("设备型号: ${android.os.Build.MODEL}")
            writer.println("设备厂商: ${android.os.Build.MANUFACTURER}")
            writer.println("=".repeat(50))
            writer.println()
            writer.println("异常类型: ${throwable.javaClass.name}")
            writer.println("异常消息: ${throwable.message}")
            writer.println()
            writer.println("堆栈跟踪:")
            writer.println(getStackTraceString(throwable))

            writer.close()

            Log.i(TAG, "崩溃日志已保存: ${logFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "保存崩溃日志失败", e)
        }
    }

    /**
     * 获取堆栈跟踪字符串
     */
    private fun getStackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        return sw.toString()
    }

    /**
     * 获取应用版本
     */
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${packageInfo.versionName} (${packageInfo.versionCode})"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * 清理旧的崩溃日志（保留最近10个）
     */
    fun cleanOldLogs() {
        try {
            val logDir = context.getExternalFilesDir("crash_logs") ?: return
            val logFiles = logDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return

            if (logFiles.size > 10) {
                logFiles.drop(10).forEach { file ->
                    file.delete()
                    Log.d(TAG, "删除旧日志: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "清理旧日志失败", e)
        }
    }
}
