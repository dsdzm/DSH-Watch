package com.dshwatch.service

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

    private val renewHandler = Handler(Looper.getMainLooper())

    private val renewTask = object : Runnable {
        override fun run() {
            if (!running) return
            acquireWakeLock()
            renewHandler.postDelayed(this, RENEW_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        /*
         * 第一件事就是进前台，而且**对所有 action 都进**。
         *
         * 系统只认一件事：只要服务是被 startForegroundService() 拉起来的，
         * 就必须在 5 秒内调用 startForeground()，否则进程被直接干掉 ——
         * 抛的是 RemoteServiceException，堆栈停在 ActivityThread$H，
         * 从日志完全看不出是「谁启动的、为什么慢」，只留下一行
         * `Context.startForegroundService() did not then call Service.startForeground()`。
         *
         * 之前只有 ACTION_START 会进前台，ACTION_UPDATE 走的是同一条拉起通道
         * 却从不调用它 —— 那是一条必然崩溃的路径，只是平时 instance 还在、
         * 根本走不到 startForegroundService 那一行，所以一直没暴露。
         */
        val lag = SystemClock.elapsedRealtime() - requestedAt
        if (lag > 1500L) {
            // 5 秒是死线。记录延迟，下次再崩就能直接看出是「主线程被谁堵住了」
            Logx.w(TAG, "onStartCommand 延迟 ${lag}ms 才执行（主线程繁忙）")
        }

        if (!goForeground()) {
            // 进不了前台就别赖着：再等几秒系统一样会崩掉整个进程，
            // 不如自己收干净，生成还能在 Application 作用域里继续跑
            Logx.w(TAG, "进入前台失败，主动停止服务")
            stopSelfSafely()
            return START_NOT_STICKY
        }

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
                runCatching { Thread { AppContainer.of(app).engine.stop() }.start() }
                    .onFailure { Logx.w(TAG, "停止生成失败：${it.message}") }
                stopSelfSafely()
            }

            ACTION_STOP_SELF -> stopSelfSafely()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        instance = null
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
     * 进入前台。成功返回 true。
     *
     * 分三步且每步都兜住异常：建渠道 → 构通知 → startForeground。
     * 任何一步失败都只返回 false，由调用方停服务 —— 绝不能让「调用过
     * startForegroundService 但我们这边悄悄失败了」这种状态存在，
     * 那等于埋一颗 5 秒后引爆的雷。
     */
    private fun goForeground(): Boolean {
        runCatching { ensureChannel() }
            .onFailure { Logx.w(TAG, "通知渠道创建失败：${it.message}") }

        val notification = runCatching { buildNotification() }
            .getOrElse {
                Logx.w(TAG, "通知构建失败：${it.message}")
                buildFallbackNotification()
            }
        return runCatching {
            startForeground(NOTIFICATION_ID, notification)
            true
        }.getOrElse {
            Logx.w(TAG, "startForeground 失败：${it.message}")
            false
        }
    }

    /**
     * 最简通知。
     *
     * 正常那条带 PendingIntent 和 action，任一环节出问题（比如包名被改、
     * 图标资源被裁）都会让 buildNotification 抛异常。而这时候系统仍然要求
     * 我们进前台 —— 一条只有标题的通知远好过一个必崩的进程。
     */
    private fun buildFallbackNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

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

    private fun stopSelfSafely() {
        releaseWakeLock()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        stopSelf()
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(progressText.ifBlank { getString(R.string.notification_working) })
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

        private const val RENEW_INTERVAL_MS = 8 * 60 * 1000L

        /** 通知上的第二行。跨进程单例，Service 与调用方都读这一份 */
        @Volatile
        private var progressText: String = ""

        const val ACTION_START = "com.dshwatch.action.START_STREAM"
        const val ACTION_UPDATE = "com.dshwatch.action.UPDATE_STREAM"
        const val ACTION_STOP_GENERATION = "com.dshwatch.action.STOP_GENERATION"
        const val ACTION_STOP_SELF = "com.dshwatch.action.STOP_SELF"

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
         * 只用于诊断：onStartCommand 的主线程排队时间一旦接近系统的 5 秒红线，
         * 崩起来是"进程直接没了"、没有任何现场。先把它记进日志，
         * 下次再出问题就能一眼看出是不是主线程被堵住。
         */
        @Volatile
        private var requestedAt: Long = 0L

        fun start(context: Context) {
            if (running) return
            running = true
            requestedAt = SystemClock.elapsedRealtime()
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, StreamingService::class.java).setAction(ACTION_START),
                )
            }.onFailure {
                // 启动失败要回滚，否则 running 卡在 true，之后每次 start() 都变成空操作
                running = false
                Logx.w(TAG, "启动失败：${it.message}")
            }
        }

        fun stop(context: Context) {
            running = false
            progressText = ""
            /*
             * 服务还在就在本进程里直接收：`stopSelf()` 走的路径和 onDestroy
             * 是同一套，通知、唤醒锁都能立刻清干净。
             * 绕一层 stopService 反而有竞态 —— 服务刚被 startForegroundService
             * 拉起但 onStartCommand 还没跑时，stopService 会把它取消掉，
             * 系统那边仍记着「启动过但没进前台」，于是照样在 5 秒后崩。
             */
            instance?.let { svc ->
                runCatching { svc.stopSelfSafely() }
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
