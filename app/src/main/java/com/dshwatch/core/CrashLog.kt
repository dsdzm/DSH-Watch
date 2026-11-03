package com.dshwatch.core

import android.content.Context
import android.os.Build
import com.dshwatch.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

/**
 * 全局未捕获异常兜底：把堆栈写进文件、留在内存里，下次启动由界面主动取走。
 *
 * ## 为什么不直接交给系统
 *
 * release 包开了 R8，系统崩溃对话框只会给用户看「屡次停止运行」，
 * 开发者这边什么都拿不到。用户能做的只有「再点一次」，我们连
 * 崩在哪一行都不知道 —— 上一轮排查崩溃就卡在这一步，最后只能靠猜。
 *
 * 所以这里做三件事：
 * 1. 崩溃瞬间把堆栈 + 机型 + 版本 + 最近几条 Logx 写到 `files/crash/last.txt`
 * 2. 仍然把异常交回默认处理器，让系统进程按正常流程结束（不吞崩溃）
 * 3. 下次启动时由 `CrashNotice` 读出来，在界面上让用户一键复制发给开发者
 *
 * 用**文件**而不是内存：崩溃后进程会没了，内存里的东西一起没；
 * 文件能跨进程存活，是唯一可靠的通道。
 */
object CrashLog {

    private const val DIR = "crash"
    private const val LAST = "last.txt"

    /** 缓存在内存里的一份，供当前进程内的诊断页读取 */
    @Volatile
    var lastText: String? = null
        private set

    /** 装到 Application 上。重复调用无副作用 */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }

            /*
             * 手表上的死锁：崩一次 → 系统弹「屡次停止运行」→ 用户点「重新打开」
             * → 又回到那个会崩的页面 → 再崩。中间没有机会切走。
             *
             * 所以除了交回系统处理器，还起一个 60 秒后的定时出口：
             * 如果系统迟迟不把进程结束（有些厂商 ROM 的崩溃对话框会一直挂着），
             * 这里主动退出，下次启动就是一次干净的重来。
             *
             * 用 System.exit 而不是 Process.killProcess：前者会走 Runtime 的
             * shutdown hook，DataStore / 文件写入的收尾还能跑完。
             */
            runCatching {
                Timer(true).schedule(
                    object : TimerTask() {
                        override fun run() = runCatching { System.exit(2) }.let { }
                    },
                    60_000L,
                )
            }

            // 仍然交回系统：不吞崩溃，保持标准行为
            previous?.uncaughtException(thread, error)
        }
        // 读上一次留下的
        runCatching {
            val f = File(File(app.filesDir, DIR), LAST)
            if (f.exists()) lastText = f.readText()
        }
    }

    /** 崩溃报告文件。没有就返回 null */
    fun file(context: Context): File? =
        File(File(context.filesDir, DIR), LAST).takeIf { it.exists() && it.length() > 0 }

    /** 用户点了「知道了」之后清掉，避免每次启动都弹 */
    fun clear(context: Context) {
        lastText = null
        runCatching { file(context)?.delete() }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            appendLine("时间：$time")
            appendLine("线程：${thread.name}")
            appendLine("版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL} / API ${Build.VERSION.SDK_INT}")
            appendLine("ABI：${Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine("异常：${error.javaClass.name}: ${error.message.orEmpty()}")
            appendLine()
            appendLine(sw.toString())
            val recent = Logx.recent()
            if (recent.isNotEmpty()) {
                appendLine()
                appendLine("---- 崩溃前最近的操作 ----")
                recent.asReversed().take(20).forEach { appendLine(it) }
            }
        }
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        // 只留最后一次：用户连续崩十次，我们要的是最新那份，不是十份
        File(dir, LAST).writeText(text)
        lastText = text
    }
}
