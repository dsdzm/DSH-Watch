package com.dshwatch.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dshwatch.domain.DeviceTier
import com.dshwatch.ui.theme.LocalBackdropDim
import com.dshwatch.ui.theme.LocalBackdropSize
import com.dshwatch.ui.theme.LocalDarkTheme
import com.dshwatch.ui.theme.LocalFrostPhoto
import com.dshwatch.ui.theme.LocalGlass
import com.dshwatch.ui.theme.LocalGlassBlur
import com.dshwatch.ui.theme.LocalGlassDim
import com.dshwatch.ui.theme.LocalPerf
import com.dshwatch.ui.theme.coverRect
import kotlin.math.roundToInt

/**
 * 「质感玻璃」的唯一入口。
 *
 * ## 设计原则：少画
 *
 * 第一版在这里叠了五层 —— 半透明底、竖向受光渐变、径向光斑、侧翼折射、上亮下暗的描边。
 * 结果是一块发灰的塑料：层层叠加都在压低背景，最后透不出任何东西。
 *
 * 拿参考应用的录屏逐像素量了一遍，真实的玻璃只有**两层**：
 *
 * | 层 | 实测 | 作用 |
 * |---|---|---|
 * | 本体 | 均匀半透明白，内部亮度从顶到底只差 2/255 | 提亮一档，保留背景的明暗 |
 * | 边 | 边缘 1px 内完成 103→154 的跳变，没有第二条亮线 | 让玻璃「有边」 |
 *
 * 所以这里也只画两层：一层均匀的白（或主题染色），一圈细边。
 * 玻璃的高级感来自**底下有什么**，不来自玻璃自己画了多少东西。
 *
 * ## 后面补的两笔：高光描边与磨砂颗粒
 *
 * 上表是「清玻璃」的模型。磨砂玻璃多两样东西，都是用户直接提出来的：
 *
 * | 层 | 参数 | 为什么 |
 * |---|---|---|
 * | 边 | 1dp，上沿 0.78~0.86 → 两侧 0.26 → 下沿 0.10 | 光打在上沿；整圈一样亮就是描边色块 |
 * | 顶面光泽 | 上沿 26% 内 0.10→0 | 光打在玻璃上表面，被边缘收住 |
 * | 颗粒 | 64×64 噪点平铺，单像素 ≤ 4.7% | 人判断「磨砂」靠的是面上的不均匀，不是模糊本身 |
 *
 * 三笔都不改本体的均匀性 —— 本体一旦做渐变，就又回到「渐变卡片」那条老路。
 * 它们只在 [LocalGlassBlur] 为 true（用户开了模糊）时加满；清玻璃下
 * 光泽减半、颗粒不画。
 *
 * ## 背景是前提
 *
 * 玻璃能「透」，前提是下面有东西可透。纯黑底上叠半透明白只会得到灰块 ——
 * 这也是第一版最致命的地方。所以玻璃主题的背景改成两团大色晕（见 Theme.kt），
 * 玻璃本体用**低 alpha 的白色**而不是不透明的容器色，让色晕透上来。
 *
 * ## 光效与形变
 *
 * - 按下：整块白 0.07（Plus 混合），像被光源照近一点
 * - 跟手：半径 1.5 倍短边的极淡径向（0.10），手指移它就移；
 *   半径取这么大是为了「整块亮起来」而不是画一个白点 —— 小半径的白点在小圆屏上很脏
 * - 形变：**放大** 4dp（不是缩小）。手指压下去，液体往四周鼓，缩小的观感是「按瘪了」
 *
 * ## 性能分层
 *
 * - 省电 / 低端档：不装 pointer、不跑动画，静态那几笔照画（都是一次填充，
 *   和普通卡片同一量级；真正贵的是每帧重绘与 blur）
 * - 中端档：加跟手光斑与弹簧形变
 * - 高端档：外边再多一道 1dp 的收边，抵消 OLED 边缘的锯齿
 *
 * 形变走 `graphicsLayer`，只改 RenderNode 的变换，不触发重绘；
 * 只有光斑跟手那几百毫秒要重画。
 */
