package com.dshwatch.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前台服务的两条硬约束（静态防线）。
 *
 * 线上崩溃（OPPO API 30）：
 * `Context.startForegroundService() did not then call Service.startForeground()`。
 *
 * 这是 Android 定的死规矩：只要服务是被 `startForegroundService()` 拉起的，
 * 5 秒内必须调用 `startForeground()`，否则系统直接杀掉整个进程，
 * 而且异常堆栈停在 `ActivityThread$H`，什么现场都留不下。
 *
 * 当时有两条路会踩：
 *  1. `ACTION_UPDATE` 也走 `startForegroundService()`，但服务端只有 `ACTION_START`
 *     会进前台 —— 一次通知刷新就等于一颗 5 秒后引爆的雷；
 *  2. `startForeground()` 被 `runCatching` 吞掉异常，进程照样被系统判定为「没进前台」。
 *
 * 这两条都不会编译报错、也不会在测试里冒出来，只能靠源码结构守住。
 */
class StreamingServiceRuleTest {

    private val root = File("src/main/java/com/dshwatch")

    private fun code(path: String): String {
        val f = File(root, path)
        assertTrue("找不到 $path", f.isFile)
        // 注释里写着「以前是怎么错的」，扫之前必须剥掉，否则必然假阳性
        return f.readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")
    }

    @Test
    fun `任何 action 都在分发之前进入前台`() {
        val svc = code("service/StreamingService.kt")
        val body = svc.substringAfter("override fun onStartCommand")
        assertTrue("onStartCommand 不见了", body.isNotEmpty())

        val head = body.substringBefore("when (intent?.action)")
        assertTrue(
            "进前台必须在 action 分发之前，否则 ACTION_UPDATE 这类分支永远不进前台",
            head.contains("goForeground()"),
        )
        assertTrue(
            "进前台失败必须自停：把「已请求启动但没进前台」的状态留给系统，5 秒后必崩",
            head.contains("stopSelfSafely()"),
        )
    }

    @Test
    fun `通知更新不再拉起服务`() {
        val svc = code("service/StreamingService.kt")
        val idx = svc.indexOf("fun update(context: Context, text: String)")
        assertTrue("找不到 update()", idx >= 0)
        val update = svc.substring(idx).substringBefore("fun notifyDone")
        assertTrue(
            "通知更新是高频路径，服务不在时应当直接放弃，而不是 startForegroundService",
            !update.contains("startForegroundService"),
        )
        assertTrue(
            "拿不到服务实例要显式跳过",
            update.contains("instance") ,
        )
    }
}
