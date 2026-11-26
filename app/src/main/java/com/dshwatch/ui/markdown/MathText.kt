package com.dshwatch.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.dshwatch.ui.canvas.Latex
import com.dshwatch.ui.canvas.MathNode

/** 段落里的一段：普通文字，或者一段公式 */
sealed interface MdPiece {
    data class Text(val text: String) : MdPiece
    data class Math(val latex: String) : MdPiece
}

/**
 * 正文里的 `$…$` / `$$…$$`。
 *
 * ## 为什么必须有这一层
 *
 * 模型写公式时，绝大多数句子是「文字里嵌一个 $x_0$」，而不是整段围栏代码。
 * 以前正文只认围栏代码块（```` ```latex ````），于是 `$T\cos60°$` 这种
 * 一行一个、整段都是的输入被原样吐在屏幕上 —— 用户看到的是源码，
 * 只能自己脑补排版，还以为「LaTeX 渲染坏了」。
 *
 * 这里做两件事：
 *  1. 把一段文字切成「文字 / 公式」交替的片段；
 *  2. 能压成一行的公式压成一行（上下标用真正的基线偏移，见 [inline]），
 *     压不下的（分数、求和）交给 [com.dshwatch.ui.canvas.MathView] 单独画。
 *
 * 判据故意保守：只在线性化**不会读错**的时候才压平。`\frac{a}{b}` 写成
 * `(a)/(b)` 一眼能读，但 `\frac{x_N}{2}=\frac{…}{2}` 这种三层嵌套压平之后
 * 全是括号，反而比断成两行难读 —— 所以顶层有分式就整段上画布。
 */
object MathText {

    /** `$...$` 与 `$$...$$` 都收；不跨行的单 `$` 才当行内公式 */
    private const val MAX_INLINE_CHARS = 72

