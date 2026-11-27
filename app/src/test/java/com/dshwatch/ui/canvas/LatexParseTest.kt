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
        is MathNode.Accent -> glyphs(node.body)
        is MathNode.Framed -> glyphs(node.body)
        is MathNode.Rows -> node.rows.joinToString("") { glyphs(it) }
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

    /**
     * 重音命令。
     *
     * 用户截图里的 `\barv=\frac{v_t+v}{2}` 直接显示成了 `\barv` ——
     * `\bar` 没有实现，落到「不认识的命令」分支原样吐源码。
     * 物理书里平均速度、均值、矢量全靠这几个命令。
     */
    @Test
    fun `bar is an accent instead of literal text`() {
        val node = Latex.parse("""\bar v""")
        assertThat(node).isInstanceOf(MathNode.Accent::class.java)
        assertThat((node as MathNode.Accent).kind).isEqualTo(AccentKind.BAR)
        assertThat(glyphs(node)).isEqualTo("v")
        assertThat(parse("""\bar v""")).doesNotContain("\\bar")
    }

    @Test
    fun `overline underline hat vec tilde dot ddot all parse`() {
        val cases = listOf(
            """\overline{AB}""" to AccentKind.OVERLINE,
            """\underline{x}""" to AccentKind.UNDERLINE,
            """\hat{y}""" to AccentKind.HAT,
            """\vec{F}""" to AccentKind.VEC,
            """\tilde{x}""" to AccentKind.TILDE,
            """\dot{x}""" to AccentKind.DOT,
            """\ddot{x}""" to AccentKind.DDOT,
        )
        for ((src, kind) in cases) {
            val node = Latex.parse(src)
            assertThat(node).isInstanceOf(MathNode.Accent::class.java)
            assertThat((node as MathNode.Accent).kind).isEqualTo(kind)
            // 命令本身不能留在输出里
            assertThat(parse(src)).doesNotContain("\\")
        }
    }

    /** 用户的整条式子：重音 + 分式 + 下标都要在，一个反斜杠命令都不能剩 */
    @Test
    fun `mean speed formula keeps structure`() {
        val node = Latex.parse("""\barv=\frac{v_t+v}{2}""")
        val text = node.toString()
        assertThat(text).doesNotContain("\\bar")
        assertThat(text).contains("Frac")
        // 分子里必须有下标（v_t），不能被重音吃掉
        assertThat(text).contains("Script")
        assertThat(glyphs(node)).isEqualTo("v=vt+v2")
    }

    /**
     * 字体切换。
     *
     * `{\rm I}` 是物理书里的罗马数字序号；`\rm` 以前也当未知命令原样输出。
     */
    @Test
    fun `rm and mathrm turn letters upright`() {
        val a = Latex.parse("""\rm I""")
        assertThat(a).isInstanceOf(MathNode.Sym::class.java)
        assertThat((a as MathNode.Sym).italic).isFalse()

        val b = Latex.parse("""\mathrm{d}""")
        assertThat(b).isInstanceOf(MathNode.Sym::class.java)
        assertThat((b as MathNode.Sym).italic).isFalse()
    }

    /** 分组形式的 `{\rm I}`：花括号里一层，外层再取出来 */
    @Test
    fun `rm inside a group is upright`() {
        val node = Latex.parse("""X_{\rm I}""")
        assertThat(node).isInstanceOf(MathNode.Script::class.java)
        val sub = (node as MathNode.Script).sub
        assertThat(sub).isInstanceOf(MathNode.Sym::class.java)
        assertThat((sub as MathNode.Sym).text).isEqualTo("I")
        assertThat(sub.italic).isFalse()
    }

    /** `\mathrm` 里带命令和上下标时不能拍平成字符串 */
    @Test
    fun `mathrm keeps inner structure`() {
        val node = Latex.parse("""\mathrm{kg\cdot m/s^2}""")
        assertThat(node.toString()).contains("Script")
        // 点乘要被翻译成真正的 ·，而不是留在源码里的 \cdot
        assertThat(parse("""\mathrm{kg\cdot m/s^2}""")).doesNotContain("\\cdot")
    }

    /** 声明式命令本身不占位 */
    @Test
    fun `style declarations do not leak`() {
        assertThat(parse("""\displaystyle \frac{a}{b}""")).doesNotContain("\\displaystyle")
        assertThat(parse("""\sum\limits_{i} a_i""")).doesNotContain("limits")
    }

    /** 图 1 的整套公式：冒号分隔的罗马数字序号 */
    @Test
    fun `ratio formula from screenshot has no leftover commands`() {
        val src = """X_{\rm I}:X_{\rm II}:X_{\rm III}:\cdots=1:3:5:\cdots"""
        val out = parse(src)
        assertThat(out).doesNotContain("\\")
        assertThat(glyphs(Latex.parse(src))).isEqualTo("XI:XII:XIII:⋯=1:3:5:⋯")
    }

    /*
     * 第二张截图：解数学题的一整段推导里，\boxed、\mathbb、\begin{cases}
     * 全部原样吐在屏幕上。这三条是「解数学题」里出现频率最高的写法，
     * 以前都落到「未知命令 → 显示原文」的兜底分支。
     */

    @Test
    fun `boxed 变成带框的节点，不再漏出命令`() {
        val node = Latex.parse("""\boxed{[-3,-2]\cup(0,2]}""")
        assertThat(node).isInstanceOf(MathNode.Framed::class.java)
        val out = glyphs(node)
        assertThat(out).doesNotContain("boxed")
        assertThat(out).contains("[-3,-2]")
        assertThat(out).contains("∪")
    }

    @Test
    fun `mathbb 映射成黑板粗体字符`() {
        val out = parse("""A\cup B=\mathbb{R}""")
        assertThat(out).doesNotContain("mathbb")
        assertThat(out).contains("ℝ")
        // 多字母要逐个映射，不能只认 R
        assertThat(glyphs(Latex.parse("""\mathbb{NZQC}"""))).isEqualTo("ℕℤℚℂ")
    }

    @Test
    fun `cases 按换行切成多行，左侧带大括号`() {
        val node = Latex.parse("""\begin{cases}2a-2\ge -3\\2+a\le 2\end{cases}""")
        assertThat(node).isInstanceOf(MathNode.Rows::class.java)
        val rows = (node as MathNode.Rows)
        assertThat(rows.rows).hasSize(2)
        assertThat(rows.left).isEqualTo("{")
        val out = glyphs(node)
        assertThat(out).doesNotContain("begin")
        assertThat(out).doesNotContain("cases")
        assertThat(out).contains("≥")
        assertThat(out).contains("≤")
    }

    @Test
    fun `环境里的对齐符被丢掉，不会变成 & 字符`() {
        val out = glyphs(Latex.parse("""\begin{aligned}a&=1\\b&=2\end{aligned}"""))
        assertThat(out).doesNotContain("&")
        assertThat(out).contains("1")
        assertThat(out).contains("2")
    }
}
