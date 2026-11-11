package com.dshwatch.service

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dshwatch.MainActivity
import com.dshwatch.R
import com.dshwatch.core.AppContainer
import com.dshwatch.core.Logx

/**
 * 流式生成期间的前台服务。
 *
 * 只做两件事：
 *  1) 让系统知道「用户正在等一个结果」，不要冻结/回收进程（手表内存回收非常激进）；
 *  2) 提供通知栏上的「停止」入口，用户不用回到 App 就能中断。
 *
 * 真正的网络请求由 [com.dshwatch.domain.ChatEngine] 在 Application 作用域里持有，
 * 因此划走界面 / 熄屏都不会打断生成。
 */
class StreamingService : Service() {

    /**
     * 短时唤醒锁。
     *
     * 前台服务只能保证「进程不被杀」，**不能阻止 CPU 休眠**。
     * 手表在熄屏后很快进 suspend，socket 读会在 kernel 层被挂住，
     * 表现出来就是「屏幕一黑生成就断了 / 报网络错误」。
     * 这里在生成期间持一个 PARTIAL_WAKE_LOCK，并设 15 分钟上限兜底防泄漏。
     */
    private var wakeLock: PowerManager.WakeLock? = null

    /** 最近一次 onStartCommand 的 startId，`stopSelf(id)` 要用 */
    @Volatile
    private var lastStartId: Int = -1

    private val renewHandler = Handler(Looper.getMainLooper())

