package com.dshwatch.domain.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.dshwatch.core.Logx

/**
 * 电池优化白名单。
 *
 * 设备操控期间 DSH 一定在后台 —— 用户看的是被操作的那个 App。国内 ROM
 * （以及 Wear OS 自己的低内存回收）对后台进程的处理比 AOSP 激进得多：
 * 前台服务也可能被清，尤其是屏幕熄灭之后。
 *
 * 把自己加进「不受电池优化限制」名单，是唯一不需要 root、不违反系统规则的
 * 加强手段。它不能让进程永生，但能把「几分钟后必被杀」变成「内存真的不够时才被杀」。
 *
 * ## 为什么优先弹系统对话框
 *
 * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 会给一个「是否允许」的原生弹窗，
 * 一步到位。但部分 ROM 把这个 action 阉掉了（抛 ActivityNotFoundException），
 * 这时退回设置列表页，让用户自己找 —— 不优雅，但至少路径存在。
 */
object BatteryKeepAlive {

    private const val TAG = "Battery"

    /** 是否已经在白名单里。读不到就当作「不在」，引导用户去设置不会有害。 */
    fun isExempt(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    fun request(context: Context) {
        val pkg = context.packageName
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$pkg"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (runCatching { context.startActivity(direct) }.isSuccess) return

        Logx.w(TAG, "直连白名单弹窗不可用，退回设置列表")
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Logx.w(TAG, "打开电池优化设置失败：${it.message}") }
    }
}
