package com.dshwatch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshwatch.core.AppContainer
import com.dshwatch.ui.AppRoot
import com.dshwatch.ui.nav.rememberWatchNavigator
import com.dshwatch.ui.theme.DshWatchTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        val container = AppContainer.of(this)

        setContent {
            val settings by container.settings.collectAsStateWithLifecycle()
            val settingsReady by container.settingsReady.collectAsStateWithLifecycle()
            val perf by container.perf.collectAsStateWithLifecycle()
            val profile by container.profile.collectAsStateWithLifecycle()

            DshWatchTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                themePreset = settings.themePreset,
                accentColor = settings.accentColor,
                fontScale = settings.fontScale,
                appLanguage = settings.appLanguage,
                perf = perf,
                profile = profile,
            ) {
                // 设置没读出来之前什么都不画：否则 onboardingDone 的默认 false
                // 会让老用户每次启动都闪一帧引导页。
                if (settingsReady) {
                    AppRoot(
                        container = container,
                        settings = settings,
                        nav = rememberWatchNavigator(),
                    )
                }
            }
        }
    }

    /**
     * Android 13（API 33）起通知是运行时权限，不申请就等于没有。
     *
     * 这个 App 的前台服务通知和「任务跑完了」提醒都靠通知栏：
     * 手表上用户判断「它还在跑 / 它跑完了」几乎只靠这一条。
     * 之前清单里声明了 POST_NOTIFICATIONS，但全项目没有任何一处申请，
     * 结果在 API 33+ 设备上前台服务通知一直被系统静默拦掉。
     */
    @Suppress("DEPRECATION")
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        runCatching {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFY)
        }
    }

    private companion object {
        const val REQUEST_NOTIFY = 0x4455
    }
}