@Composable
fun Modifier.glassSurface(
    shape: Shape,
    /**
     * 染色。默认 `Unspecified` —— 不染色，玻璃就是「背景提亮一档」，
     * 这是列表卡片该有的样子。主按钮传 primary，会盖成实心。
     */
    tint: Color = Color.Unspecified,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    /**
     * 圆角半径。传 null 表示「胶囊」—— 取高度的一半。
     * 绘图时必须知道这个值：`Shape` 是个黑盒，问不出圆角。
     */
    cornerRadius: Dp? = null,
    /**
     * 实心玻璃：主按钮用。染色不透明，只保留高光与描边 ——
     * 半透明的主色会发灰，按钮就看不出是「可点的那个」了。
     */
    solid: Boolean = false,
    /**
     * 「浮起来」的玻璃：对话框用。
     *
     * 普通卡片的 0.24 白是让它贴在背景上「透」出来；
     * 但对话框要盖住下面的界面，透太多就分不清哪层是哪层。
     * elevated 把本体提到 0.34、边提到顶 0.40，观感是
     * 「同一块玻璃，但离你近一档」—— 仍然是半透明，只是更实。
     */
    elevated: Boolean = false,
    /**
     * 外部传入的按压源。
     *
     * 为什么需要：`glassSurface` 自己有 `onClick` 时，很多调用点是
     * `glassSurface(...) .tap(onClick = ...)` —— 两处都在挂事件、都在画反馈，
     * 结果按下时亮了两层（alpha 叠加到 0.25，像一块白板）。
     *
     * 正确用法是：`glassSurface(...)` 只管画和形变，点击交给
     * `.tap(quiet = true, ...)`；把这里的 interaction 传给它，
     * 两边看到同一个按压状态，形变与减法就永远同步。
     *
     * 传 null 时内部自建一个（兼容旧调用点）。
     */
    interaction: MutableInteractionSource? = null,
    /**
     * 玻璃的「分量」，1.0 = 完整的一层，0.4~0.6 = 轻量控件（图标按钮、分段条的凹槽）。
     *
     * 只乘最终 alpha，不动颜色配比 —— 整层按同一比例变淡，看上去是「同一块玻璃，
     * 只是薄一点」。顶栏图标、开关轨道这类面积小、数量多的控件，用整层会被
     * 读成一排白扣子，压到一半才像控件自己的底。
     */
    weight: Float = 1f,
    /**
     * 染色玻璃的浓度，只在 [tint] 有效时生效。
     *
     * 0.42 是「染色」—— 主色只给卡片上一层色，底下的东西还看得见。
     * 用户消息气泡要 0.82：那是一条**内容**，不是列表项，读不清就是坏的；
     * 0.82 时颜色已经足够压住底，`onPrimary` 的文字在任何背景图上都读得出来，
     * 但仍然留 18% 让背后的糊影透上来，不至于变成一块纯色贴片。
     */
    tintAlpha: Float = 0.42f,
    /**
     * 是否跟踪手指位置，让光斑**跟手**。
     *
     * 默认跟着 [onClick]：能点的玻璃本来就应该跟手。
     *
     * 但有一类控件不接收点击 —— 点击由外层（整行 / 兄弟节点）处理，自己只负责画。
     * 典型是 [com.dshwatch.ui.components.GlassSwitch] 的轨道：
     * `onClick = null`，事件挂在 `.tap(quiet = true)` 上。
     * 这类控件如果按默认值走，`pointerInput` 根本不装 —— 用户按下去，
     * 光斑永远停在静态位置（中心偏上），也就是「光晕不跟手」。
     *
     * 所以这里给一个显式开关，而不是靠 `onClick != null` 去猜意图。
     *
     * 默认值还有一个 `interaction != null`：传了外部按压源，就说明这个控件的事件
     * 由别人（通常是 `.tap(quiet = true)`）转进来，它自己 `onClick` 是 null
     * 但**照样需要跟手** —— 发送键、GhostButton、对话框按钮、选项行、引导卡
     * 全是这种写法。不这样兜的话，它们按下去会亮、但光钉在中间不动。
     */
    trackPointer: Boolean = onClick != null || interaction != null,
): Modifier {
    // ---------- 非玻璃主题：一行都不多 ----------
    if (!LocalGlass.current) {
        // 灰掉的透明度由调用方决定，这里不再二次叠加
        val base = this.clip(shape).background(if (tint == Color.Unspecified) MaterialTheme.colorScheme.surfaceContainerHigh else tint)
        return if (onClick != null) base.tap(enabled = enabled, onClick = onClick) else base
    }

    val perf = LocalPerf.current
    val animated = perf.enableAnimations
    // 这里原来写着 `animated && perf.tier == DeviceTier.HIGH`，但 rich 这个参数
    // 在 drawGlassPanel 里从来没被读过 —— 是个死参数，写什么都一样。
    // 更糟的是它跟 Modifiers.kt 的判据（`tier != LOW`）不一致，
    // 将来谁想用它，两条规则会打架。直接删掉。
    /*
     * 明暗取自主题，不再从 background 反推。
     *
     * 玻璃预设的 background 是**透明**的，`luminance()` 只看 RGB，透明色算出来是 0 ——
     * 于是浅色主题也会被当成深色：卡片走深色分支，再加上 1.22.3 的 0.68 黑底，
     * 浅色模式直接变成黑卡片配深色字，什么都读不出来。
     */
    val dark = LocalDarkTheme.current
    // 卡片底下是不是真糊着（只在玻璃 + 用户开启 + API 26+ 时为 true）
    val frosted = LocalGlassBlur.current
    // 浅色底上「压暗」用的颜色：正文色。在 Composable 作用域里取好再传进 DrawScope
    val onSurface = MaterialTheme.colorScheme.onSurface
    // 卡片「压暗」档位 0~1（见设置里的滑块）。只在 Composable 作用域里取 ——
    // `CompositionLocal.current` 是 @Composable getter，drawBehind 里读不了
    val glassDim = LocalGlassDim.current
    /*
     * 卡片自己那句「高斯模糊」。
     *
     * 磨砂开启时，卡片**不再靠背景层糊**：它按自己的屏幕坐标，从一张重度模糊的
     * 背景副本里采一块画在纱底下（见 [drawFrostSlice]）。这样：
     *
     *   · 关掉「背景模糊」→ 壁纸清晰、玻璃仍然发毛（用户报的「卡片像透明了」）；
     *   · 打开「背景模糊」→ 背景糊一档、卡片糊得更狠，层次反而更清楚。
     *
     * 三个前提缺一不可：磨砂开着、有那张图、这张卡不在对话框里。
     * 低端档不采样：每张卡多一次缩放贴图，列表里十几张一起画在 512MB 的机器上不划算。
     *
     * `elevated`（对话框）**不采样**：`Dialog` 画在另一个 Window，节点坐标是相对
     * 那个窗口的，按它去取背景会取错位置；而且对话框本来就该盖住底下的界面，
     * 不该把自己变成一块「透出壁纸的洞」。
     */
    val frostPhoto = LocalFrostPhoto.current
    val backdropSize = LocalBackdropSize.current
    val backdropDim = LocalBackdropDim.current
    val sampleBackdrop = frosted &&
        !elevated &&
        frostPhoto != null &&
        backdropSize.width > 0 &&
        backdropSize.height > 0 &&
        perf.tier != DeviceTier.LOW

    val ownInteraction = remember { MutableInteractionSource() }
    val src = interaction ?: ownInteraction
    val pressed by src.collectIsPressedAsState()

    // 0 = 松开, 1 = 按住。一个 Animatable 同时驱动形变与提亮，保证两者永远同步。
    val press = remember { Animatable(0f) }
    // 光斑位置：静止时落在中心偏上，手指按下后跟手
    var sheen by remember { mutableStateOf(Offset.Unspecified) }
    var touching by remember { mutableStateOf(false) }

    LaunchedEffect(pressed, animated) {
        if (!animated) {
            press.snapTo(if (pressed) 1f else 0f)
            return@LaunchedEffect
        }
        press.animateTo(
            targetValue = if (pressed) 1f else 0f,
            animationSpec = if (pressed) {
                // 按下：快落。150ms 的 tween 让形变在手指还没抬起时就走到位 ——
                // 慢了会出现「按下去半天没反应，松手才看到动了一下」
                tween(durationMillis = 150, easing = FastOutSlowInEasing)
            } else {
                // 松手：弹簧回弹 + 过冲。阻尼比 0.42 偏低，
                // 回弹时会冲过 0 一点点再回来，那一下就是「水」的手感；
                // 原来的 0.5 基本不过冲，所以看着像「弹了一下就没了」
                spring(dampingRatio = 0.42f, stiffness = 380f)
            },
        )
        // 回弹跑完之后，才把光斑交回静止位置。
        //
        // 之前是在松手那一刻就直接 `sheen = Offset.Unspecified`，于是
        // press 还在弹（光还亮着）的这几百毫秒里，光斑已经跳回中心偏上 ——
        // 用户看到的就是「松手后光晕突然跑到控件中间闪一下」。
        // 抬手指时，光应该在**原地**散掉，这才是水/玻璃该有的样子。
        if (!pressed) sheen = Offset.Unspecified
    }

    // 手指悬停（手表上一般没有，留给调试与未来设备）

    // 渐变 Brush 在 draw 阶段每帧构造会每帧新建一个 Shader 对象，
    // 列表滚动时就是几百个短命对象 —— 提到这里按主题缓存。
    // 透明度不写进 stops，改由 drawRoundRect 的 alpha 参数乘，这样缓存键只有 dark。
    /*
     * 描边的亮度分布：上沿最亮、两侧中等、下沿几乎消失。
     *
     * 为什么不做对角线渐变（左上最亮、右下最暗）：卡片的长宽比差异太大。
     * 一条 350×60dp 的设置行几乎是个横条，对角线退化成「左亮右暗」——
     * 看起来像从左到右打了个渐变，而不是「上沿受光」。竖直渐变在任何
     * 宽高比下都是「顶边亮、侧边有厚度」，这才是玻璃的形状语言。
     *
     * 磨砂时整圈都要提亮（0.78 / 0.26 / 0.10）：面被奶白糊住之后，
     * 只剩这道边还带着「玻璃」的信息；清玻璃下则保持克制，太亮就成了贴纸边框。
     */
    val edgeBrush = remember(dark, elevated, frosted) {
        Brush.verticalGradient(
            0f to Color.White.copy(
                alpha = when {
                    frosted && dark -> 0.86f
                    frosted -> 0.95f
                    // 深色弹窗：本体压暗之后，这条边成了「弹窗从背景里切出来」
                    // 的唯一信号，必须够亮
                    dark && elevated -> 0.70f
                    // 浅色弹窗是近白的面，白边本来就看不出，靠更亮的顶边 + 下面那道
                    // 正文色内描边一起成形
                    elevated -> 0.92f
                    dark -> 0.52f
                    else -> 0.62f
                },
            ),
            // 中段不能太亮：整圈都亮就成了描边色块。
            // 深色弹窗的中段略提，让侧边有厚度但不喧宾夺主
            0.34f to Color.White.copy(
                alpha = when {
                    frosted && dark -> 0.30f
                    frosted -> 0.34f
                    dark && elevated -> 0.24f
                    dark -> 0.16f
                    else -> 0.22f
                },
            ),
            // 右下角：深色弹窗收到 0.02。玻璃越薄，下沿那道暗越像「脏边」
            1f to Color.White.copy(
                alpha = when {
                    frosted -> 0.12f
                    dark && elevated -> 0.04f
                    dark -> 0.06f
                    else -> 0.10f
                },
            ),
        )
    }
    /*
     * 顶面光泽：从卡片上沿往下 1/4 逐渐消失的一层白。
     *
     * 位置只在上沿，不是整卡渐变 —— 整卡渐变会把玻璃画成「渐变色的卡片」，
     * 那是这个组件第一版的老毛病。这里它只负责「光打在玻璃上表面」。
     *
     * 实心按钮给得更重（0.18）：不透明的主色没有背景可透，
     * 全靠这一层光泽和描边才不像一块纯色贴片。
     *
     * 普通玻璃卡只给 0.05：卡片顶上通常就是第一行文字，光泽再重一点
     * 会把那里的底抬到 95 上下，白字的对比度掉到 2.4:1 —— 亮是亮了，读不动。
     */
    val glossBrush = remember(dark, solid, frosted) {
        Brush.verticalGradient(
            0f to Color.White.copy(
                alpha = when {
                    solid -> 0.18f
                    frosted && dark -> 0.09f
                    frosted -> 0.15f
                    dark -> 0.06f
                    else -> 0.06f
                },
            ),
            0.26f to Color.White.copy(alpha = 0f),
            1f to Color.Transparent,
        )
    }
    val bottomBrush = remember(dark, elevated) {
        Brush.verticalGradient(
            0.86f to Color.Transparent,
            // 底暗是「厚度」，但它同时也是「发灰」的来源之一。
            //
            // 三个档位都是量出来的：
            //   · 浅色实底弹窗 0.03 —— 实底上再压 0.05 会显出一道灰边，看着像脏了
            //   · 深色弹窗 0.04 —— 本体已经压到 0.16 了，再压一道 0.13 的暗，
            //     下沿会明显比卡片暗一截，像没洗干净。留一点暗示厚度就够
            //   · 普通卡片 0.13 —— 这张贴着背景，需要这道暗把它「垫」起来
            1f to Color.Black.copy(
                alpha = when {
                    !dark && elevated -> 0.03f
                    dark && elevated -> 0.04f
                    dark -> 0.13f
                    else -> 0.05f
                },
            ),
        )
    }

    var m: Modifier = this
    if (animated) {
        m = m.graphicsLayer {
            // ---- 为什么缩放要很克制 ----
            //
            // 之前是「整块放大 7dp」。这里有个致命细节：graphicsLayer 的缩放
            // 是**以自身中心为原点**的，于是文字、图标、开关会跟着一起被拉伸 ——
            // 一条设置项按下去，里面的「显示思维链」四个字会变大变模糊。
            // 这在观感上就是「奇怪」：物理世界里按下一个按钮，按钮不会连字一起胀。
            //
            // 所以这里把缩放拆成两层语义：
            //   · 容器（玻璃本体）：按 shaped 放大，负责「鼓起」
            //   · 内容（子 Composable）：由调用方包一层反向缩放来抵消
            // 但让每个调用点都包一层太脆，所以改成**放大比例压到很小**（1.5dp 以内），
            // 靠光的变化承担主要的「被按住」信号 —— 光才是玻璃的反馈语言，
            // 形变只是辅助。1.5dp 的膨胀肉眼几乎看不见文字的变形，但能感觉到「动了」。
            val p = press.value
            // easeOutQuart：p=0.3 时走完 76%
            val rest = (1f - p).coerceAtLeast(0f)
            val shaped = 1f - rest * rest * rest * rest

            val growDp = 1.5.dp.toPx()
            val ratio = (growDp / size.height.coerceAtLeast(1f)).coerceAtMost(0.04f)
            scaleX = 1f + ratio * shaped
            scaleY = 1f + ratio * shaped
        }
    }

    /*
     * 卡片在屏幕上的位置。
     *
     * 用一个**普通对象**而不是 state 存：滚动时这个值每帧都变，写成 state 会让
     * 每张卡每帧重组一次。而绘制层本来就会跟着卡片一起平移，读到旧值也不会错位
     * 到肉眼能看出来的程度（采样源是一张糊到没有细节的图）。
     */
    val origin = remember { OffsetHolder() }
    if (sampleBackdrop) {
        m = m.onGloballyPositioned { origin.value = it.positionInRoot() }
    }

    m = m
        .clip(shape)
        /*
         * 磨砂取样层：画在纱底下、被形状裁住。
         *
         * 必须在 `clip` 之后、面板之前 —— 顺序错了就是「糊影盖在卡片上」，
         * 文字会跟着糊成一片。两个 drawBehind 的先后顺序就是绘制顺序。
         */
        .then(
            if (sampleBackdrop) {
                Modifier.drawBehind {
                    drawFrostSlice(
                        photo = frostPhoto ?: return@drawBehind,
                        origin = origin.value,
                        rootSize = backdropSize,
                        dim = backdropDim,
                        dark = dark,
                        alpha = if (enabled) 1f else 0.45f,
                    )
                }
            } else {
                Modifier
            },
        )
        .then(
            // 只要想跟手就装，与「能不能点」解耦 —— 见 trackPointer 的说明
            if (animated && trackPointer) {
                Modifier.pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        // requireUnconsumed = false：滚动列表时这里只「看」不「吃」，
                        // 不会把垂直滑动从 WearList 手里抢走
                        val down = awaitFirstDown(requireUnconsumed = false)
                        sheen = down.position
                        touching = true
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                                ?: break
                            if (!change.pressed) break
                            sheen = change.position
                        }
                        touching = false
                        // 这里**不清 sheen** —— 由上面 LaunchedEffect 在回弹结束后清。
                        // 在回弹期间清会让光斑瞬间跳回中心（见那段注释）
                    }
                }
            } else {
                Modifier
            },
        )
        .drawBehind {
            val p = if (animated) press.value else 0f
            drawGlassPanel(
                corner = cornerRadius?.toPx() ?: (size.height / 2f),
                dark = dark,
                frosted = frosted,
                bodyTint = onSurface,
                tint = tint,
                press = p,
                sheen = if (sheen == Offset.Unspecified) {
                    Offset(size.width * 0.5f, size.height * 0.34f)
                } else {
                    sheen
                },
                // 光斑只在按住时出现；不可点的卡片留一点常驻环境光
                sheenAlpha = when {
                    !animated -> 0f
                    touching || p > 0.02f -> 1f
                    // 第三支的判据从 `onClick == null` 改成「也不跟手」：
                    // GlassSwitch 的轨道 onClick 是 null 但它**跟手**，
                    // 按旧判据会被当成展示卡片，永远挂着 0.45 的静态光 ——
                    // 用户看到的就是「按住时那一团光不动」
                    onClick == null && !trackPointer -> 0.45f
                    else -> 0f
                } * (if (solid) 0.5f else 1f),
                solid = solid,
                elevated = elevated,
                alpha = (if (enabled) 1f else 0.45f) * weight,
                tintAlpha = tintAlpha,
                dim = glassDim,
                bottomBrush = bottomBrush,
                edgeBrush = edgeBrush,
                glossBrush = glossBrush,
            )
        }

    if (onClick != null) {
        // indication = null：水波纹在玻璃上是脏的，反馈交给光斑与形变
        m = m.clickable(
            interactionSource = src,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
    }
    return m
}