    private val COMMANDS_WITH_SPACE_AFTER = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan",
        "log", "ln", "lg", "exp", "max", "min", "lim", "sup", "inf", "det", "gcd",
    )

    fun split(text: String): List<MdPiece> {
        if (text.indexOf('$') < 0 && !text.contains("\\(") && !text.contains("\\[")) {
            return listOf(MdPiece.Text(text))
        }
        val out = ArrayList<MdPiece>(8)
        val buf = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length && (text[i + 1] == '(' || text[i + 1] == '[')) {
                val display = text[i + 1] == '['
                val closer = if (display) "\\]" else "\\)"
                val end = text.indexOf(closer, i + 2)
                if (end > 0) {
                    val body = text.substring(i + 2, end).trim()
                    if (body.isNotEmpty()) {
                        flush(buf, out)
                        out += MdPiece.Math(body)
                        i = end + 2
                        continue
                    }
                }
            }
            if (c == '$') {
                val display = i + 1 < text.length && text[i + 1] == '$'
                val open = if (display) 2 else 1
                val closeAt = closeOf(text, i + open, display)
                if (closeAt > 0) {
                    val body = text.substring(i + open, closeAt).trim()
                    if (body.isNotEmpty() && looksLikeMath(body)) {
                        flush(buf, out)
                        out += MdPiece.Math(body)
                        i = closeAt + open
                        continue
                    }
                }
            }
            buf.append(c)
            i++
        }
        flush(buf, out)
        return if (out.isEmpty()) listOf(MdPiece.Text(text)) else out
    }

    /**
     * 找配对的 `$`（或 `$$`）。
     *
     * 行内公式不允许跨行：`价格 $5 到 $10` 这种句子如果允许跨行配对，
     * 会把半篇文章吞进公式里。返回 -1 表示没配上，调用方按普通文字处理。
     */
    private fun closeOf(text: String, from: Int, display: Boolean): Int {
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == '\n' && !display) return -1
            if (c == '$') {
                if (!display) return i
                if (i + 1 < text.length && text[i + 1] == '$') return i
            }
            i++
        }
        return -1
    }

    private fun flush(buf: StringBuilder, out: MutableList<MdPiece>) {
        if (buf.isEmpty()) return
        out += MdPiece.Text(buf.toString())
        buf.clear()
    }

    /**
     * `$…$` 之间真的是公式吗。
     *
     * 中文里 `$` 还会当价格用（「$5 到 $10」），两个美元符号之间的东西
     * 一旦被当成公式，半句话就没了。公式里出现裸中文的只有 `\text{…}`，
     * 所以：带中文且没有 `\text` 的，一律不当公式。
     */
    private fun looksLikeMath(body: String): Boolean {
        val hasHan = body.any { it.code in 0x4E00..0x9FFF }
        if (!hasHan) return true
        return body.contains("\\text") || body.contains("\\mathrm")
    }

    /** 这段公式能不能压成一行 */
    fun inlineable(latex: String): Boolean {
        if (latex.length > MAX_INLINE_CHARS) return false
        if (latex.contains('\n')) return false
        val node = runCatching { Latex.parse(latex) }.getOrNull() ?: return false
        return !needsBlock(node)
    }

    private fun needsBlock(node: MathNode): Boolean = when (node) {
        is MathNode.Seq -> node.items.any { needsBlock(it) }
        is MathNode.Frac, is MathNode.Big -> true
        is MathNode.Script ->
            needsBlock(node.base) ||
                node.sup?.let { needsBlock(it) } == true ||
                node.sub?.let { needsBlock(it) } == true
        is MathNode.Delim -> needsBlock(node.body)
        /*
         * 重音：上划线在纯文本里没有对应字符，只能上画布；
         * 其余（bar / hat / vec / tilde / dot）用组合字符压平，能留在句子里。
         */
        is MathNode.Accent ->
            node.kind == com.dshwatch.ui.canvas.AccentKind.UNDERLINE ||
                node.kind == com.dshwatch.ui.canvas.AccentKind.OVERLINE ||
                needsBlock(node.body)
        is MathNode.Sqrt -> false
        else -> false
    }

    /**
     * 把 `$…$` 压成一段带上下标的文本。
     *
     * 用真正的 `baselineShift` 而不是「缩小字号再挪 Y」：Compose 的文本排版
     * 会自己处理行高与基线，压平的公式因此和左右文字在同一条基线上 ——
     * `x₀` 的下标不会再掉到下一行去（那是画布版曾经的毛病）。
     */
    fun inline(
        text: String,
        colors: InlineColors,
        fontSize: TextUnit,
        /**
         * 相对正文的放大倍数。
         *
         * 数学斜体的 x-height（小写字母高度）比中文小一截，同字号并排时
         * 公式看起来比汉字小一圈 —— 用户说的「正文里的比较小」。
         * 1.1 倍让两者视觉等重；行内画布公式另有更大的倍数。
         */
        scale: Float = 1.1f,
    ): AnnotatedString {
        val node = runCatching { Latex.parse(text) }.getOrNull() ?: return AnnotatedString(text)
        val base = fontSize * scale
        return buildAnnotatedString {
            // 整段套一层字号：内部只有 Sym 设字重/斜体，字号从这里继承
            withStyle(SpanStyle(fontSize = base)) {
                emit(node, this, base, colors, depth = 0)
            }
        }
    }

    private fun emit(
        node: MathNode,
        b: AnnotatedString.Builder,
        base: TextUnit,
        colors: InlineColors,
        depth: Int,
    ) {
        if (depth > 16) return
        with(b) {
        when (node) {
            is MathNode.Seq -> node.items.forEach { emit(it, b, base, colors, depth + 1) }

            is MathNode.Sym -> b.withStyle(
                SpanStyle(
                    fontStyle = if (node.italic) FontStyle.Italic else FontStyle.Normal,
                    fontWeight = if (node.bold) androidx.compose.ui.text.font.FontWeight.Bold
                    else androidx.compose.ui.text.font.FontWeight.Normal,
                )
            ) { append(node.text) }

            is MathNode.Text -> append(node.raw)

            is MathNode.Frac -> {
                val parenNum = !atomic(node.num)
                val parenDen = !atomic(node.den)
                if (parenNum) append("(")
                emit(node.num, b, base, colors, depth + 1)
                if (parenNum) append(")")
                append("/")
                if (parenDen) append("(")
                emit(node.den, b, base, colors, depth + 1)
                if (parenDen) append(")")
            }

            is MathNode.Script -> {
                emit(node.base, b, base, colors, depth + 1)
                node.sup?.let {
                    b.withStyle(
                        SpanStyle(
                            fontSize = base * 0.68f,
                            baselineShift = BaselineShift(0.42f),
                        )
                    ) { emit(it, this, base * 0.68f, colors, depth + 1) }
                }
                node.sub?.let {
                    b.withStyle(
                        SpanStyle(
                            fontSize = base * 0.68f,
                            baselineShift = BaselineShift(-0.18f),
                        )
                    ) { emit(it, this, base * 0.68f, colors, depth + 1) }
                }
            }

            is MathNode.Sqrt -> {
                append("√")
                val paren = !atomic(node.body)
                if (paren) append("(")
                emit(node.body, b, base, colors, depth + 1)
                if (paren) append(")")
            }

            is MathNode.Big -> {
                append(node.glyph)
                node.sup?.let {
                    b.withStyle(SpanStyle(fontSize = base * 0.62f, baselineShift = BaselineShift(0.5f))) {
                        emit(it, this, base * 0.62f, colors, depth + 1)
                    }
                }
                node.sub?.let {
                    b.withStyle(SpanStyle(fontSize = base * 0.62f, baselineShift = BaselineShift(-0.2f))) {
                        emit(it, this, base * 0.62f, colors, depth + 1)
                    }
                }
            }

            is MathNode.Delim -> {
                if (node.open != ".") append(node.open)
                emit(node.body, b, base, colors, depth + 1)
                if (node.close != ".") append(node.close)
            }

            /*
             * 重音压平：用「组合用附加符号」贴在前面那个字符上。
             *
             * U+0304 macron / U+0302 circumflex / U+20D7 箭头 / U+0303 tilde /
             * U+0307 dot。组合符只作用于**紧邻的前一个字符**，所以多字母的
             * `\overline{AB}` 会落在 B 上 —— 但它已经排除在压平之外（走画布），
             * 能走到这里的都是单个字母。
             */
            is MathNode.Accent -> {
                emit(node.body, b, base, colors, depth + 1)
                val mark = when (node.kind) {
                    com.dshwatch.ui.canvas.AccentKind.BAR -> "\u0304"
                    com.dshwatch.ui.canvas.AccentKind.OVERLINE -> "\u0304"
                    com.dshwatch.ui.canvas.AccentKind.HAT -> "\u0302"
                    com.dshwatch.ui.canvas.AccentKind.VEC -> "\u20D7"
                    com.dshwatch.ui.canvas.AccentKind.TILDE -> "\u0303"
                    com.dshwatch.ui.canvas.AccentKind.DOT -> "\u0307"
                    com.dshwatch.ui.canvas.AccentKind.DDOT -> "\u0307\u0307"
                    com.dshwatch.ui.canvas.AccentKind.UNDERLINE -> "\u0332"
                }
                append(mark)
            }
        }
        }
    }

    /** 单个字符/符号不必加括号 */
    private fun atomic(node: MathNode): Boolean = when (node) {
        is MathNode.Sym -> node.text.length <= 2
        is MathNode.Text -> node.raw.length <= 1
        is MathNode.Seq -> node.items.size == 1 && atomic(node.items[0])
        else -> false
    }
}
