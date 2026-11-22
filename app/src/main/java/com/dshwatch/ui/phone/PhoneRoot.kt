package com.dshwatch.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.ui.ScreenHost
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.Screen
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
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PhoneRoot(
    container: AppContainer,
    settings: AppSettings,
) {
    val nav = rememberPhoneNavigator()
    val profile = LocalDeviceProfile.current

    /*
     * 键盘弹出时把底部导航收起来。
     *
     * 不只是「看起来更干净」：导航栏在布局里占着 80dp，键盘又从屏幕底往上盖，
     * 于是对话页的 `imePadding` 等于「屏幕底 − 导航栏 − 键盘」，输入框会
     * 比键盘顶高出整整一条导航栏。机型不同（手势条 / 三键 / 键盘是否覆盖
     * 导航区）这条空白的高度也不同 —— 这就是「输入框在不同设备上位置不一样」。
     *
     * 收起之后那一块高度回到内容区，输入框正好贴在键盘上沿。
     */
    val imeVisible = WindowInsets.isImeVisible

    /*
     * 共用页面（酒馆、写作、插件…）内部会直接 `nav.push(Screen.Settings)`。
     *
     * 它们不知道自己在手机版里，于是会出现「压了一层手表设置页」：
     * 底部导航消失、返回键回不到刚才那一页。这里做一次收敛 ——
     * 如果栈顶其实是某个 tab 的根页，就当成切 tab 处理（清栈 + 选中）。
     */
    LaunchedEffect(nav.current, nav.canGoBack) {
        val owner = PhoneTab.of(nav.current)
        if (nav.canGoBack && owner != null) nav.select(owner)
    }

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
                val page = nav.current
                val appearance = page is Screen.SettingsGroup &&
                    page.id == com.dshwatch.ui.screens.settings.SettingsGroups.APPEARANCE
                when {
                    page == Screen.Providers ->
                        PhoneProvidersScreen(container = container, settings = settings, nav = nav)

                    /*
                     * 外观单独一页。
                     *
                     * 共用的 `SettingsGroupScreen` 里那张外观卡是**为手表写的**：
                     * 界面体系、玻璃材质、屏幕形状、整体缩放、看板娘 —— 这五项
                     * 手机主题一项都不读，摆出来就是五个按下去没反应的开关。
                     * 手机只看颜色（明暗 / 取色 / 种子色）与语言，所以另画一页。
                     */
                    appearance ->
                        PhoneAppearanceScreen(container = container, settings = settings, nav = nav)

                    else -> WatchStyledPage(bottomInset = true) {
                        ScreenHost(
                            page = page,
                            container = container,
                            settings = settings,
                            profile = profile,
                            nav = nav.watchStack,
                        )
                    }
                }
            } else {
                when (nav.tab) {
                    /*
                     * 「对话」这个 tab 按场景分发 —— 和手表那套同一套规则
                     * （见 `ScreenHost` 里 `Screen.Chat` 那条）。
                     *
                     * 之前这里写死 `PhoneChatScreen`，于是手机版只有编程一个模式：
                     * 设置里把场景切成酒馆，回到对话页仍然是编程界面，
                     * 而酒馆首页只能从手表界面进 —— 手机上等于不存在。
                     */
                    PhoneTab.Chat -> when (com.dshwatch.domain.roleplay.Scenes.of(settings.useScene)) {
                        com.dshwatch.domain.roleplay.Scenes.TAVERN ->
                            WatchStyledPage {
                                com.dshwatch.ui.screens.tavern.TavernScreen(
                                    container = container,
                                    settings = settings,
                                    nav = nav.watchStack,
                                )
                            }

                        com.dshwatch.domain.roleplay.Scenes.WRITING ->
                            WatchStyledPage {
                                com.dshwatch.ui.screens.writing.WritingScreen(
                                    container = container,
                                    settings = settings,
                                    nav = nav.watchStack,
                                )
                            }

                        else -> PhoneChatScreen(container = container, settings = settings, nav = nav)
                    }

                    PhoneTab.Sessions -> PhoneSessionsScreen(container = container, settings = settings, nav = nav)
                    PhoneTab.Settings -> PhoneSettingsScreen(container = container, settings = settings, nav = nav)
                }
            }
        }
        if (!nav.canGoBack && !imeVisible) {
            PhoneBottomBar(nav)
        }
    }
}

/**
 * 手表那套骨架的落地容器。
 *
 * 手机是 edge-to-edge 的（`enableEdgeToEdge`），而 `WatchScaffold` / `WatchTopBar`
 * 不知道有状态栏 —— 它们是为「表盘上没有状态栏」写的。不补这一层，
 * 酒馆 / 写作 / 插件这些页面的标题会正好压在系统时钟下面。
 *
 * 底部那一条只在**没有底部导航**时补：有导航时导航栏自己带了导航条内边距，
 * 再补一次会在最后一条内容下面多出十几 dp 的空白。
 */
@Composable
private fun WatchStyledPage(bottomInset: Boolean = false, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .then(if (bottomInset) Modifier.navigationBarsPadding() else Modifier),
    ) {
        content()
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
