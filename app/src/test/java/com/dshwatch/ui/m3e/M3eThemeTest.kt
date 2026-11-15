package com.dshwatch.ui.m3e

import androidx.compose.material3.darkColorScheme
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Typography as WearTypography
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.dshwatch.domain.model.UiStyles
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3E 主题层的守卫。
 *
 * 这里测的不是「颜色好不好看」，而是几条**错了会很难发现**的不变量：
 * 两套 MaterialTheme 的色板必须同源，否则同一屏里自己画的字和 M3E 卡片的字
 * 会呈现两种白；切换界面体系不能让页面底色变化，否则用户会以为是换了主题。
 */
class M3eThemeTest {

    @Test
    fun `界面体系的未知取值一律落回经典`() {
        // 老存档里没有这个字段（空串）、或者手改坏了，都不能让界面画不出来
        assertThat(UiStyles.of("")).isEqualTo(UiStyles.CLASSIC)
        assertThat(UiStyles.of("m3e")).isEqualTo(UiStyles.M3E)
        assertThat(UiStyles.of("m3expressive")).isEqualTo(UiStyles.CLASSIC)
        assertThat(UiStyles.of("M3E")).isEqualTo(UiStyles.CLASSIC)
        assertThat(UiStyles.isM3e(UiStyles.M3E)).isTrue()
        assertThat(UiStyles.isM3e(UiStyles.CLASSIC)).isFalse()
    }

    @Test
    fun `Wear 色板与手机色板同源`() {
        val phone = darkColorScheme(
            primary = Color(0xFF7AB8FF),
            onPrimary = Color(0xFF00203F),
            primaryContainer = Color(0xFF1B3B5C),
            onPrimaryContainer = Color(0xFFCFE5FF),
            secondary = Color(0xFFB7C8DA),
            tertiary = Color(0xFFFFB4AB),
            background = Color(0xFF0E1116),
            onBackground = Color(0xFFE2E6EC),
            surface = Color(0xFF12161C),
            surfaceContainer = Color(0xFF171C23),
            surfaceContainerHigh = Color(0xFF1D232B),
            onSurface = Color(0xFFE2E6EC),
            onSurfaceVariant = Color(0xFFA8B2BF),
            outline = Color(0xFF6E7885),
            outlineVariant = Color(0xFF3A424C),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
        )

        val wear = wearColorScheme(phone)

        // 主色 / 正文色 / 背景：这三条只要有一条不一致，界面上就会看出色差
        assertThat(wear.primary).isEqualTo(phone.primary)
        assertThat(wear.onSurface).isEqualTo(phone.onSurface)
        assertThat(wear.onSurfaceVariant).isEqualTo(phone.onSurfaceVariant)
        assertThat(wear.background).isEqualTo(phone.background)
        assertThat(wear.error).isEqualTo(phone.error)

        // Wear 只有三档 surface，手机那边的名字不同，映射必须落在同一层
        assertThat(wear.surfaceContainerLow).isEqualTo(phone.surface)
        assertThat(wear.surfaceContainer).isEqualTo(phone.surfaceContainer)
        assertThat(wear.surfaceContainerHigh).isEqualTo(phone.surfaceContainerHigh)
    }

    @Test
    fun `透明背景不会让 Wear 色板也变透明`() {
        // 有自定义背景图时手机会把 background 换成 Transparent，
        // 好让照片透上来。Wear 的 background 是 AppScaffold 画的**实色底**，
        // 跟着变透明就等于把整页底色弄没了 —— 必须退回 surface。
        val phone = darkColorScheme(
            background = Color.Transparent,
            surface = Color(0xFF12161C),
        )
        val wear = wearColorScheme(phone)
        assertThat(wear.background).isEqualTo(phone.surface)
        assertThat(wear.background.alpha).isEqualTo(1f)
    }

