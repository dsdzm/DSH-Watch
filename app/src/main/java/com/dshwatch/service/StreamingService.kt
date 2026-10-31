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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dshwatch.MainActivity
import com.dshwatch.R
import com.dshwatch.core.AppContainer

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                ensureChannel()
                runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
                    .onFailure { com.dshwatch.core.Logx.w(TAG, "前台服务启动失败：${it.message}") }
                acquireWakeLock()
                scheduleRenew()
            }

            /**
             * 只更新通知文案。
             *
             * 刷 B站 那种任务要跑好几分钟、几十次工具调用，用户早就把界面划走了。
             * 通知上一直写「正在生成」等于什么都没说 —— 至少让他看见「在读字幕」。
             */
            ACTION_UPDATE -> {
                runCatching {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, buildNotification())
                }
                // 顺带续一次唤醒锁：长任务在这里被自然拉长，不会跑到一半 CPU 睡了
                acquireWakeLock()
            }

            ACTION_STOP_GENERATION -> {
                AppContainer.of(this).engine.stop()
                stopSelfSafely()
            }

            ACTION_STOP_SELF -> stopSelfSafely()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        renewHandler.removeCallbacks(renewTask)
        releaseWakeLock()
        super.onDestroy()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
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

        fun start(context: Context) {
            if (running) return
            running = true
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, StreamingService::class.java).setAction(ACTION_START),
                )
            }.onFailure { com.dshwatch.core.Logx.w(TAG, "启动失败：${it.message}") }
        }

        fun stop(context: Context) {
            running = false
            progressText = ""
            runCatching {
                context.startService(
                    Intent(context, StreamingService::class.java).setAction(ACTION_STOP_SELF)
                )
            }
        }

        /** 更新通知第二行（当前在做什么）。服务没在跑时静默忽略 */
        fun update(context: Context, text: String) {
            if (!running) return
            progressText = text.take(60)
            runCatching {
                context.startService(
                    Intent(context, StreamingService::class.java).setAction(ACTION_UPDATE)
                )
            }.onFailure { com.dshwatch.core.Logx.w(TAG, "更新通知失败：${it.message}") }
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
