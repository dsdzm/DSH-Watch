package com.dshwatch.ui.theme

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双布局主题的静态防线。
 *
 * 手表这套的价值在**别自己发明 UI**：骨架、标题、返回、滚动指示器、卡片、
 * 按钮全部来自 `androidx.wear.compose.material3`。一旦有人图省事手搓一个
 * 「看起来像 Wear」的替代品，它就会在下一版慢慢跑偏 —— 这些断言守着这条线。
 */
class LayoutThemeRuleTest {

    private val root = File("src/main/java/com/dshwatch")

    private fun code(path: String): String {
        val f = File(root, path)
        assertTrue("找不到 $path", f.isFile)
        return f.readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")
    }

    @Test
    fun `底栏和主操作绝不能被静默丢弃`() {
        /*
         * 1.15.24 的真实事故：`bottom` 只在「没有返回键」时才渲染，
         * 而手表布局的对话/输入/供应商编辑页都有返回键 —— 于是保存、发送、
         * 输入这些控件整块消失，屏幕上什么都点不到。
         *
         * 这条断言守两件事：
         * 1. 骨架里必须真的 invoke bottom（要么在边键上，要么排在内容末尾）；
         * 2. 五个有主操作的页面必须给手表布局提供 edgeAction。
         */
        val screen = code("ui/wear/WearScreen.kt")
        assertTrue(
            "WearScreen 必须把 bottom 挂在 edgeAction 之外的分支上，不能只判 onBack",
            screen.contains("bottomAtEnd") && screen.contains("bottom?.invoke()"),
        )

        listOf(
            "ui/screens/chat/ChatScreen.kt",
            "ui/screens/chat/RoleplayChatScreen.kt",
            "ui/screens/input/InputScreen.kt",
            "ui/screens/provider/ProviderEditScreen.kt",
        ).forEach { path ->
            val s = code(path)
            assertTrue(
                "$path 必须在手表布局下提供 edgeAction —— 否则这块主操作没有任何入口",
                s.contains("edgeAction = if (watchLayout)"),
            )
        }
    }

    @Test
    fun `对话页的输入必须挂在屏幕下缘的边键上`() {
        val chat = code("ui/screens/chat/ChatScreen.kt")
        assertTrue(
            "输入动作要用 WearEdgeAction（官方 EdgeButton），不能再用横贯的底栏 —— " +
                "圆底那一行只有屏宽一半的弦，横条两端必被切掉",
            chat.contains("WearEdgeAction("),
        )
        assertTrue(
            "手表布局不能同时给 bottom：一个屏幕只有一个下缘",
            chat.contains("bottom = if (watchLayout)"),
        )
    }

    @Test
    fun `WatchScaffold 必须按布局主题分派到官方骨架`() {
        val common = code("ui/components/Common.kt")
        assertTrue(
            "WatchScaffold 里必须读 LocalLayoutTheme 并分派到 WearScreen",
            common.contains("LocalLayoutTheme") && common.contains("WearScreen("),
        )
        assertTrue(
            "列表页必须走 WatchListScaffold —— 它的 content 是 WearListScope，" +
                "列表 state 才能和 ScreenScaffold 共用一个",
            common.contains("fun WatchListScaffold(") && common.contains("WearListScreen("),
        )
    }

    @Test
    fun `手表骨架必须用官方 ScreenScaffold 而不是自绘`() {
        val screen = code("ui/wear/WearScreen.kt")
        assertTrue("必须用 ScreenScaffold", screen.contains("ScreenScaffold("))
        assertTrue("标题必须是 ListHeader（Wear 没有 top app bar）", screen.contains("ListHeader("))
        assertTrue("返回必须是 EdgeButton", screen.contains("EdgeButton("))
        assertTrue(
            "滚动指示器来自 ScreenScaffold，不能自己画 —— 所以必须把滚动状态交给它",
            screen.contains("ScrollInfoProvider(") || screen.contains("scrollState = state"),
        )
        assertFalse(
            "不能再自己按弦长算边距：官方 contentPadding 已经按屏宽比例给好了",
            screen.contains("insetAt("),
        )
    }

    @Test
    fun `不能再自己画时钟`() {
        val screen = code("ui/wear/WearScreen.kt")
        assertFalse(
            "时间由 AppScaffold 的 TimeText 沿圆顶弧线绘制，自绘的 SimpleDateFormat 版本会与它打架",
            screen.contains("SimpleDateFormat") || screen.contains("WearClock"),
        )
        val theme = code("ui/wear/WearTheme.kt")
        assertTrue("WearAppFrame 必须提供 AppScaffold", theme.contains("AppScaffold"))
        assertTrue(
            "必须把手机配色映射进 Wear 的 ColorScheme，否则 Wear 组件用默认紫灰色，与页面拼贴",
            theme.contains("ColorScheme("),
        )
    }

    @Test
    fun `滚动容器只能有一层`() {
        val chat = code("ui/screens/chat/ChatScreen.kt")
        assertTrue(
            "对话页消息区自带 LazyColumn + weight，骨架必须传 scrollable = false，" +
                "否则 weight 撞无限高度约束直接崩",
            chat.contains("scrollable = false"),
        )
        val lists = code("ui/wear/WearLists.kt")
        assertTrue(
            "wearVerticalScroll 在骨架已滚时必须放弃自己的滚动（返回 this）",
            lists.contains("if (scaffoldScroll != null) return this"),
        )
    }

    @Test
    fun `设置页的缩放滑块必须夹上限`() {
        val settings = code("ui/screens/settings/SettingsGroupScreen.kt")
        assertTrue(
            "滑块上限必须读 LocalUiScaleMax —— 手表布局是 1.0、手机布局是 1.15",
            settings.contains("LocalUiScaleMax"),
        )
        assertTrue(
            "滑块 value 必须先 coerceIn(range) 再交给 WatchSlider",
            settings.contains(".coerceIn(range.start, range.endInclusive)"),
        )
    }

    @Test
    fun `主题必须按布局切换排版`() {
        val theme = code("ui/theme/Theme.kt")
        assertTrue(
            "DshWatchTheme 要按 LocalLayoutTheme 在 DshTypography / WearTypography 之间切换",
            theme.contains("WearTypography") && theme.contains("isWatchLayout"),
        )
        assertTrue(
            "手表布局的推荐缩放值是 1.0（按屏重排），不能沿用 320dp 基准的 auto",
            theme.contains("isWatchLayout(layoutTheme)) 1f"),
        )
    }
}
