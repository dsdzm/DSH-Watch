package com.dshwatch.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/**
 * LaTeX 公式渲染。
 *
 * ## 为什么自己画
 *
 * WebView + KaTeX 是行业标准做法，但在这个应用里不成立：
 * KaTeX 的 js + css + 字体资源约 1.2MB，且每次流式刷新都要重新 `loadData`，
 * 512MB 内存的手表上会直接卡死（[com.dshwatch.ui.markdown.MarkdownParser] 的注释里
 * 同样理由解释了为什么连 Markdown 都是手写的）。
 *
 * 所以这里做的是**子集**：解析 → 布局盒子 → Canvas 绘制。
 * 覆盖的是聊天里真正会出现的公式（分数、根号、上下标、求和积分、希腊字母、常用运算符），
 * 不是完整的 LaTeX。遇到不认识的命令按原文显示，不会变成空白。
 *
 * ## 盒子模型
 *
 * 每个节点算出一个 [MathBox]：宽、基线之上高度（ascent）、基线之下深度（descent），
 * 以及一个「把内容画在 (x, baseline)」的 lambda。分数、根号、上下标都靠
 * 这三个数字对齐 —— 这是排版引擎最朴素的写法，也足够正确。
 */
@Composable
fun MathView(
    latex: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: Float = 20f,
    compact: Boolean = false,
) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val resolved = if (color == Color.Unspecified) Color(0xFFE8E8F0) else color
    val sized = if (compact) fontSize * 0.86f else fontSize
    val box = remember(latex, sized, resolved) {
        val node = Latex.parse(latex)
        buildMathBox(node, sized, measurer, resolved)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        // 高度跟着盒子走：固定高度会把多行公式裁掉，自适应又需要密度换算
        val heightDp = with(LocalDensity.current) { (box.height + 6f).toDp() }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp),
        ) {
            val scale = if (box.width > size.width - 2f && box.width > 0f) {
                (size.width - 2f) / box.width
            } else {
                1f
            }
            // 超宽公式整体缩到可视宽度内：等宽换行做不到（公式没有合法的断点），
            // 缩放在 200dp 的屏幕上反而是可读性最好的选择
            withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
                box.draw(this, 0f, box.ascent + 2f / scale)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 节点
// ---------------------------------------------------------------------------

sealed interface MathNode {
    data class Seq(val items: List<MathNode>) : MathNode
    data class Sym(val text: String, val italic: Boolean = true, val bold: Boolean = false) : MathNode
    data class Frac(val num: MathNode, val den: MathNode) : MathNode
    data class Script(val base: MathNode, val sup: MathNode? = null, val sub: MathNode? = null) : MathNode
    data class Sqrt(val body: MathNode, val index: MathNode? = null) : MathNode
    data class Big(val glyph: String, val sup: MathNode? = null, val sub: MathNode? = null) : MathNode
    data class Delim(val open: String, val body: MathNode, val close: String) : MathNode
    data class Text(val raw: String) : MathNode
}

/** 每个节点的排版结果 */
class MathBox(
    val width: Float,
    val ascent: Float,
    val descent: Float,
    val draw: DrawScope.(x: Float, baseline: Float) -> Unit,
) {
    val height: Float get() = ascent + descent
}

private fun buildMathBox(
    node: MathNode,
    size: Float,
    m: TextMeasurer,
    color: Color,
): MathBox = when (node) {
    is MathNode.Seq -> {
        val parts = node.items.map { buildMathBox(it, size, m, color) }
        MathBox(
            width = parts.sumOf { it.width.toDouble() }.toFloat(),
            ascent = parts.maxOfOrNull { it.ascent } ?: 0f,
            descent = parts.maxOfOrNull { it.descent } ?: 0f,
            draw = { x, base ->
                var cx = x
                parts.forEach { p ->
                    p.draw(this, cx, base)
                    cx += p.width
                }
            },
        )
    }

    is MathNode.Sym -> textBox(node.text, size, m, color, node.italic, node.bold)

    is MathNode.Text -> textBox(node.raw, size, m, color, italic = false, bold = false, upright = true)

    is MathNode.Frac -> {
        val num = buildMathBox(node.num, size * 0.92f, m, color)
        val den = buildMathBox(node.den, size * 0.92f, m, color)
        val gap = size * 0.12f
        val barW = max(num.width, den.width) + size * 0.24f
        val barThick = max(1f, size * 0.05f)
        val ascent = num.height + gap + barThick / 2
        val descent = den.height + gap
        MathBox(
            width = barW,
            ascent = ascent,
            descent = descent,
            draw = { x, base ->
                val barY = base - barThick / 2
                val cx = x + (barW - num.width) / 2
                num.draw(this, cx, barY - gap)
                drawLine(
                    color = color,
                    start = Offset(x + size * 0.06f, barY),
                    end = Offset(x + barW - size * 0.06f, barY),
                    strokeWidth = barThick,
                )
                val dx = x + (barW - den.width) / 2
                den.draw(this, dx, barY + gap + den.ascent)
            },
        )
    }

    is MathNode.Script -> {
        val baseBox = buildMathBox(node.base, size, m, color)
        val scriptSize = size * 0.62f
        val sup = node.sup?.let { buildMathBox(it, scriptSize, m, color) }
        val sub = node.sub?.let { buildMathBox(it, scriptSize, m, color) }
        val supRise = size * 0.42f
        val subDrop = size * 0.16f
        val ascent = max(baseBox.ascent, (sup?.height ?: 0f) - supRise + baseBox.ascent)
        val descent = max(baseBox.descent, (sub?.height ?: 0f) - subDrop + (baseBox.descent * 0.2f))
        val scriptW = max(sup?.width ?: 0f, sub?.width ?: 0f)
        MathBox(
            width = baseBox.width + scriptW,
            ascent = ascent,
            descent = descent,
            draw = { x, base ->
                baseBox.draw(this, x, base)
                sup?.let { s ->
                    // 上标底部贴着 base 的基线往上抬
                    s.draw(this, x + baseBox.width, base - supRise)
                }
                sub?.let { s ->
                    s.draw(this, x + baseBox.width, base + subDrop + s.ascent)
                }
            },
        )
    }

    is MathNode.Sqrt -> {
        val body = buildMathBox(node.body, size, m, color)
        val idx = node.index?.let { buildMathBox(it, size * 0.55f, m, color) }
        val pad = size * 0.16f
        val over = max(1f, size * 0.05f)
        val hookW = size * 0.42f
        val ascent = body.height + size * 0.18f
        MathBox(
            width = hookW + body.width + pad * 2,
            ascent = ascent,
            descent = body.descent,
            draw = { x, base ->
                val top = base - ascent
                val bottom = base + body.descent * 0.2f
                val path = Path().apply {
                    moveTo(x + 1f, top + ascent * 0.42f)
                    lineTo(x + hookW * 0.34f, top + ascent * 0.30f)
                    lineTo(x + hookW * 0.62f, bottom)
                    lineTo(x + hookW, top)
                    lineTo(x + hookW + body.width + pad * 2, top)
                }
                drawPath(path, color, style = Stroke(width = over))
                idx?.draw(this, x + 1f, top + ascent * 0.34f)
                body.draw(this, x + hookW + pad, base)
            },
        )
    }

    is MathNode.Big -> {
        // 求和/积分：符号本身放大，上下限缩到 0.6
        val glyph = buildMathBox(MathNode.Sym(node.glyph, italic = false), size * 1.5f, m, color)
        val sup = node.sup?.let { buildMathBox(it, size * 0.6f, m, color) }
        val sub = node.sub?.let { buildMathBox(it, size * 0.6f, m, color) }
        val sideW = max(sup?.width ?: 0f, sub?.width ?: 0f)
        val ascent = glyph.ascent + (sup?.height ?: 0f)
        val descent = glyph.descent + (sub?.height ?: 0f)
        MathBox(
            width = glyph.width + sideW,
            ascent = ascent,
            descent = descent,
            draw = { x, base ->
                val gx = x + (sideW - glyph.width) / 2 + sideW / 2
                glyph.draw(this, gx, base)
                sup?.draw(this, x + sideW / 2 + glyph.width / 2, base - glyph.ascent * 0.95f)
                sub?.draw(this, x + sideW / 2 + glyph.width / 2, base + glyph.descent * 0.95f + sub.ascent)
            },
        )
    }

    is MathNode.Delim -> {
        val body = buildMathBox(node.body, size, m, color)
        val open = textBox(node.open, size * 1.15f, m, color, italic = false)
        val close = textBox(node.close, size * 1.15f, m, color, italic = false)
        MathBox(
            width = open.width + body.width + close.width,
            ascent = max(body.ascent, max(open.ascent, close.ascent)),
            descent = max(body.descent, max(open.descent, close.descent)),
            draw = { x, base ->
                open.draw(this, x, base)
                body.draw(this, x + open.width, base)
                close.draw(this, x + open.width + body.width, base)
            },
        )
    }
}

private fun textBox(
    text: String,
    size: Float,
    m: TextMeasurer,
    color: Color,
    italic: Boolean,
    bold: Boolean = false,
    upright: Boolean = false,
): MathBox {
    if (text.isEmpty()) return MathBox(0f, 0f, 0f, { _, _ -> })
    val style = TextStyle(
        color = color,
        fontSize = size.sp,
        fontStyle = if (italic && !upright) FontStyle.Italic else FontStyle.Normal,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    )
    val result = m.measure(
        text = text,
        style = style,
        constraints = Constraints(),
    )
    val w = result.size.width.toFloat()
    val h = result.size.height.toFloat()
    // 文本没有基线信息可用，按经验取 0.78 作为基线位置（绝大多数无衬线字体的 cap height 比例）
    val ascent = h * 0.78f
    return MathBox(
        width = w,
        ascent = ascent,
        descent = h - ascent,
        draw = { x, base ->
            drawText(
                textLayoutResult = result,
                topLeft = Offset(x, base - ascent),
            )
        },
    )
}

// ---------------------------------------------------------------------------
// 解析
// ---------------------------------------------------------------------------

/**
 * LaTeX 子集解析器。
 *
 * 递归下降，只认三种结构：分组 `{}`、上下标 `^ _`、命令 `\xxx`。
 * 未识别的命令保留为普通文本（`\foo` → 「\foo」），这样即使模型写了
 * 冷门命令，用户看到的也是原文而不是消失。
 */
object Latex {

    private val GREEK = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
        "zeta" to "ζ", "eta" to "η", "theta" to "θ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π", "rho" to "ρ",
        "sigma" to "σ", "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "chi" to "χ",
        "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ",
        "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
    )

    private val OPS = mapOf(
        "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓", "cdot" to "·",
        "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "ne" to "≠", "neq" to "≠",
        "approx" to "≈", "equiv" to "≡", "sim" to "∼", "propto" to "∝",
        "infty" to "∞", "partial" to "∂", "nabla" to "∇", "degree" to "°",
        "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "leftrightarrow" to "↔",
        "Rightarrow" to "⇒", "Leftarrow" to "⇐", "iff" to "⇔",
        "in" to "∈", "notin" to "∉", "subset" to "⊂", "supset" to "⊃",
        "cup" to "∪", "cap" to "∩", "forall" to "∀", "exists" to "∃",
        "cdots" to "⋯", "ldots" to "…", "dots" to "…", "vdots" to "⋮",
        "angle" to "∠", "triangle" to "△", "perp" to "⊥", "parallel" to "∥",
        "circ" to "∘", "prime" to "′", "hbar" to "ℏ", "ell" to "ℓ",
        "quad" to " ", "qquad" to "  ", "," to " ", ";" to " ", ":" to " ",
        "!" to "", " " to " ",
    )

    private val FUNCS = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan",
        "log", "ln", "lg", "exp", "lim", "max", "min", "sup", "inf", "det", "gcd",
    )

    private val BIG = setOf("sum", "int", "prod", "iint", "oint", "bigcup", "bigcap")

    private const val MAX_DEPTH = 24

    fun parse(src: String): MathNode {
        val clean = src.trim().removePrefix("$$").removeSuffix("$$")
            .trim().removePrefix("\\[").removeSuffix("\\]")
        val tokens = tokenize(clean)
        val parser = Parser(tokens)
        return parser.parseSeq(depth = 0, stopAtBrace = false)
    }

    private sealed interface Tok {
        data class Cmd(val name: String) : Tok
        data class Ch(val c: Char) : Tok
        data object Open : Tok
        data object Close : Tok
        data object Sup : Tok
        data object Sub : Tok
    }

    private fun tokenize(s: String): List<Tok> {
        val out = ArrayList<Tok>(s.length)
        var i = 0
        while (i < s.length) {
            when (val c = s[i]) {
                '\\' -> {
                    var j = i + 1
                    if (j < s.length && !s[j].isLetter()) {
                        // \{ \} \, \; 这类单字符命令
                        out += Tok.Cmd(s[j].toString())
                        i = j + 1
                    } else {
                        while (j < s.length && s[j].isLetter()) j++
                        out += Tok.Cmd(s.substring(i + 1, j))
                        i = j
                    }
                }
                '{' -> { out += Tok.Open; i++ }
                '}' -> { out += Tok.Close; i++ }
                '^' -> { out += Tok.Sup; i++ }
                '_' -> { out += Tok.Sub; i++ }
                ' ', '\n', '\t' -> i++
                else -> { out += Tok.Ch(c); i++ }
            }
        }
        return out
    }

    private class Parser(private val toks: List<Tok>) {
        private var pos = 0

        fun parseSeq(depth: Int, stopAtBrace: Boolean): MathNode {
            val items = ArrayList<MathNode>()
            while (pos < toks.size) {
                val t = toks[pos]
                if (t is Tok.Close) {
                    if (stopAtBrace) break else { pos++; continue }
                }
                if (t is Tok.Sup || t is Tok.Sub) {
                    // 没有基底的上下标：当成空基底处理，避免吞掉后面的内容
                    val base = items.removeLastOrNull() ?: MathNode.Seq(emptyList())
                    items += parseScript(base, depth)
                    continue
                }
                items += parseAtom(depth)
            }
            return if (items.size == 1) items[0] else MathNode.Seq(items)
        }

        private fun parseScript(base: MathNode, depth: Int): MathNode {
            var sup: MathNode? = null
            var sub: MathNode? = null
            while (pos < toks.size) {
                when (toks[pos]) {
                    is Tok.Sup -> { pos++; sup = parseGroup(depth + 1) }
                    is Tok.Sub -> { pos++; sub = parseGroup(depth + 1) }
                    else -> break
                }
            }
            return MathNode.Script(base, sup, sub)
        }

        /** 上下标的参数：`{...}` 或单个原子 */
        private fun parseGroup(depth: Int): MathNode {
            if (depth > MAX_DEPTH) return MathNode.Text("")
            val t = toks.getOrNull(pos) ?: return MathNode.Text("")
            if (t is Tok.Open) {
                pos++
                val inner = parseSeq(depth, stopAtBrace = true)
                if (toks.getOrNull(pos) is Tok.Close) pos++
                return inner
            }
            return parseAtom(depth)
        }

        private fun parseAtom(depth: Int): MathNode {
            if (depth > MAX_DEPTH) return MathNode.Text("")
            val t = toks.getOrNull(pos) ?: return MathNode.Text("")
            pos++
            return when (t) {
                is Tok.Open -> {
                    val inner = parseSeq(depth + 1, stopAtBrace = true)
                    if (toks.getOrNull(pos) is Tok.Close) pos++
                    // 紧跟上下标时把整个分组当基底
                    if (toks.getOrNull(pos) is Tok.Sup || toks.getOrNull(pos) is Tok.Sub) {
                        parseScript(inner, depth)
                    } else {
                        inner
                    }
                }
                is Tok.Ch -> {
                    val base = MathNode.Sym(t.c.toString())
                    if (toks.getOrNull(pos) is Tok.Sup || toks.getOrNull(pos) is Tok.Sub) {
                        parseScript(base, depth)
                    } else {
                        base
                    }
                }
                is Tok.Cmd -> parseCommand(t.name, depth)
                else -> MathNode.Text("")
            }
        }

        private fun parseCommand(name: String, depth: Int): MathNode {
            // \frac{}{} —— 两个必选参数
            if (name == "frac" || name == "dfrac" || name == "tfrac") {
                val num = parseGroup(depth + 1)
                val den = parseGroup(depth + 1)
                return MathNode.Frac(num, den)
            }
            if (name == "sqrt") {
                var index: MathNode? = null
                if (toks.getOrNull(pos) == Tok.Ch('[')) {
                    pos++
                    val sb = StringBuilder()
                    while (pos < toks.size && toks[pos] != Tok.Ch(']')) {
                        when (val t = toks[pos]) {
                            is Tok.Ch -> sb.append(t.c)
                            is Tok.Cmd -> sb.append(t.name)
                            else -> Unit
                        }
                        pos++
                    }
                    if (pos < toks.size) pos++
                    index = MathNode.Sym(sb.toString(), italic = false)
                }
                return MathNode.Sqrt(parseGroup(depth + 1), index)
            }
            if (name == "text" || name == "mathrm" || name == "textbf" || name == "operatorname") {
                return MathNode.Text(readBraceRaw())
            }
            if (name == "left" || name == "right") {
                // \left( ... \right) 简化：读取紧随的分隔符作为普通符号
                val d = toks.getOrNull(pos)
                if (d is Tok.Ch) pos++
                return MathNode.Sym(d?.let { (it as? Tok.Ch)?.c?.toString() } ?: "", italic = false)
            }
            if (name in BIG) {
                val glyph = when (name) {
                    "sum" -> "∑"
                    "int" -> "∫"
                    "prod" -> "∏"
                    "iint" -> "∬"
                    "oint" -> "∮"
                    "bigcup" -> "⋃"
                    "bigcap" -> "⋂"
                    else -> "∑"
                }
                var sup: MathNode? = null
                var sub: MathNode? = null
                while (pos < toks.size) {
                    when (toks[pos]) {
                        is Tok.Sub -> { pos++; sub = parseGroup(depth + 1) }
                        is Tok.Sup -> { pos++; sup = parseGroup(depth + 1) }
                        else -> break
                    }
                }
                return MathNode.Big(glyph, sup, sub)
            }
            GREEK[name]?.let { return MathNode.Sym(it, italic = false) }
            OPS[name]?.let { return MathNode.Text(it) }
            if (name in FUNCS) return MathNode.Sym(name, italic = false)

            // lim_{x->0} 这种：函数名后面直接跟上下标
            if (toks.getOrNull(pos) is Tok.Sup || toks.getOrNull(pos) is Tok.Sub) {
                return parseScript(MathNode.Sym(name, italic = false), depth)
            }
            // 不认识的命令：原样显示，不要让它凭空消失
            return MathNode.Text("\\$name")
        }

        private fun readBraceRaw(): String {
            if (toks.getOrNull(pos) !is Tok.Open) {
                val t = toks.getOrNull(pos)
                if (t is Tok.Ch) { pos++; return t.c.toString() }
                return ""
            }
            pos++
            val sb = StringBuilder()
            var depth = 1
            while (pos < toks.size) {
                when (val t = toks[pos]) {
                    is Tok.Open -> { depth++; sb.append('{'); pos++ }
                    is Tok.Close -> {
                        depth--
                        pos++
                        if (depth == 0) break
                        sb.append('}')
                    }
                    is Tok.Ch -> { sb.append(t.c); pos++ }
                    is Tok.Cmd -> { sb.append(' '); pos++ }
                    is Tok.Sup -> { sb.append('^'); pos++ }
                    is Tok.Sub -> { sb.append('_'); pos++ }
                }
            }
            return sb.toString()
        }
    }
}

/** 兜底：解析失败时展示原文，不让一张坏公式把整条消息吞掉 */
@Composable
fun MathFallback(latex: String) {
    Text(
        text = latex,
        modifier = Modifier.fillMaxWidth().padding(4.dp),
        style = TextStyle(fontSize = 12.sp, color = Color(0xFFB0B0C0)),
    )
}
