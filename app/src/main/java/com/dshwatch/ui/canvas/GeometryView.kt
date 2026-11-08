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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 几何画板。
 *
 * ## 为什么是 JSON 而不是直接画 SVG
 *
 * SVG 里全是绝对坐标和路径数据，模型要算好每个像素才能画对；而几何题的核心
 * 是「点与点之间的关系」。用 JSON 描述**元素与引用**，模型只需说
 * 「A(0,0)、B(4,0)、以 A 为圆心过 B 作圆」，坐标变换、缩放、标签避让全在这里做。
 * 换屏幕尺寸不用改一个字 —— 这在手表上尤其重要。
 *
 * ## 支持的 DSL
 *
 * ```json
 * {
 *   "points":  [{"name":"A","x":0,"y":0},{"name":"B","x":4,"y":0}],
 *   "segments":[["A","B"]],
 *   "lines":   [["A","C"]],
 *   "circles": [{"center":"A","r":2}],
 *   "polygons":[["A","B","C"]],
 *   "angles":  [{"at":"B","from":"A","to":"C","label":"60°"}],
 *   "texts":   [{"x":1,"y":2,"text":"AB=4"}],
 *   "axes": true
 * }
 * ```
 *
 * 也兼容 `{"elements":[{"kind":"point","at":[0,0],"label":"A"},…]}` 这种扁平写法 ——
 * 模型有时会按「图元列表」的思路输出，没必要因此让整张图失败。
 *
 * 点可以只给 `"name"` 和坐标，也可以直接在段/圆里写 `[x,y]`，解析时统一成具名点池。
 */