    private val renewTask = object : Runnable {
        override fun run() {
            if (!running) return
            acquireWakeLock()
            renewHandler.postDelayed(this, RENEW_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 进前台要尽可能早，所以放在 [onCreate]。
     *
     * `startForegroundService()` 之后系统只给 5 秒（Android 12 起 10 秒），
     * 到点没看到 `startForeground()` 就往主线程抛
     * `ForegroundServiceDidNotStartInTimeException`，整个进程一起没。
     *
     * onStartCommand 比 onCreate **晚一个主线程消息周期**。平时这点延迟是
     * 零，但手表上不是：界面被系统回收后重建（用户日志里连着三次）时，
     * 主线程正在跑 Compose 全量重组，那一轮消息可能排出去好几秒。
     * 1.21.2 的崩溃现场正是这样 —— 服务被拉起，onStartCommand 迟迟不来，
     * 10 秒后死在 ActivityThread$H 里，日志上除了这行异常什么都没有。
     *
     * 所以死线内能做的事，全部挪到 onCreate，而且用一条**不读任何业务状态**
     * 的最小通知：渠道、图标、标题，三样静态资源，构建耗时在微秒级。
     * 带「停止」按钮的正式通知在 onStartCommand 里再覆盖上去。
     */
    override fun onCreate() {
        super.onCreate()
        instance = this
        enterForegroundQuietly()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        this.lastStartId = startId

        /*
         * onCreate 已经进过前台的话，这里只剩「把通知换成带按钮的正式版」。
         *
         * 之前这里只有一条路：goForeground() 失败就 stopSelf()。问题是
         * stopSelf() 取消不了系统那边的 fgRequired 记账（在某些 ROM 上
         * 反而会留下一个"启动过但没进前台"的 ServiceRecord），10 秒后照样崩。
         * 现在改成：只要有任何一次成功的 startForeground，死线就解除了。
         */
        if (!isForegrounded()) {
            enterForegroundQuietly()
        }
        if (!isForegrounded()) {
            Logx.w(TAG, "进入前台失败，主动停止服务（startId=$startId）")
            stopSelfSafely(startId)
            return START_NOT_STICKY
        }

        val lag = SystemClock.elapsedRealtime() - requestedAt
        if (lag > 1500L) {
            Logx.w(TAG, "onStartCommand 延迟 ${lag}ms 才执行（主线程繁忙）")
        }

        // 前台状态已就位，再把通知升级成带「停止」按钮的正式版。
        // 这一步失败只丢按钮，不影响前台状态，所以不参与任何分支。
        upgradeNotification()

        when (intent?.action) {
            ACTION_START -> {
                acquireWakeLock()
                scheduleRenew()
            }

            /*
             * 只更新文案。刷 B站 那种任务要跑好几分钟、几十次工具调用，
             * 用户早就把界面划走了，通知上一直写「正在生成」等于什么都没说。
             *
             * 走到这里说明服务确实在：update() 已经不会再用 startForegroundService
             * 去拉起一个不存在的服务，前台状态在上面也一并就位了。
             */
            ACTION_UPDATE -> refreshNotification()

            /*
             * 通知栏上的「停止」。
             *
             * 这一步可能是进程被杀之后、由通知按钮重新拉起来的第一次执行，
             * 此时 [AppContainer] 还没构造过 —— 它的构造要读 keystore、开 DataStore，
             * 在主线程做足够触发 ANR。所以扔到后台线程，服务这边立刻收工。
             */
            ACTION_STOP_GENERATION -> {
                val app = applicationContext
                runCatching {
                    Thread {
                        // 设备操控期间用户是按「停止操作」，不是「停止生成」——
                        // 两件事都要做：先拉闸让工具层不再动作，再停掉这一轮生成
                        com.dshwatch.domain.device.DeviceControlHub.requestStop("用户在通知栏点了停止")
                        AppContainer.of(app).engine.stop()
                    }.start()
                }.onFailure { Logx.w(TAG, "停止生成失败：${it.message}") }
                stopSelfSafely(startId)
            }

            ACTION_STOP_SELF -> stopSelfSafely(startId)
        }
        /*
         * START_STICKY：被系统回收后由系统重新拉起。
         *
         * 进程刚重建时没有任何任务在跑（holds 是内存里的），这时候继续常驻
         * 只是一条没有意义的通知，所以立刻收掉。真正的重新拉起发生在
         * 「服务被杀但任务还在」的场景 —— 那种情况在手表上几乎只剩一种：
         * 内存吃紧时系统按优先级回收，前台服务通常是最后几个。
         */
        if (holds.isEmpty()) {
            stopSelfSafely(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        instance = null
        foregroundedAt = 0L
        renewHandler.removeCallbacks(renewTask)
        releaseWakeLock()
        // 被 stopService() 直接收掉时也要把常驻通知摘掉，否则会留一条永远「正在生成」
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        super.onDestroy()
    }

    /**
     * 进前台。成功返回进来的时刻（`elapsedRealtime`），失败返回 0。
     *
     * 用的是**最小通知**：不含 PendingIntent、不读 [com.dshwatch.domain.device.DeviceControlHub]、
     * 不做字符串拼接。死线之内只允许花这几微秒 —— 正式通知在
     * [onStartCommand] 里覆盖上去，那时前台状态已经稳了，慢一点无所谓。
     *
     * 渠道必须在 startForeground 之前存在（O+ 上渠道缺失会直接抛
     * `IllegalArgumentException: Invalid notification (no valid small icon)` 之外的
     * 另一类异常），所以先建渠道；渠道建不出来就说明系统通知服务本身不正常，
     * 这时候任何通知都进不了前台，只能让调用方收摊。
     */
    private fun enterForegroundQuietly(): Boolean {
        runCatching { ensureChannel() }
            .onFailure { Logx.w(TAG, "通知渠道创建失败：${it.message}") }

        val notification = runCatching { minimalNotification() }
            .getOrElse {
                Logx.w(TAG, "最小通知构建失败：${it.message}")
                return false
            }
        val at = runCatching {
            startForeground(NOTIFICATION_ID, notification)
            SystemClock.elapsedRealtime()
        }.getOrElse {
            Logx.w(TAG, "startForeground 失败：${it.message}")
            return false
        }
        foregroundedAt = at
        return true
    }

    /**
     * 死线专用的最小通知。三样静态资源，没有一次查表、没有一次锁。
     *
     * 刻意不带 `setContentIntent`：PendingIntent 要跨进程解析 Activity，
     * 在冷启动 / 界面重建期间这一步偶发几十毫秒。进前台不需要它。
     */
    private fun minimalNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /**
     * 把最小通知换成带「停止」按钮的正式通知。
     *
     * 直接用 `notify()` 覆盖，而不是再调一次 `startForeground()`：
     * 后者每次都会走一遍 foregroundServiceType 的权限校验，一旦某个 ROM
     * 在这一点上判失败，会把**已经进好的前台状态**搞没。
     */
    private fun upgradeNotification() {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        }.onFailure { Logx.w(TAG, "通知升级失败：${it.message}") }
    }

    /** 直接刷通知（不走 startService，后台调用不会被系统拦） */
    private fun refreshNotification() {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        }
        // 顺带续一次唤醒锁：长任务在这里被自然拉长，不会跑到一半 CPU 睡了
        acquireWakeLock()
    }

    /**
     * 取/续唤醒锁。
     *
     * 刻意**不**在「已持有」时直接 return：长任务动辄十几分钟，
     * 而 15 分钟的兜底超时一到，CPU 该睡还是会睡，socket 读被挂住，
     * 表现就是「跑到一半停了」。每次 [ACTION_UPDATE] 重取一次即可无缝续上。
     */
    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = wakeLock ?: pm
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        }.onFailure { com.dshwatch.core.Logx.w(TAG, "唤醒锁获取失败：${it.message}") }
    }

    /** 没有工具调用推进时，也要让唤醒锁活下去（纯模型思考可能好几分钟） */
    private fun scheduleRenew() {
        renewHandler.removeCallbacks(renewTask)
        renewHandler.postDelayed(renewTask, RENEW_INTERVAL_MS)
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }
        wakeLock = null
    }

