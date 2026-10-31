package com.dshwatch.domain.bili

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.getSystemService
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.service.BiliDailyReceiver

/**
 * 每日定时刷一轮。
 *
 * ## 为什么用 AlarmManager 而不是 WorkManager
 *
 * WorkManager 在手表上会被 Doze 推得没边 —— 「每天 03:30」经常变成「大概凌晨某个时候」，
 * 而这个任务本身要跑十几分钟、要联网、要前台服务，用 WorkManager 反而要额外处理
 * 约束与重试。AlarmManager 的 `setAndAllowWhileIdle` 语义更直接：到点就醒。
 *
 * ## 为什么每次触发都要重新排
 *
 * `setRepeating` 在 Doze 下会被批量推迟，且改过时间之后无法精确重排。
 * 每天触发时排下一天，顺带把「改过时区 / 改过时间」的情况自然纠正过来。
 */
object BiliSchedule {

    const val ACTION_RUN = "com.dshwatch.action.BILI_DAILY"

    /** PendingIntent 的 requestCode，取消时必须用同一个 */
    private const val REQUEST = 0x8417

    /** 上次排的时间点，用来避免设置里每改一个字就重排一次 */
    @Volatile
    private var scheduledAt: Long = 0L

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST,
        Intent(context, BiliDailyReceiver::class.java).setAction(ACTION_RUN),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(context: Context, hour: Int, minute: Int) {
        val am = context.getSystemService<AlarmManager>() ?: return
        val triggerAt = BiliClock.nextTrigger(System.currentTimeMillis(), hour, minute)
        if (triggerAt == scheduledAt) return

        val pi = pending(context)
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                // 没有精确闹钟权限就退化成普通闹钟：晚几分钟无所谓，但一定不能崩
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }.isSuccess
        if (ok) scheduledAt = triggerAt
    }

    fun cancel(context: Context) {
        runCatching {
            context.getSystemService<AlarmManager>()?.cancel(pending(context))
        }
        scheduledAt = 0L
    }

    /**
     * 按当前设置对齐闹钟。
     *
     * 关掉开关就取消，改过时间就重排 —— 调用方不需要自己判断，
     * 设置页保存后、App 启动时都调这一个入口即可。
     */
    fun sync(context: Context, settings: AppSettings) {
        if (settings.biliEnabled && settings.biliAutoRun) {
            schedule(context, settings.biliAutoHour, settings.biliAutoMinute)
        } else {
            cancel(context)
        }
    }

    /** 定时任务要发给模型的那句话。模式不同，动作不同 */
    fun prompt(settings: AppSettings): String = buildString {
        append("自动刷一轮 B站：按我关注的关键词挑几条真正值得看的，读字幕判断，把挑中的写进今天的日报。")
        when (BiliModes.of(settings)) {
            BiliMode.COMMENT ->
                append("挑一条最有意思的，用我平时说话的口气评论一句；如果有人回了我的话，挑值得接的接一句。")
            BiliMode.DM ->
                append("挑一条最值得说两句的，给 UP 主发一条简短的私信。")
            BiliMode.SUMMARY ->
                append("只要日报，不用发评论也不用私信。")
        }
        append("做完把今天的结论列给我。")
    }
}
