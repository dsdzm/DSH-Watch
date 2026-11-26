package com.dshwatch.ui.canvas

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * 上下标的几何不变量。
 *
 * 这类错位在模拟器的小字号下看不出来，上手表放大就非常刺眼
 * （用户原话：「太大导致错位了」）。而它每次都是同一个错误：
 * 把「让开字形」的余量当成位移，再叠一个 ascent。
 *
 * 几何本身跑不出单测（要 Compose 的 TextMeasurer），所以这里守源码：
 * 位移只允许出现一次、只允许以「只含位移的常量为形状」出现在 draw 里。
 */
class MathScriptGeometryTest {

    private val mathSrc: String by lazy {
        File("src/main/java/com/dshwatch/ui/canvas/MathView.kt").readText()
    }

    private fun scriptBranch(): String {
        val start = mathSrc.indexOf("is MathNode.Script ->")
        assertThat(start).isGreaterThan(0)
        val end = mathSrc.indexOf("is MathNode.Accent ->", start)
        assertThat(end).isGreaterThan(start)
        return mathSrc.substring(start, end)
    }

    @Test
    fun `下标位移只有 0_2em，不再叠一层 ascent`() {
        val branch = scriptBranch()
        // LaTeX 的下沉量是 0.2em 量级；0.7em 会把 0 推到分数线附近
        assertThat(branch).contains("subShift = size * 0.20f")
        // draw 的第二个参数就是基线，再 + ascent 等于又压低一个字高
        assertThat(branch).contains("base + subShift)")
        // 关键：不能再声明一个「让开字形」的沉量再和位移相加
        assertThat(branch).doesNotContain("val subSink")
        assertThat(branch).doesNotContain("subSink = maxOf")
    }

    @Test
    fun `上标位移 0_4em`() {
        val branch = scriptBranch()
        assertThat(branch).contains("supShift = size * 0.40f")
        assertThat(branch).contains("base - supShift)")
    }

    @Test
    fun `盒子高度用子盒的 descent，不用 height`() {
        /*
         * height = ascent + descent。拿它算 descent 会把盒底多算一整个 ascent，
         * 分式里的分子因此被抬高、下标落到框外。
         */
        val branch = scriptBranch()
        assertThat(branch).contains("subShift + (sub?.descent ?: 0f)")
        assertThat(branch).doesNotContain("subSink + (sub?.height")
    }

    @Test
    fun `卡片公式不再用 19sp`() {
        val block = File("src/main/java/com/dshwatch/ui/canvas/CanvasBlock.kt").readText()
        // 19sp 让带角标的分式在卡片里比正文高出一倍，位移被同步放大
        assertThat(block).doesNotContain("if (compact) 19f")
        assertThat(block).contains("if (compact) 16.5f")
    }

    @Test
    fun `正文公式字号大于正文`() {
        /*
         * 数学斜体的 x-height 比汉字小，同字号并排会显得小一圈。
         * 压平公式 1.1 倍、行内画布 1.45 倍，两个入口都得放大。
         */
        val math = File("src/main/java/com/dshwatch/ui/markdown/MathText.kt").readText()
        assertThat(math).contains("scale: Float = 1.1f")

        val md = File("src/main/java/com/dshwatch/ui/markdown/MarkdownText.kt").readText()
        assertThat(md).contains("maxOf(sizeSp * 1.45f, 17f)")
    }
}
