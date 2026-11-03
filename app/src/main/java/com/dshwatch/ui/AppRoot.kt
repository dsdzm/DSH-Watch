package com.dshwatch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.components.VersionNoticeCard
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.screens.about.AboutScreen
import com.dshwatch.ui.screens.about.DonateScreen
import com.dshwatch.ui.screens.about.GroupChatScreen
import com.dshwatch.ui.screens.about.ThanksScreen
import com.dshwatch.ui.screens.chat.ChatScreen
import com.dshwatch.ui.screens.chat.RoleplayChatScreen
import com.dshwatch.ui.screens.tavern.CardEditScreen
import com.dshwatch.ui.screens.tavern.MemoryDocScreen
import com.dshwatch.ui.screens.tavern.TavernScreen
import com.dshwatch.ui.screens.writing.WritingScreen
import com.dshwatch.ui.screens.diagnostics.DiagnosticsScreen
import com.dshwatch.ui.screens.input.InputScreen
import com.dshwatch.ui.screens.pair.PairScreen
import com.dshwatch.ui.screens.provider.ProviderEditScreen
import com.dshwatch.ui.screens.provider.ProvidersScreen
import com.dshwatch.ui.screens.sessions.SessionsScreen
import com.dshwatch.ui.screens.settings.BiliLoginScreen
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
    // 首次启动：先走三步引导（形状 / 布局 / 主题）。
    // 引导页不挂导航栈，也不允许返回键退出 —— 它本身就是根。
    if (!settings.onboardingDone) {
        com.dshwatch.ui.screens.onboarding.OnboardingScreen(
            container = container,
            settings = settings,
        )
        return
    }

    BackHandler(enabled = nav.canGoBack) { nav.pop() }

    /*
     * 一次性版本说明。
     *
     * v1.12 悄悄移除了本地 Python 引擎 —— 这是用户会当场撞上的事：
     * 以前能跑 `python xxx.py`，现在会失败。静默改行为的代价是用户
     * 以为是坏了，所以升级后第一次进主界面要说一句，看到就写回，不再重复。
     *
     * 判定用 lastSeenVersion < 触发版本，而不是"等于某个值"：
     * 从更老的版本一路升上来的用户同样该看到。
     */
    val scope = rememberCoroutineScope()
    val appCtx = LocalContext.current
    val currentVersion = remember(appCtx) {
        runCatching {
            appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    var noticeDismissed by remember(settings.lastSeenVersion) { mutableStateOf(false) }
    val showPythonNotice = !noticeDismissed && shouldShowPythonNotice(settings.lastSeenVersion, currentVersion)

    // 屏幕圆角安全边距由 MainActivity 注入的 DeviceProfile 决定
    val profile = com.dshwatch.ui.theme.LocalDeviceProfile.current

    // 对话页是唯一的根屏，其余页面一律 push 进来、用系统返回退出。
    // 1.10 之前这里是「对话 / 设置」两页横滑；在 Wear 上那条横滑手势会和
    // 系统的边缘返回打架（圆屏边缘右滑 = 返回上一级），所以改成标准的层级导航。
    // 页面切换**不做任何转场动画**。
    //
    // v1.15 试过一版「新页从下方弹入、旧页缩小淡出」，在手表上直接崩 —— 而且就算不崩，
    // 手表屏幕一共就 200dp 宽，把两页同时放进组合再互相错位、缩放，
    // 换来的不是「灵动」，是「慢半拍」。这一版整段撤掉，页面直接换。
    //
    // 但**弹窗要糊背景**这件事必须在这一层做：Dialog 画在另一个 Window 里，
    // 它模糊不了自己背后那个 Activity。所以整块页面交给 GlassBackdropHost，
    // 由它读一个闸（GlassDialog 打开时置 1），把页面子树整体糊掉。详见 Backdrop.kt。
    val screen = nav.current
    com.dshwatch.ui.components.GlassBackdropHost {
    when (screen) {
        // 根屏按「用户的主要使用场景」分发：编程进工作台，酒馆/写作进各自的列表。
        // 这样用户选了"角色扮演"，进 App 直接看到角色，而不是先看一张编程界面。
        Screen.Chat -> when (Scenes.of(settings.useScene)) {
            Scenes.TAVERN -> TavernScreen(container = container, settings = settings, nav = nav)
            Scenes.WRITING -> WritingScreen(container = container, settings = settings, nav = nav)
            else -> ChatScreen(container = container, settings = settings, nav = nav)
        }
        Screen.Sessions -> SessionsScreen(container = container, nav = nav)
        Screen.Settings -> SettingsScreen(container = container, settings = settings, profile = profile, nav = nav)
        Screen.Providers -> ProvidersScreen(container = container, settings = settings, nav = nav)
        Screen.Usage -> UsageScreen(container = container, nav = nav)
        Screen.Pair -> PairScreen(container = container, settings = settings, nav = nav)
        Screen.About -> AboutScreen(container = container, nav = nav)
        Screen.GroupChat -> GroupChatScreen(nav = nav)
        Screen.Donate -> DonateScreen(nav = nav)
        Screen.Thanks -> ThanksScreen(nav = nav)
        Screen.Diagnostics -> DiagnosticsScreen(container = container, settings = settings, nav = nav)
        Screen.Workspace -> WorkspaceScreen(container = container, nav = nav)
        Screen.BiliLogin -> BiliLoginScreen(
            container = container,
            nav = nav,
        )
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

        // ---------------- 场景（v1.13） ----------------
        Screen.TavernHome -> TavernScreen(container = container, settings = settings, nav = nav)
        Screen.WritingHome -> WritingScreen(container = container, settings = settings, nav = nav)
        Screen.RoleplayChat -> RoleplayChatScreen(container = container, settings = settings, nav = nav)
        is Screen.CardEdit -> CardEditScreen(
            container = container,
            nav = nav,
            kind = screen.kind,
            cardId = screen.cardId,
        )
        is Screen.MemoryDoc -> MemoryDocScreen(
            container = container,
            cardId = screen.cardId,
            nav = nav,
        )
    }

    // 浮层单独一层 Box：页面自己没有变换，但浮层仍然要和页面的布局分开，
    // 否则 CrashNotice 会被页面的 padding / 滚动根卷进去
    Box(modifier = Modifier.fillMaxSize()) {
        // 崩溃报告：上次进程被未捕获异常带走时，这次启动把报告摆到用户面前
        com.dshwatch.ui.components.CrashNotice()

        // 顶部浮层：只盖住上沿，不挡下面的滑动与返回手势
        if (showPythonNotice) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                VersionNoticeCard(
                    title = "本地 Python 已移除",
                    lines = listOf(
                        "为了缩小安装包，v1.12 起不再内置 Python 运行时，只保留设备自带的 shell 与 JavaScript。",
                        "需要跑 Python / Java / C++ 时，交给云端执行就行；在 shell 里敲 python 会明确提示「请用云端」。",
                    ),
                    dismissLabel = "知道了",
                    onDismiss = {
                        noticeDismissed = true
                        scope.launch {
                            container.settingsRepo.update { it.copy(lastSeenVersion = currentVersion) }
                        }
                    },
                )
            }
        }
    }
    }
}

/**
 * 是否该弹「本地 Python 已移除」。
 *
 * 从 v1.12 起本地没有 Python。所以：
 *  - 从没记录过版本（老用户升级上来）→ 提示一次；
 *  - 记录过但低于 1.12 → 提示；
 *  - 已经是 1.12+ 装过又升级（记录里有值且 >= 1.12）→ 不再提示。
 *
 * 版本号按段比较，不能用字符串比 —— "1.9" > "1.12" 是字符串事实，不是版本事实。
 */
internal fun shouldShowPythonNotice(lastSeen: String, current: String): Boolean {
    if (current.isBlank()) return false
    if (lastSeen.isBlank()) return true
    return compareVersion(lastSeen, "1.12") < 0
}

internal fun compareVersion(a: String, b: String): Int {
    fun parts(v: String) = v.trim().split('.', '-').mapNotNull { it.toIntOrNull() }
    val pa = parts(a)
    val pb = parts(b)
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val d = (pa.getOrNull(i) ?: 0).compareTo(pb.getOrNull(i) ?: 0)
        if (d != 0) return d
    }
    return 0
}
