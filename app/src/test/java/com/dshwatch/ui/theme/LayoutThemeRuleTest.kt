package com.dshwatch.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双布局主题的静态防线。
 *
 * 「手表布局」这套东西的价值全在**分派点唯一**：所有页面都通过 `WatchScaffold`
 * 拿到骨架，所以只要那一个点分派对了，就不可能出现「一半页面手表、一半页面手机」
 * 的拼贴。这些约束不会抛异常，只会让界面在某个角落悄悄坏掉，所以用源码结构守着。
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
    fun `WatchScaffold 必须按布局主题分派`() {
        val common = code("ui/components/Common.kt")
        assertTrue(
            "WatchScaffold 里必须读 LocalLayoutTheme 并分派到 WearM3Scaffold —— " +
                "否则页面仍然走旧的 CircleScaffold，手表布局等于没做",
            common.contains("LocalLayoutTheme") && common.contains("WearM3Scaffold"),
        )
    }

    @Test
    fun `手表骨架必须按弦长算内容宽度`() {
        val scaffold = code("ui/wear/WearM3Scaffold.kt")
        assertTrue(
            "WearM3Scaffold 的横向边距必须来自 CircleGeometry.insetAt —— " +
                "写死 padding 会在圆屏上下端把内容切掉",
            scaffold.contains("insetAt("),
        )
        assertTrue(
            "内容区高度必须固定成圆内的一段（contentBottom），不能用 weight(1f) 撑满",
            scaffold.contains("CONTENT_BOTTOM_RATIO"),
        )
    }

    @Test
    fun `设置页的缩放滑块必须夹上限`() {
        val settings = code("ui/screens/settings/SettingsGroupScreen.kt")
        assertTrue(
            "滑块上限必须读 LocalUiScaleMax —— 手表布局是 1.0、手机布局是 1.15，" +
                "写死 UiScale.MAX 会让手表布局也能拖到 1.15 并越出圆周",
            settings.contains("LocalUiScaleMax"),
        )
        assertTrue(
            "滑块 value 必须先 coerceIn(range) 再交给 WatchSlider，否则越界存值会画到轨道外",
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
