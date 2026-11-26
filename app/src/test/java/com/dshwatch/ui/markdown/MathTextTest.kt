package com.dshwatch.ui.markdown

import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 正文里的 `$…$`。
 *
 * 用例都来自真机截图：整段物理推导里每一行都是 `$T\cos60°=\mu\left(…\right)$`，
 * 屏幕上却是一屏源码 —— 因为正文只认围栏代码块，行内公式从来没被识别过。
 */
class MathTextTest {

    private val colors = InlineColors(
        text = androidx.compose.ui.graphics.Color.Black,
        muted = androidx.compose.ui.graphics.Color.Gray,
        codeText = androidx.compose.ui.graphics.Color.Blue,
        codeBackground = androidx.compose.ui.graphics.Color.LightGray,
        link = androidx.compose.ui.graphics.Color.Blue,
        bold = androidx.compose.ui.graphics.Color.Black,
    )

    @Test
    fun `行内公式被切出来`() {
        val pieces = MathText.split("代入 \$\\sin60°=\\dfrac{\\sqrt3}{2}\$，得 \$\\mu=\\dfrac{\\sqrt3}{3}\$")
        assertThat(pieces.filterIsInstance<MdPiece.Math>()).hasSize(2)
        assertThat((pieces[0] as MdPiece.Text).text).isEqualTo("代入 ")
    }

    @Test
    fun `双美元符号也认`() {
        val pieces = MathText.split("\$\$\\frac{T}{2}=20\\sqrt3\$\$")
        assertThat(pieces.single()).isInstanceOf(MdPiece.Math::class.java)
        assertThat((pieces.single() as MdPiece.Math).latex).isEqualTo("\\frac{T}{2}=20\\sqrt3")
    }

    /** 价格不能被当成公式，否则「$5 到 $10」中间那半句话会消失 */
    @Test
    fun `中文之间的美元符号不当公式`() {
        val text = "价格 \$5 到 \$10 之间"
        val pieces = MathText.split(text)
        assertThat(pieces.all { it is MdPiece.Text }).isTrue()
        assertThat(pieces.joinToString("") { (it as MdPiece.Text).text }).isEqualTo(text)
    }

    /** 流式输出到一半：只有开没有合，必须原样留着，不能把后半段吞掉 */
    @Test
    fun `没闭合的公式原样保留`() {
        val text = "\$\$\\frac{T}{2} + \\frac{T}{2} = 20"
        val pieces = MathText.split(text)
        assertThat(pieces.single()).isInstanceOf(MdPiece.Text::class.java)
        assertThat((pieces.single() as MdPiece.Text).text).isEqualTo(text)
    }

    @Test
    fun `分数走画布，上下标和根号压平`() {
        assertThat(MathText.inlineable("\\frac{1}{2}")).isFalse()
        assertThat(MathText.inlineable("\\sum_{i=1}^{n} a_i")).isFalse()
        assertThat(MathText.inlineable("x_0^2")).isTrue()
        assertThat(MathText.inlineable("\\sqrt{x_0^2+2x_0L}")).isTrue()
        assertThat(MathText.inlineable("T\\cos 60°")).isTrue()
    }

    @Test
    fun `压平后文字还在，顺序不乱`() {
        val out = MathText.inline("x_0^2", colors, 15.sp).text
        assertThat(out).isEqualTo("x02")
    }

    @Test
    fun `根号压平成可读的一串`() {
        val out = MathText.inline("\\sqrt{x_0^2+2x_0L}", colors, 15.sp).text
        assertThat(out).startsWith("√(")
        assertThat(out).contains("x02")
    }

    @Test
    fun `text 里的中文原样留下`() {
        val out = MathText.inline("\\sqrt{6}\\ \\text{kg}", colors, 15.sp).text
        assertThat(out).contains("kg")
    }

    /** 用户给的源码：三层分式嵌套，必须整段交给画布，不能压成一串括号 */
    @Test
    fun `用户那条长公式走画布`() {
        val latex = "\\frac{x_N}{2}=\\frac{x_0+\\sqrt{x_0^2+2x_0L}}{2}>\\frac{x_0+x_0}{2}=x_0=x_P"
        assertThat(MathText.inlineable(latex)).isFalse()
        val pieces = MathText.split("\$$latex\$")
        assertThat(pieces.single()).isInstanceOf(MdPiece.Math::class.java)
    }

    /**
     * 平均速度 `\bar v`：能压平，重音用组合字符贴在字母上。
     *
     * 压平不了的话，一句「平均速度 $\bar v$ 等于位移除以时间」会被拆成
     * 文字-公式-文字三段，公式那行还独占一整行高度。
     */
    @Test
    fun `bar 压平成带横线的字母`() {
        assertThat(MathText.inlineable("\\bar v")).isTrue()
        val out = MathText.inline("\\bar v", colors, 15.sp).text
        assertThat(out).isEqualTo("v\u0304")
    }

    /** 模型漏写空格的 `\barv` 也要认，否则屏幕上就是源码 */
    @Test
    fun `粘住的 barv 也能压平`() {
        assertThat(MathText.inlineable("\\barv")).isTrue()
        assertThat(MathText.inline("\\barv", colors, 15.sp).text).isEqualTo("v\u0304")
    }

    /** 上划线在纯文本里没有对应字符（多字母也压不平），必须走画布 */
    @Test
    fun `overline 交给画布`() {
        assertThat(MathText.inlineable("\\overline{AB}")).isFalse()
        assertThat(MathText.inlineable("\\underline{x}")).isFalse()
        // 单个字母的帽子仍可压平
        assertThat(MathText.inlineable("\\hat{y}")).isTrue()
        assertThat(MathText.inlineable("\\vec{F}")).isTrue()
    }
}
