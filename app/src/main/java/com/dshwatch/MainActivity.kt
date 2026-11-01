package com.dshwatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshwatch.core.AppContainer
import com.dshwatch.ui.AppRoot
import com.dshwatch.ui.nav.rememberWatchNavigator
import com.dshwatch.ui.theme.DshWatchTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
}
