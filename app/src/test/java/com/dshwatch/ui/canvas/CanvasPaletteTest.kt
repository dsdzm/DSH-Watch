package com.dshwatch.ui.canvas

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 画布配色必须在两种明暗下都读得出来。
 *
 * ## 这组测试守的是「公式凭空消失」
 *
 * 三类画布（公式 / 导图 / 几何图）的颜色原先是照着深色卡片挑的：正文 `#E8E8F0`、
 * 连线 `#5A6C8C`、节点底 `#232A38`。深色下没问题 —— 亮字压暗底。
 *
 * 浅色模式下卡片是白的，`#E8E8F0` 在白底上的对比度是 **1.04 : 1**：
 * 不是「颜色淡淡的不太清楚」，是**整段公式看不见**，只剩一个空框。
 * 用户的判断只能是「公式渲染坏了」，而实际上渲染得完全正确。
 *
 * 所以这里不再靠肉眼验收：把两档配色各自和它的背景算一遍对比度，
 * 低于 4.5 就红。新增颜色时也会被迫先算一遍。
 */
class CanvasPaletteTest {

    private val root = File("src/main/java/com/dshwatch")
    private val canvasDir = File(root, "ui/canvas")

    /** 正文标准。WCAG AA 对小字号的要求就是 4.5:1 */
    private val TEXT_MIN = 4.5f

    /** 图形元素（线、边框）：不承载文字，3:1 即可辨识 */
    private val GRAPHIC_MIN = 3f

    private val palettes = listOf("深色" to darkPalette(), "浅色" to lightPalette())

    private fun check(name: String, fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color, min: Float) {
        val c = contrastRatio(fg, bg)
        assertTrue(
            "$name 的对比度只有 %.2f:1（要求 ≥ %.1f）—— 低于这个值就是「看得见有东西，但读不出来」"
                .format(c, min),
            c >= min,
        )
    }

    @Test
    fun `正文在两种明暗下都过 AA`() {
        palettes.forEach { (name, p) ->
            check("$name·公式正文", p.ink, p.cardBase, TEXT_MIN)
            check("$name·次要文字", p.inkDim, p.cardBase, TEXT_MIN)
        }
    }

    @Test
    fun `三个语义色都过 AA`() {
        /*
         * 公式 / 导图 / 几何图三种颜色承担的是**区分**职责。
         * 区分的前提是三个都读得出来 —— 有一个太淡，用户就分不清
         * 「这是公式块还是导图块」。
         */
        palettes.forEach { (name, p) ->
            check("$name·公式色", p.math, p.cardBase, TEXT_MIN)
            check("$name·导图色", p.mind, p.cardBase, TEXT_MIN)
            check("$name·几何色", p.geom, p.cardBase, TEXT_MIN)
        }
    }

    @Test
    fun `节点里的文字压在节点底上也要过 AA`() {
        palettes.forEach { (name, p) ->
            check("$name·节点文字", p.ink, p.nodeFill, TEXT_MIN)
            check("$name·根节点文字", p.ink, p.nodeFillRoot, TEXT_MIN)
        }
    }

    @Test
    fun `连线与边框至少达到图形对比度`() {
        palettes.forEach { (name, p) ->
            check("$name·导图连线", p.link, p.cardBase, GRAPHIC_MIN)
            check("$name·图形描边", p.shapeStroke, p.cardBase, GRAPHIC_MIN)
            check("$name·节点描边(根)", p.nodeStrokeRoot, p.nodeFillRoot, GRAPHIC_MIN)
        }
    }

    @Test
    fun `浅色档确实比深色档暗，而不是换了个名字`() {
        // 防「复制一份深色档、忘了改色值」—— 那样测试会全绿，界面照旧看不见
        assertTrue(
            "浅色档的正文必须比深色档暗",
            lightPalette().ink.luminance() < darkPalette().ink.luminance(),
        )
        assertTrue(
            "浅色档的卡片底必须比深色档亮",
            lightPalette().cardBase.luminance() > darkPalette().cardBase.luminance(),
        )
    }

    // ---------------- 结构：颜色只能从调色板来 ----------------

    /** 剥掉注释再扫：「以前写的是 #E8E8F0」这种说明会把断言骗红 */
    private fun stripComments(text: String): String =
        text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")

    @Test
    fun `画布代码里不出现写死的语义色`() {
        /*
         * 这些色值一旦在别处再写一遍，就会绕过调色板 —— 浅色模式下那处又是隐形的，
         * 而测试仍然全绿（因为它只测了调色板本身）。
         * 所以扫整个 ui/canvas 目录，只有 CanvasPalette.kt 允许出现它们。
         *
         * 0xF00B0D12 是全屏那层写死的近黑底，正是「浅色模式深字压黑底」的源头。
         */
        val banned = listOf(
            "0xFFE8E8F0", "0xFF8FB4E8", "0xFFE0B04A", "0xFFDCE4F2", "0xFF5A6C8C",
            "0xF00B0D12",
        )
        val offenders = canvasDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "CanvasPalette.kt" }
            .filter { f ->
                val code = stripComments(f.readText())
                banned.any { code.contains(it) }
            }
            .map { it.name }
            .toList()
        assertTrue(
            "这些文件里还写死着画布颜色，应该从 rememberCanvasPalette() 取：$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `全屏底色与文字色同源`() {
        /*
         * 这条守的是「浅色模式全屏变成黑底深字」。
         *
         * 成因不是某个色值错了，而是**底色和字色各走各的**：外壳固定画近黑，
         * View 按主题取字色。只要外壳重新开始写死底色，这个 bug 就会回来。
         */
        val block = stripComments(File(canvasDir, "CanvasBlock.kt").readText())
        assertTrue(
            "全屏不能再写死某一档调色板，要按主题取",
            !block.contains("darkPalette()"),
        )
        assertTrue(
            "全屏底色必须取 palette.cardBase，才能和字色用同一档配色",
            block.contains(".background(fullscreenPalette.cardBase)"),
        )
        assertTrue(
            "全屏要把 palette 下发给子 View，否则它们还是按主题猜",
            block.contains("LocalCanvasPalette provides"),
        )
    }

    @Test
    fun `全屏按钮底色往背景的反方向走`() {
        // 深色底上用半透明白、浅色底上用半透明黑。写死其中一个，另一个就隐形
        assertTrue(
            "深色档的按钮应该比底色亮",
            darkPalette().chromeWash.luminance() > darkPalette().cardBase.luminance(),
        )
        assertTrue(
            "浅色档的按钮应该比底色暗",
            lightPalette().chromeWash.luminance() < lightPalette().cardBase.luminance(),
        )
    }
}
