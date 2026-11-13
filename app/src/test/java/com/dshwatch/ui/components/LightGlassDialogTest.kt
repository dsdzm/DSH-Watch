package com.dshwatch.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 浅色玻璃弹窗必须「读得出来」。
 *
 * ## 用户看到的现象
 *
 * 「浅色玻璃模式下弹窗可读性差」—— 截一张图就明白：下面的聊天页在动，
 * 弹窗只有 10% 的正文色压痕，等于 90% 透明。字压在一页滚动的文字和气泡上，
 * 像隔着一层脏玻璃。
 *
 * 之前那版在**算数**上是对的（正文 27 压在 224 上 ≈ 12:1），错在假设：
 * 它假设「卡片底就是白色」。实际卡片底是「90% 的页面」，页面里有什么，
 * 卡片底就是什么。
 *
 * ## 改法（照着用户给的参考应用）
 *
 * 1. 卡片本体加一层 0.80 的白 —— 只透 20%，看得出背后是模糊的色块，读不出内容；
 * 2. 浅色玻璃的遮罩从「完全透明」改成 0.12 的黑 —— 页面压到 218，卡片 247，
 *    白卡片才有地方站；
 * 3. 背景模糊 14dp → 18dp，把「被推远」的观感做足（可读性已由 1 保证，不再靠糊）。
 *
 * 这几条都是绘制参数，没法在单测里渲染验证，所以守住源码结构：
 * 任何人把它们改回去（或者复制一份旧的浅色分支），这里会红。
 */
class LightGlassDialogTest {

    private val dir = File("src/main/java/com/dshwatch/ui/components")

    private fun stripComments(text: String): String =
        text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")

    private fun code(name: String) = stripComments(File(dir, name).readText())

    @Test
    fun `浅色弹窗本体是磨砂白，不是九成透明`() {
        val glass = code("Glass.kt")
        assertTrue(
            "浅色弹窗要叠一层白色的磨砂（0.80）—— 低于这个值就回到" +
                "「字压在一页滚动的聊天记录上」",
            glass.contains("drawRoundRect(Color.White, cornerRadius = r, alpha = 0.80f"),
        )
    }

    @Test
    fun `浅色玻璃的遮罩不是透明的`() {
        val dialog = code("GlassDialog.kt")
        assertTrue(
            "浅色玻璃的遮罩必须压一点点黑（0.12）：页面和卡片都是近白，" +
                "没有这一层就分不出哪块是浮起来的",
            dialog.contains("glass -> if (dark) Color.Transparent else Color.Black.copy(alpha = 0.12f)"),
        )
        assertTrue(
            "深色玻璃仍然不要遮罩 —— 深色卡片自己有一层暗底，再压黑就回到「太暗」",
            dialog.contains("if (dark) Color.Transparent"),
        )
    }

    @Test
    fun `背景模糊默认给足`() {
        val backdrop = code("Backdrop.kt")
        val radius = Regex("""MaxBlurRadius\s*=\s*(\d+)\.dp""")
            .find(backdrop)?.groupValues?.get(1)?.toIntOrNull()
        assertTrue("Backdrop.kt 里找不到 MaxBlurRadius", radius != null)
        assertTrue(
            "背景模糊半径只有 ${radius}dp —— 用户要的是「玻璃默认对背景有模糊」，" +
                "小于 16dp 在 200dp 宽的屏上看不出糊",
            radius!! >= 16,
        )
    }

    @Test
    fun `浅色弹窗有一道细内描边`() {
        // 白卡片压在压暗的浅底上，靠高光是白的、等于没有边界；
        // 这一道 0.12 的正文色细线才是「卡片到这里为止」
        val glass = code("Glass.kt")
        assertTrue(
            "浅色弹窗要有 1dp 的正文色内描边",
            glass.contains("if (!dark && elevated)") && glass.contains("SolidColor(bodyTint)"),
        )
    }
}
