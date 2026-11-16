package com.dshwatch.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.ui.ScreenHost
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.theme.LocalDeviceProfile

/**
 * 手机界面的根。
 *
 * ## 结构
 *
 * ```
 * Column
 *  ├─ Box(weight 1f)   当前页面（根页自带顶栏）
 *  └─ NavigationBar    对话 / 记录 / 设置
 * ```
 *
 * 为什么不直接用官方 `Scaffold`：`Scaffold` 的 `bottomBar` 会把
 * `PaddingValues` 往下传，而输入框要贴着底部导航上沿、还要跟着键盘走。
 * 自己排一个 Column 更直接 —— 页面拿到的是「导航栏以上的整块区域」。
 *
 * ## 次级页
 *
 * 栈深 > 1 时整屏交给 [ScreenHost]，底部导航收起。这些页面（模型、插件、
 * 配对、酒馆…）共用同一套实现，自带顶栏与返回键；手机上多一条底部导航
 * 只会挤掉本就不多的内容高度。
 *
 * ## 返回键
 *
 * 栈里有页面就 pop，栈空交给系统（退出 App）。手机上从「记录」按返回，
 * 用户期待的是退出，而不是回到上一个 tab。
 */
@Composable
fun PhoneRoot(
    container: AppContainer,
    settings: AppSettings,
) {
    val nav = rememberPhoneNavigator()
    val profile = LocalDeviceProfile.current

    BackHandler(enabled = nav.canGoBack) {
        nav.pop()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (nav.canGoBack) {
                // 少数页面值得单独做手机版：模型列表是首次配置的必经之路，
                // 用一条条全宽行摆出「名字 / 有没有填 Key / 用哪个模型」比卡片好用。
                // 其余次级页（表单、插件、配对、酒馆…）复用现有实现。
                when (nav.current) {
                    com.dshwatch.ui.nav.Screen.Providers ->
                        PhoneProvidersScreen(container = container, settings = settings, nav = nav)
                    else -> ScreenHost(
                        page = nav.current,
                        container = container,
                        settings = settings,
                        profile = profile,
                        nav = nav.watchStack,
                    )
                }
            } else {
                when (nav.tab) {
                    PhoneTab.Chat -> PhoneChatScreen(container = container, settings = settings, nav = nav)
                    PhoneTab.Sessions -> PhoneSessionsScreen(container = container, settings = settings, nav = nav)
                    PhoneTab.Settings -> PhoneSettingsScreen(container = container, settings = settings, nav = nav)
                }
            }
        }
        if (!nav.canGoBack) {
            PhoneBottomBar(nav)
        }
    }
}

@Composable
private fun PhoneBottomBar(nav: PhoneNavigator) {
    val s = LocalStrings.current
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        PhoneTab.entries.forEach { tab ->
            val selected = nav.tab == tab
            val label = when (tab) {
                PhoneTab.Chat -> s.navChat
                PhoneTab.Sessions -> s.navSessions
                PhoneTab.Settings -> s.navSettings
            }
            NavigationBarItem(
                selected = selected,
                onClick = {
                    // 再点一次当前 tab：回到根。用户连点两下想要的是「回到列表」
                    if (selected) nav.popToRoot() else nav.select(tab)
                },
                icon = {
                    Icon(
                        imageVector = when (tab) {
                            PhoneTab.Chat -> Icons.AutoMirrored.Filled.Chat
                            PhoneTab.Sessions -> Icons.AutoMirrored.Filled.List
                            PhoneTab.Settings -> Icons.Filled.Settings
                        },
                        contentDescription = label,
                    )
                },
                label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
