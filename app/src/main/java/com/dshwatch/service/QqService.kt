package com.dshwatch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.dshwatch.MainActivity
import com.dshwatch.R
import com.dshwatch.core.AppContainer
import com.dshwatch.core.Logx
import com.dshwatch.domain.qq.QqState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * QQ 通道的常驻前台服务。
 *
 * 和 [StreamingService] 分开而不是合并，因为两者的生命周期正好相反：
 * 那个是「生成期间在、生成完就走」，这个是「一直在线」。
 * 合成一个的话，用户在 App 里点「停止生成」会顺手把 QQ 通道也关掉。
 *
 * 保活做了两件事：
 *
 *  1. 前台服务 —— 让系统知道这不是可回收的后台任务（手表内存回收很激进）；
 *  2. PARTIAL_WAKE_LOCK —— 前台服务**不阻止 CPU 休眠**。手表熄屏后很快进
 *     suspend，socket 读被挂住，表现出来就是「屏幕一黑就掉线，亮屏又自己连上」，
 *     看起来像网络问题，其实是 CPU 睡了。
 *
 * 唤醒锁设 10 分钟上限并每 8 分钟续一次：真出异常（比如崩溃前最后一刻）时
 * 不会把电池一直耗着。
 */
class QqService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                ensureChannel()
                runCatching { startForeground(NOTIFICATION_ID, buildNotification(QqState.CONNECTING)) }
                    .onFailure { Logx.w(TAG, "前台服务启动失败：${it.message}") }
                acquireWakeLock()
                running = true
                startBridge()
            }

            ACTION_STOP -> {
                stopBridge()
                stopSelfSafely()
            }
        }
        return START_STICKY
    }

    private fun startBridge() {
        val container = AppContainer.of(this)
        container.qqBridge.restart()

        // 状态变化同步到通知：连上了写「在线」，重连中写「重连中」，
        // 用户扫一眼表盘就知道是不是断了，不用点进来。
        scope.launch {
            container.qqBridge.state.collectLatest { st ->
                runCatching {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, buildNotification(st))
                }
                if (st == QqState.ONLINE) acquireWakeLock()
            }
        }
    }

    private fun stopBridge() {
        running = false
        runCatching { AppContainer.of(this).qqBridge.stop() }
    }

    override fun onDestroy() {
        stopBridge()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = wakeLock ?: pm
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
            // 每次重取都会刷新超时，等于「只要还在线就一直是醒的」
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        }.onFailure { Logx.w(TAG, "唤醒锁获取失败：${it.message}") }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
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

    private fun buildNotification(state: QqState): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, QqService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (state) {
            QqState.ONLINE -> getString(R.string.qq_notification_title)
            QqState.CONNECTING, QqState.RECONNECTING -> getString(R.string.qq_notification_connecting)
            QqState.FAILED -> getString(R.string.qq_notification_connecting)
            QqState.IDLE -> getString(R.string.qq_notification_connecting)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text)
            .setContentText(getString(R.string.qq_channel_desc))
            .setContentIntent(open)
            .addAction(0, getString(R.string.qq_notification_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.qq_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.qq_channel_desc)
                setShowBadge(false)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "QqService"
        private const val CHANNEL_ID = "dsh_qq"
        private const val NOTIFICATION_ID = 0x4457
        private const val WAKE_LOCK_TAG = "dshwatch:qq"

        /** 心跳 41 秒一次，10 分钟足够覆盖十几次心跳；每次状态变化都会续期 */
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60_000L

        const val ACTION_START = "com.dshwatch.qq.START"
        const val ACTION_STOP = "com.dshwatch.qq.STOP"

        fun start(context: Context) {
            val intent = Intent(context, QqService::class.java).setAction(ACTION_START)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Logx.w(TAG, "启动 QQ 服务失败：${it.message}") }
        }

        fun stop(context: Context) {
            val intent = Intent(context, QqService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
