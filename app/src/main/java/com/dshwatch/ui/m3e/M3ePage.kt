package com.dshwatch.ui.m3e

import androidx.compose.runtime.Composable
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.roleplay.Scenes
import com.dshwatch.ui.m3e.screens.M3eChatScreen
import com.dshwatch.ui.m3e.screens.M3eSessionsScreen
import com.dshwatch.ui.m3e.screens.M3eSettingsScreen
import com.dshwatch.ui.m3e.screens.M3eUsageScreen
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator

/**
 * M3E 界面的页面分派。
 *
 * ## 为什么要有这一层，而不是继续在页面里写 `if (isM3eUi)`
 *
 * 之前 M3E 是「在经典页面的每个组件里分支」：顶栏分派、气泡分派、
 * 按钮分派、卡片分派。控件层这样做是对的（一套控件两种外观），但**页面结构**
 * 也这么做就出问题了 —— 页面骨架仍然是经典那套 `WatchScaffold + WearList`，
 * 只是里面换成了 M3E 的卡片。结果就是用户说的：**「布局还是手机样式的」**。
 *
 * 骨架决定了版式的根本：能放几个动作、内容从哪开始、滚动时元素怎么变化。
 * 这些不是换控件能改的，必须整页换掉。
 *
 * 所以这里按**页面**分派：M3E 下命中的页面走独立实现，没命中的回落到经典实现。
 * 返回 `true` 表示「这一页已经画完了」。
 *
 * ## 为什么不是一次全部替换
 *
 * 二十多个页面里，真正每天要走的是四条：对话、会话列表、设置、用量。
 * 剩下的是「配一次就不管」的深页（供应商编辑、配对、关于、更新日志…）。
 * 把它们一起重写，收益是「更一致」，代价是这一版几乎全部精力花在低频页上，
 * 而高频页的细节没人打磨 —— 这不是划算的交换。
 *
 * 落回的页面在 M3E 下**不会崩**：它们的控件（`SettingRow` / `Card` / 按钮）
 * 内部已经按 M3E 分派了，只是骨架还是经典那套。
 */
@Composable
fun M3ePage(
    page: Screen,
    container: AppContainer,
    settings: AppSettings,
    nav: WatchNavigator,
): Boolean {
    when (page) {
        // 根屏：编程场景才走 M3E 对话页。酒馆 / 写作有各自的列表与对话页，
        // 它们还没做 M3E 版，回落经典实现（控件仍是 M3E 的）。
        Screen.Chat -> {
            if (Scenes.of(settings.useScene) != Scenes.CODING) return false
            M3eChatScreen(container = container, settings = settings, nav = nav)
            return true
        }

        Screen.Sessions -> {
            M3eSessionsScreen(container = container, nav = nav)
            return true
        }

        is Screen.SceneSessions -> {
            M3eSessionsScreen(
                container = container,
                nav = nav,
                filterScene = page.scene,
                filterCardId = page.cardId,
            )
            return true
        }

        Screen.Settings -> {
            M3eSettingsScreen(container = container, settings = settings, nav = nav)
            return true
        }

        Screen.Usage -> {
            M3eUsageScreen(container = container, nav = nav)
            return true
        }

        else -> return false
    }
}