@Composable
fun GeometryView(
    source: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val scene = remember(source) { Geometry.parse(source) }
    if (scene == null || scene.points.isEmpty()) {
        MathFallback("几何图解析失败，原文：\n$source")
        return
    }

    val measurer = rememberTextMeasurer(cacheSize = 64)
    val labelStyle = TextStyle(
        fontSize = if (compact) 10.sp else 12.sp,
        color = Color(0xFFDCE4F2),
        fontWeight = FontWeight.Medium,
    )

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 全屏时交给外层撑满（见 MindMapView 同处注释）
            .then(if (compact) Modifier.height(170.dp) else Modifier.fillMaxSize())
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .pointerInput(scene) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 4f)
                    offset += pan + centroid * (1f - zoom)
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pad = 20f
            val w = size.width - pad * 2
            val h = size.height - pad * 2
            if (w <= 0 || h <= 0) return@Canvas

            // 等比缩放：几何图被拉伸就失真了（圆变椭圆、直角变钝角）
            val spanX = (scene.maxX - scene.minX).coerceAtLeast(0.0001f)
            val spanY = (scene.maxY - scene.minY).coerceAtLeast(0.0001f)
            val k = minOf(w / spanX, h / spanY) * scale
            val ox = pad + (w - spanX * k) / 2f + offset.x
            val oy = pad + (h - spanY * k) / 2f + offset.y
            fun px(x: Float) = ox + (x - scene.minX) * k
            fun py(y: Float) = oy + (scene.maxY - y) * k

            if (scene.axes) drawAxes(scene, ::px, ::py, k)

            // 1) 多边形填充（最底层，避免盖住线）
            scene.polygons.forEach { poly ->
                if (poly.size < 3) return@forEach
                val path = Path()
                poly.forEachIndexed { i, p ->
                    val x = px(p.first)
                    val y = py(p.second)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, Color(0xFF4A7EDB).copy(alpha = 0.16f))
                drawPath(path, Color(0xFF6E9BD6), style = Stroke(width = 1.6f))
            }

            // 2) 线段 / 直线 / 圆
            scene.segments.forEach { (a, b) ->
                drawLine(
                    color = Color(0xFF8FB4E8),
                    start = Offset(px(a.first), py(a.first)),
                    end = Offset(px(b.first), py(b.first)),
                    strokeWidth = 2f,
                )
            }
            scene.lines.forEach { (a, b) ->
                // 直线：沿方向延伸到画布边界（简易做法：延长 4 倍）
                val dx = b.first - a.first
                val dy = b.second - a.second
                val len = hypot(dx, dy).coerceAtLeast(0.0001f)
                val ux = dx / len
                val uy = dy / len
                val ext = (spanX + spanY) * 2f
                drawLine(
                    color = Color(0xFF8FB4E8).copy(alpha = 0.85f),
                    start = Offset(px(a.first - ux * ext), py(a.second - uy * ext)),
                    end = Offset(px(a.first + ux * ext), py(a.second + uy * ext)),
                    strokeWidth = 1.6f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)),
                )
            }
            scene.circles.forEach { c ->
                val r = c.r * k
                drawCircle(
                    color = Color(0xFF8FB4E8),
                    radius = r,
                    center = Offset(px(c.cx), py(c.cy)),
                    style = Stroke(width = 1.8f),
                )
            }

            // 3) 角度弧
            scene.angles.forEach { ang ->
                val vx1 = ang.a.first - ang.cx
                val vy1 = ang.a.second - ang.cy
                val vx2 = ang.b.first - ang.cx
                val vy2 = ang.b.second - ang.cy
                val r = (minOf(hypot(vx1, vy1), hypot(vx2, vy2)) * 0.28f).coerceAtLeast(0.15f)
                val startDeg = Math.toDegrees(atan2(-vy1, vx1).toDouble()).toFloat()
                val endDeg = Math.toDegrees(atan2(-vy2, vx2).toDouble()).toFloat()
                var sweep = endDeg - startDeg
                while (sweep > 180f) sweep -= 360f
                while (sweep < -180f) sweep += 360f
                drawArc(
                    color = Color(0xFFE0B04A),
                    startAngle = startDeg,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(px(ang.cx) - r * k, py(ang.cy) - r * k),
                    size = Size(r * k * 2, r * k * 2),
                    style = Stroke(width = 1.4f),
                )
                if (ang.label.isNotBlank()) {
                    val mid = Math.toRadians(((startDeg + endDeg) / 2).toDouble())
                    val lx = px(ang.cx) + (cos(mid) * r * k * 1.45f).toFloat()
                    val ly = py(ang.cy) - (sin(mid) * r * k * 1.45f).toFloat()
                    drawLabel(measurer, ang.label, lx, ly, labelStyle, Color(0xFFE0B04A))
                }
            }

            // 4) 点与标签（最上层）
            scene.points.forEach { p ->
                val cx = px(p.x)
                val cy = py(p.y)
                drawCircle(Color(0xFFFF6B6B), radius = 3.4f, center = Offset(cx, cy))
                drawCircle(Color(0x66FFFFFF), radius = 5.6f, center = Offset(cx, cy), style = Stroke(0.8f))
                if (p.label.isNotBlank()) {
                    drawLabel(measurer, p.label, cx + 6f, cy - 14f, labelStyle, Color(0xFFDCE4F2))
                }
            }

            // 5) 自由文本
            scene.texts.forEach { t ->
                drawLabel(
                    measurer = measurer,
                    text = t.text,
                    x = px(t.x),
                    y = py(t.y),
                    style = labelStyle,
                    color = Color(0xFFB9C6DC),
                )
            }
        }
    }
}

private fun DrawScope.drawLabel(
    measurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
    x: Float,
    y: Float,
    style: TextStyle,
    color: Color,
) {
    if (text.isBlank()) return
    val result = measurer.measure(
        text = text,
        style = style.copy(color = color),
        constraints = Constraints(maxWidth = 260),
    )
    drawText(textLayoutResult = result, topLeft = Offset(x, y))
}

private fun DrawScope.drawAxes(
    scene: Geometry.Scene,
    px: (Float) -> Float,
    py: (Float) -> Float,
    k: Float,
) {
    val x0 = px(0f)
    val y0 = py(0f)
    drawLine(Color(0x33FFFFFF), Offset(0f, y0), Offset(size.width, y0), 1f)
    drawLine(Color(0x33FFFFFF), Offset(x0, 0f), Offset(x0, size.height), 1f)
    // 刻度只画整数位，且步长随缩放变化，避免密到糊成一条线
    val step = when {
        k > 60 -> 1f
        k > 30 -> 2f
        k > 12 -> 5f
        else -> 10f
    }
    val from = (scene.minX / step).toInt() - 1
    val to = (scene.maxX / step).toInt() + 1
    for (i in from..to) {
        val v = i * step
        val x = px(v)
        drawLine(Color(0x33FFFFFF), Offset(x, y0 - 3f), Offset(x, y0 + 3f), 1f)
    }
}

