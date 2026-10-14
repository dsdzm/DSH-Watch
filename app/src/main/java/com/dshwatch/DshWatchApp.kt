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
                if (streaming) StreamingService.start(this) else StreamingService.stop(this)
            }
        }
    }
}
