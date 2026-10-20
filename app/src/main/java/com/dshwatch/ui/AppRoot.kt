package com.dshwatch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.screens.about.AboutScreen
import com.dshwatch.ui.screens.chat.ChatScreen
import com.dshwatch.ui.screens.diagnostics.DiagnosticsScreen
import com.dshwatch.ui.screens.input.InputScreen
import com.dshwatch.ui.screens.pair.PairScreen
import com.dshwatch.ui.screens.provider.ProviderEditScreen
import com.dshwatch.ui.screens.provider.ProvidersScreen
import com.dshwatch.ui.screens.sessions.SessionsScreen
import com.dshwatch.ui.screens.settings.ExecSettingsScreen
import com.dshwatch.ui.screens.settings.SettingsGroupScreen
import com.dshwatch.ui.screens.settings.SettingsScreen
import com.dshwatch.ui.screens.usage.UsageScreen
import com.dshwatch.ui.screens.workspace.WorkspacePreviewScreen
import com.dshwatch.ui.screens.workspace.WorkspaceScreen

@Composable
fun AppRoot(
    container: AppContainer,
    settings: AppSettings,
    nav: WatchNavigator,
) {
    BackHandler(enabled = nav.canGoBack) { nav.pop() }

    // 屏幕圆角安全边距由 MainActivity 注入的 DeviceProfile 决定
    val profile = com.dshwatch.ui.theme.LocalDeviceProfile.current

    // 对话页与设置页是同一层的两页（横滑切换），共用同一个 Pager 容器 ——
    // 这样滑动过程中的动画、指示点、页面状态都不会因为导航而重建
    if (nav.current == Screen.Chat || nav.current == Screen.Settings) {
        MainPager(
            container = container,
            settings = settings,
            profile = profile,
            nav = nav,
        )
    } else when (val screen = nav.current) {
        Screen.Chat -> ChatScreen(container = container, settings = settings, nav = nav)
        Screen.Sessions -> SessionsScreen(container = container, nav = nav)
        Screen.Settings -> SettingsScreen(container = container, settings = settings, profile = profile, nav = nav)
        Screen.Providers -> ProvidersScreen(container = container, settings = settings, nav = nav)
        Screen.Usage -> UsageScreen(container = container, nav = nav)
        Screen.Pair -> PairScreen(container = container, settings = settings, nav = nav)
        Screen.About -> AboutScreen(container = container, nav = nav)
        Screen.Diagnostics -> DiagnosticsScreen(container = container, settings = settings, nav = nav)
        Screen.Workspace -> WorkspaceScreen(container = container, nav = nav)
        Screen.ExecSettings -> ExecSettingsScreen(
            container = container,
            settings = settings,
            nav = nav,
        )
        is Screen.SettingsGroup -> SettingsGroupScreen(
            groupId = screen.id,
            container = container,
            settings = settings,
            profile = profile,
            nav = nav,
        )

        is Screen.WorkspacePreview -> WorkspacePreviewScreen(
            container = container,
            path = screen.path,
            nav = nav,
        )
        is Screen.ProviderEdit -> ProviderEditScreen(
            container = container,
            settings = settings,
            providerId = screen.providerId,
            nav = nav,
        )

        is Screen.Input -> InputScreen(
            container = container,
            nav = nav,
            initial = screen.initial,
        )
    }
}
