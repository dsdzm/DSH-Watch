package com.dshwatch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dshwatch.MainActivity
import com.dshwatch.R
import com.dshwatch.core.AppContainer
import com.dshwatch.core.Logx
import com.dshwatch.domain.qq.QqBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * QQ 桥接的常驻服务。
 *
 * ## 为什么必须是前台服务
 *
 * 「QQ 来消息自动进对话」要求 App 在后台也持续轮询。Android 8 起后台进程
 * 会被冻结，12 起连定时任务都要打折扣；只有前台服务能稳定活下来。
 * 代价是通知栏常驻一条 —— 这条通知同时也是「现在到底通没通」的唯一出口，
 * 用户不用进 App 就能看见。
 *
 * ## 通知为什么是低优先级
 *
 * 它每一轮都可能更新一次（「已处理 3 条」）。用默认优先级会在通知栏
 * 反复弹横幅，几小时下来没人受得了。IMPORTANCE_LOW 让它安静地待在栏里。
 */
class QqService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    /**
     * 本实例是否已成功进前台。
     *
     * 是实例字段而不是静态的：服务被 stop 后再 start 是**新实例**，
     * 而 static 变量会留成 true，于是新实例跳过 startForeground，
     * 系统那边却认为它从没进过前台 —— 10 秒后直接崩。
     */
    private var foregrounded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runCatching { ensureChannel() }
        startForegroundSafely(build(st = null, enabled = true))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            runCatching { AppContainer.of(this).qqBridge.stop() }
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isForegrounded()) {
            startForegroundSafely(build(st = null, enabled = true))
            if (!isForegrounded()) {
                Logx.w(TAG, "进前台失败，停止服务")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val container = AppContainer.of(this)
        val bridge = container.qqBridge

        if (!container.settings.value.qq.enabled) {
            bridge.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        bridge.start()

        if (watcher?.isActive != true) {
            watcher = scope.launch {
                bridge.state.collect { st ->
                    runCatching { notify(build(st, enabled = true)) }
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        // 服务没了轮询也没有意义：留着只会在后台空转、还占着前台服务配额
        runCatching { AppContainer.of(this).qqBridge.stop() }
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

    // ---------------- 通知 ----------------

    private fun build(st: QqBridge.State?, enabled: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, QqService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when {
            !enabled -> "QQ 桥接已关闭"
            st == null -> "QQ 桥接启动中"
            st.connected -> "QQ 已连接" + if (st.nickname.isNotBlank()) "·${st.nickname}" else ""
            else -> "QQ 未连接"
        }

        val text = when {
            st == null -> "正在连本机后端…"
            st.error.isNotBlank() -> st.error
            st.selfId.isNotBlank() -> "已处理 ${st.handled} 条" +
                if (st.lastLine.isNotBlank()) " · ${st.lastLine}" else ""
            else -> "等待后端响应"
        }

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notify(n: Notification) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, n)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(
            CHANNEL,
            "QQ 消息桥接",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "把 QQ 消息转进对话，并把回复发回去"
            setShowBadge(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun isForegrounded(): Boolean = foregrounded

    private fun startForegroundSafely(n: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
            foregrounded = true
        }.onFailure { Logx.w(TAG, "startForeground 失败：${it.message}") }
    }

    companion object {
        private const val TAG = "QqService"
        private const val CHANNEL = "qq_bridge"
        private const val NOTIFICATION_ID = 0x5151

        const val ACTION_START = "com.dshwatch.qq.START"
        const val ACTION_STOP = "com.dshwatch.qq.STOP"

        fun start(ctx: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    ctx,
                    Intent(ctx, QqService::class.java).setAction(ACTION_START),
                )
            }.onFailure { Logx.w(TAG, "启动 QQ 服务失败", it) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, QqService::class.java)) }
        }
    }
}
