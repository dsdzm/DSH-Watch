package com.dshwatch.ui.canvas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * LaTeX 子集的解析边界。
 *
 * 两条用例来自真机截图：模型写 `\Big(\sum…\Big)^2` 和 `\Longleftrightarrow`，
 * 屏幕上直接把命令原文吐了出来（`f(t)=\Big∑…`、`\Longleftrightarrow`），
 * 用户看到的是「公式没渲染」。
 *
 * 这里只测解析 —— 排版要 TextMeasurer，跑不了 JVM 单测。但「命令有没有被翻译成
 * 符号」正是出问题的那一层。
 */
class LatexParseTest {

    private fun parse(src: String) = Latex.parse(src).toString()

    /** 把节点树里的字符拼回成字符串：toString 会把 "170" 拆成三个 Sym */
    private fun glyphs(node: MathNode): String = when (node) {
        is MathNode.Seq -> node.items.joinToString("") { glyphs(it) }
        is MathNode.Sym -> node.text
        is MathNode.Text -> node.raw
        is MathNode.Frac -> glyphs(node.num) + glyphs(node.den)
        is MathNode.Script -> glyphs(node.base) +
            (node.sup?.let { glyphs(it) }.orEmpty()) +
            (node.sub?.let { glyphs(it) }.orEmpty())
        is MathNode.Sqrt -> glyphs(node.body)
        is MathNode.Big -> node.glyph
        is MathNode.Delim -> node.open + glyphs(node.body) + node.close
    }

    /** 用户截图里的原公式 */
    private val quadratic = """
        f(t)=\Big(\sum_{i} a_i \Big)^2-2\Big(\sum_{i} a_i b_i \Big)t+\Big(\sum_{i} b_i \Big)
    """.trimIndent()

    @Test
    fun `big delimiters are translated instead of leaking backslash commands`() {
        val out = parse(quadratic)
        assertThat(out).doesNotContain("\\Big")
        // 左右括号都还在，只是变成了放大后的符号
        assertThat(out).contains("(")
        assertThat(out).contains(")")
    }

    @Test
    fun `big delimiters keep the sum glyph`() {
        val out = parse(quadratic)
        assertThat(out).contains("∑")
    }

    @Test
    fun `long left right arrow maps to a glyph`() {
        val out = parse("""|u| |v| \ge u\cdot v \Longleftrightarrow \sqrt{170} \approx 13.04 \ge 7""")
        assertThat(out).doesNotContain("Longleftrightarrow")
        assertThat(out).contains("⟺")
    }

    @Test
    fun `sqrt keeps its radicand`() {
        val node = Latex.parse("""\sqrt{170}""")
        assertThat(node).isInstanceOf(MathNode.Sqrt::class.java)
        assertThat(glyphs(node)).contains("170")
    }

    @Test
    fun `left right pairing does not leak commands`() {
        val out = parse("""\left( a+b \right)""")
        assertThat(out).doesNotContain("\\left")
        assertThat(out).doesNotContain("\\right")
        assertThat(out).contains("(")
        assertThat(out).contains(")")
    }

    @Test
    fun `invisible delimiter drops out`() {
        // \left. 是「这一侧不要括号」，不能画成一个点
        val out = parse("""\left. x \right|_{0}""")
        assertThat(out).doesNotContain("\\left")
        assertThat(out).doesNotContain("\\right")
    }

    @Test
    fun `escaped braces and angle brackets`() {
        assertThat(parse("""\{a\}""")).contains("{")
        assertThat(parse("""\langle x \rangle""")).contains("⟨")
    }

    @Test
    fun `common operators still work`() {
        val out = parse("""a \le b \ne c \ge d \pm e \times f""")
        with(out) {
            assertThat(contains("≤")).isTrue()
            assertThat(contains("≠")).isTrue()
            assertThat(contains("≥")).isTrue()
            assertThat(contains("±")).isTrue()
            assertThat(contains("×")).isTrue()
        }
    }

    @Test
    fun `unknown command is shown as written`() {
        // 宁可显示原文，也不要静默消失
        assertThat(parse("""\foobar""")).contains("\\foobar")
    }
}
