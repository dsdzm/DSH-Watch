package com.dshwatch

import android.app.Application
import com.dshwatch.core.AppContainer
import com.dshwatch.core.Logx
import com.dshwatch.service.StreamingService

class DshWatchApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Logx.enabled = BuildConfig.DEBUG
        container = AppContainer(this)

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
    }
}
