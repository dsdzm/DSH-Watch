package com.dshwatch.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dshwatch.ui.components.tap
import com.dshwatch.ui.theme.LocalDarkTheme
import com.dshwatch.ui.wear.CircleGeometry

/**
 * 画布类内容的统一外壳：公式 / 思维导图 / 几何图。
 *
 * 三者共用一套「卡片 + 点开全屏」的行为，原因很实际：
 * 手表的圆屏可视区域只有二百来 dp，任何一张图在消息流里都只能显示个大概。
 * 所以卡片态只负责「让用户认出这是什么」，真正的阅读发生在全屏。
 */
enum class CanvasKind(val id: String, val title: String) {
    MATH("math", "公式"),
    MINDMAP("mindmap", "思维导图"),
    GEOMETRY("geom", "几何图"),
    ;

    companion object {
        /**
         * 从代码块语言标记判断是不是画布。
         *
         * 收得比较宽：模型写 ```latex / ```katex 的都有，写 ```mermaid 的还要看首行
         * 是不是 mindmap。宁可多认几个别名，也不要让一段公式退化成等宽代码。
         */
        fun of(language: String, body: String): CanvasKind? {
            val lang = language.lowercase().trim()
            return when {
                lang in setOf("math", "latex", "katex", "tex", "formula") -> MATH
                lang in setOf("mindmap", "mind-map", "mind_map", "脑图", "思维导图") -> MINDMAP
                lang in setOf("geom", "geometry", "figure", "几何") -> GEOMETRY
                lang == "mermaid" && body.trimStart().startsWith("mindmap", ignoreCase = true) -> MINDMAP
                else -> null
            }
        }
    }
}

/**
 * 这一类画布的强调色。
 *
 * 卡片标题和全屏标题必须一致，否则同一个「几何图」在消息流里是橙色、
 * 点开全屏变成蓝色 —— 看起来像是两段不同的内容。
 */
fun CanvasKind.accentOf(palette: CanvasPalette): Color = when (this) {
    CanvasKind.MATH -> palette.math
    CanvasKind.MINDMAP -> palette.mind
    CanvasKind.GEOMETRY -> palette.geom
}

/**
 * 消息流里的画布卡片。
 *
 * @param compact 给聊天流用（矮一点）；全屏时传 false
 */
@Composable
fun CanvasCard(
    kind: CanvasKind,
    source: String,
    modifier: Modifier = Modifier,
    compact: Boolean = true,
    expandable: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val palette = rememberCanvasPalette()
    val accent = kind.accentOf(palette)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.cardWash)
            .padding(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = kind.title,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
            )
            if (expandable) {
                Text(
                    text = if (compact) "全屏 ›" else "收起",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        // 点击挂在文字上，不挂整张卡片：卡片内部有缩放/拖拽手势，
                        // 外层再拦点击会让「想拖一下」变成「打开全屏」
                        .tap(onClick = { expanded = !expanded }),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        when (kind) {
            CanvasKind.MATH -> MathView(latex = source, compact = compact)
            CanvasKind.MINDMAP -> MindMapView(source = source, compact = compact)
            CanvasKind.GEOMETRY -> GeometryView(source = source, compact = compact)
        }
        if (expandable && compact) {
            Spacer(Modifier.height(4.dp))
        }
    }

    if (expanded) {
        CanvasFullscreen(kind = kind, source = source, onClose = { expanded = false })
    }
}

/** 全屏画布。用 Dialog 而不是新开路由：画布没有自己的状态，返回键就该就地消失 */
@Composable
fun CanvasFullscreen(
    kind: CanvasKind,
    source: String,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // 圆屏和方屏得走两套布局。圆屏上「铺满」等于四个角全被切掉，
        // 右上角那个关闭按钮直接落在圆外 —— 全屏进去就出不来。
        //
        // 底色**跟主题走**，并且和文字颜色取自同一个 palette：
        // 曾经这里固定画近黑底、而 View 按主题取字色，浅色模式下就成了
        // 「深字压黑底」，一整屏推导只剩轮廓。
        val dark = LocalDarkTheme.current
        val fullscreenPalette = rememberCanvasPalette(dark)
        val circle = com.dshwatch.ui.wear.LocalCircle.current
        CompositionLocalProvider(LocalCanvasPalette provides fullscreenPalette) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // cardBase 就是这套 palette 做对比度自检时假定的背景
                    .background(fullscreenPalette.cardBase),
            ) {
                if (circle != null) RoundFullscreen(circle, kind, source, onClose, fullscreenPalette)
                else WideFullscreen(kind, source, onClose, fullscreenPalette)
            }
        }
    }
}

