package com.dshwatch.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshwatch.core.Logx
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
    /**
     * 高度上限。
     *
     * 只在圆屏全屏时用：公式卡片是个矩形，宽度收到了圆内，高度一超四角照样
     * 会冲出圆周。传了上限，超出的部分**整体等比缩小**，不是裁掉 ——
     * 多行公式（矩阵、多行推导）在手表上本来也读不清，缩进圆里至少完整。
     */
    maxHeight: Dp? = null,
    /**
     * 正文里的公式：不铺卡片底、不留大边距。
     *
     * 一段话里夹着三行推导时，每行都套一张带圆角的卡片，读起来像三张截图；
     * 这里只画公式本身，靠留白把它和正文分开。
     */
    plain: Boolean = false,
    /**
     * 宽度有富余时自动放大。
     *
     * 全屏看公式时，一条两三个字符的式子（`v=v_0+at`）如果按 24sp 画，
     * 只占圆屏中间一小块 —— 用户点开「全屏」就是为了看清，结果字还是那么小。
     * 传一个大于 1 的上限，公式会撑到刚好占满可用宽度。
     *
     * 正文里不要开：那里公式应当和正文同字号，忽大忽小反而跳。
     */
    autoFit: Boolean = false,
    /** [autoFit] 的放大上限。放太大时细笔画会糊，也不像正文的一部分 */
    maxAutoScale: Float = 2.0f,
) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    /*
     * 默认正文色跟着主题走。
     *
     * 原来是写死的 `#E8E8F0`（近白）：深色卡片上正好，浅色卡片上就是
     * 「白字压白纸」—— 对比度 1.04 : 1，用户看到的是一段空白，
     * 只会以为公式没渲染出来。深色档的色值没变，改的只是「从哪来」。
     */
    val palette = rememberCanvasPalette()
    val resolved = if (color == Color.Unspecified) palette.ink else color
    /*
     * 不再对 compact 打折。
     *
     * 以前消息流里按 0.86 缩，理由是"卡片态只是让你认出这是什么"。
     * 但正文公式（plain = true）也走这条路，13sp × 0.86 ≈ 11sp，
     * 分数一压就成了一团墨点 —— 用户的原话是「正文部分的公式太小了，看不清」。
     * 卡片该让位的是**高度**，不是字号。
     */
    val sized = fontSize
    val box = remember(latex, sized, resolved) {
        // 解析在组合期跑（Choreographer 帧回调），异常会直接杀进程；公式再怪也只该显示原文
        val node = runCatching { Latex.parse(latex) }
            .onFailure { Logx.w("Math", "公式解析失败，按原文显示", it) }
            .getOrNull() ?: MathNode.Text(latex)
        buildMathBox(node, sized, measurer, resolved)
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (plain) {
                    Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
                } else {
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.cardWash)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                },
            ),
    ) {
        val density = LocalDensity.current
        /*
         * 宽高分开算，都从「内容实际占多少」出发：
         *
         * - 放不下（宽或高超出）→ 整体等比缩，这是原来就有的行为；
         * - 放得下且允许 autoFit → 放大到刚好占满，上限 maxAutoScale；
         * - 否则 1:1。
         *
         * 缩放用画布变换而不是重新排版：字号变了要重新测量整棵树，
         * 流式刷新时每帧都做一遍会卡；变换是纯绘制，结果一致。
         */
        val availW = with(density) { constraints.maxWidth.toFloat() }
        val fit = if (box.width > 1f && availW > 1f) (availW - 2f) / box.width else 1f
        val capPx = maxHeight?.let { with(density) { it.toPx() } }
        var scale = when {
            fit < 1f -> fit.coerceAtLeast(0.35f)
            autoFit -> fit.coerceAtMost(maxAutoScale)
            else -> 1f
        }
        // 高度上限优先：多行推导宁可整体缩，也不能冲出圆屏四角
        if (capPx != null && box.height > 0f && box.height * scale > capPx) {
            scale = (capPx / box.height).coerceAtLeast(0.35f)
        }
        val heightPx = (box.height + 6f) * scale
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { heightPx.toDp() }),
        ) {
            // 宽度方向再兜一次：maxWidth 可能因为父级测量顺序比可用宽度略大
            val byWidth = if (box.width > 1f && box.width * scale > size.width - 2f) {
                (size.width - 2f) / box.width
            } else {
                scale
            }
            val s = minOf(byWidth, scale)
            withTransform({ scale(s, s, pivot = Offset.Zero) }) {
                box.draw(this, 1f, box.ascent + 2f)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 节点
// ---------------------------------------------------------------------------

sealed interface MathNode {
    data class Seq(val items: List<MathNode>) : MathNode

    /**
     * 单个符号。
     *
     * [scale] 是给定界符用的：`\Big(`、`\left[` 这类命令要求括号比正文高，
     * 现在按字号倍率实现 —— 内容高度变化时括号不会自动跟随，但比「原样吐出
     * \Big 命令」好得多，也不用把解析器改成能跨节点配对的括号栈。
     */
    data class Sym(
        val text: String,
        val italic: Boolean = true,
        val bold: Boolean = false,
        val scale: Float = 1f,
    ) : MathNode

    data class Frac(val num: MathNode, val den: MathNode) : MathNode
    data class Script(val base: MathNode, val sup: MathNode? = null, val sub: MathNode? = null) : MathNode
    data class Sqrt(val body: MathNode, val index: MathNode? = null) : MathNode
    data class Big(val glyph: String, val sup: MathNode? = null, val sub: MathNode? = null) : MathNode
    data class Delim(val open: String, val body: MathNode, val close: String) : MathNode
    data class Text(val raw: String) : MathNode

    /**
     * 重音 / 上划线。
     *
     * 物理和统计里到处都是 `\bar v`（平均速度）、`\bar x`（均值）、`\vec v`、
     * `\hat y`。这些命令以前没有实现，落到「不认识的命令」分支，原样吐出
     * `\barv` —— 一条本来能看懂的式子立刻变成源码。
     */
    data class Accent(val body: MathNode, val kind: AccentKind) : MathNode
}

/** 重音种类。画法分两类：横线（bar / underline）与居中符号（其余） */
enum class AccentKind {
    /** `\bar v`：短横线，只覆盖一个字母，可以压平进句子 */
    BAR,
    /** `\overline{AB}`：覆盖整个组，可能好几位字符 —— 压平会把线只画在最后一个字母上 */
    OVERLINE,
    UNDERLINE, HAT, VEC, TILDE, DOT, DDOT,
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

    is MathNode.Sym -> textBox(node.text, size * node.scale, m, color, node.italic, node.bold)

    is MathNode.Text -> textBox(node.raw, size, m, color, italic = false, bold = false, upright = true)

    is MathNode.Frac -> {
        val num = buildMathBox(node.num, size * 0.96f, m, color)
        val den = buildMathBox(node.den, size * 0.96f, m, color)
        val gap = size * 0.14f
        /*
         * 分数线坐在「数学轴」上，不是蹲在基线上。
         *
         * LaTeX 把分数线放在基线上方约 0.25em —— 那是 `=`、`+`、`−` 横画的
         * 高度。以前分数线压在基线上，`x_0=\frac{…}{2}` 里的等号和分数线
         * 一高一低，整个等式看起来是歪的（用户说的「分式不太好看」）。
         */
        val axis = size * 0.25f
        val barW = max(num.width, den.width) + size * 0.30f
        val barThick = max(1f, size * 0.045f)
        val ascent = num.height + gap + barThick / 2 + axis
        val descent = den.height + gap + barThick / 2 - axis
        MathBox(
            width = barW,
            ascent = ascent,
            descent = descent,
            draw = { x, base ->
                val barY = base - axis
                val cx = x + (barW - num.width) / 2
                /*
                 * 分子要按**框底**贴线，不是按基线。
                 *
                 * 分子的基线在框底上方 descent 处（`v_0^2` 这种带下标的，
                 * descent 差不多是半个字高）。原来直接把基线放在 `barY - gap`，
                 * 框底就多出一个 descent 的缝 —— 分子被整体抬高，
                 * 分数线下面却紧贴着分母，整条分式看起来是错位的。
                 */
                num.draw(this, cx, barY - gap - num.descent)
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
        val scriptSize = size * 0.66f
        val sup = node.sup?.let { buildMathBox(it, scriptSize, m, color) }
        val sub = node.sub?.let { buildMathBox(it, scriptSize, m, color) }
        /*
         * 上下标：位移就是「子盒基线相对主基线的偏移」，只有这一个量。
         *
         * 这里踩过两次坑，都是把「位移」和「让开字形」混成了一个数：
         *
         *  1. 旧版 ascent 写 `sup.height - supRise`，高度里减位移、框却不跟着变高；
         *  2. 上一版把下标画在 `base + subSink + sub.ascent` —— 第二个参数是
         *     **基线**，再叠一个 ascent 等于把下标又压低了整整一个字高。
         *     而 subSink 自身还用 `0.08 + sub.ascent` 让位，两笔加起来
         *     下标被推到基线下 0.7em 开外。19sp 时这就是用户看到的
         *     「v 在上、0 掉到分数线附近」—— 字号越大越明显。
         *
         * LaTeX 的实际取值：上标 0.4em、下标 0.2em（以主字号为 em）。
         * 盒子高度必须由「位移 + 子盒自身的 ascent/descent」算，
         * 用 descent 而不是 height —— height 里含 ascent，会把盒子撑高一整行。
         */
        val supShift = size * 0.40f
        val subShift = size * 0.20f
        // 斜体字母的右下有笔画，下标要留一点横向缝，否则「x₀」会挤在一起
        val kern = if (baseBox.width > 0f) size * 0.03f else 0f
        val scriptW = max(sup?.width ?: 0f, sub?.width ?: 0f)
        val ascent = max(baseBox.ascent, supShift + (sup?.ascent ?: 0f))
        val descent = max(baseBox.descent, subShift + (sub?.descent ?: 0f))
        MathBox(
            width = baseBox.width + kern + scriptW,
            ascent = ascent,
            descent = descent,
            draw = { x, base ->
                baseBox.draw(this, x, base)
                sup?.let { s -> s.draw(this, x + baseBox.width + kern, base - supShift) }
                sub?.let { s -> s.draw(this, x + baseBox.width + kern, base + subShift) }
            },
        )
    }

    /*
     * 重音：\bar \overline \hat \vec \tilde \dot \ddot \underline
     *
     * 之前这些命令全部落到「不认识的命令」分支，原样吐出 `\barv` ——
     * 用户看到的是一串 LaTeX 源码混在算好的式子里（截图里 `\barv=\frac{…}`）。
     * 横线类直接画线（能精确对齐宽度），帽子类画一个居中的符号。
     */
    is MathNode.Accent -> {
        val body = buildMathBox(node.body, size, m, color)
        val thick = max(1f, size * 0.05f)
        when (node.kind) {
            AccentKind.BAR, AccentKind.OVERLINE, AccentKind.UNDERLINE -> {
                val gap = size * 0.12f
                val above = node.kind != AccentKind.UNDERLINE
                MathBox(
                    width = body.width,
                    ascent = body.ascent + if (above) gap + thick else 0f,
                    descent = body.descent + if (above) 0f else gap + thick,
                    draw = { x, base ->
                        body.draw(this, x, base)
                        val y = if (above) {
                            base - body.ascent - gap - thick / 2
                        } else {
                            base + body.descent + gap + thick / 2
                        }
                        drawLine(
                            color = color,
                            start = Offset(x, y),
                            end = Offset(x + body.width, y),
                            strokeWidth = thick,
                        )
                    },
                )
            }

            else -> {
                val mark = when (node.kind) {
                    AccentKind.HAT -> "ˆ"
                    AccentKind.VEC -> "→"
                    AccentKind.TILDE -> "˜"
                    AccentKind.DOT -> "·"
                    AccentKind.DDOT -> "··"
                    else -> ""
                }
                val markBox = textBox(mark, size * 0.9f, m, color, italic = false, upright = true)
                val gap = size * 0.02f
                MathBox(
                    width = body.width,
                    ascent = body.ascent + gap + markBox.height,
                    descent = body.descent,
                    draw = { x, base ->
                        body.draw(this, x, base)
                        markBox.draw(
                            this,
                            x + (body.width - markBox.width) / 2,
                            base - body.ascent - gap,
                        )
                    },
                )
            }
        }
    }

    is MathNode.Sqrt -> {
        val body = buildMathBox(node.body, size, m, color)
        val idx = node.index?.let { buildMathBox(it, size * 0.55f, m, color) }
        val pad = size * 0.16f
        val over = max(1f, size * 0.055f)
        val hookW = size * 0.46f
        /*
         * 横线必须贴着内容的顶端。
         *
         * 之前 ascent 取的是 body.height（字高），而内容实际是从 body.ascent
         * 往上顶的 —— 两者差 0.22×字高，再加 0.18×字号，横线与数字之间空出近半行。
         * 用户看到的就是「√ 上面一条线，170 漂在下面」，像是根号没包住内容。
         */
        val gap = size * 0.10f
        val ascent = body.ascent + gap
        val descent = max(size * 0.10f, body.descent)
        MathBox(
            width = hookW + body.width + pad * 2,
            ascent = ascent,
            descent = descent,
            draw = { x, base ->
                val top = base - ascent
                val bottom = base + body.descent
                val path = Path().apply {
                    moveTo(x + 1f, base - ascent * 0.44f)
                    lineTo(x + hookW * 0.32f, base - ascent * 0.30f)
                    lineTo(x + hookW * 0.60f, bottom)
                    lineTo(x + hookW, top)
                    lineTo(x + hookW + body.width + pad * 2, top)
                }
                drawPath(path, color, style = Stroke(width = over))
                idx?.draw(this, x + 1f, top + ascent * 0.32f)
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
        /*
         * 去掉 Android 默认的字体留白。
         *
         * 原来这里靠「行高 × 0.78」猜基线，而 Android 的行高里含着 ascent 之上
         * 的额外留白（约 0.1em）和 descent 之下的留白，整行约 1.15em。
         * 猜出来的基线偏低、盒子偏矮，分式里的分子分母就整体贴着分数线，
         * 下标还会穿过横线 —— 那正是用户截图里「带角标的分式错位」。
         * 关掉留白后行高就等于字体自身的 ascent + descent，配合下面的
         * firstBaseline，盒子边界才和字形对得上。
         */
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
    val result = m.measure(
        text = text,
        style = style,
        constraints = Constraints(),
    )
    val w = result.size.width.toFloat()
    val h = result.size.height.toFloat()
    /*
     * 基线取排版结果的真实值，不再按比例猜。
     *
     * `firstBaseline` 是这一行基线相对文本顶部的距离，字体、字号、粗细变了都
     * 自动跟着变；猜的 0.78 只对某几个字体成立。
     */
    val baseline = result.firstBaseline
    val ascent = if (baseline.isFinite() && baseline > 0f) baseline else h * 0.78f
    return MathBox(
        width = w,
        ascent = ascent,
        descent = (h - ascent).coerceAtLeast(0f),
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
        "Rightarrow" to "⇒", "Leftarrow" to "⇐", "Leftrightarrow" to "⇔", "iff" to "⇔",
        // 长箭头：模型写「等价于」时几乎都用 Longleftrightarrow，之前会原样吐出来
        "Longleftrightarrow" to "⟺", "Longleftarrow" to "⟸", "Longrightarrow" to "⟹",
        "longleftrightarrow" to "⟷", "longleftarrow" to "⟵", "longrightarrow" to "⟶",
        "mapsto" to "↦", "longmapsto" to "⟼", "implies" to "⟹", "impliedby" to "⟸",
        "uparrow" to "↑", "downarrow" to "↓", "updownarrow" to "↕",
        "in" to "∈", "notin" to "∉", "subset" to "⊂", "supset" to "⊃",
        "subseteq" to "⊆", "supseteq" to "⊇", "emptyset" to "∅", "varnothing" to "∅",
        "cup" to "∪", "cap" to "∩", "forall" to "∀", "exists" to "∃",
        "neg" to "¬", "lnot" to "¬", "land" to "∧", "wedge" to "∧", "lor" to "∨", "vee" to "∨",
        "cdots" to "⋯", "ldots" to "…", "dots" to "…", "vdots" to "⋮", "ddots" to "⋱",
        "angle" to "∠", "triangle" to "△", "perp" to "⊥", "parallel" to "∥",
        "circ" to "∘", "prime" to "′", "hbar" to "ℏ", "ell" to "ℓ",
        "because" to "∵", "therefore" to "∴",
        "lvert" to "|", "rvert" to "|", "vert" to "|", "mid" to "|", "|" to "‖",
        "lVert" to "‖", "rVert" to "‖", "Vert" to "‖",
        "langle" to "⟨", "rangle" to "⟩", "lfloor" to "⌊", "rfloor" to "⌋",
        "lceil" to "⌈", "rceil" to "⌉",
        // \{ \} 是转义花括号，不是分组，直接当字符画
        "{" to "{", "}" to "}",
        "quad" to " ", "qquad" to "  ", "," to " ", ";" to " ", ":" to " ",
        "!" to "", " " to " ",
    )

    private val FUNCS = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan",
        "log", "ln", "lg", "exp", "lim", "max", "min", "sup", "inf", "det", "gcd",
    )

    private val BIG = setOf("sum", "int", "prod", "iint", "oint", "bigcup", "bigcap")

    /**
     * 「请把括号画高一点」的命令 → 字号倍率。
     *
     * LaTeX 里 big/Big/bigg/Bigg 是四档固定高度，语义上等价于「比正文高多少」，
     * 所以这里直接映射成倍率，不区分配对关系。
     */
    private val BIG_DELIM = mapOf(
        "big" to 1.2f, "bigl" to 1.2f, "bigr" to 1.2f, "bigm" to 1.2f,
        "Big" to 1.5f, "Bigl" to 1.5f, "Bigr" to 1.5f, "Bigm" to 1.5f,
        "bigg" to 1.8f, "biggl" to 1.8f, "biggr" to 1.8f, "biggm" to 1.8f,
        "Bigg" to 2.1f, "Biggl" to 2.1f, "Biggr" to 2.1f, "Biggm" to 2.1f,
    )

    /** 紧跟在这些命令后面、可以直接当字符用的定界符 */
    private val DELIM_CHARS = setOf('(', ')', '[', ']', '|', '.', '/', '<', '>')

    /** 用命令写的定界符 */
    private val DELIM_CMDS = mapOf(
        "{" to "{", "}" to "}", "|" to "‖",
        "lvert" to "|", "rvert" to "|", "vert" to "|", "mid" to "|",
        "lVert" to "‖", "rVert" to "‖", "Vert" to "‖",
        "langle" to "⟨", "rangle" to "⟩",
        "lfloor" to "⌊", "rfloor" to "⌋", "lceil" to "⌈", "rceil" to "⌉",
    )

    private const val MAX_DEPTH = 24

    /** 重音命令 → 画法 */
    private val ACCENTS = mapOf(
        "bar" to AccentKind.BAR,
        "overline" to AccentKind.OVERLINE,
        "underline" to AccentKind.UNDERLINE,
        "hat" to AccentKind.HAT,
        "widehat" to AccentKind.HAT,
        "vec" to AccentKind.VEC,
        "overrightarrow" to AccentKind.VEC,
        "tilde" to AccentKind.TILDE,
        "widetilde" to AccentKind.TILDE,
        "dot" to AccentKind.DOT,
        "ddot" to AccentKind.DDOT,
    )

    /** 正体：数学里 `d`、`e`、单位、`{\rm I}` 都该直立 */
    private val UPRIGHT_CMDS = setOf(
        "mathrm", "mathit", "mathsf", "mathtt", "mathcal", "operatorname",
        "rm", "it", "sf", "tt", "cal",
    )

    private val BOLD_CMDS = setOf("mathbf", "boldsymbol", "bm", "bf")

    /** 声明式命令：作用于排版环境，本身不出字形 */
    private val STYLE_CMDS = setOf(
        "displaystyle", "textstyle", "scriptstyle", "scriptscriptstyle",
        "limits", "nolimits", "nonumber", "notag", "left.", "right.",
    )

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

    /** 把一段字母变成符号节点：整体是希腊字母名时按一个符号处理（`\bartheta`） */
    private fun symbolsOf(rest: String): MathNode {
        GREEK[rest]?.let { return MathNode.Sym(it, italic = false) }
        val chars = rest.map { MathNode.Sym(it.toString()) }
        return when (chars.size) {
            0 -> MathNode.Text("")
            1 -> chars[0]
            else -> MathNode.Seq(chars)
        }
    }

    /**
     * 把一棵子树里的字母改成直立（`\mathrm` / `\rm`）。
     *
     * 不直接取原文：`\mathrm{kg\cdot m/s^2}` 里含命令和上下标，按字符串读会
     * 丢掉结构（`^2` 变成两个字面字符）。走节点树就只改变字形，结构保留。
     */
    private fun uprightOf(node: MathNode, bold: Boolean = false): MathNode = when (node) {
        is MathNode.Sym -> node.copy(italic = false, bold = node.bold || bold)
        is MathNode.Seq -> MathNode.Seq(node.items.map { uprightOf(it, bold) })
        is MathNode.Script -> MathNode.Script(
            base = uprightOf(node.base, bold),
            sup = node.sup?.let { uprightOf(it, bold) },
            sub = node.sub?.let { uprightOf(it, bold) },
        )
        is MathNode.Frac -> MathNode.Frac(uprightOf(node.num, bold), uprightOf(node.den, bold))
        is MathNode.Sqrt -> MathNode.Sqrt(
            body = uprightOf(node.body, bold),
            index = node.index?.let { uprightOf(it, bold) },
        )
        is MathNode.Big -> MathNode.Big(
            glyph = node.glyph,
            sup = node.sup?.let { uprightOf(it, bold) },
            sub = node.sub?.let { uprightOf(it, bold) },
        )
        is MathNode.Delim -> MathNode.Delim(node.open, uprightOf(node.body, bold), node.close)
        is MathNode.Accent -> MathNode.Accent(uprightOf(node.body, bold), node.kind)
        is MathNode.Text -> node
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
            /*
             * 重音命令。放在最前面：`\bar v`、`\bar{v}`、`\overline{AB}` 都要吃
             * 紧随其后的一个组或一个原子，晚于下面的通用分支处理就会被当成未知命令。
             */
            ACCENTS[name]?.let { kind ->
                return MathNode.Accent(parseGroup(depth + 1), kind)
            }
            /*
             * `\barv` —— 命令名和后面的字母粘在一起。
             *
             * 模型写 `\bar v` 时经常漏掉空格或花括号，直接输出 `\barv`。
             * 严格按命令名解析就会得到「未知命令 barv」，屏幕上是一串源码 ——
             * 用户截图里正是 `\barv=\frac{v_t+v}{2}`。
             * 拆前缀比要求模型每次写对更实际。
             */
            ACCENTS.entries
                .filter { name.length > it.key.length && name.startsWith(it.key) }
                .maxByOrNull { it.key.length }
                ?.let { (prefix, kind) ->
                    return MathNode.Accent(symbolsOf(name.substring(prefix.length)), kind)
                }
            /*
             * 字体切换。
             *
             * `{\rm I}`、`\mathrm{d}`、`\bf v` 在物理书里到处都是。`\rm` 是声明式
             * 命令（作用于后面的内容），这里按「吃一个参数」处理 —— 它后面通常
             * 紧跟 `{...}` 或一个字母，两种写法都能对上。
             */
            if (name in UPRIGHT_CMDS || name in BOLD_CMDS) {
                val body = parseGroup(depth + 1)
                return uprightOf(body, bold = name in BOLD_CMDS)
            }
            /*
             * 无参数的样式声明与间距命令：本身不占位，吃掉即可。
             *
             * 以前它们会落到兜底分支原样显示 —— `\displaystyle` 就这么占了小半行。
             */
            if (name in STYLE_CMDS) return MathNode.Text("")

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
            if (name == "text" || name == "textbf" || name == "mbox" || name == "textnormal") {
                return MathNode.Text(readBraceRaw())
            }
            /*
             * 定界符：\left( … \right)、\Big( … \Big)、\bigl[ … \bigr]
             *
             * 模型写「(∑a)²」时几乎都会用 \Big( 或 \left( —— 它们只是告诉排版引擎
             * 「括号要比正文高」。这里不建括号栈（那要跨节点配对，遇上 ^ 挂在哪一侧
             * 很容易错位），而是把命令本身翻译成「放大一档的括号字符」：
             * 视觉上括号确实变高了，`^2` 也自然挂在右括号上。
             *
             * 不这么做的话，`\Big` 会走到最后的兜底分支，原样显示成 "\Big" ——
             * 用户看到的就是一屏「f(t)=\Big(∑…」。
             */
            if (name in BIG_DELIM) {
                val d = readDelimiter() ?: return MathNode.Text("")
                return MathNode.Sym(d, italic = false, scale = BIG_DELIM.getValue(name))
            }
            if (name == "left" || name == "middle") {
                val d = readDelimiter() ?: return MathNode.Text("")
                return if (d == ".") MathNode.Text("") else MathNode.Sym(d, italic = false, scale = 1.35f)
            }
            if (name == "right") {
                val d = readDelimiter() ?: return MathNode.Text("")
                return if (d == ".") MathNode.Text("") else MathNode.Sym(d, italic = false, scale = 1.35f)
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

        /**
         * 读紧随其后的定界符：`(`、`[`、`|`、`\{`、`\langle` 都算。
         *
         * 返回 null 表示后面不是定界符（例如 `\Big` 后面直接跟了 ∑）——
         * 这种情况返回空文本，不把 "\Big" 吐到屏幕上。
         */
        private fun readDelimiter(): String? {
            val t = toks.getOrNull(pos) ?: return null
            return when (t) {
                is Tok.Ch -> if (t.c in DELIM_CHARS) { pos++; t.c.toString() } else null
                is Tok.Open -> { pos++; "{" }
                is Tok.Close -> { pos++; "}" }
                is Tok.Cmd -> DELIM_CMDS[t.name]?.also { pos++ }
                else -> null
            }
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
    // 解析失败时至少要用当前主题的正文色 —— 否则浅色模式下这段原文也是隐形的
    val palette = rememberCanvasPalette()
    Text(
        text = latex,
        modifier = Modifier.fillMaxWidth().padding(4.dp),
        style = TextStyle(fontSize = 12.sp, color = palette.inkDim),
    )
}
