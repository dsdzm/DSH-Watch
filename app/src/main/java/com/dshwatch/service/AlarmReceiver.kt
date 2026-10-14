package com.dshwatch.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.dshwatch.MainActivity
import com.dshwatch.R

/**
 * agent 通过 set_alarm 工具设置的闹钟在这里落地。
 *
 * 手表上没有系统时钟 App 可依赖（很多国产表只有个简陋的闹钟界面），
 * 所以闹钟响铃由本 App 自己发通知 + 振动完成，不依赖第三方组件。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "闹钟"
        showNotification(context, label)
        vibrate(context)
    }

    private fun showNotification(context: Context, label: String) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.notification_channel_alarm),
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply { description = context.getString(R.string.notification_channel_alarm_desc) }
                )
            }
        }

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(label)
            .setContentText(context.getString(R.string.notification_alarm_body))
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()

        runCatching { manager.notify(label.hashCode() and 0xFFFF, notification) }
    }

    private fun vibrate(context: Context) {
        val vibrator = context.getSystemService<Vibrator>() ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 400, 200, 400, 200, 400), -1)
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.dshwatch.action.ALARM_FIRE"
        const val EXTRA_LABEL = "label"
        const val EXTRA_TRIGGER_AT = "triggerAt"
        private const val CHANNEL_ID = "dsh_alarm"
    }
}
