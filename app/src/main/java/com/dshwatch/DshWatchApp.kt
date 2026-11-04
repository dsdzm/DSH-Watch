package com.dshwatch

import android.app.Application
import com.dshwatch.core.AppContainer
import com.dshwatch.core.CrashLog
import com.dshwatch.core.Logx
import com.dshwatch.domain.bili.BiliSchedule
import com.dshwatch.service.StreamingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class DshWatchApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 最开头就写下"启动中"标记。
        //
        // 顺序很重要：必须在任何可能崩的事情之前。这个 App 启动路径上有几个
        // 已知在国产 ROM 上易碎的环节（Keystore、外部存储、DataStore 首次建库），
        // 标记如果写在它们后面，崩在那一步就等于什么都没记下。
        CrashLog.beginLaunch(this)
        CrashLog.mark("Application.onCreate 开始")

        Logx.enabled = BuildConfig.DEBUG
        // 崩溃兜底要尽量早装：越早，能抓到的启动期问题越多
        CrashLog.install(this)
        CrashLog.mark("崩溃兜底已安装")

        // 下面这几步各自包一层：任何一步失败都不该让整个 App 起不来。
        // 用户报的是「装上就闪退」，而启动路径上绝大多数操作都不是必须的 ——
        // 读不到 GitHub token 就用不了 GitHub，但不该连主界面都进不去。
        container = runCatching { AppContainer(this) }
            .onFailure { Logx.e("App", "依赖容器构造失败", it) }
            .getOrElse { cause ->
                // 容器都构造不出来，说明是致命问题（磁盘满、权限异常）。
                // 这时候没有 container 什么都做不了，只能把现场写下来再抛。
                runCatching {
                    CrashLog.export(this, CrashLog.buildLaunchFailureReport(this), "启动失败-容器.txt")
                }
                throw cause
            }
        CrashLog.mark("依赖容器构造完成")

        // 生成开始 / 结束时同步前台服务，保证手表锁屏后不被冻结
        container.engine.onStreamingChanged = { streaming ->
            if (container.settings.value.foregroundService) {
                if (streaming) {
                    StreamingService.start(this)
                } else {
                    StreamingService.stop(this)
                    // 跑完给一条通知：手表上没人愿意反复抬腕确认「到底完没完」
                    if (container.settings.value.biliNotifyDone) StreamingService.notifyDone(this)
                }
            }
        }

        // 通知上写「正在做什么」。刷一轮 B站 动辄几十次工具调用，
        // 一直显示「正在生成」等于什么都没说。
        container.engine.onToolStarted = { text ->
            if (container.settings.value.foregroundService) {
                StreamingService.update(this, text)
            }
        }
        CrashLog.mark("引擎回调已挂上")

        // 定时刷 B站：启动时对齐一次，之后只在「开关 / 时间」变化时重排。
        // 订阅整个 settings 会让改个关键词也重排闹钟，没必要。
        //
        // 外层套 runCatching：AlarmManager 在部分 ROM 上对
        // setExactAndAllowWhileIdle 有额外限制，抛异常不该带走整个启动。
        /*
         * 心跳：每 30 秒把"我还活着"写进 filesDir/alive.at。
         *
         * 目的是让"上次没走完"这句话变得可解释。现在它只有两种可能，
         * 靠存活时长就能分开：
         *   - 几秒内就没 → 启动期崩溃或被系统杀，是故障
         *   - 跑了几小时才没 → 用户切走、系统回收后台，不算故障
         *
         * 用 appScope 里的死循环，而不是 AlarmManager：这是纯诊断用的，
         * 不值得为它换醒系统。进程被冻结时这个循环也跟着停 —— 那恰好也是
         * 我们想知道的事实（心跳停了）。
         */
        container.appScope.launch {
            while (true) {
                CrashLog.touchAlive(this@DshWatchApp)
                delay(30_000L)
            }
        }

        container.appScope.launch {
            runCatching {
                container.settings
                    .map { Triple(it.biliEnabled, it.biliAutoRun, it.biliAutoHour to it.biliAutoMinute) }
                    .distinctUntilChanged()
                    .collect { BiliSchedule.sync(this@DshWatchApp, container.settings.value) }
            }.onFailure { Logx.w("Bili", "定时同步失败", it) }
        }
        CrashLog.mark("Application.onCreate 结束")
    }
}
