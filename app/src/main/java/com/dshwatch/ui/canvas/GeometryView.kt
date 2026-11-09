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
    /*
     * 只判 null，不再判 points 为空。
     *
     * 模型完全可以只用内联坐标画图（`"segments":[[[x,y],[x,y]],…]`、只给 texts），
     * 这时没有 points 数组，图却完全有效。原来的「points 为空就算失败」会把这类
     * 输出直接打成「解析失败，原文」，用户看到的是一屏 JSON。
     */
    if (scene == null) {
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
            scene.segments.forEach { seg ->
                val (start, end) = segmentScreen(seg, ::px, ::py)
                drawLine(
                    color = Color(0xFF8FB4E8),
                    start = start,
                    end = end,
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

/**
 * 线段两个端点在画布上的坐标。
 *
 * 单独抽出来是因为这里出过一个只有截图才能发现的 bug：`py(a.first)` 把点的 x
 * 当成 y 传了进去。A(0,0) 恰好不受影响，于是 AB 画成 A→(5,5)，BC 与 CA 也都落在
 * y=x 上，整个三角形缩成一条斜线 —— 而顶点、标签全部正常，看起来像是"线段没解析"。
 *
 * 抽成纯函数之后，「水平边的两个端点 y 必须相等」这类断言才能写进单测。
 */
internal fun segmentScreen(
    seg: Pair<Pair<Float, Float>, Pair<Float, Float>>,
    px: (Float) -> Float,
    py: (Float) -> Float,
): Pair<Offset, Offset> {
    val (a, b) = seg
    return Offset(px(a.first), py(a.second)) to Offset(px(b.first), py(b.second))
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

        /**
         * 引用：点名、[x,y] 坐标对、或 {x,y} 对象。
         *
         * 三个分支都要用安全转换。之前写的是 `arr[0].jsonPrimitive` ——
         * 对 `[[-1.5,0.5],[-0.5,3.25]]` 这种坐标对写法，arr[0] 本身是 JsonArray，
         * 强转 JsonPrimitive 会抛 ClassCastException，整段解析直接中断，
         * 界面上就是「几何图解析失败，原文」。而这正是模型画函数图时最常见的写法。
         */
        fun resolve(el: kotlinx.serialization.json.JsonElement?): Pair<Float, Float>? {
            if (el == null) return null
            val arr = el as? JsonArray
            if (arr != null) {
                if (arr.size < 2) return null
                val x = (arr[0] as? JsonPrimitive)?.doubleOrNull?.toFloat()
                val y = (arr[1] as? JsonPrimitive)?.doubleOrNull?.toFloat()
                return if (x != null && y != null) x to y else null
            }
            val obj = el as? JsonObject
            if (obj != null) return obj.xy()
            val s = (el as? JsonPrimitive)?.contentOrNull ?: return null
            return points[s]?.let { it.x to it.y }
        }

        /**
         * 一段可以是两点的线段，也可以是三点以上的折线。
         *
         * 画函数图象时模型习惯一次给一条 polyline（本例 8 个点连成 f(t) 的折线），
         * 只取前两个点会画成一条短斜线。
         */
        fun addSeg(
            list: MutableList<Pair<Pair<Float, Float>, Pair<Float, Float>>>,
            el: kotlinx.serialization.json.JsonElement,
        ) {
            val arr = el as? JsonArray
            if (arr != null) {
                val pts = arr.mapNotNull { resolve(it) }
                if (pts.size < 2) return
                pts.zipWithNext().forEach { (a, b) -> list += a to b }
                return
            }
            // {"from":…,"to":…} 这种对象写法也认，模型切换风格时不必整张图作废
            val o = el as? JsonObject ?: return
            val a = resolve(o["from"] ?: o["a"] ?: o["start"]) ?: return
            val b = resolve(o["to"] ?: o["b"] ?: o["end"]) ?: return
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

        // 只有「一个图元都没有」才算失败。模型可以只用内联坐标或只给文字，
        // 那同样是有效的图，不该被打成解析失败。
        if (points.isEmpty() && segments.isEmpty() && lines.isEmpty() &&
            circles.isEmpty() && polygons.isEmpty() && texts.isEmpty()
        ) {
            return null
        }

        val showAxes = (root["axes"] as? JsonPrimitive)?.booleanOrNull == true

        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        points.values.forEach { xs += it.x; ys += it.y }
        segments.forEach { xs += it.first.first; xs += it.second.first; ys += it.first.second; ys += it.second.second }
        lines.forEach { xs += it.first.first; xs += it.second.first; ys += it.first.second; ys += it.second.second }
        circles.forEach {
            xs += it.cx - it.r; xs += it.cx + it.r
            ys += it.cy - it.r; ys += it.cy + it.r
        }
        /*
         * 自由文本也要进包围盒。
         *
         * 模型标边长时习惯把标签放在图形外侧（本例 AB 的 y=-0.4，比所有点都低），
         * 只按点/线段算视野的话，标签会落到画布外被裁掉 —— 用户看到的是一张
         * 缺了标注的图。代价是极端坐标会把整张图缩小，但那是模型写错，不是布局问题。
         */
        texts.forEach { xs += it.x; ys += it.y }
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
        /*
         * 要坐标系就得让原点进视野。
         *
         * 模型画函数图时会写 "axes":true，但曲线常常整体在 t 轴上方（本例 y 从 0.4 到 3.25），
         * 只按数据算包围盒的话 y=0 落在画布外，轴和刻度全被裁掉 —— 开关打开了却什么都看不到。
         */
        if (showAxes) {
            minX = minOf(minX, 0f)
            maxX = maxOf(maxX, 0f)
            minY = minOf(minY, 0f)
            maxY = maxOf(maxY, 0f)
        }
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
            axes = showAxes,
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
