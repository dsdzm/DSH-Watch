package com.dshwatch.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.dshwatch.DshWatchApp
import com.dshwatch.core.Logx
import com.dshwatch.domain.bili.BiliSchedule

/**
 * 每日定时任务到点后在这里启动一轮刷 B站。
 *
 * 关键顺序：**先把明天的闹钟排上**，再去启动前台服务与模型。否则一旦这一轮
 * 抛异常（没网、没登录、模型欠费），明天的闹钟就再也不会响了，而且用户不会收到
 * 任何提示 —— 表现为「设置了定时任务，但从来没跑过」。
 */
class BiliDailyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? DshWatchApp ?: return
        val settings = app.container.settings.value

        // 开机 / 升级后闹钟全丢（AlarmManager 的排队不跨重启），必须重排一次
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            runCatching { BiliSchedule.sync(context, settings) }
                .onFailure { Logx.w(TAG, "开机重排失败：${it.message}") }
            return
        }
        if (intent.action != BiliSchedule.ACTION_RUN) return
        if (!settings.biliEnabled || !settings.biliAutoRun) {
            Logx.i(TAG, "定时任务已关闭，跳过")
            return
        }

        // 先排下一天：这一轮怎么失败都不影响明天
        runCatching { BiliSchedule.schedule(context, settings.biliAutoHour, settings.biliAutoMinute) }
            .onFailure { Logx.w(TAG, "排下一次失败：${it.message}") }

        // 后台跑十几分钟必须有前台服务，否则手表一熄屏就被冻结
        //
        // 但 Android 12 起，**广播接收器这类后台上下文里根本不允许起前台服务**：
        // Context.startForegroundService() 会同步抛 ForegroundServiceStartNotAllowedException。
        // 这不是 bug，是系统明令。定时任务的广播正好落在这条禁令里 ——
        // 以前每次都抛一次、被 runCatching 吞掉，看起来"跑了"，其实一行保活都没有。
        //
        // 所以先看自己在不在前台：在就正常起；不在就明说没保活，
        // 让用户知道这一轮结果可能因为熄屏而中断，而不是留一条假的成功日志。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !StreamingService.inForeground(context)
        ) {
            Logx.w(TAG, "Android 12+ 后台不允许启动前台服务，本轮不做保活（熄屏可能中断）")
        } else {
            runCatching { StreamingService.start(context) }
                .onFailure { Logx.w(TAG, "前台服务启动失败：${it.message}") }
        }

        runCatching { app.container.engine.send(BiliSchedule.prompt(settings)) }
            .onFailure { Logx.w(TAG, "定时任务启动失败：${it.message}") }
    }

    private companion object {
        const val TAG = "BiliDaily"
    }
}
