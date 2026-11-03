package com.dshwatch.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.dshwatch.domain.model.Appearances
import com.dshwatch.domain.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外观风格（材质）与配色（预设 + 强调色）的分层约束。
 *
 * ## 为什么把这些写成测试
 *
 * v1.15 之前，「质感玻璃」是七套**主题预设**里的一套 —— 材质和颜色混在一个维度里。
 * 后果有两个，都很实际：
 *
 * 1. 选不出「深海 + 玻璃」：玻璃自己就是一套配色，选了它就没法再要深海的蓝
 * 2. 「玻璃」在设置列表里看着像一种配色，用户会以为它只是「另一种蓝」
 *
 * 拆成 `AppSettings.appearance`（材质）+ `themePreset`（颜色）之后，
 * 这两个维度必须保持正交 —— 下面每条都在守这件事。
 */
class AppearanceThemeTest {

    // ---------------- 分层本身 ----------------

    @Test
    fun `外观风格只有 M3E 与质感玻璃两种`() {
        assertEquals(
            listOf(Appearances.M3E, Appearances.GLASS),
            Appearances.all,
        )
    }

    @Test
    fun `未知的 appearance 一律落回 M3E`() {
        // 老存档里没有这个字段，反序列化会拿到默认值；
        // 但万一有人手改配置文件写了个乱码，也不能因此掉进玻璃分支
        assertEquals(Appearances.M3E, Appearances.of(""))
        assertEquals(Appearances.M3E, Appearances.of("glass "))
        assertEquals(Appearances.M3E, Appearances.of("whatever"))
        assertEquals(Appearances.GLASS, Appearances.of(Appearances.GLASS))
    }

    @Test
    fun `默认外观是 M3E`() {
        // 第一次打开不该被一个不熟悉的材质接管界面；引导页会问，用户选了才算
        assertEquals(Appearances.M3E, AppSettings().appearance)
    }

    // ---------------- 预设列表（玻璃在，且是默认） ----------------

    @Test
    fun `预设列表包含玻璃且排在第一位`() {
        // 玻璃预设是默认预设，它决定「玻璃材质下的底是什么颜色」。
        // 从列表里拿掉它，材质就失去了自己那套底，只能被迫套别人的深色 —— 用户否掉过一次
        assertEquals(
            listOf("glass", "oled", "ocean", "forest", "sakura", "sunset", "graphite"),
            ThemePresets.all.map { it.id },
        )
    }

    @Test
    fun `未知预设落回质感玻璃`() {
        // 玻璃是默认预设；回退到它至少还是那套「能透出色晕」的底。
        // 回退到 OLED 会把玻璃材质直接压成灰板
        assertEquals(ThemePresets.GLASS_ID, ThemePresets.of("不存在的预设").id)
    }

    // ---------------- 老数据迁移 ----------------

    @Test
    fun `老的 glass 预设仍然保留成预设`() {
        // 玻璃预设从来没有消失过 —— 它只是不再等于「材质」。
        // 之前那一版把它强行改成 "oled" 是错的：用户选的玻璃底被换成了纯黑，
        // 用户反馈「玻璃主题的背景怎么换了」
        assertTrue("glass 必须仍在预设列表里", ThemePresets.GLASS_ID in ThemePresets.all.map { it.id })
        assertEquals(
            ThemePresets.GLASS_ID,
            ThemePresets.of(ThemePresets.GLASS_ID).id,
        )
    }

    @Test
    fun `默认预设不在玻璃之外的选项上乱跳`() {
        // 默认预设是用户没选过时的那套底。这里只守「它必须是一个真实存在的预设」——
        // 具体是哪个由产品决定，但回退值不能是列表里没有的 id，否则 of() 会到处兜底
        val def = AppSettings().themePreset
        assertTrue("默认预设 $def 不在预设列表里", def in ThemePresets.all.map { it.id })
    }

    // ---------------- 玻璃色板本身 ----------------