    @Test
    fun `暗强调色不是纯黑`() {
        // primaryDim 用来画按下态。如果直接拿 primary 乘 alpha，深色底上会糊成一片黑，
        // 看起来像是"按钮坏了"。这里守的是"它必须还能看出主色"。
        val phone = lightColorScheme(primary = Color(0xFF2F5C8A), background = Color(0xFFFDFCFF))
        val wear = wearColorScheme(phone)
        val dim = wear.primaryDim
        assertThat(dim.alpha).isEqualTo(1f)
        assertThat(dim).isNotEqualTo(Color.Black)
        assertThat(dim.blue).isGreaterThan(dim.red)
    }

    @Test
    fun `按钮尺寸仍然是规范值`() {
        /*
         * 这条测试看着像在抄文档，其实是**升级哨兵**。
         *
         * M3eControls 里所有按钮都写死 `ButtonDefaults.Height` / `IconSize`，
         * 一旦 Wear Compose 改版把 52 改成 56、26 改成 24，界面上不会报错，
         * 只会「莫名变挤」或「图标显得小」—— 那种问题极难反查。
         * 这里把当前依赖的三个数钉住：库一升级，测试先红。
         *
         * 20sp 是按钮标签的字号（labelLarge），不是正文：手表上的按钮字
         * 必须比手机上大一号，用 14sp 会让整屏看起来像缩略图。
         */
        assertThat(ButtonDefaults.Height.value).isEqualTo(52f)
        assertThat(ButtonDefaults.IconSize.value).isEqualTo(26f)
        assertThat(ButtonDefaults.CompactButtonHeight.value).isEqualTo(48f)
        assertThat(WearTypography().labelLarge?.fontSize?.value).isEqualTo(20f)
        assertThat(WearTypography().labelMedium?.fontSize?.value).isEqualTo(15f)
    }

    /**
     * 对话框的按钮必须**先包一层 Column** 再塞进 `confirmButton`。
     *
     * 用户报的原话：
     * > 「m3e 主题有一些保存的选项没有了，只剩取消了」
     *
     * 根因在 Wear 的签名上：`confirmButton: @Composable RowScope.() -> Unit`。
     * 那个槽是一**行**，而 M3E 的按钮是整宽按钮 —— 两颗一起放进去，
     * 第一颗吃掉整行，第二颗被排到对话框外面，永远看不见。
     * 编译器对此一言不发，改坏了也不会报错，只会静默少一个按钮。
     *
     * 单测跑不起 Compose 布局，所以这里读源码守结构。
     */
    @Test
    fun `对话框按钮竖排，不横着挤掉第二颗`() {
        val f = File("src/main/java/com/dshwatch/ui/m3e/M3eControls.kt")
        assertTrue("找不到 M3eControls.kt：${f.absolutePath}", f.isFile)
        val src = f.readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")

        val slot = src.substringAfter("confirmButton = {").substringBefore("dismissButton")
        assertThat(slot).isNotEmpty()
        assertTrue(
            "confirmButton 是 RowScope：必须用 Column(fillMaxWidth) 包住 buttons()，否则第二颗按钮会被挤出行外",
            slot.contains("Column(") && slot.contains("buttons()"),
        )
        assertTrue(
            "fillMaxWidth 少了就等于把整行让给第一个按钮",
            slot.contains("fillMaxWidth"),
        )
    }

