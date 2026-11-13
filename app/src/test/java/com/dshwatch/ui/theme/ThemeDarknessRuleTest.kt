package com.dshwatch.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 明暗必须来自主题，不能从一个可能透明的颜色里反推。
 *
 * ## 这组测试守的是一个真实事故
 *
 * v1.22.3 修好了深色弹窗的对比度（卡片先压 0.68 的黑，再叠 0.12 的白），
 * 结果用户在**浅色模式**里看到的是一块黑卡片配深色字 —— 什么都读不出来。
 *
 * 根因不在那两笔，在判据：
 *
 * ```kotlin
 * val dark = MaterialTheme.colorScheme.background.luminance() < 0.35f
 * ```
 *
 * 「质感玻璃」预设的 `background` 是 `Color.Transparent`（透明是玻璃能透出
 * 底下色晕的前提，见 ThemePresets.GlassPreset）。而 `luminance()` 只看 RGB、
 * 不看 alpha —— 透明色算出来是 0，于是**浅色主题也被判成深色**。
 *
 * 这个错误早就存在，只是以前深色分支画的是「白 0.26」，在浅色底上看着像
 * 一块灰玻璃，谁都不会觉得不对。v1.22.3 把它换成 0.68 的黑之后，
 * 同一个错误立刻变成了「浅色模式怎么变黑了」。
 *
 * 教训：**明暗是主题的属性**。`ThemeMode` 已经在 [DshWatchTheme] 里解析成
 * 一个布尔值了，往下传就行，没必要让每个组件再去猜。
 */
class ThemeDarknessRuleTest {

    private val root = File("src/main/java/com/dshwatch")

    /** 扫源码前先剥注释 —— 注释里解释「以前写的是 xxx」会把断言骗红。 */
    private fun stripComments(text: String): String =
        text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")

    private fun src(path: String): String {
        val f = File(root, path)
        assertTrue("找不到 $path：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    // ---------------- 根因本身 ----------------

    @Test
    fun `透明背景的亮度是 0，所以不能拿来判明暗`() {
        /*
         * 这条是把事故的根因钉住：任何人以后想「看颜色亮度判断明暗」，
         * 都会先撞到这一行。0.35、0.5 这些阈值在这个前提下全都不成立 ——
         * 不是阈值选得不对，是被测量的东西根本不含这个信息。
         */
        assertEquals(0f, Color.Transparent.luminance(), 0.0001f)

        // 玻璃预设的背景确实是透明的，否则这条测试就是空谈
        val presets = stripComments(src("ui/theme/ThemePresets.kt"))
        assertTrue(
            "玻璃预设的 background 必须是透明（玻璃要透出底下的色晕）",
            presets.contains("bg = Color.Transparent"),
        )
    }

    @Test
    fun `主题明暗由 DshWatchTheme 往下传`() {
        val theme = stripComments(src("ui/theme/Theme.kt"))
        assertTrue(
            "Theme.kt 必须定义 LocalDarkTheme",
            theme.contains("val LocalDarkTheme = staticCompositionLocalOf"),
        )
        assertTrue(
            "DshWatchTheme 必须把解析好的 dark 提供给下面，" +
                "否则组件只能退回去猜颜色",
            theme.contains("LocalDarkTheme provides dark"),
        )
        assertTrue(
            "浅底色判定也要用主题的明暗（!dark）—— 用 background 的亮度会在" +
                "透明背景上恒为「深色」，浅色模式整页被压暗",
            theme.contains("val lightBase = !dark"),
        )
    }

    @Test
    fun `界面代码里不再用 background 的亮度反推明暗`() {
        /*
         * 四个调用点都犯过同一个错：玻璃卡片、弹窗、雾面遮罩、用户气泡。
         * 只要还剩一处，浅色模式就会在那处变黑 —— 所以扫全目录，而不是逐个点名。
         */
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { stripComments(it.readText()).contains("background.luminance()") }
            .map { it.relativeTo(root).path }
            .toList()
        assertTrue(
            "这些文件还在用 background 的亮度判明暗，浅色 + 玻璃会判错：$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `四个组件都改问主题要明暗`() {
        val expected = mapOf(
            "ui/components/Glass.kt" to "LocalDarkTheme.current",
            "ui/components/GlassDialog.kt" to "LocalDarkTheme.current",
            "ui/components/Backdrop.kt" to "!LocalDarkTheme.current",
            "ui/screens/chat/ChatScreen.kt" to "!LocalDarkTheme.current",
        )
        expected.forEach { (path, needle) ->
            val code = stripComments(src(path))
            assertTrue("$path 应当用 `$needle`", code.contains(needle))
        }
    }

    // ---------------- 新加的那两笔不能漏到浅色 ----------------

    @Test
    fun `压黑与压白都只在深色分支里`() {
        val glass = stripComments(src("ui/components/Glass.kt"))
        val darkBranch = glass.indexOf("if (dark) {")
        assertTrue("Glass.kt 里应当有 `if (dark) {` 分支", darkBranch > 0)

        val black = glass.indexOf("drawRoundRect(Color.Black", darkBranch)
        val elseBranch = glass.indexOf("} else {", darkBranch)
        assertTrue("深色分支里应当先压一层黑（v1.22.3）", black > darkBranch)
        assertTrue(
            "压黑必须落在 `if (dark)` 与 `else` 之间 —— 跑到浅色分支就是" +
                "「浅色模式变黑」那次事故",
            elseBranch > 0 && black < elseBranch,
        )
    }

    @Test
    fun `主按钮实心，浅色深色都成立`() {
        /*
         * 半透明的主色（0.42）压在浅色卡片上会变成一块淡蓝，白色文字读不出来；
         * 压在压暗过的深色卡片上则暗到看不出是主按钮。两头都要求实心。
         */
        val dialog = stripComments(src("ui/components/GlassDialog.kt"))
        assertTrue(
            "主按钮必须 solid：`solid = primary`",
            dialog.contains("solid = primary"),
        )
    }
}
