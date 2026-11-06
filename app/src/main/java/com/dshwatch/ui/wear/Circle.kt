package com.dshwatch.ui.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.sqrt

/*
 * 圆屏几何层
 * ==========
 *
 * 圆屏不是「方屏少四个角」，它是**每一行的可用宽度都不一样**。
 * 设半径 r、以屏幕中心为原点，则距离中心 dy 的那一行可用宽度是
 *      w(dy) = 2·√(r² − dy²)
 *
 * 以 205dp 表盘（r = 102.5dp）为例：
 *
 * | 距中心 | 该行宽度 | 占屏宽 |
 * |---|---|---|
 * | 0dp（正中） | 205dp | 100% |
 * | 50dp | 180dp | 88% |
 * | 80dp | 128dp | 62% |
 * | 95dp | 77dp  | 37% |
 *
 * 所以「固定横向 padding」在圆屏上必错：写 12dp 等于假设整屏都宽 181dp，
 * 而实际上离开正中就开始不够。屏幕外的部分既不显示、**也点不到** ——
 * Compose 的命中测试跟着可见区域走，用户看着半个按钮按下去没反应。
 *
 * 这一层只提供几何，不提供布局策略。怎么用见 [com.dshwatch.ui.components.CircleScaffold]：
 * 内容区取一个「内接矩形」，四角落在圆周上，矩形内的一切永远在圆内。
 *
 * ## 为什么不做逐行变宽的容器
 *
 * 试过：一个按每一行弦长收窄的 Column。静态内容是漂亮的，但列表一滚动，
 * 每一项的宽度就跟着连续变化，文字不断重排 —— 屏幕在「呼吸」，看两秒就晕。
 * 而且滚动时子项的 y 是内容坐标，不是屏幕坐标，算出来的宽度本来就是错的
 * （得把 scrollValue 传进去，再每帧重测，代价和收益完全不成比例）。
 *
 * 矩形是唯一稳定解。代价是左右两侧的弧形空间用不上，但那部分本来就放不下
 * 可读的中文。
 *
 * ## 为什么不引 horologist
 *
 * 它有现成的 `fillMaxRectangle()`，但那是给 ScalingLazyColumn 用的常量化方案；
 * 为了一个函数加 800KB 依赖，在 512MB 内存的表上不划算。几何本身是两行数学。
 */

/**
 * 圆的几何参数。
 *
 * 单位一律用 dp 值（Float），避开 Dp 之间不能相乘的问题。
 */
@Immutable
data class CircleGeometry(
    val width: Dp,
    val height: Dp,
) {
    /** 短边即直径（表盘是 1:1，但矩形屏也可能走到这里） */
    val diameter: Dp = if (width < height) width else height
    val radius: Dp = diameter / 2

    /**
     * 圆的顶边在**屏幕上**的 y。
     *
     * 手表屏幕不一定是正方形：这台是 412×502 px（329.6×401.6 dp），
     * 圆只占中间那条直径 329.6dp 的带子，上下各有一条 33dp 的黑边。
     *
     * 所以「距屏幕顶边多少 dp」和「距圆顶多少 dp」是两回事。所有按比例
     * 排版的 y 都必须从 [top] 起算，否则整个布局会整体下沉 —— 顶栏压到圆心附近、
     * 内容区顶到圆周外面去。之前就是这么错的：拿屏幕高当直径用。
     */
    val top: Dp = (height - diameter) / 2

    /**
     * 内接正方形边长 = d / √2 ≈ 0.707d。
     *
     * 这是「无论内容多高都绝不会出圆」的安全宽度。205dp 表盘上约 145dp ——
     * 比屏宽少 30%。只能当**兜底**，不能当默认值：内容区实际只占屏幕中间一段，
     * 那里能用的宽度比 0.707d 大得多（见 CircleScaffold 的算法）。
     */
    val inscribed: Dp = diameter * SQRT_HALF

    /** 距屏幕顶边 [y] 处那一行的圆内宽度 */
    fun widthAt(y: Dp): Dp = widthAt(y.value).dp

    fun widthAt(y: Float): Float = widthAtScaled(y, 1f)

    /**
     * 把可用半径缩到 [scale] 倍后的同心圆，在距顶边 [y] 处的宽度。
     *
     * scale < 1 就是「整体内缩」。和减 padding 的区别是：padding 每行减同一个数，
     * scale 每行按比例减 —— 后者在两端收得更自然，不会「中间太宽、两头还是切」。
     */
    fun widthAtScaled(y: Float, scale: Float): Float {
        val r = radius.value * scale
        if (r <= 0f) return 0f
        val dy = y - height.value / 2f
        val h2 = r * r - dy * dy
        if (h2 <= 0f) return 0f
        return 2f * sqrt(h2)
    }

    /** 距顶边 [y] 处，左右各要收进去多少才不越过圆周 */
    fun insetAt(y: Float, scale: Float = 1f): Float =
        ((width.value - widthAtScaled(y, scale)) / 2f).coerceAtLeast(0f)

    companion object {
        const val SQRT_HALF = 0.70710678f
    }
}

/**
 * 当前屏幕的圆形几何。**圆屏之外是 null。**
 *
 * 用 null 而不是给一个「假圆」，是因为方屏上按弦长收窄是**纯损失**：
 * 方屏可用区是矩形，套圆的算法会白扔两角。组件里写
 * `LocalCircle.current ?: 走矩形分支`，比到处判 `isRound` 更直白。
 */
val LocalCircle = staticCompositionLocalOf<CircleGeometry?> { null }

/**
 * 表盘边缘的暗角（模拟曲面膜 / 机械表的内阴影）。
 *
 * 手表屏幕边缘是向下弯的，正对着看时边缘本来就比中心暗一档。用一圈极淡的
 * 径向渐变把这层「物理事实」画出来 —— 内容滚到边缘自然被压暗，比硬邦邦地
 * 被裁掉更像一块表。
 *
 * 是**盖在内容之上**画的（`drawWithContent` 之后再画），所以对列表底部那种
 * 贴近边缘的部分同样生效。
 */
fun Modifier.circleBezel(
    color: Color = Color.Black,
    width: Dp = 22.dp,
    strength: Float = 0.42f,
): Modifier = drawWithContent {
    drawContent()
    val r = min(size.width, size.height) / 2f
    if (r <= 0f) return@drawWithContent
    val wpx = width.toPx().coerceAtMost(r)
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color.Transparent,
            (1f - wpx / r).coerceIn(0f, 1f) to Color.Transparent,
            1f to color.copy(alpha = strength),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = r,
        ),
    )
}