    /**
     * 新增的组件必须两套界面各有实现。
     *
     * 这条守着用户反复提过的那一点：
     * > 「所有插件都要适配任何 UI 模式」
     *
     * 两套界面的**结构**本来就不一样，靠「组件层分派」这一个机制兜住 ——
     * 页面代码写 `SettingCard`，经典下是玻璃卡、M3E 下是圆角底。
     * 但有一个陷阱：M3E 的 `M3eSettingCard` 自己**没有底色也没有内边距**，
     * 卡片感来自里面每一行自带的 `M3eStaticSurface`。所以任何「一行自定义内容」
     * 直接塞进 SettingCard，在 M3E 下就会变成裸文字浮在页面上 —— 不报错、
     * 只在 M3E 模式里看起来像没做完。
     *
     * 用量页的上下文卡片是最容易踩的一个（它里面有 Canvas 和五段自定义行），
     * 所以这里把它的两条分支都钉死。
     */
    @Test
    fun `上下文卡片两套界面各有容器`() {
        // v1.23.5 起这块从用量页搬到了 components（对话页顶栏也要开它），
        // 路径跟着改 —— 测试守的是「卡片必须有两种容器」，不是某个文件的位置。
        val f = File("src/main/java/com/dshwatch/ui/components/ContextUsage.kt")
        assertTrue("找不到 ContextUsage.kt：${f.absolutePath}", f.isFile)
        val src = f.readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")

        val card = src.substringAfter("fun ContextUsageCard(")
            .substringBefore("@Composable\nprivate fun ContextMeterBar(")
        assertThat(card).isNotEmpty()

        assertTrue(
            "M3E 分支要包 M3eStaticSurface：M3eSettingCard 本身没有底色",
            card.contains("M3eStaticSurface"),
        )
        assertTrue(
            "经典分支仍走 SettingCard，别把两套写成一模一样",
            card.contains("SettingCard("),
        )
        assertTrue(
            "要靠 isM3eUi 分派，不能只认一种界面",
            card.contains("isM3eUi"),
        )
        assertTrue(
            "M3E 的字阶必须取自 Wear 的 typography：labelMedium 在两边差了 3sp",
            card.contains("W3.typography"),
        )
    }

    /**
     * 对话页的速率读数在 M3E 下要短一档。
     *
     * 那一行是「模型 chip + Agent chip + 生成中」，圆屏的弦宽只有约 220dp。
     * 经典界面里 `生成中 · 38 tok/s` 就已经贴着边了，M3E 的 chip 更大，
     * 再塞同样的字符串会把 chip 挤成两行。所以 M3E 分支只留数字和单位。
     */
    @Test
    fun `生成速率在 M3E 下用短写法`() {
        val f = File("src/main/java/com/dshwatch/ui/screens/chat/ChatScreen.kt")
        assertTrue("找不到 ChatScreen.kt：${f.absolutePath}", f.isFile)
        val src = f.readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")

        assertTrue(
            "两处「生成中」都要带上速率，否则流式时看不出模型是不是卡住了",
            Regex("m3e -> \"\\\$tps t/s\"").containsMatchIn(src),
        )
        // 两处调用点（非编程场景 / 编程场景）
        val occurrences = Regex("\\\$tps t/s").findAll(src).count()
        assertThat(occurrences).isEqualTo(2)
    }

    /**
     * 种子色卡片在 M3E 下不能变成裸文字。
     *
     * 与上下文卡片踩的是同一个坑：M3E 的 `M3eSettingCard` 只是一列（无底色、
     * 无内边距），里面每一行必须自带 `M3eStaticSurface` 才有卡片感。
     * 色点网格是「一行自定义内容」，最容易漏。
     */
    @Test
    fun `种子色卡片两套界面各有容器`() {
        val f = File("src/main/java/com/dshwatch/ui/screens/settings/ThemeColorSection.kt")
        assertTrue("找不到 ThemeColorSection.kt：${f.absolutePath}", f.isFile)
        val src = f.readText()

        assertThat(src).contains("M3eStaticSurface")
        assertThat(src).contains("SettingCard(")
        assertThat(src).contains("isM3eUi")
        assertThat(src).contains("W3.typography")
        // 「跟随壁纸取色」的开关也要两套：经典走 SettingCard 包 SwitchRow
        assertThat(src).contains("SwitchRow(")
    }

    /**
     * 对话页顶栏加到五颗时，M3E 的命中区必须降档。
     *
     * 圆屏那一行的弦宽约 189dp。五颗 44dp = 220dp，两端的图标会被圆周切掉 ——
     * 而且**不会报错**，只是在某些表上看着"图标缺了个角"。
     */
    @Test
    fun `五颗动作时 M3E 顶栏降档`() {
        val f = File("src/main/java/com/dshwatch/ui/screens/chat/ChatScreen.kt")
        val src = f.readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")

        assertThat(src).contains("denseBar")
        assertThat(src).contains("diameter = if (denseBar) 36.dp else 44.dp")
        assertThat(src).contains("iconSize = if (denseBar) 24.dp else 26.dp")
    }
}