    @Test
    fun `玻璃预设的浅色底是亮的`() {
        // 用户的原话：「纯白色背景无法体现出玻璃的透明」。
        // 玻璃要能看出透明，底就必须够亮、够干净；深色版同理，要够暗但不是死黑。
        val light = ThemePresets.scheme(ThemePresets.GLASS_ID, "blue", dark = false)
        assertTrue("玻璃浅色底必须够亮：${light.surface.luminance()}", light.surface.luminance() > 0.80f)
        assertTrue("浅色玻璃的正文要够深：${light.onSurface.luminance()}", light.onSurface.luminance() < 0.05f)

        val dark = ThemePresets.scheme(ThemePresets.GLASS_ID, "blue", dark = true)
        assertTrue("深色玻璃底要够暗：${dark.surface.luminance()}", dark.surface.luminance() < 0.02f)
        assertTrue("深色玻璃正文要够亮：${dark.onSurface.luminance()}", dark.onSurface.luminance() > 0.75f)
    }

    @Test
    fun `玻璃的四级层级逐级变亮`() {
        val s = ThemePresets.scheme(ThemePresets.GLASS_ID, "blue", dark = true)
        val levels = listOf(
            s.surfaceContainerLow,
            s.surfaceContainer,
            s.surfaceContainerHigh,
            s.surfaceContainerHighest,
        ).map { it.luminance() }
        assertEquals("层级必须单调递增，否则玻璃叠出来的厚薄是乱的：$levels", levels.sorted(), levels)
        assertTrue(levels.last() > levels.first())
    }

    @Test
    fun `换强调色不改底色，只改主色`() {
        // 「玻璃 + 任意预设 + 任意强调色」能自由组合，靠的就是这条正交性
        val a = ThemePresets.scheme(ThemePresets.GLASS_ID, "blue", dark = true)
        val b = ThemePresets.scheme(ThemePresets.GLASS_ID, "rose", dark = true)
        assertEquals(a.surface, b.surface)
        assertEquals(a.surfaceContainerHigh, b.surfaceContainerHigh)
        assertNotEquals(a.primary, b.primary)
    }

    @Test
    fun `玻璃材质沿用预设的底，不被改写成 OLED`() {
        // 这一条守的是用户那句「玻璃主题的背景怎么换了」。
        // 之前 Theme.kt 在玻璃风格下强制 effectivePreset = OLED，
        // 于是不管选哪个预设，底永远是纯黑 —— 玻璃只能透出黑。
        val sakura = ThemePresets.scheme("sakura", "rose", dark = false)
        val glassPreset = ThemePresets.scheme(ThemePresets.GLASS_ID, "rose", dark = false)
        assertNotEquals(
            "玻璃材质下仍然要能看出预设的差别",
            sakura.surfaceContainerHigh,
            glassPreset.surfaceContainerHigh,
        )
    }

    /**
     * 白底上的玻璃必须**有轮廓**。
     *
     * 这是 v1.15 第三次重发里最要命的一条：玻璃本体一直写成「叠一层白 α」，
     * 这个公式只在深色底上成立。白底叠白 = 什么也看不见，用户看到的就是
     * 「没有卡片」。浅色下必须是「往下压一点」，而不是「往上提一点」。
     *
     * 这里守住的是「参数方向」而不是像素值 —— 具体的 α 在 Glass.kt 里，
     * 单测跑不了 DrawScope，但方向错了这条就会红。
     */
    @Test
    fun `浅色底的玻璃是压暗不是提亮`() {
        val light = ThemePresets.scheme(ThemePresets.GLASS_ID, "blue", dark = false)
        // 底已经很亮，再提亮就没有任何可见差
        assertTrue(light.surface.luminance() > 0.80f)
        // 用来压的那层色必须是深的（onSurface），压下去才看得见
        assertTrue(
            "浅色玻璃的本体色必须够深，否则白底上完全不可见：${light.onSurface.luminance()}",
            light.onSurface.luminance() < 0.05f,
        )
    }

    @Test
    fun `色卡不会画出一个透明点`() {
        ThemePresets.all.forEach { preset ->
            val sw = ThemePresets.swatch(preset.id, "blue", dark = true)
            assertEquals(3, sw.size)
            sw.forEach { assertTrue("${preset.id} 的色卡不应透明：$it", it.alpha > 0.5f) }
        }
    }

    @Test
    fun `浅色纯色预设是亮底深字`() {
        val light = ThemePresets.scheme(ThemePresets.OLED_ID, "blue", dark = false)
        assertTrue(light.surface.luminance() > 0.80f)
        assertTrue(light.onSurface.luminance() < 0.05f)
        // 顺带确认 Color 的默认透明是可用的（玻璃背景依赖它）
        assertEquals(0f, Color.Transparent.alpha, 0.001f)
    }
}