    /**
     * 收掉自己。
     *
     * [startId] 传具体值走 `stopSelf(id)`：只停到这一次 start 为止。
     * 无参的 `stopSelf()` 会停掉**所有**已投递的 start —— 上一版就是无参版本，
     * 而 release() 释放完立刻又 hold() 的路径在手表上很常见（一轮生成结束、
     * 用户马上追问第二轮）。后一次 start 刚进队列就被前一次的无参 stopSelf
     * 抹掉，系统那边却仍记着「startForegroundService 过」，10 秒后照样崩。
     * 传 id 之后，先到的 stop 只能停掉它自己那一次，碰不到后来者。
     */
    private fun stopSelfSafely(startId: Int) {
        releaseWakeLock()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        foregroundedAt = 0L
        if (startId >= 0) stopSelf(startId) else stopSelf()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, StreamingService::class.java).setAction(ACTION_STOP_GENERATION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /*
         * 设备操控期间，通知要写「在操作什么」，而不是「正在生成」。
         *
         * 这时候 App 在后台、用户盯着别的应用，通知栏是唯一还能确认
         * 「AI 到底在干什么」的地方。写一句泛泛的「正在生成」等于什么都没说。
         */
        val device = com.dshwatch.domain.device.DeviceControlHub.state.value
        val title = if (device.running) {
            getString(R.string.notification_device_title)
        } else {
            getString(R.string.notification_title)
        }
        val body = when {
            device.running && device.intent.isNotBlank() -> device.intent
            device.running -> getString(R.string.notification_device_body)
            progressText.isNotBlank() -> progressText
            else -> getString(R.string.notification_working)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setShowWhen(false)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_streaming),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_streaming_desc)
                setShowBadge(false)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "StreamingService"
        private const val CHANNEL_ID = "dsh_streaming"
        private const val NOTIFICATION_ID = 0x4453
        private const val NOTIFICATION_DONE_ID = 0x4454
        private const val CHANNEL_DONE = "dsh_done"
        private const val EXTRA_TEXT = "text"
        private const val WAKE_LOCK_TAG = "dshwatch:stream"

        /** 唤醒锁最长持有时间，防止异常情况下漏释放把电池耗光 */
        private const val WAKE_LOCK_TIMEOUT_MS = 15 * 60 * 1000L

        /**
         * 看门狗等多久才认定「这一次没进前台」。
         *
         * 系统死线在两代 Android 上不一样：**API 26–30 是 5 秒，API 31 起是 10 秒**。
         * 按 8 秒取值在 API 31 的设备上是够的，但在 API 30 及更老的机器上
         * 反而比死线还晚 —— 那等于看门狗刚睁眼，进程已经被带走了。
         *
         * 所以取 3 秒：对两代死线都留出至少 2 秒余量，又远大于正常启动耗时
         * （实测进前台是几十毫秒级）。
         */
        private const val DEADLINE_GUARD_MS = 3_000L

        private const val RENEW_INTERVAL_MS = 8 * 60 * 1000L

