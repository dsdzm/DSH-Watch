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
            val perf by container.perf.collectAsStateWithLifecycle()
            val profile by container.profile.collectAsStateWithLifecycle()

            DshWatchTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                fontScale = settings.fontScale,
                perf = perf,
                profile = profile,
            ) {
                AppRoot(
                    container = container,
                    settings = settings,
                    nav = rememberWatchNavigator(),
                )
            }
        }
    }
}