object Geometry {

    class Pt(val name: String, val x: Float, val y: Float, val label: String)

    class Circle(val cx: Float, val cy: Float, val r: Float)

    class TextLabel(val x: Float, val y: Float, val text: String)

    class Angle(
        val cx: Float,
        val cy: Float,
        val a: Pair<Float, Float>,
        val b: Pair<Float, Float>,
        val label: String,
    )

    class Scene(
        val points: List<Pt>,
        val segments: List<Pair<Pair<Float, Float>, Pair<Float, Float>>>,
        val lines: List<Pair<Pair<Float, Float>, Pair<Float, Float>>>,
        val circles: List<Circle>,
        val polygons: List<List<Pair<Float, Float>>>,
        val angles: List<Angle>,
        val texts: List<TextLabel>,
        val axes: Boolean,
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(source: String): Scene? {
        val root = runCatching { json.parseToJsonElement(extractJson(source)).jsonObject }.getOrNull()
            ?: return null

        val points = LinkedHashMap<String, Pt>()
        val segments = ArrayList<Pair<Pair<Float, Float>, Pair<Float, Float>>>()
        val lines = ArrayList<Pair<Pair<Float, Float>, Pair<Float, Float>>>()
        val circles = ArrayList<Circle>()
        val polygons = ArrayList<List<Pair<Float, Float>>>()
        val angles = ArrayList<Angle>()
        val texts = ArrayList<TextLabel>()

        fun addPoint(name: String, x: Float, y: Float, label: String = name): Pair<Float, Float> {
            points.getOrPut(name) { Pt(name, x, y, label) }
            return x to y
        }

        // 具名点池
        (root["points"] as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val name = o.str("name") ?: o.str("label") ?: return@forEach
            val xy = o.xy() ?: return@forEach
            addPoint(name, xy.first, xy.second, o.str("label") ?: name)
        }

        /** 引用：可以是点名，也可以是 [x,y] */
        fun resolve(el: kotlinx.serialization.json.JsonElement?): Pair<Float, Float>? {
            if (el == null) return null
            (el as? JsonArray)?.let { arr ->
                if (arr.size >= 2) {
                    val x = arr[0].jsonPrimitive.doubleOrNull?.toFloat()
                    val y = arr[1].jsonPrimitive.doubleOrNull?.toFloat()
                    if (x != null && y != null) return x to y
                }
                return null
            }
            val s = el.jsonPrimitive.contentOrNull ?: return null
            return points[s]?.let { it.x to it.y }
        }

        fun addSeg(list: MutableList<Pair<Pair<Float, Float>, Pair<Float, Float>>>, el: kotlinx.serialization.json.JsonElement) {
            val arr = el as? JsonArray ?: return
            if (arr.size < 2) return
            val a = resolve(arr[0]) ?: return
            val b = resolve(arr[1]) ?: return
            list += a to b
        }

        (root["segments"] as? JsonArray)?.forEach { addSeg(segments, it) }
        (root["lines"] as? JsonArray)?.forEach { addSeg(lines, it) }

        (root["circles"] as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val center = resolve(o["center"]) ?: return@forEach
            val r = o.num("r")
            if (r != null && r > 0) {
                circles += Circle(center.first, center.second, r)
            } else {
                // 「过某点作圆」：半径 = 到该点的距离
                val through = resolve(o["through"]) ?: return@forEach
                val rr = hypot(through.first - center.first, through.second - center.second)
                if (rr > 0) circles += Circle(center.first, center.second, rr)
            }
        }

        (root["polygons"] as? JsonArray)?.forEach { el ->
            val arr = el as? JsonArray ?: return@forEach
            val pts = arr.mapNotNull { resolve(it) }
            if (pts.size >= 3) polygons += pts
        }

        (root["angles"] as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val at = resolve(o["at"] ?: o["vertex"]) ?: return@forEach
            val from = resolve(o["from"]) ?: return@forEach
            val to = resolve(o["to"]) ?: return@forEach
            angles += Angle(at.first, at.second, from, to, o.str("label").orEmpty())
        }

        (root["texts"] as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val xy = o.xy() ?: return@forEach
            texts += TextLabel(xy.first, xy.second, o.str("text") ?: o.str("label") ?: return@forEach)
        }

        // 扁平写法：elements
        (root["elements"] as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            when ((o.str("kind") ?: o.str("type") ?: "").lowercase()) {
                "point" -> {
                    val name = o.str("name") ?: o.str("label") ?: return@forEach
                    val xy = o.xy() ?: return@forEach
                    addPoint(name, xy.first, xy.second, o.str("label") ?: name)
                }
                "segment" -> {
                    val a = resolve(o["from"] ?: o["a"]) ?: return@forEach
                    val b = resolve(o["to"] ?: o["b"]) ?: return@forEach
                    segments += a to b
                }
                "line" -> {
                    val a = resolve(o["from"] ?: o["a"]) ?: return@forEach
                    val b = resolve(o["to"] ?: o["b"]) ?: return@forEach
                    lines += a to b
                }
                "circle" -> {
                    val c = resolve(o["center"]) ?: return@forEach
                    val r = o.num("r")
                    if (r != null) circles += Circle(c.first, c.second, r)
                }
            }
        }

        if (points.isEmpty() && segments.isEmpty() && circles.isEmpty() && polygons.isEmpty()) return null

        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        points.values.forEach { xs += it.x; ys += it.y }
        segments.forEach { xs += it.first.first; xs += it.second.first; ys += it.first.second; ys += it.second.second }
        circles.forEach {
            xs += it.cx - it.r; xs += it.cx + it.r
            ys += it.cy - it.r; ys += it.cy + it.r
        }
        if (xs.isEmpty()) {
            xs += -1f; xs += 1f; ys += -1f; ys += 1f
        }
        // 给一个最小视野，避免「所有点重合」时除零
        var minX = xs.min()
        var maxX = xs.max()
        var minY = ys.min()
        var maxY = ys.max()
        if (abs(maxX - minX) < 0.001f) { minX -= 1f; maxX += 1f }
        if (abs(maxY - minY) < 0.001f) { minY -= 1f; maxY += 1f }
        val padX = (maxX - minX) * 0.08f
        val padY = (maxY - minY) * 0.08f

        return Scene(
            points = points.values.toList(),
            segments = segments,
            lines = lines,
            circles = circles,
            polygons = polygons,
            angles = angles,
            texts = texts,
            axes = (root["axes"] as? JsonPrimitive)?.booleanOrNull == true,
            minX = minX - padX,
            maxX = maxX + padX,
            minY = minY - padY,
            maxY = maxY + padY,
        )
    }

    /** 模型有时会把 JSON 包在 ```json 围栏里，或前后带一句解释 */
    private fun extractJson(src: String): String {
        val start = src.indexOf('{')
        val end = src.lastIndexOf('}')
        if (start < 0 || end <= start) return src
        return src.substring(start, end + 1)
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.num(key: String): Float? =
        (this[key] as? JsonPrimitive)?.doubleOrNull?.toFloat()

    /** 坐标可能写成 at:[x,y]、x/y、或 pos:[x,y] */
    private fun JsonObject.xy(): Pair<Float, Float>? {
        (this["at"] as? JsonArray)?.let { arr ->
            if (arr.size >= 2) {
                val x = arr[0].jsonPrimitive.doubleOrNull?.toFloat()
                val y = arr[1].jsonPrimitive.doubleOrNull?.toFloat()
                if (x != null && y != null) return x to y
            }
        }
        val x = num("x")
        val y = num("y")
        return if (x != null && y != null) x to y else null
    }
}