        /** 通知上的第二行。跨进程单例，Service 与调用方都读这一份 */
        @Volatile
        private var progressText: String = ""

        const val ACTION_START = "com.dshwatch.action.START_STREAM"
        const val ACTION_UPDATE = "com.dshwatch.action.UPDATE_STREAM"
        const val ACTION_STOP_GENERATION = "com.dshwatch.action.STOP_GENERATION"
        const val ACTION_STOP_SELF = "com.dshwatch.action.STOP_SELF"

        /** 占用原因：一次生成 / 一次设备操控。两者可以同时存在 */
        private const val REASON_STREAM = "stream"
        private const val REASON_DEVICE = "device"

        /**
         * 谁在占用前台服务。
         *
         * 以前只有一个布尔 `running`，对应「有没有在生成」。设备操控打破了
         * 这个假设：生成结束的那一刻，AI 可能正停在别的 App 上等下一屏。
         * 于是改成引用计数语义 —— 全部释放才真的停服务。
         */
        private val holds: MutableSet<String> =
            java.util.Collections.synchronizedSet(mutableSetOf<String>())

        @Volatile
        var running: Boolean = false
            private set

        /**
         * 正在运行的服务实例。
         *
         * 更新通知不必再发一个 Intent 走 startService —— Android 8+ 明确禁止后台进程
         * startService，异常会被 runCatching 吞掉，结果就是通知永远停在「正在生成」。
         */
        @Volatile
        private var instance: StreamingService? = null

        /**
         * 最近一次 start() 的时刻。
         *
         * 只用于诊断：onStartCommand 的主线程排队时间一旦接近系统的死线（5 或 10 秒），
         * 崩起来是"进程直接没了"、没有任何现场。先把它记进日志，
         * 下次再出问题就能一眼看出是不是主线程被堵住。
         */
        @Volatile
        private var requestedAt: Long = 0L

        /**
         * 最近一次**成功**进前台（`startForeground` 返回）的时刻。
         *
         * 0 表示还没进过。看门狗拿它和本次 start 的时刻比较，判断
         * 「这一次启动到底进没进前台」—— 只有这个能决定要不要主动撤销。
         */
        @Volatile
        private var foregroundedAt: Long = 0L

        /** 本次 start 的序号，让老的看门狗认得出自己已经过期 */
        @Volatile
        private var startSeq: Int = 0

        /**
         * 被「后台不许起前台服务」挡掉的那次请求。
         *
         * 「发完消息立刻息屏」在手表上是常态：那一刻 App 已经不算前台，
         * 硬起会被 ROM 排队、最后拖成死线异常。但生成还在跑，保活是需要的 ——
         * 所以先记下来，等 [resumePending] 在界面回到前台时补上。
         */
        @Volatile
        private var pendingStart: Boolean = false

        /**
         * 界面回到前台时补一次被挡掉的保活。
         *
         * 由 `MainActivity.onResume` 调用（界面被系统回收后重建也会走到这里，
         * 那正是最需要补的一次）。
         */
        fun resumePending(context: Context) {
            if (!pendingStart) return
            pendingStart = false
            if (running) return
            hold(context, REASON_STREAM)
        }

        fun start(context: Context) = hold(context, REASON_STREAM)

        fun stop(context: Context) = release(context, REASON_STREAM)

        /**
         * 设备操控期间占用前台服务。
         *
         * 和 [start] 用的是同一个服务、同一条通知，只是记账原因不同：
         * 生成结束会 `stop`，但那时候 AI 可能还在操作设备 —— 如果直接停服务，
         * DSH 就退回成普通后台进程，手表的低内存回收几分钟内就会把它带走，
         * 表现是「操作到一半，悬浮条没了，任务也没了」。
         */
        fun holdDevice(context: Context) = hold(context, REASON_DEVICE)

        fun releaseDevice(context: Context) = release(context, REASON_DEVICE)

        /** 当前这个服务实例有没有成功进过前台 */
        private fun isForegrounded(): Boolean =
            instance != null && foregroundedAt > 0L