/**
 * 玻璃面板：两层 + 一层光。
 *
 * 全部用 `drawRoundRect`：圆角矩形自带裁切，光斑不会溢出，
 * 也就不需要额外的 `clipPath`（每帧一条 Path 比填充贵得多）。
 */
private fun DrawScope.drawGlassPanel(
    corner: Float,
    dark: Boolean,
    /** 底是不是真的糊了：决定本体要不要变薄（见 body 的注释） */
    frosted: Boolean,
    bodyTint: Color,
    tint: Color,
    press: Float,
    sheen: Offset,
    sheenAlpha: Float,
    solid: Boolean,
    elevated: Boolean,
    alpha: Float,
    /** 染色玻璃的浓度（见 [glassSurface] 的同名参数） */
    tintAlpha: Float,
    /** 卡片压暗档位 0~1，0.5 = 标准（见 [LocalGlassDim]） */
    dim: Float,
    bottomBrush: Brush,
    edgeBrush: Brush,
    /** 上沿那一层光泽（见 glossBrush 的注释） */
    glossBrush: Brush,
) {
    if (alpha <= 0.01f) return
    val r = CornerRadius(corner.coerceAtLeast(0f), corner.coerceAtLeast(0f))
    val w = size.width
    val h = size.height

    /*
     * 压暗档位 → 网的厚度。
     *
     * 0.5 时系数正好是 1.0，也就是上一版写死的那套配比 —— 升级不改变观感。
     * 两端留了 0.35 / 1.65 的余量：再淡卡片就成一层雾，再实还不如关掉玻璃。
     * 上限 0.92：留 8% 的缝，卡片永远不是一块不透明的板。
     */
    val k = 0.35f + 1.3f * dim.coerceIn(0f, 1f)
    fun veil(base: Float): Float = (base * k).coerceIn(0f, 0.92f)

    // 浅色底下的「厚度」不能是黑的：白底上压一道黑，看着像脏边/没擦干净。
    // 浅色下这层底色本来就是 onSurface 的极淡版，直接用它，只是更浓一点，
    // 观感是「玻璃下沿积了一点色」，而不是「一道灰印」。
    val shadeBrush = if (dark) bottomBrush else Brush.verticalGradient(
        0.86f to Color.Transparent,
        1f to bodyTint.copy(alpha = 0.10f),
    )

    // ---- 1) 本体 ----
    if (solid && tint != Color.Unspecified) {
        // 主按钮：实心。按下时略微提亮，但不改色相。
        // 底暗与边照画 —— 少了这两笔它就不是玻璃，是一块纯色贴片
        drawRoundRect(tint, cornerRadius = r, alpha = alpha)
        drawRoundRect(Color.White, cornerRadius = r, alpha = 0.06f * press * alpha)
        drawBottomShade(r, shadeBrush, alpha)
    } else if (tint != Color.Unspecified) {
        // 染色玻璃：选中态、错误色、用户消息。用染色**替换**白色而不是叠加，
        // 叠加会让两者互相稀释，最后既不白也不彩
        drawRoundRect(tint.copy(alpha = tintAlpha), cornerRadius = r, alpha = alpha)
        drawBottomShade(r, shadeBrush, alpha)
    } else {
        /*
         * 面 = 一层压暗 + 一层白（深色）/ 一层奶白 + 一点正文色（浅色）。
         *
         * ## 深色为什么从 0.68 收到 0.55
         *
         * 0.68 的黑配 0.24 的白，合起来是 **83% 不透明**：底被压成一块均匀的灰，
         * 卡片也就成了灰板 —— 透不出色相，也没有背景的明暗。量一下就知道有多糟：
         *
         *   底 55 → 压黑 0.68 = 17.6 → 叠白 0.24 = 74.6
         *   正文 242 压上去 → 对比度 (247)/(79.6) ≈ 3.1:1，低于 4.5 的及格线
         *
         * 又灰又读不清，正是「深色模式下表现不好」。现在改成 0.55 / 0.09：
         *
         *   底 55 → 24.75 → 45.5，正文对比度 ≈ 4.9:1，同时还有 40% 的背景透上来。
         *
         * 卡片会比背景略暗 —— 深色玻璃本该如此（烟熏玻璃），形状靠 1dp 的上沿
         * 高光说出来，比「一块比背景亮的灰」干净得多。
         *
         * ## 浅色为什么不再只压一层极淡的暗
         *
         * 清玻璃在浅色下是**沉下去**的，只叠 0.085 的正文色，透出来就是照片本身。
         * 磨砂开启后底已经糊了，再只压一点暗，卡片就没有「毛」的质感 ——
         * 0.26 奶白 + 0.045 正文色是「奶玻璃」：糊掉的底被提亮一档，
         * 深色文字压上去仍有 6:1 以上。
         */
        when {
            dark -> {
                // 弹窗要盖住下面那一层，压得更实
                val scrim = veil(
                    when {
                        elevated -> 0.74f
                        frosted -> 0.60f
                        else -> 0.55f
                    },
                )
                val film = veil(
                    when {
                        elevated -> 0.10f
                        frosted -> 0.10f
                        else -> 0.09f
                    },
                )
                drawRoundRect(Color.Black, cornerRadius = r, alpha = scrim * alpha)
                drawRoundRect(Color.White, cornerRadius = r, alpha = film * alpha)
            }
            elevated -> {
                // 浅色弹窗：0.80 的白在观感上就是一块白板（背后糊成什么样都看不见）。
                // 0.52 才让「底下是糊的」成立，正文仍有 7:1 以上
                drawRoundRect(Color.White, cornerRadius = r, alpha = veil(0.52f) * alpha)
                drawRoundRect(bodyTint, cornerRadius = r, alpha = veil(0.05f) * alpha)
            }
            frosted -> {
                drawRoundRect(Color.White, cornerRadius = r, alpha = veil(0.26f) * alpha)
                drawRoundRect(bodyTint, cornerRadius = r, alpha = veil(0.045f) * alpha)
            }
            else -> {
                // 清玻璃：不叠白，只压一点正文色 —— 卡片与底之间靠「略暗」分开
                drawRoundRect(bodyTint, cornerRadius = r, alpha = veil(0.085f) * alpha)
            }
        }
        drawBottomShade(r, shadeBrush, alpha)
    }

    // ---- 1.5) 顶面光泽 ----
    //
    // 位置只在上面四分之一，且画在本体之上、描边之下：
    // 光落在玻璃的上表面，被边缘那一圈高光「收住」，顺序反了就会盖住描边。
    drawRoundRect(brush = glossBrush, cornerRadius = r, alpha = alpha)

    // ---- 1.6) 磨砂颗粒 ----
    //
    // 只在「底下真的糊了」时才铺。清玻璃是光滑面，铺颗粒等于给镜片打毛，
    // 和它的通透感自相矛盾。
    //
    // 颗粒图里最亮档 9/255（见 GlassGrain.MAX_ALPHA），再乘 0.32~0.45：
    // 单像素差只有 2~4/255，落在「看得出是磨砂、看不出是噪点」的区间。
    // 上一版是 12 × 0.9 ≈ 11/255，浅色白面上被读成一层脏点 —— 用户那句
    // 「浅色模式下颗粒感有点大」就是这个。
    if (frosted) {
        // 浅色要更淡：白面上 12/255 的噪点会被读成「脏」，深色底上同样的值只是「毛」
        drawRoundRect(
            brush = GlassGrain.brush,
            cornerRadius = r,
            alpha = (if (dark) 0.32f else 0.45f) * alpha,
        )
    }

    // ---- 2) 边 ----
    // **一条**边，顶端最亮、向下衰减到几乎消失。
    //
    // 这里踩过一次：先画一圈 0.6dp 均匀边，再在顶端叠一道 0.8dp 高光，
    // 结果长条胶囊的顶边出现「双线」—— 像描了两遍边，很脏。
    // 真实玻璃只有一条边，亮度的差异全部由它自己承担。
    drawEdge(corner, w, h, alpha, edgeBrush)

    /*
     * 浅色弹窗再补一道 1dp 的正文色内描边。
     *
     * 浅色主题的页面本来就是近白的，卡片 0.52 的白叠在糊掉的白底上，和页面
     * 只差十几级 —— 高光那条边又是白的，等于没有边界。用户看到的会是一块
     * 「没有形状的白」，这也是「弹窗没有做玻璃」的一部分：玻璃得先有形状。
     * 这一道暗线才是「卡片到这里为止」的信号。
     */
    if (!dark && elevated) {
        drawEdge(corner, w, h, alpha * 0.14f, SolidColor(bodyTint))
    }

    // ---- 3) 光 ----
    if (sheenAlpha > 0.01f) {
        // 和 tap() 用同一套光：从触点向外衰减，三层 stop。
        //
        // 之前这里是「中心 0.10、55% 处 0.035」两段 —— 中间几乎没有衰减，
        // 等于在控件上盖了一块均匀的白。现在补中间一档（35% 处 0.10），
        // 让亮部有明显的边界，看起来是「一团光」而不是「一层膜」。
        // 光晕强度和 [tap] 对齐：一处按下去，视觉重量要一样，
        // 否则「有玻璃的按钮」和「只有 tap 的按钮」按着像两种东西
        val radius = size.minDimension * 1.15f
        drawRoundRect(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to Color.White.copy(alpha = 0.34f * sheenAlpha * alpha),
                    0.22f to Color.White.copy(alpha = 0.24f * sheenAlpha * alpha),
                    0.45f to Color.White.copy(alpha = 0.11f * sheenAlpha * alpha),
                    0.72f to Color.White.copy(alpha = 0.03f * sheenAlpha * alpha),
                    1f to Color.Transparent,
                ),
                center = sheen,
                radius = radius,
            ),
            cornerRadius = r,
        )
    }
}

