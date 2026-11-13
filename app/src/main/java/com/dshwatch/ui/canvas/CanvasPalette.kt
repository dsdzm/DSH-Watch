package com.dshwatch.ui.canvas

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.dshwatch.ui.theme.LocalDarkTheme

/**
 * 画布（公式 / 思维导图 / 几何图）的配色。
 *
 * ## 为什么必须有一套，而不是各处写死颜色
 *
 * 这三类内容的颜色原本是照着「深色卡片」挑的：正文 `#E8E8F0`、连线 `#5A6C8C`、
 * 节点底 `#232A38`。深色模式下没问题 —— 亮字压在暗底上。
 *
 * 但浅色模式下卡片是白的：`#E8E8F0` 叠在白底上是 **1.04 : 1**，
 * 肉眼几乎看不见。用户看到的现象不是「颜色淡」，而是**公式整段消失**，
 * 只剩一个空白的框 —— 很容易被当成渲染失败。
 *
 * 所以颜色必须跟着主题走。这里做的是**一套语义化的调色板**：谁负责正文、
 * 谁负责强调、谁是「线」、谁是「节点底」，由主题决定具体色值。
 *
 * ## 为什么不全用 MaterialTheme 的颜色
 *
 * 公式 / 图例 / 角度弧这三种颜色承担的是**区分**职责（这是公式、那是辅助线、
 * 那是角度）。直接映射到 primary / secondary / tertiary 会让它们跟着用户换主题
 * 一起变色 —— 「深海蓝」主题下公式和导图会撞成同一个蓝，区分就没了。
 *
 * 所以正文与次要文字用主题色（保证和界面一致），三个语义色用固定的色相，
 * 只在明暗两档之间调整**明度**，保证两档都过 4.5 : 1。
 *
 * ## 全屏画布是另一回事
 *
 * 全屏时背景固定是近黑的（`CanvasFullscreen` 自己画的那层），与主题无关 ——
 * 用户点开全屏是为了看图，不是看壁纸。所以那里固定用 [darkPalette]，
 * 不管当前是浅色还是深色主题。
 */
@Immutable
data class CanvasPalette(
    val dark: Boolean,
    /** 公式正文、导图节点文字、几何图标签 */
    val ink: Color,
    /** 次要文字：辅助说明、坐标轴标签 */
    val inkDim: Color,
    /** 语义色：公式块标题 */
    val math: Color,
    /** 语义色：思维导图 */
    val mind: Color,
    /** 语义色：几何图 */
    val geom: Color,
    /** 导图连线 */
    val link: Color,
    /** 导图普通节点 */
    val nodeFill: Color,
    /** 导图根节点（比普通节点重一档） */
    val nodeFillRoot: Color,
    val nodeStroke: Color,
    val nodeStrokeRoot: Color,
    /** 几何图形填充（带 alpha 的浅色） */
    val shapeFill: Color,
    val shapeStroke: Color,
    /** 角度弧、强调标注 */
    val accent: Color,
    /** 顶点 */
    val point: Color,
    val pointHalo: Color,
    /** 坐标轴 */
    val axis: Color,
    /**
     * 卡片底色（半透明的一层）。
     *
     * 浅色下不能再用「叠一点白」：白底上叠白等于没有卡片，公式会像是浮在页面
     * 空白处。改成叠一点正文色，卡片才有边界。
     */
    val cardWash: Color,
    /**
     * 这一档调色板**假定**的背景色。
     *
     * 只用于对比度自检（[contrastRatio]）：颜色对不对是肉眼的事，
     * 但「至少读得出来」是可以算的，也应该在测试里钉死。
     */
    val cardBase: Color,
)

/** WCAG 对比度：1.0 ~ 21.0。正文要 4.5 以上。 */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05f) / (lo + 0.05f)
}

fun darkPalette(): CanvasPalette = CanvasPalette(
    dark = true,
    ink = Color(0xFFE8E8F0),
    inkDim = Color(0xFFB0B0C0),
    math = Color(0xFF8FB4E8),
    mind = Color(0xFF7FD1AE),
    geom = Color(0xFFE0B04A),
    link = Color(0xFF5A6C8C),
    nodeFill = Color(0xFF232A38),
    nodeFillRoot = Color(0xFF2B4A72),
    nodeStroke = Color(0xFF46536B),
    nodeStrokeRoot = Color(0xFF6E9BD6),
    shapeFill = Color(0x294A7EDB),
    shapeStroke = Color(0xFF6E9BD6),
    accent = Color(0xFFE0B04A),
    point = Color(0xFFFF6B6B),
    pointHalo = Color(0x66FFFFFF),
    axis = Color(0x33FFFFFF),
    cardWash = Color.White.copy(alpha = 0.04f),
    cardBase = Color(0xFF0E0F14),
)

/**
 * 浅色档。
 *
 * 每个语义色都往暗里走了一到两档 —— 不是审美，是换白底之后必须补偿明度：
 * `#8FB4E8` 在白底上只有 2.0 : 1，而 `#2C5FA8` 是 6.0 : 1。
 */
fun lightPalette(): CanvasPalette = CanvasPalette(
    dark = false,
    ink = Color(0xFF1B1B1F),
    inkDim = Color(0xFF5A6274),
    math = Color(0xFF2C5FA8),
    mind = Color(0xFF1F7A5C),
    geom = Color(0xFF8A5A00),
    link = Color(0xFF71829C),
    nodeFill = Color(0xFFEDF1F7),
    nodeFillRoot = Color(0xFFDCE7F7),
    nodeStroke = Color(0xFFC3CEDE),
    nodeStrokeRoot = Color(0xFF3F72B8),
    shapeFill = Color(0x2E4A7EDB),
    shapeStroke = Color(0xFF4A7EDB),
    accent = Color(0xFFB5761A),
    point = Color(0xFFD93B3B),
    pointHalo = Color(0x33000000),
    axis = Color(0x33000000),
    // 浅色下卡片靠「压一点」成形，理由见 cardWash 的注释
    cardWash = Color(0xFF1B1B1F).copy(alpha = 0.05f),
    cardBase = Color(0xFFF7F8FA),
)

/**
 * 当前主题该用哪一档。
 *
 * `dark` 由 [LocalDarkTheme] 给（不能从 background 反推：玻璃预设的背景是透明的，
 * 见 Theme.kt 里那段说明）。
 */
@Composable
fun rememberCanvasPalette(): CanvasPalette {
    val dark = LocalDarkTheme.current
    // 卡片文字最终落在 surface / surfaceVariant 上，正文色直接取主题的，
    // 这样换预设、换强调色时画布文字和正文永远是一致的
    val onSurface = MaterialTheme.colorScheme.onSurface
    val base = if (dark) darkPalette() else lightPalette()
    return if (dark) base else base.copy(ink = onSurface, inkDim = MaterialTheme.colorScheme.onSurfaceVariant)
}
