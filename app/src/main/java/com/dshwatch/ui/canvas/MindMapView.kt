package com.dshwatch.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 思维导图渲染。
 *
 * ## 输入格式
 *
 * 两种都吃：
 *  1. Markdown 缩进列表（模型最自然的输出方式）；
 *  2. mermaid 的 `mindmap` 块（模型被要求画导图时也常这么写）。
 *
 * ## 为什么是横向树
 *
 * 手表屏幕是圆的，纵向空间比横向更珍贵；而思维导图的天然读法是「从左往右展开」。
 * 横向树在圆屏上可以横向拖、纵向不用拖 —— 手势方向上更省事。
 *
 * ## 布局算法
 *
 * 经典的两趟：先算每层最大宽度定 x，再后序遍历按叶子顺序定 y（父节点取子节点中点）。
 * 没有力导向、没有避让 —— 这类图上节点不会重叠，简单算法反而更可预测。
 */
@Composable
fun MindMapView(
    source: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val measurer = rememberTextMeasurer(cacheSize = 128)
    val tree = remember(source) { MindMap.parse(source) }
    if (tree == null) {
        MathFallback("思维导图解析失败，原文：\n$source")
        return
    }

    val textStyle = TextStyle(fontSize = if (compact) 11.sp else 13.sp, color = Color(0xFFE6E6F0))
    val rootStyle = textStyle.copy(fontWeight = FontWeight.Bold, color = Color(0xFFB9D8FF))
    val placed = remember(source, compact) { MindMap.layout(tree, measurer, textStyle, rootStyle) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 全屏时交给外层撑满：写死高度会让「全屏」只剩中间一条
            .then(if (compact) Modifier.height(150.dp) else Modifier.fillMaxSize())
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .pointerInput(placed) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.4f, 3f)
                    offset += pan
                    // 让缩放围绕手指位置，而不是画布左上角
                    offset += centroid * (1f - zoom)
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (placed.nodes.isEmpty()) return@Canvas
            // 首次自适应：整张图塞进可视区。用户放大后不再重算，否则会「怎么放都弹回去」
            val fit = if (scale == 1f && offset == Offset.Zero) {
                minOf(
                    (size.width - 12f) / placed.width.coerceAtLeast(1f),
                    (size.height - 12f) / placed.height.coerceAtLeast(1f),
                    1f,
                )
            } else {
                1f
            }
            withTransform({
                translate(
                    left = 6f + offset.x + (size.width - 12f - placed.width * fit * scale) / 2f,
                    top = 6f + offset.y + (size.height - 12f - placed.height * fit * scale) / 2f,
                )
                scale(fit * scale, fit * scale, pivot = Offset.Zero)
            }) {
                // 1) 连线：三次贝塞尔，水平控制点让曲线「从左往右」而不是直线折角
                placed.edges.forEach { (a, b) ->
                    val path = Path().apply {
                        val sx = a.x + a.w
                        val sy = a.y + a.h / 2
                        val ex = b.x
                        val ey = b.y + b.h / 2
                        val dx = (ex - sx) * 0.55f
                        moveTo(sx, sy)
                        cubicTo(sx + dx, sy, ex - dx, ey, ex, ey)
                    }
                    drawPath(path, Color(0xFF5A6C8C), style = Stroke(width = 1.4f))
                }
                // 2) 节点
                placed.nodes.forEach { n ->
                    drawRoundRect(
                        color = if (n.depth == 0) Color(0xFF2B4A72) else Color(0xFF232A38),
                        topLeft = Offset(n.x, n.y),
                        size = Size(n.w, n.h),
                        cornerRadius = CornerRadius(n.h / 2.6f),
                    )
                    drawRoundRect(
                        color = if (n.depth == 0) Color(0xFF6E9BD6) else Color(0xFF46536B),
                        topLeft = Offset(n.x, n.y),
                        size = Size(n.w, n.h),
                        cornerRadius = CornerRadius(n.h / 2.6f),
                        style = Stroke(width = 1f),
                    )
                    // 文字居中：drawText 给的是左上角，所以按盒子尺寸算偏移
                    drawText(
                        textLayoutResult = n.text,
                        topLeft = Offset(
                            n.x + (n.w - n.text.size.width) / 2f,
                            n.y + (n.h - n.text.size.height) / 2f,
                        ),
                    )
                }
            }
        }
    }
}

