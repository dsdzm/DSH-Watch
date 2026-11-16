package com.dshwatch.ui.phone

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * 守手机版的**独立性与结构**：它不能偷偷借用手表那一套。
 *
 * 为什么这个约束值得写测试：手机版和手表版在同一个模块里，`ThemePresets`、
 * `WatchScaffold`、`glassSurface` 全都是可以直接 import 的。一次「顺手复用」
 * 就会让手机界面变成「放大版手表」—— 而这种事在代码评审里很难看出来，
 * 编译、测试、运行都不会报错，只有用户会。
 */
class PhoneUiStructureTest {

    private fun src(path: String): String {
        val f = File("src/main/java/com/dshwatch/ui/phone/$path")
        assertThat(f.isFile).isTrue()
        return f.readText()
    }

    /**
     * 去注释：注释里会提到这些名字（解释「为什么不用」），不能算违规。
     *
     * 两条 lookbehind 是必需的：附件选择器传的 MIME 通配符（星号 斜杠 星号）
     * 里含有块注释的开头，会被当成注释起点一路吞到下一个注释结尾 ——
     * 第一次写这层过滤时它把整段输入栏代码吃掉了，于是「有 PhoneSceneSheet 调用」
     * 这条断言莫名其妙地失败。`://` 同理，URL 里的双斜杠不是注释。
     */
    private fun code(text: String): String = text
        .replace(Regex("(?<=\\s|^)\\Q/*\\E[\\s\\S]*?\\Q*/\\E"), "")
        .replace(Regex("(?<!:)//[^\\n]*"), "")

    @Test
    fun `手机主题不读手表的外观字段`() {
        val body = code(src("PhoneTheme.kt"))
        // 这些是手表那套的东西：预设色板、强调色、玻璃/纯色材质、界面体系、
        // 屏幕形状、整体缩放。手机版一个都不该读 —— 它只有明暗和动态取色。
        listOf("themePreset", "accentColor", "appearance", "uiStyle", "screenShape", "uiScale")
            .forEach { field ->
                assertThat(body).doesNotContain("settings.$field")
            }
    }

    @Test
    fun `手机页面不用手表骨架与玻璃材质`() {
        val pages = listOf(
            "PhoneRoot.kt",
            "PhoneChatScreen.kt",
            "PhoneSessionsScreen.kt",
            "PhoneSettingsScreen.kt",
            "PhoneOnboarding.kt",
        )
        for (page in pages) {
            val body = code(src(page))
            listOf("WatchScaffold", "WearList", "glassSurface", "M3eScaffold", "CircleScaffold")
                .forEach { component ->
                    assertThat(body).doesNotContain("$component(")
                }
        }
    }

    @Test
    fun `手机主页面走官方 Material3 组件`() {
        val chat = code(src("PhoneChatScreen.kt"))
        // 顶栏、输入栏按参考图重做过：一颗圆形菜单键 + 两个药丸 + 悬浮输入胶囊。
        // 不用 TopAppBar —— 它的标题区容不下「药丸套药丸」，硬塞只能往 title 里
        // 再堆一个 Row。组件本身仍是 M3 的（Surface / TextField / FilledIconButton）。
        assertThat(chat).contains("PhoneChatHeader(")
        assertThat(chat).contains("PhoneComposer(")
        assertThat(chat).contains("GreetingHome(")

        val chrome = code(src("PhoneChatChrome.kt"))
        assertThat(chrome).contains("TextField(")
        assertThat(chrome).contains("ModalBottomSheet(")

        val sessions = code(src("PhoneSessionsScreen.kt"))
        assertThat(sessions).contains("LargeTopAppBar(")
        assertThat(sessions).contains("LazyColumn(")

        val root = code(src("PhoneRoot.kt"))
        assertThat(root).contains("NavigationBar(")
        assertThat(root).contains("NavigationBarItem(")
    }

    @Test
    fun `手机版能进酒馆与写作`() {
        // 之前只把 PhoneChatScreen 写死在「对话」tab 上，场景切换只存在于手表界面，
        // 手机用户根本进不去这两个模式。三处都必须有出口，缺一处就断链。
        val root = code(src("PhoneRoot.kt"))
        assertThat(root).contains("Scenes.of(settings.useScene)")
        assertThat(root).contains("TavernScreen(")
        assertThat(root).contains("WritingScreen(")

        val chrome = code(src("PhoneChatChrome.kt"))
        assertThat(chrome).contains("fun PhoneSceneSheet(")
        assertThat(chrome).contains("Scenes.all.forEach")

        val chat = code(src("PhoneChatScreen.kt"))
        assertThat(chat).contains("PhoneSceneSheet(")
    }

    @Test
    fun `酒馆会话由角色扮演页打开而不是手机对话页`() {
        // 手机对话页不认识角色卡与剧情开关，把酒馆会话塞进去等于剥掉上下文展示
        val sessions = code(src("PhoneSessionsScreen.kt"))
        assertThat(sessions).contains("Screen.RoleplayChat")
        assertThat(sessions).contains("Scenes.CODING")
    }

    @Test
    fun `手机界面的页面容器补状态栏内边距`() {
        // 手机是 edge-to-edge 的，手表骨架不知道有状态栏：不补这一层，
        // 酒馆 / 写作 / 插件这些页面的标题会压在系统时钟下面。
        assertThat(code(src("PhoneRoot.kt"))).contains("statusBarsPadding()")
        assertThat(code(src("PhoneChatChrome.kt"))).contains("statusBarsPadding()")
    }

    @Test
    fun `语音输入走系统识别器并兜住缺失`() {
        val chat = code(src("PhoneChatScreen.kt"))
        assertThat(chat).contains("ACTION_RECOGNIZE_SPEECH")
        // 精简 ROM 上没有识别器，launch 会抛；不接住就是一个按下去没反应的麦克风
        assertThat(chat).contains("runCatching { voice.launch(intent) }")
    }

    @Test
    fun `底部导航恰好三个入口`() {
        val root = code(src("PhoneNavigator.kt"))
        // 枚举声明必须只有这三项 —— 多一个会挤，少一个做不到「翻记录」
        assertThat(root).contains("Chat,")
        assertThat(root).contains("Sessions,")
        assertThat(root).contains("Settings,")
        assertThat(PhoneTab.entries).hasSize(3)
    }

    @Test
    fun `次级页复用同一份 ScreenHost 而不是另写一套`() {
        val root = code(src("PhoneRoot.kt"))
        assertThat(root).contains("ScreenHost(")
    }
}