/**
 * 底部压暗：只占 14% 高度。
 *
 * 不做顶部渐变 —— 那会把玻璃画成「渐变卡片」，正是上一版的毛病。
 * 底部这一笔的作用是「厚度」：玻璃压在下面的东西上，下沿必然有一道暗。
 */
private fun DrawScope.drawBottomShade(corner: CornerRadius, brush: Brush, alpha: Float) {
    drawRoundRect(brush = brush, cornerRadius = corner, alpha = alpha)
}

/**
 * 一圈等宽的描边，亮度沿左上→右下衰减。
 *
 * - 左上 0.34~0.80：玻璃上沿接收直射光，是整块最亮的一笔
 * - 中段 0.10~0.30：两侧的厚度感
 * - 右下 0.02~0.10：只留一点，全暗会让玻璃「没有下沿」
 *
 * 面是均匀的、边是渐变的 —— 反过来（面做渐变、边做均匀）就会像一张卡片，
 * 那是上一版的错法。
 *
 * ## 1dp 而不是 0.6dp
 *
 * 0.6dp 在 1.5x 屏上只有 0.9px，抗锯齿一稀释就只剩半格灰 —— 描边「在但看不见」。
 * 玻璃靠这道边成形，看不见边就等于没有形状。1dp 在 1.5x 上是 1.5px，
 * 亮部能实打实亮起来，暗部又刚好被抗锯齿吃掉一半，不会变成硬边框。
 */