object MindMap {

    class Node(val text: String, val depth: Int) {
        val children = ArrayList<Node>()
    }

    class Placed(
        val nodes: List<Box>,
        val edges: List<Pair<Box, Box>>,
        val width: Float,
        val height: Float,
    )

    class Box(
        val text: androidx.compose.ui.text.TextLayoutResult,
        val depth: Int,
        var x: Float,
        var y: Float,
        val w: Float,
        val h: Float,
    )

    /**
     * 解析。
     *
     * 缩进按「每 2 空格一级」判断，tab 当 4 空格。mermaid 的 `root((x))` 与
     * 普通 `x` 都当成根文本处理 —— 括号在手表上只会变成视觉噪音。
     */
    fun parse(source: String): Node? {
        val lines = source.lines()
            .map { it.replace("\t", "    ") }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        var startIndex = 0
        var root: Node? = null
        if (lines.first().trim().equals("mindmap", ignoreCase = true)) startIndex = 1

        val stack = ArrayList<Pair<Int, Node>>()
        for (i in startIndex until lines.size) {
            val raw = lines[i]
            val indent = raw.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
            var text = raw.trim()
                .removePrefix("- ").removePrefix("* ").removePrefix("+ ")
                .trim()
            if (text.isEmpty()) continue
            // mermaid 节点形状：root((文字)) / A[文字] / B{文字}
            text = text
                .replace(Regex("^\\(\\((.*)\\)\\)$"), "$1")
                .replace(Regex("^\\[(.*)]$"), "$1")
                .replace(Regex("^\\{(.*)}$"), "$1")
                .replace(Regex("^\\((.*)\\)$"), "$1")
                .trim()
            if (text.isEmpty()) continue

            val level = indent / 2
            val node = Node(text, level)
            if (root == null) {
                root = node
                stack.clear()
                stack += level to node
                continue
            }
            // 找到最近一个层级比自己小的祖先
            while (stack.isNotEmpty() && stack.last().first >= level) stack.removeAt(stack.size - 1)
            val parent = stack.lastOrNull()?.second ?: root
            parent.children += node
            stack += level to node
        }
        return root
    }

    fun layout(
        root: Node,
        measurer: TextMeasurer,
        style: TextStyle,
        rootStyle: TextStyle,
    ): Placed {
        val padH = 14f
        val padV = 8f
        val gapX = 26f
        val rowGap = 8f

        // 1) 先量所有节点，算出每层最大宽度
        val measured = HashMap<Node, Box>()
        val maxByDepth = HashMap<Int, Float>()
        fun measure(n: Node) {
            val st = if (n.depth == 0) rootStyle else style
            val result = measurer.measure(n.text, st, constraints = Constraints(maxWidth = 360))
            val w = result.size.width + padH * 2
            val h = result.size.height + padV * 2
            measured[n] = Box(result, n.depth, 0f, 0f, w, h)
            maxByDepth[n.depth] = maxOf(maxByDepth[n.depth] ?: 0f, w)
            n.children.forEach { measure(it) }
        }
        measure(root)

        // 2) 每层的 x 起点
        val maxDepth = maxByDepth.keys.maxOrNull() ?: 0
        val levelX = FloatArray(maxDepth + 1)
        var cursorX = 0f
        for (d in 0..maxDepth) {
            levelX[d] = cursorX
            cursorX += (maxByDepth[d] ?: 0f) + gapX
        }

        // 3) 后序分配 y：叶子依次排，父取子中点
        var leafCursor = 0f
        val edges = ArrayList<Pair<Box, Box>>()
        fun place(n: Node): Box {
            val box = measured.getValue(n)
            box.x = levelX[n.depth]
            if (n.children.isEmpty()) {
                box.y = leafCursor
                leafCursor += box.h + rowGap
            } else {
                val kids = n.children.map { place(it) }
                kids.forEach { edges += box to it }
                val first = kids.first()
                val last = kids.last()
                box.y = (first.y + last.y + last.h) / 2f - box.h / 2f
            }
            return box
        }
        place(root)

        val all = measured.values.toList()
        val width = all.maxOfOrNull { it.x + it.w } ?: 0f
        val height = all.maxOfOrNull { it.y + it.h } ?: 0f
        return Placed(all, edges, width, height)
    }
}