/**
 * 圆屏全屏画布。
 *
 * 两件事必须做对：
 *
 * 1. **内容收进圆内**。圆屏「铺满」时四角在圆外，公式卡片、思维导图的
 *    边角会被裁掉一圈。能完整放进圆里的最大正方形是 `直径 / √2`；公式是横条，
 *    可以给到 0.86 的宽度（圆在中线附近最宽），二维图形则按内接正方形收。
 * 2. **关闭按钮挪到顶部中央**。手表圆屏的 x 轴上，越靠上可用宽度越窄：
 *    在 y≈40dp 那一行整个可用宽度只有约 150dp，右上角那个位置根本在圆外，
 *    看不见也点不到。
 *
 * 几何值一律从 [CircleGeometry] 取，不自己拿屏幕宽高当直径 ——
 * 有些表是 412×502，圆只占中间那条 412 宽的带子，用屏幕高算会整体错位。
 */
@Composable
private fun RoundFullscreen(
    circle: CircleGeometry,
    kind: CanvasKind,
    source: String,
    onClose: () -> Unit,
    palette: CanvasPalette,
) {
    val d = circle.diameter
    Box(Modifier.fillMaxSize()) {
        when (kind) {
            // 公式：横条，宽度可以给到 0.86d。高度上限卡在 0.40d，
            // 保证「宽度 0.86d × 高度 0.40d + 内边距」这个矩形四角还在圆内；
            // 多行公式超出时 MathView 会整体等比缩小，而不是被裁掉。
            CanvasKind.MATH -> Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(d * 0.86f),
            ) {
                MathView(latex = source, fontSize = 24f, maxHeight = d * 0.40f)
            }

            // 二维图形：必须整个矩形都在圆内，用内接正方形，再留 4% 余量
            CanvasKind.MINDMAP -> Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(d * CircleGeometry.SQRT_HALF * 0.96f),
            ) {
                MindMapView(source = source, modifier = Modifier.fillMaxSize())
            }

            CanvasKind.GEOMETRY -> Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(d * CircleGeometry.SQRT_HALF * 0.96f),
            ) {
                GeometryView(source = source, modifier = Modifier.fillMaxSize())
            }
        }

        // 圆顶中央。40dp 是手表上不用瞄准的触摸尺寸，再小就得「点两下试试」。
        // circle.top 是圆顶相对屏幕顶的偏移（屏幕比圆高时不是 0）。
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = circle.top + 6.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(palette.chromeWash)
                .tap(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "✕",
                style = MaterialTheme.typography.labelLarge,
                color = palette.ink,
            )
        }
    }
}

/** 方屏（模拟器、方形手表）全屏画布：原来那套，铺满即可 */
@Composable
private fun WideFullscreen(
    kind: CanvasKind,
    source: String,
    onClose: () -> Unit,
    palette: CanvasPalette,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = kind.title,
                style = MaterialTheme.typography.labelMedium,
                color = kind.accentOf(palette),
            )
            Text(
                text = "关闭 ✕",
                style = MaterialTheme.typography.labelMedium,
                color = palette.inkDim,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.chromeWash)
                    .tap(onClick = onClose)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp)),
        ) {
            when (kind) {
                CanvasKind.MATH -> MathView(latex = source, fontSize = 26f)
                CanvasKind.MINDMAP -> MindMapView(source = source, modifier = Modifier.fillMaxSize())
                CanvasKind.GEOMETRY -> GeometryView(source = source, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