        /**
         * 本进程现在算不算「前台」。
         *
         * Android 11 起第三方应用调 `getRunningAppProcesses()` 只能看到自己，
         * 但这里要的恰好就是自己。取到 FOREGROUND_SERVICE（125）为止 ——
         * 前台服务在跑也算「允许起前台服务」。
         */
        fun inForeground(context: Context): Boolean = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses?.any {
                it.pid == Process.myPid() &&
                    it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
            } == true
        }.getOrDefault(false)

        private fun hold(context: Context, reason: String) {
            /*
             * Android 12 起，**后台不允许起前台服务**。
             *
             * 这条限制的可怕之处在于它有两种表现：AOSP 上抛
             * ForegroundServiceStartNotAllowedException（能被 runCatching 接住），
             * 而国产 ROM（EMUI 尤其明显）选择「接受请求、排队等应用回前台」——
             * 不抛异常，ServiceRecord 却已经登记了。于是 onStartCommand 迟迟不来，
             * 10 秒后由 system_server 往主线程抛 ForegroundServiceDidNotStartInTime，
             * 整个进程没。1.21.2 那次崩溃就是这个形状：日志上什么都没有，
             * 只有一行 from ActivityThread$H 的异常。
             *
             * 所以从零启动之前先看自己在不在前台：不在就**根本不发这个请求**。
             * 少一次保活远好过整进程崩溃，况且这种请求在 Android 12 上本来就不会成功。
             *
             * 只对「生成」生效：设备操控是用户明确点下去的动作，那时 App 已经
             * moveTaskToBack，不能按同一条标准拦。
             */
            if (reason == REASON_STREAM &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !running && !inForeground(context)
            ) {
                Logx.w(TAG, "应用在后台，跳过前台服务启动（Android 12+ 不允许）")
                // 记下来，等界面回到前台再补 —— 否则「发完消息立刻息屏」这一轮
                // 就永远没有保活了，而生成还在跑
                pendingStart = true
                return
            }
            holds.add(reason)
            if (running) return
            running = true
            val startedAt = SystemClock.elapsedRealtime()
            requestedAt = startedAt
            val seq = ++startSeq
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, StreamingService::class.java).setAction(ACTION_START),
                )
            }.onFailure {
                /*
                 * 启动失败要回滚，否则 running 卡在 true，之后每次 start() 都变成空操作。
                 * 同时把占用的原因也撤掉：没起来就是没保住，留着只会让后来
                 * 一次 release 误以为「还有别人在用」而不停服务。
                 */
                holds.remove(reason)
                running = false
                /*
                 * Android 12 起，后台调 startForegroundService 会抛
                 * ForegroundServiceStartNotAllowedException。这不是意外，
                 * 是系统在明确告诉你「现在不允许起前台服务」—— 最典型的
                 * 来源是定时任务的广播接收器。记清楚，别让人以为是别的毛病。
                 */
                Logx.w(TAG, "启动失败（$reason）：${it.javaClass.simpleName}: ${it.message}")
            }.onSuccess {
                // 起来了才需要看门狗；异常已经同步抛出，那种情况系统没登记
                watchForegroundDeadline(context.applicationContext, seq, startedAt)
            }
        }

        /**
         * 死线看门狗：3 秒内没进前台，就**主动撤销这次启动**。
         *
         * 系统那头有一条计时器（Android 12 起 10 秒；更早是 5 秒），
         * 触发时往主线程抛 `ForegroundServiceDidNotStartInTimeException`，
         * 进程整个没。我们没法延长它，但可以抢在它前面把事情做掉：
         * `stopService()` 会让 framework 在 `bringDownServiceLocked` 里
         * `removeMessages(SERVICE_FOREGROUND_TIMEOUT_MSG)`，计时器随之取消。
         *
         * 代价是这一次没有前台保护，生成退回普通后台进程 —— 但生成本身
         * 跑在 Application 作用域里，不会因为这个停；而进程崩掉是全都完蛋。
         *
         * 用裸线程而不是 Handler：看门狗要防的正是「主线程不转」，
         * 把它的执行也放在主线程上，等于在堵住的那条路上再排一个队。
         */
        private fun watchForegroundDeadline(app: Context, seq: Int, startedAt: Long) {
            runCatching {
                Thread {
                    runCatching { Thread.sleep(DEADLINE_GUARD_MS) }
                    // 期间又起了一次新的，这条已经过期，让新的那条去管
                    if (seq != startSeq) return@Thread
                    if (foregroundedAt >= startedAt) return@Thread
                    Logx.w(
                        TAG,
                        "3 秒未进前台，主动撤销这次启动" +
                            "（避免系统的 5/10 秒死线把整个进程带走）",
                    )
                    runCatching {
                        app.stopService(Intent(app, StreamingService::class.java))
                    }
                    running = false
                }.apply {
                    isDaemon = true
                    name = "dsh-fgs-deadline"
                }.start()
            }
        }

        private fun release(context: Context, reason: String) {
            holds.remove(reason)
            // 还有别的占用者（例如设备操控仍在进行）就不停
            if (holds.isNotEmpty()) return
            running = false
            /*
             * 生成已经结束，那次没发出去的保活请求也就作废了。
             *
             * 不清的话会留一颗雷：用户回到 App 时 `onResume` 看到 pendingStart
             * 还是 true，会拉起一个**没有任何任务在跑**的前台服务 ——
             * 通知栏挂着「正在生成」，实际什么都不做，只能等它自己超时。
             */
            pendingStart = false
            progressText = ""
            /*
             * 服务还在就在本进程里直接收：`stopSelf()` 走的路径和 onDestroy
             * 是同一套，通知、唤醒锁都能立刻清干净。
             * 绕一层 stopService 反而有竞态 —— 服务刚被 startForegroundService
             * 拉起但 onStartCommand 还没跑时，stopService 会把它取消掉，
             * 系统那边仍记着「启动过但没进前台」，于是照样在 5 秒后崩。
             *
             * 传 lastStartId 而不是无参：**无参 stopSelf 会停掉所有已投递的 start**。
             * 「生成结束 → 用户马上追问」在手表上是常态，前一次的无参 stop
             * 会顺手把后一次刚投递的 start 也抹掉，而系统的死线记账仍然在。
             */
            instance?.let { svc ->
                runCatching { svc.stopSelfSafely(svc.lastStartId) }
                    .onFailure { Logx.w(TAG, "停止服务失败：${it.message}") }
                return
            }
            runCatching { context.stopService(Intent(context, StreamingService::class.java)) }
                .onFailure { Logx.w(TAG, "停止服务失败：${it.message}") }
        }

        /**
         * 更新通知第二行（当前在做什么）。
         *
         * 服务不在就**什么都不做**。这里以前会 startForegroundService() 去
         * 「顺手把服务拉起来」，但发的是 ACTION_UPDATE，服务端不会进前台 ——
         * 每一次更新都等于一次 5 秒后的 RemoteServiceException。
         * 通知少刷一次无所谓，进程崩了生成才真的断了。
         */
        fun update(context: Context, text: String) {
            if (!running) return
            progressText = text.take(60)
            // 服务就在本进程里跑着，直接刷比绕一圈 Intent 可靠得多
            val svc = instance
            if (svc == null) {
                Logx.w(TAG, "服务不在，跳过通知更新")
                return
            }
            svc.refreshNotification()
        }

        /**
         * 跑完发一条**可以点掉**的普通通知。
         *
         * 手表上最难受的不是慢，是「不知道跑完没有、要不要抬腕看」。
         * 所以只在用户明确打开开关时发，避免变成打扰。
         */
        fun notifyDone(context: Context) {
            runCatching {
                val manager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (manager.getNotificationChannel(CHANNEL_DONE) == null) {
                        manager.createNotificationChannel(
                            NotificationChannel(
                                CHANNEL_DONE,
                                context.getString(R.string.notification_channel_done),
                                NotificationManager.IMPORTANCE_DEFAULT,
                            ).apply {
                                description =
                                    context.getString(R.string.notification_channel_done_desc)
                            }
                        )
                    }
                }
                val open = PendingIntent.getActivity(
                    context, 2,
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val n = NotificationCompat.Builder(context, CHANNEL_DONE)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.notification_done_title))
                    .setContentText(context.getString(R.string.notification_done_body))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
                manager.notify(NOTIFICATION_DONE_ID, n)
            }.onFailure { com.dshwatch.core.Logx.w(TAG, "完成通知失败：${it.message}") }
        }
    }
}
