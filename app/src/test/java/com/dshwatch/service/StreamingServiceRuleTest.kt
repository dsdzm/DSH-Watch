package com.dshwatch.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前台服务的硬约束（静态防线）。
 *
 * 线上崩溃（HUAWEI API 31，1.21.2）：
 * `ForegroundServiceDidNotStartInTimeException: Context.startForegroundService()
 * did not then call Service.startForeground(): ServiceRecord{... StreamingService}`。
 *
 * 这是 Android 的死规矩：被 `startForegroundService()` 拉起的服务，必须在一定
 * 时间内调用 `startForeground()`（API 26–30 是 5 秒，API 31 起 10 秒），
 * 否则系统往主线程抛异常，整个进程没。堆栈只停在 `ActivityThread$H`。
 *
 * 三次踩坑，三次都是「代码看起来没问题」：
 *  1. `ACTION_UPDATE` 走同一条拉起通道却从不进前台；
 *  2. `startForeground()` 抛异常被 `runCatching` 吞掉，系统那边仍记着没进前台；
 *  3. **1.21.2**：进前台的逻辑在 `onStartCommand`，而它比 `onCreate` 晚一个
 *     主线程消息周期。手表把界面回收重建时主线程正忙着全量重组，这一轮
 *     消息排出去几秒 —— 死线就是这么走完的。日志里 onStartCommand
 *     连一行都没打出来，说明它根本没轮到执行。
 *  4. **1.24.1-beta.35**（sprd 手表，API 28）：进前台已经挪到 onCreate 了，
 *     但**请求本身**是后台线程发的（`onStreamingChanged` 在 Dispatchers.Default）。
 *     系统的计时器从收到请求那一刻起跑，跟应用主线程忙不忙无关；界面重建时
 *     主线程要跑几秒 Compose，onCreate 排不上。3 秒看门狗醒了两次、
 *     stopService 也按了，ServiceRecord 那时还没 create，计时器没被摘掉 ——
 *     5 秒到点，RemoteServiceException 带走整个进程。
 *
 * 这几条都不会编译报错、也不会在正常测试里冒出来，只能靠源码结构守住。
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

    private fun functionBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("找不到 $signature", start >= 0)
        return src.substring(start)
    }

    @Test
    fun `进前台发生在 onCreate 里，不依赖 onStartCommand`() {
        val svc = code("service/StreamingService.kt")
        val oncreate = functionBody(svc, "override fun onCreate()")
            .substringBefore("override fun onStartCommand")

        assertTrue(
            "onCreate 是系统给的最早回调，必须在这里进前台；" +
                "放在 onStartCommand 里就等于把死线交给主线程消息队列去赌",
            oncreate.contains("enterForegroundQuietly()"),
        )
    }

    @Test
    fun `进前台逻辑在 action 分发之前，且失败必须自停`() {
        val svc = code("service/StreamingService.kt")
        val body = svc.substringAfter("override fun onStartCommand")
        assertTrue("onStartCommand 不见了", body.isNotEmpty())

        val head = body.substringBefore("when (intent?.action)")
        assertTrue(
            "进前台的检查必须在 action 分发之前，否则 ACTION_UPDATE 这类分支永远不进前台",
            head.contains("enterForegroundQuietly()"),
        )
        assertTrue(
            "进前台失败必须自停：把「已请求启动但没进前台」的状态留给系统，死线一到必崩",
            head.contains("stopSelfSafely(startId)"),
        )
    }

    @Test
    fun `stopSelf 一律带 startId`() {
        val svc = code("service/StreamingService.kt")
        // 无参 stopSelfSafely 最终会走无参 stopSelf，而它会停掉**所有**已投递的 start。
        // 「生成结束 → 用户马上追问」时，前一次的无参 stop 会把后一次刚投递的
        // start 一起抹掉，系统的死线记账却还在 —— 只在实机上偶发的崩溃路径。
        assertTrue(
            "调用 stopSelfSafely 必须传 startId，否则会误杀后续的 start",
            !Regex("""stopSelfSafely\(\s*\)""").containsMatchIn(svc),
        )
    }

    @Test
    fun `拉起服务的请求必须回主线程发`() {
        val svc = code("service/StreamingService.kt")
        val hold = functionBody(svc, "private fun hold(").substringBefore("private fun issueStart(")
        assertTrue("hold() 不见了", hold.isNotEmpty())
        assertTrue(
            "不能在调用方线程上直接发 startForegroundService：onStreamingChanged 走的是 " +
                "Dispatchers.Default，系统的死线计时器会当场开跑，而 onCreate 排在" +
                "主线程那轮全量重组后面 —— 1.24.1-beta.35 的崩溃就是这么来的",
            !hold.contains("startForegroundService"),
        )
        assertTrue(
            "要么本来就在主线程直接执行，要么 post 到主线程；两条分支都要有",
            hold.contains("Looper.myLooper() == Looper.getMainLooper()") && hold.contains("mainHandler.post"),
        )
    }

    @Test
    fun `startForegroundService 之后必须挂死线看门狗`() {
        val svc = code("service/StreamingService.kt")
        val issue = functionBody(svc, "private fun issueStart(")
            .substringBefore("private fun watchForegroundDeadline(")
        assertTrue(
            "拉起服务后要盯死线：进不了前台就主动 stopService 撤销，" +
                "framework 会把 SERVICE_FOREGROUND_TIMEOUT_MSG 摘掉，计时器随之取消",
            issue.contains("watchForegroundDeadline"),
        )
        assertTrue("看门狗必须只对成功拉起的这次生效", issue.contains("onSuccess"))
        assertTrue(
            "已经 release 掉的请求不该再拉起一个没有任务的服务",
            issue.contains("holds.isEmpty()"),
        )
    }

    @Test
    fun `进前台成功要留痕`() {
        val svc = code("service/StreamingService.kt")
        val enter = functionBody(svc, "private fun enterForegroundQuietly(")
            .substringBefore("private fun minimalNotification(")
        assertTrue(
            "成功路径必须有记录：beta.35 那次崩溃只能靠「没有任何失败日志」反推 onCreate 没跑，" +
                "下次要能一眼分清是没跑还是跑了不被认",
            enter.contains("Logx.remember"),
        )
    }

    @Test
    fun `看门狗从请求发出时刻算起，不是从想发的时刻`() {
        val svc = code("service/StreamingService.kt")
        val issue = functionBody(svc, "private fun issueStart(")
            .substringBefore("private fun watchForegroundDeadline(")
        assertTrue(
            "看门狗必须拿「发出时刻」去比：主线程排了几秒才发出的请求是健康的，" +
                "拿 requestedAt 去比会把它当成失败，刚进前台就被自己撤掉",
            issue.contains("watchForegroundDeadline(app, seq, issuedAt)"),
        )
        val watchdog = functionBody(svc, "private fun watchForegroundDeadline(")
            .substringBefore("private fun release(")
        assertTrue(
            "比较的基准也得换成 issuedAt",
            watchdog.contains("foregroundedAt >= issuedAt"),
        )
    }

    @Test
    fun `看门狗时限短于最老的那条死线`() {
        val svc = code("service/StreamingService.kt")
        val m = Regex("""DEADLINE_GUARD_MS\s*=\s*([0-9_]+)L""").find(svc)
        assertTrue("找不到 DEADLINE_GUARD_MS", m != null)
        val ms = m!!.groupValues[1].replace("_", "").toLong()
        assertTrue(
            "看门狗时限 ${ms}ms 必须小于 API 26–30 的 5 秒死线，否则在旧设备上等于没有",
            ms < 5_000L,
        )
        assertTrue("太短会误杀正常启动，留 2 秒以上余量", ms <= 3_000L)
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
        assertTrue("拿不到服务实例要显式跳过", update.contains("instance"))
    }

    @Test
    fun `定时任务不在后台硬起前台服务`() {
        val rcv = code("service/BiliDailyReceiver.kt")
        assertTrue(
            "Android 12 起广播接收器里不允许起前台服务，必须先前台判断，" +
                "否则每次定时都抛一次 ForegroundServiceStartNotAllowedException",
            rcv.contains("inForeground(context)"),
        )
        val idx = rcv.indexOf("inForeground(context)")
        assertTrue("前台判断必须在 start() 之前", idx < rcv.indexOf("StreamingService.start"))
    }

    @Test
    fun `后台被挡掉的保活，生成结束时必须作废`() {
        val svc = code("service/StreamingService.kt")
        val release = functionBody(svc, "private fun release(").substringBefore("fun update(")
        assertTrue(
            "release 里不清 pendingStart，用户回到 App 时会拉起一个没有任务的前台服务，",
            release.contains("pendingStart = false"),
        )
        assertTrue(
            "补保活要有入口，否则「发完消息立刻息屏」那一轮永远没有保活",
            svc.contains("fun resumePending("),
        )
    }
}
