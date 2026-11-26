package com.dshwatch.domain.fish

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.getSystemService
import com.dshwatch.core.Logx
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.service.ProactiveReceiver

/**
 * 主动发言的排队闹钟。
 *
 * ## 为什么只有一个闹钟
 *
 * 四个默认时点 + 若干一次性计划，理论上可以各挂一个 PendingIntent。
 * 但手表上的 AlarmManager 是被各家 ROM 重点照顾的对象：闹钟越多，
 * 被合并、被延后、被「省电策略」清掉的概率越大，而且改设置时要逐个
 * 对齐，漏一个就是"三个点都响、第四个不响"。
 *
 * 于是所有来源都收敛到 [FishProactive.next] 一个函数，每次只挂**下一次**。
 * 触发时立刻排下一次 —— 和 [com.dshwatch.domain.bili.BiliSchedule] 同一套做法，
 * 好处是改设置、跨时区、跨重启都只需要一次 sync。
 *
 * ## 为什么是 setExactAndAllowWhileIdle
 *
 * Doze 下普通 `set()` 会被攒到维护窗口里一起放出来，「晚上 10 点」可能变成
 * 「凌晨 3 点」。主动消息的价值全在时机，晚三个小时不如不发。
 * 没有精确闹钟权限时退回 `setAndAllowWhileIdle`：晚几分钟可以接受，崩不行。
 */
object FishProactiveSchedule {

    const val ACTION_FIRE = "com.dshwatch.action.FISH_PROACTIVE"

    /** PendingIntent 的 requestCode，取消时必须用同一个 */
    private const val REQUEST = 0x8421

    private const val TAG = "FishProactive"

    /** 上次排的时间点，避免设置里每改一个字就重排一次 */
    @Volatile
    private var scheduledAt: Long = 0L

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST,
        Intent(context, ProactiveReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * 排下一次。
     *
     * @param planTimes 一次性计划的时间戳（调用方从 [com.dshwatch.data.store.ProactiveStore] 取）
     */
    fun schedule(context: Context, settings: AppSettings, planTimes: List<Long>) {
        /*
         * 两道闸：养鱼总开关（用户同意过这个模式）与主动发言开关（他要不要她先开口）。
         *
         * 一次性计划（她自己约的）也要跟着总开关走：总开关关着意味着"这个模式没在用"，
         * 这时候还按旧计划弹通知，用户看到的就是一个自己不用的功能在后台发请求。
         */
        if (!settings.fishEnabled) {
            cancel(context)
            return
        }
        val next = FishProactive.next(
            now = System.currentTimeMillis(),
            times = settings.fishProactiveTimes,
            plans = planTimes,
            enabled = settings.fishProactive,
        )
        if (next == null) {
            cancel(context)
            return
        }
        // 同一个时刻已经在排队，不用重排（每 30 秒同步一次也不会反复动闹钟）
        if (next == scheduledAt) return

        val am = context.getSystemService<AlarmManager>() ?: return
        val pi = pending(context)
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            }
        }.isSuccess
        if (ok) {
            scheduledAt = next
            Logx.i(TAG, "下一次主动发言：${FishProactive.clockOf(next)}")
        } else {
            Logx.w(TAG, "排闹钟失败")
        }
    }

    fun cancel(context: Context) {
        runCatching {
            context.getSystemService<AlarmManager>()?.cancel(pending(context))
        }
        scheduledAt = 0L
    }

    /** 按当前设置与计划对齐。设置页保存后、App 启动时、开机广播里都调这一个入口 */
    fun sync(context: Context, settings: AppSettings, planTimes: List<Long>) {
        schedule(context, settings, planTimes)
    }
}
