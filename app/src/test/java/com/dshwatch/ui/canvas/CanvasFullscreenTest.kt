package com.dshwatch.ui.canvas

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * 全屏画板的圆屏布局约束。
 *
 * 这些事情在模拟器（方屏）上永远看不出来，发到手表上才发现 ——
 * 而手表上「看不见」和「点不到」是同一个意思：Compose 的命中测试跟着可见区走。
 */
class CanvasFullscreenTest {

    private val src: String by lazy {
        // 单测工作目录是模块根（app/）
        File("src/main/java/com/dshwatch/ui/canvas/CanvasBlock.kt").readText()
    }

    @Test
    fun `圆屏分支用 CircleGeometry，不用 isScreenRound`() {
        /*
         * 有的表是 412×502：圆只占中间那条 412 宽的带子，上下各留黑边。
         * 拿屏幕宽高自己算直径会整体错位，LocalCircle 里的几何已经把这件事
         * 处理过了（见 ui/wear/Circle.kt 的 top）。
         */
        assertThat(src).contains("LocalCircle.current")
        assertThat(src).contains("RoundFullscreen(circle")
        // 自己判屏幕形状就会绕开那套几何
        assertThat(src).doesNotContain("isScreenRound")
    }

    @Test
    fun `圆屏上关闭按钮在顶部中央`() {
        /*
         * 原来是 TopEnd（右上角）。圆屏上那个位置在圆外：看不见，也点不到，
         * 全屏进去只能靠返回键出来。
         */
        val round = roundBranch()
        val idx = round.indexOf("onClick = onClose")
        assertThat(idx).isGreaterThan(0)
        val before = round.substring(0, idx)
        assertThat(before).contains("Alignment.TopCenter")
        assertThat(before).doesNotContain("Alignment.TopEnd")
    }

    @Test
    fun `二维图形用内接正方形`() {
        // 矩形四角能占多大取决于圆：直径/√2 是「无论内容多高都不出圆」的边长
        val round = roundBranch()
        assertThat(round).contains("CircleGeometry.SQRT_HALF")
        // 两个二维画布都要收
        assertThat(countOf(round, "SQRT_HALF")).isEqualTo(2)
    }

    @Test
    fun `公式有高度上限，超出时等比缩小`() {
        val round = roundBranch()
        assertThat(round).contains("maxHeight = d * 0.40f")
        val math = File("src/main/java/com/dshwatch/ui/canvas/MathView.kt").readText()
        // 只有宽度缩放的话，多行公式会竖向冲出圆外
        assertThat(math).contains("byHeight")
        assertThat(math).contains("minOf(byWidth, byHeight)")
    }

    private fun roundBranch(): String {
        val start = src.indexOf("private fun RoundFullscreen")
        assertThat(start).isGreaterThan(0)
        val end = src.indexOf("private fun WideFullscreen", start)
        assertThat(end).isGreaterThan(start)
        return src.substring(start, end)
    }

    private fun countOf(text: String, needle: String): Int {
        var n = 0
        var i = text.indexOf(needle)
        while (i >= 0) {
            n++
            i = text.indexOf(needle, i + needle.length)
        }
        return n
    }
}
