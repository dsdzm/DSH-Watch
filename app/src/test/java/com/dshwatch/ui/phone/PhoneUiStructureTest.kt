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

    /** 去注释：注释里会提到这些名字（解释「为什么不用」），不能算违规 */
    private fun code(text: String): String = text
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .replace(Regex("//[^\\n]*"), "")

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
        assertThat(chat).contains("TopAppBar(")
        assertThat(chat).contains("OutlinedTextField(")

        val sessions = code(src("PhoneSessionsScreen.kt"))
        assertThat(sessions).contains("LargeTopAppBar(")
        assertThat(sessions).contains("LazyColumn(")

        val root = code(src("PhoneRoot.kt"))
        assertThat(root).contains("NavigationBar(")
        assertThat(root).contains("NavigationBarItem(")
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
