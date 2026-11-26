package com.dshwatch.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.dshwatch.DshWatchApp
import com.dshwatch.core.Logx
import com.dshwatch.domain.fish.FishProactiveSchedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 主动发言到点。
 *
 * ## 顺序不能变
 *
 * 1. **先排下一次**，再干别的。生成可能因为没网、欠费、模型抽风而失败，
 *    而 AlarmManager 的排队是一次性的 —— 不先排，失败一次就再也不会响。
 * 2. 认出这一下是谁触发的：是模型自己约的一次性计划（带 note），
 *    还是每天例行的四个点之一。前者要把计划从账上划掉。
 * 3. 生成 → 通知。
 *
 * ## 关于后台限制
 *
 * 广播接收器属于后台上下文，Android 12 起不允许从这里直接 `startForegroundService`。
 * 不过**精确闹钟触发的广播是官方豁免场景之一**，所以 [StreamingService] 那条
 * 保活路径仍然值得试一次；失败也不影响生成本身，只是熄屏后更容易被冻结。
 * 这也是设置里那个「允许完全后台 / 电池优化」开关存在的理由。
 */
class ProactiveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? DshWatchApp ?: return

        // 开机 / 升级：AlarmManager 的排队不跨重启，必须重排
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    FishProactiveRunner.reschedule(context)
                } catch (t: Throwable) {
                    Logx.w(TAG, "开机重排失败：${t.message}")
                } finally {
                    pending.finish()
                }
            }
            return
        }

        if (intent.action != FishProactiveSchedule.ACTION_FIRE) return

        val pending = goAsync()
        val lock = acquireLock(context)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = app.container.settingsRepo.current()

                // 1) 谁触发的
                val plans = runCatching { app.container.proactiveStore.pending() }
                    .getOrDefault(emptyList())
                val now = System.currentTimeMillis()
                val due = plans.firstOrNull { it.triggerAt <= now + PLAN_GRACE_MS }
                if (due != null) {
                    runCatching { app.container.proactiveStore.remove(due.id) }
                }

                // 2) 排下一次（无论如何都要排）
                val left = runCatching { app.container.proactiveStore.pending().map { it.triggerAt } }
                    .getOrDefault(emptyList())
                runCatching { FishProactiveSchedule.sync(context, settings, left) }
                    .onFailure { Logx.w(TAG, "排下一次失败：${it.message}") }

                // 3) 该不该说话
                if (!settings.fishProactive && due == null) {
                    Logx.i(TAG, "主动发言已关闭，跳过")
                    return@launch
                }

                // 4) 生成
                FishProactiveRunner.run(context, due?.note.orEmpty())
            } catch (t: Throwable) {
                Logx.w(TAG, "主动发言失败：${t.message}")
            } finally {
                releaseLock(lock)
                pending.finish()
            }
        }
    }

    /**
     * 把 CPU 按住一小会儿。
     *
     * 闹钟广播本身只会让设备醒很短一瞬，生成要几十秒；这期间如果 CPU 又睡了，
     * 请求会停在半路。前台服务能解决大部分情况，但它不一定起得来（见类注释），
     * 这把锁是兜底。
     *
     * 限时 3 分钟并带超时：宁可这一轮没说完，也不能拿一把永不过期的锁
     * 把用户的表电池耗干。
     */
    private fun acquireLock(context: Context): PowerManager.WakeLock? = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dsh:fish-proactive").apply {
            setReferenceCounted(false)
            acquire(3 * 60 * 1000L)
        }
    }.getOrNull()

    private fun releaseLock(lock: PowerManager.WakeLock?) {
        runCatching { if (lock?.isHeld == true) lock.release() }
    }

    private companion object {
        const val TAG = "FishProactive"

        /** 计划时间与"现在"差这么多以内，就算这条触发的 */
        const val PLAN_GRACE_MS = 2 * 60 * 1000L
    }
}
