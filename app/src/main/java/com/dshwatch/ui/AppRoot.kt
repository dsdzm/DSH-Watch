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

    /*
     * 系统返回手势 / 返回键：直接出栈。
     *
     * 页面切换的淡入淡出由 ExpansionHost 监听 current 变化自动播放，
     * 导航器不需要知道「动画」这件事，也不用记按钮位置。
     */
    BackHandler(enabled = nav.canGoBack) {
        nav.pop()
    }

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

    // 注：「启动完成」的判定和启动体检**不在这里**，已经挪到 MainActivity。
    //
    // 原因很具体：本函数开头有一段 `if (!onboardingDone) { 引导页; return }`，
    // 新用户装完先看到的是 4 步引导，走不到这里。他中途退出（很常见），
    // 启动标记就一直留着，下次启动报「上次没能启动完成」—— 这是个假阳性，
    // 而且刚好会污染「装上就闪退」这种反馈。
    //
    // 现在的判定点是「只要能画出内容就算启动成功」，见 MainActivity 的 LaunchedEffect。

    // 上次启动没走完，且这次也没有 Java 堆栈（否则 CrashNotice 已经有内容了）。
    // 这种情况直接给一份"没有堆栈的报告" —— 总比告诉用户"什么都没有"强：
    // 机型、ROM、ABI、走到第几步，这些已经能砍掉一大半可能性。
    var launchFailure by remember {
        mutableStateOf(
            if (com.dshwatch.core.CrashLog.launchWasIncomplete && com.dshwatch.core.CrashLog.lastText == null) {
                com.dshwatch.core.CrashLog.buildLaunchFailureReport(appCtx)
            } else {
                null
            },
        )
    }

    // 屏幕圆角安全边距由 MainActivity 注入的 DeviceProfile 决定
    val profile = com.dshwatch.ui.theme.LocalDeviceProfile.current

    // 对话页是唯一的根屏，其余页面一律 push 进来、用系统返回退出。
    // 1.10 之前这里是「对话 / 设置」两页横滑；在 Wear 上那条横滑手势会和
    // 系统的边缘返回打架（圆屏边缘右滑 = 返回上一级），所以改成标准的层级导航。
    // 页面切换由 ExpansionHost 用官方淡入淡出处理。
    // 背景由这一层的 Box 铺一层主题底色，这样两页交叉期间底下永远有纸，
    // 不会露出窗口的黑。
    //
    // **弹窗要糊背景**这件事仍然在这一层做：Dialog 画在另一个 Window 里，
    // 它模糊不了自己背后那个 Activity。所以每页都套一层 GlassBackdropHost，
    // 由它读一个闸（GlassDialog 打开时置 1），把页面子树整体糊掉。详见 Backdrop.kt。
    /*
     * 性能档位单独取一次，并在下面打一条日志。
     *
     * 为什么要在启动日志里写这一行：转场只在「动画开着」时存在，
     * 而"动画开没开"由设备档位 + 用户选的性能模式叠加决定。
     * 之前排查"没有动画"时，谁也不知道当时到底走的哪条分支 ——
     * 只能靠猜。现在启动体检里能直接看到。
     */
    val perfNow = com.dshwatch.ui.theme.LocalPerf.current
    androidx.compose.runtime.LaunchedEffect(perfNow) {
        com.dshwatch.core.Logx.i(
            "Perf",
            "档位=${perfNow.tier} 模式=${perfNow.mode} 动画=${perfNow.enableAnimations}",
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        com.dshwatch.ui.nav.ExpansionHost(
            nav = nav,
            // LocalPerf 由主题层注入，已经综合了设备档位和用户选的性能模式
            reducedMotion = !perfNow.enableAnimations,
        ) { page ->
            // resetKey 传 page：页面一换，宿主立刻把模糊清零。
            //
            // 不传的后果很具体：弹窗关闭后闸要 160ms 才淡出，用户在这期间返回上一页，
            // 新页面就带着上一页的模糊进来 —— 表现为「点进去只有一片糊，内容看不见」。
            // 光靠弹窗自己的 onDispose 管不到「页面换了」这件事。
            com.dshwatch.ui.components.GlassBackdropHost(resetKey = page) {
                when (page) {
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
                        groupId = page.id,
                        container = container,
                        settings = settings,
                        profile = profile,
                        nav = nav,
                    )

                    is Screen.WorkspacePreview -> WorkspacePreviewScreen(
                        container = container,
                        path = page.path,
                        nav = nav,
                    )
                    is Screen.ProviderEdit -> ProviderEditScreen(
                        container = container,
                        settings = settings,
                        providerId = page.providerId,
                        nav = nav,
                    )

                    is Screen.Input -> InputScreen(
                        container = container,
                        nav = nav,
                        initial = page.initial,
                    )

                    // ---------------- 场景（v1.13） ----------------
                    Screen.TavernHome -> TavernScreen(container = container, settings = settings, nav = nav)
                    Screen.WritingHome -> WritingScreen(container = container, settings = settings, nav = nav)
                    Screen.RoleplayChat -> RoleplayChatScreen(container = container, settings = settings, nav = nav)
                    is Screen.CardEdit -> CardEditScreen(
                        container = container,
                        nav = nav,
                        kind = page.kind,
                        cardId = page.cardId,
                    )
                    is Screen.MemoryDoc -> MemoryDocScreen(
                        container = container,
                        cardId = page.cardId,
                        nav = nav,
                    )
                }
            }
        }

        /*
         * 浮层：**在转场外面**。
         *
         * 以前它挂在 GlassBackdropHost 里面（也就是页面子树里）。转场改成
         * 两页同时存在之后，那样做会立刻坏两件事：
         *
         *   1. 浮层被画两份 —— 崩溃报告点掉一份，另一份还在，
         *      表现为「点了知道了没反应」
         *   2. 浮层跟着页面缩放 —— 它是屏幕级的东西，不该从按钮里长出来
         *
         * 代价是弹窗打开时浮层不再被一起模糊。可以接受：浮层自己是独立的卡片，
         * 而真正的弹窗（Dialog）本来就画在另一个 Window 上，从来不受影响。
         */
        Box(modifier = Modifier.fillMaxSize()) {
            // 崩溃报告：上次进程被未捕获异常带走时，这次启动把报告摆到用户面前
            com.dshwatch.ui.components.CrashNotice()

            // 上次启动没走到主界面（没有 Java 堆栈那种）。单独一个提示，
            // 因为这类情况用户最常说的是"闪退，没有日志" —— 得让他知道
            // 现在手里这份东西就是日志，只是长的不一样。
            launchFailure?.let { report ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    VersionNoticeCard(
                        title = "上次没能启动完成",
                        lines = listOf(
                            "没有拿到崩溃堆栈，但已经记下了启动信息和设备信息。",
                            "点「复制」发给我，就能判断是不是机型 / ROM 的问题。",
                            "也可以连电脑，从 Android/data/com.dshwatch/files/crash/ 取文件。",
                        ),
                        dismissLabel = "知道了",
                        onDismiss = { launchFailure = null },
                    )
                }
            }

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