private fun DrawScope.drawEdge(corner: Float, w: Float, h: Float, alpha: Float, brush: Brush) {
    val width = 1.dp.toPx()
    val inset = width / 2f
    drawRoundRect(
        brush = brush,
        alpha = alpha,
        topLeft = Offset(inset, inset),
        size = Size((w - width).coerceAtLeast(0f), (h - width).coerceAtLeast(0f)),
        cornerRadius = CornerRadius(
            (corner - inset).coerceAtLeast(0f),
            (corner - inset).coerceAtLeast(0f),
        ),
        style = Stroke(width = width),
    )
}

/**
 * 卡片磨砂：把「我背后那一块背景」画进玻璃底下。
 *
 * ## 为什么不是「给卡片挂一个 blur」
 *
 * `Modifier.blur` 糊的是**节点自己的内容**。挂在卡片上，文字和图标一起糊；
 * 挂在卡片内部一个空 Box 上，它背后什么都没有，糊出来是透明的。
 * Compose 至今没有 backdrop-filter，卡片读不到「它背后已经画了什么」。
 *
 * 所以反过来做：背景图在主题层已经备好一张**重度模糊**的小图
 * （[com.dshwatch.ui.theme.LocalFrostPhoto]），这里只按卡片在屏幕上的位置，
 * 把整张图平移过去、用它自己的坐标系铺满卡片 —— 被形状裁住之后，
 * 露出来的正好是「我背后那一块」的模糊版本。
 *
 * 一次 `drawImage` 就是全部成本：没有离屏层、没有逐帧卷积，
 * 图是几十张卡共用的一张，内存不随卡片数增长。
 *
 * ## 位置是「读的时候」拿的，不是「布局的时候」写进 state 的
 *
 * 列表滚动时每张卡的坐标每帧都变。写进 state 就是每张卡每帧重组一次；
 * 而绘制层本来就会跟着卡片一起平移，采样源又是糊到没有细节的图，
 * 差几个像素肉眼无法分辨。所以用一个普通对象持有，只在真正重绘时读一次。
 */
