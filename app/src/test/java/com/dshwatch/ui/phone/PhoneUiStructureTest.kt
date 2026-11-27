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
            "PhoneScenes.kt",
            "PhoneRoleplayChat.kt",
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
        //
        // 现在这两个模式在手机上各自有一份 MD3 实现（`PhoneScenes.kt`），
        // 不再借手表的 `TavernScreen` / `WritingScreen` —— 那两份是为 200dp 圆屏写的。
        val root = code(src("PhoneRoot.kt"))
        assertThat(root).contains("Scenes.of(settings.useScene)")
        assertThat(root).contains("PhoneTavernScreen(")
        assertThat(root).contains("PhoneWritingScreen(")
        assertThat(root).doesNotContain("screens.tavern.TavernScreen")
        assertThat(root).doesNotContain("screens.writing.WritingScreen")
        assertThat(root).contains("FishPhoneScreen(")

        val chrome = code(src("PhoneChatChrome.kt"))
        assertThat(chrome).contains("fun PhoneSceneSheet(")
        assertThat(chrome).contains("Scenes.all.forEach")

        val chat = code(src("PhoneChatScreen.kt"))
        assertThat(chat).contains("PhoneSceneSheet(")
    }

    @Test
    fun `酒馆与写作的手机版共用同一套顶栏与卡片`() {
        val scenes = code(src("PhoneScenes.kt"))
        // 四个模式的顶栏必须是同一颗：☰ + 标题/副标题 + 模型药丸
        assertThat(scenes).contains("PhoneChatHeader(")
        assertThat(scenes).contains("PhoneModelSheet(")
        assertThat(scenes).contains("PhoneHistoryDrawer(")
        // 卡片不再用玻璃
        assertThat(scenes).doesNotContain("glassSurface(")
        // 两份列表页收拢成一个实现，靠参数区分
        assertThat(scenes).contains("private fun PhoneRoleList(")
        assertThat(scenes).contains("showImport = false")
        assertThat(scenes).contains("showImport = true")
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
    fun `对话页没有语音输入`() {
        // 语音要额外拉起系统识别器，而多数精简 ROM 上根本没有那一个；
        // 空框时那颗麦克风的结果是「按下去弹一句话」，不如换成发送键
        val chat = code(src("PhoneChatScreen.kt"))
        val chrome = code(src("PhoneChatChrome.kt"))
        listOf(chat, chrome).forEach { body ->
            assertThat(body).doesNotContain("ACTION_RECOGNIZE_SPEECH")
            assertThat(body).doesNotContain("RecognizerIntent")
            assertThat(body).doesNotContain("Icons.Filled.Mic")
        }
    }

    @Test
    fun `加号打开拍照相册文件三格`() {
        // 只拉起文件选择器的话，想发刚拍的照片得先去相册找一遍
        val chat = code(src("PhoneChatScreen.kt"))
        val chrome = code(src("PhoneChatChrome.kt"))
        assertThat(chat).contains("PhoneAttachSheet(")
        // 拍照实现搬进了公共组件（手表输入页也用同一份），
        // 这里改断言调用点，具体 contract 由 CameraShot 自己保证
        assertThat(chat).contains("rememberCameraShot(")
        /*
         * 相册与文件都走 rememberFilePicking，不再各自持有 ActivityResultLauncher：
         * 走哪条路由设置里的「用内置文件浏览器」决定，而系统那条路唤不起来时
         * 分派器会退回内置面板 —— 这两件事各写一遍就会各错一遍。
         */
        assertThat(chat).contains("rememberFilePicking()")
        assertThat(chat).contains("picking.pick(")
        assertThat(chat).doesNotContain("ActivityResultContracts.OpenDocument()")
        assertThat(chrome).contains("fun PhoneAttachSheet(")
        assertThat(chrome).contains("拍照")
        assertThat(chrome).contains("相册")
        assertThat(chrome).contains("文件")
    }

    @Test
    fun `左上角是历史记录抽屉`() {
        // 那颗按钮在聊天应用里叫「历史记录」：点开该看到一列会话，不是一列设置入口
        val chat = code(src("PhoneChatScreen.kt"))
        assertThat(chat).contains("ModalNavigationDrawer(")
        assertThat(chat).contains("PhoneHistoryDrawer(")

        val drawer = code(src("PhoneHistoryDrawer.kt"))
        assertThat(drawer).contains("ModalDrawerSheet(")
        // 长按才出操作：token / 重命名 / 置顶 / 删除
        assertThat(drawer).contains("onLongClick")
        assertThat(drawer).contains("关于对话")
        assertThat(drawer).contains("重命名")
        // 按自然日分组，不是按 24 小时差值
        assertThat(drawer).contains("昨天")
        assertThat(drawer).contains("7 天内")
    }

    @Test
    fun `手机外观页不摆手表专有的开关`() {
        // 界面体系 / 玻璃材质 / 屏幕形状 / 整体缩放 / 看板娘在手机上按下去都不动，
        // 摆出来只会让用户以为设置是装饰
        val body = code(src("PhoneAppearanceScreen.kt"))
        listOf("uiStyle", "appearance", "screenShape", "uiScale", "showMascot", "chatLayout")
            .forEach { field -> assertThat(body).doesNotContain("settings.$field") }
        // 真能生效的那几项必须在
        assertThat(body).contains("dynamicColor")
        assertThat(body).contains("seedColor")
        assertThat(body).contains("themeMode")
    }

    @Test
    fun `手机主题真的用种子色生成色板`() {
        // 1.24.0 的手机主题只有「系统动态取色 + 写死 baseline」两条路，
        // 设置里的种子色在这套界面上点完什么都不变
        val theme = code(src("PhoneTheme.kt"))
        assertThat(theme).contains("SeedSource.resolve")
        assertThat(theme).contains("PhonePalette.of")

        val palette = code(src("PhonePalette.kt"))
        assertThat(palette).contains("MonetScheme.of")
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

    @Test
    fun `角色对话页与写作对话页在手机上有自己的实现`() {
        // 这两件事以前都落进 `else -> ScreenHost`：从角色卡点「开始对话」、
        // 从记录点开一条会话，落地的都是 `WatchScaffold` 写的那一页。
        val root = code(src("PhoneRoot.kt"))
        assertThat(root).contains("Screen.RoleplayChat ->")
        assertThat(root).contains("PhoneRoleplayChatScreen(")

        val page = code(src("PhoneRoleplayChat.kt"))
        assertThat(page).contains("PhoneChatHeader(")
        assertThat(page).contains("PhoneComposer(")
        // 对话页是压栈进来的：顶栏必须有返回，不能被当成根页
        assertThat(page).contains("onBack =")
        // 写作的正文整宽铺开，不塞进 0.86 宽的气泡
        assertThat(page).contains("isWriting")
        assertThat(page).contains("fillMaxWidth(if (fullWidth) 1f else 0.86f)")
    }

    @Test
    fun `养鱼会话交给养鱼页渲染而不是角色对话页`() {
        // 「从记录点进养鱼的对话，回到手表对话页」的根因：会话页不看会话自己的场景。
        val page = code(src("PhoneRoleplayChat.kt"))
        assertThat(page).contains("scene == Scenes.FISH")
        assertThat(page).contains("FishPhoneScreen(")

        // 那一页本来是对话 tab 的根；被压栈进来时要能退回记录列表
        val fish = File("src/main/java/com/dshwatch/ui/screens/fish/FishPhoneScreen.kt").readText()
        assertThat(fish).contains("onBack: (() -> Unit)? = null")
    }

    @Test
    fun `从记录打开会话会把当前模式一起切过去`() {
        // 不切的话：设置停在养鱼，点开一条酒馆会话——会话是酒馆的、界面还是鱼，
        // 用户会以为"点了没反应"。
        val sessions = code(src("PhoneSessionsScreen.kt"))
        assertThat(sessions).contains("val scene = Scenes.of(conv.scene)")
        assertThat(sessions).contains("copy(useScene = scene)")

        val chat = code(src("PhoneChatScreen.kt"))
        assertThat(chat).contains("copy(useScene = target)")
    }

    @Test
    fun `某张卡的历史在手机上也是记录页`() {
        // 手表那版是 WearList：点开一张卡的历史却看到表盘列表，等于换了 App
        val root = code(src("PhoneRoot.kt"))
        assertThat(root).contains("Screen.SceneSessions")
        assertThat(root).contains("filterCardId")

        val sessions = code(src("PhoneSessionsScreen.kt"))
        assertThat(sessions).contains("filterScene")
        assertThat(sessions).contains("listFor(")
        assertThat(sessions).contains("onBack")
    }
}