private fun DrawScope.drawFrostSlice(
    photo: ImageBitmap,
    /** 卡片左上角在根布局里的坐标，px */
    origin: Offset,
    /** 根布局（= 背景层）的尺寸，px */
    rootSize: IntSize,
    /** 背景图上那层遮罩的浓度。不补的话卡片底会比周围亮一块 */
    dim: Float,
    dark: Boolean,
    alpha: Float,
) {
    if (alpha <= 0.01f) return
    // 照片在根布局上是「等比铺满 + 居中裁」—— 与背景层用同一个纯函数，
    // 否则卡片里的糊影会和周围的照片错开
    val cover = coverRect(
        imageWidth = photo.width,
        imageHeight = photo.height,
        canvasWidth = rootSize.width.toFloat(),
        canvasHeight = rootSize.height.toFloat(),
    ) ?: return

    drawImage(
        image = photo,
        // 整张图平移过来：卡片自己的原点对应根布局的 (origin)，所以铺图的
        // 左上角要减去它。画到卡片外的部分由 clip(shape) 裁掉
        dstOffset = IntOffset(
            (cover.left - origin.x).roundToInt(),
            (cover.top - origin.y).roundToInt(),
        ),
        dstSize = IntSize(cover.width, cover.height),
        alpha = alpha,
        // Low = 双线性：这张图本来就是「放大后发虚」，再贵的采样也看不出差别
        filterQuality = FilterQuality.Low,
    )
    if (dim > 0.01f) {
        drawRect(
            color = if (dark) Color.Black else Color.White,
            alpha = dim.coerceIn(0f, 0.9f),
        )
    }
}

/** 只用来放一个 Offset，不参与快照系统 —— 见 [drawFrostSlice] 的说明 */
private class OffsetHolder {
    var value: Offset = Offset.Zero
}
