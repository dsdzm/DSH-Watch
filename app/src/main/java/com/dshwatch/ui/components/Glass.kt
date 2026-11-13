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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dshwatch.domain.DeviceTier
import com.dshwatch.ui.theme.LocalDarkTheme
import com.dshwatch.ui.theme.LocalGlass
import com.dshwatch.ui.theme.LocalPerf

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
 * 所以这里也只画两层：一层均匀的白（或主题染色），一圈 0.6dp 的细边。
 * 玻璃的高级感来自**底下有什么**，不来自玻璃自己画了多少东西。
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
 * - 省电 / 低端档：只画两层，不装 pointer、不跑动画，和普通卡片同开销
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
    // 浅色底上「压暗」用的颜色：正文色。在 Composable 作用域里取好再传进 DrawScope
    val onSurface = MaterialTheme.colorScheme.onSurface

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
    val edgeBrush = remember(dark, elevated) {
        Brush.verticalGradient(
            0f to Color.White.copy(
                alpha = when {
                    // 深色弹窗：本体压暗之后，这条边成了「弹窗从背景里切出来」
                    // 的唯一信号，必须够亮。0.46 → 0.62
                    dark && elevated -> 0.62f
                    elevated -> 0.50f
                    dark -> 0.34f
                    else -> 0.42f
                },
            ),
            // 中段不能太亮：整圈都亮就成了描边色块。
            // 深色弹窗的中段略提，让侧边有厚度但不喧宾夺主
            0.34f to Color.White.copy(
                alpha = when {
                    // 侧边中段跟着提一点，让整圈有厚度但不至于变成描边色块
                    dark && elevated -> 0.22f
                    dark -> 0.10f
                    else -> 0.24f
                },
            ),
            // 下沿：深色弹窗收到 0.02。玻璃越薄，下沿那道暗越像「脏边」
            1f to Color.White.copy(
                alpha = when {
                    // 深色弹窗下沿收到 0.02：底已经亮了，再留亮边会和背景糊在一起
                    dark && elevated -> 0.02f
                    dark -> 0.05f
                    else -> 0.10f
                },
            ),
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

    m = m
        .clip(shape)
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
                alpha = if (enabled) 1f else 0.45f,
                bottomBrush = bottomBrush,
                edgeBrush = edgeBrush,
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
    bodyTint: Color,
    tint: Color,
    press: Float,
    sheen: Offset,
    sheenAlpha: Float,
    solid: Boolean,
    elevated: Boolean,
    alpha: Float,
    bottomBrush: Brush,
    edgeBrush: Brush,
) {
    if (alpha <= 0.01f) return
    val r = CornerRadius(corner.coerceAtLeast(0f), corner.coerceAtLeast(0f))
    val w = size.width
    val h = size.height

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
        // 染色玻璃：选中态、错误色。用染色**替换**白色而不是叠加，
        // 叠加会让两者互相稀释，最后既不白也不彩
        drawRoundRect(tint.copy(alpha = 0.42f), cornerRadius = r, alpha = alpha)
        drawBottomShade(r, shadeBrush, alpha)
    } else {
        // 中性玻璃：白 0.24。
        //
        // 这个数字是量出来的，不是猜的：参考应用的玻璃亮度与背景之比约 1.45，
        // 换算成叠加就是 0.30 左右的白。一开始我用了 0.075，只提亮 7%，
        // 玻璃看上去跟纯黑贴纸没有区别 —— 因为它下面那层颜色根本没被抬起来。
        val body = when {
            // 深色弹窗 0.34 — 上一版压到 0.16，现在调回来。
            //
            // 那一版是基于一个错误的前提：我以为「暗」是卡片太灰，
            // 于是把本体压到 0.16、从背景透上来找色相。其实卡片灰是因为
            // **它背后那层被遮罩压黑了**，玻璃透过去的只有 55 的亮度。
            //
            // 现在遮罩改成「只糊不暗」（模糊由页面层的 GlassBackdropHost 负责），
            // 背后回到 76~80，本体就该回到 0.34 —— 这时候透上来的是
            // 还亮着的背景，卡片才是「玻璃」而不是「灰板」。
            //
            // 一句话：本体的透明度要和「底有多亮」配对。底被压暗时抬本体，
            // 底亮起来就该放回去。之前是拿本体去补遮罩的账，补不平。
            // 浅色底：玻璃必须「往下沉」，而不是「往上提」。
            //
            // 这是 v1.15 第三次重发里最要命的一条。玻璃本体一直是
            // 「叠一层白 α」——这个公式只在深色底上成立：底越亮，叠白越看不出东西，
            // 白底上叠 0.92 的白等于直接消失，卡片看起来就是「没有卡片」。
            // 用户说的「纯白色背景无法体现出玻璃的透明」正是这个：
            // 透明感的来源不是「变亮」，是**卡片与底之间有差**。
            //
            // 白底上的玻璃在现实里是：透过去的白比周围略暗一点（玻璃有折射损耗）、
            // 上沿一道高光、下沿一道暗边 —— 全靠「压一点」来成形。
            // 所以浅色下改成叠**极淡的暗**（surface 的正文色，0.06~0.10），
            // 再配原本就有的顶边高光。看着像玻璃，而不是像一张白纸。
            // ---- 弹窗（elevated）：它背后现在**已经被糊掉了**，所以不能太实 ----
            //
            // 用户的最新反馈：「OLED 黑和其他主题色的弹窗太透明了，没有给弹窗背景做模糊」。
            // 前半句和后半句是同一件事：因为背后没糊，透出来的是一页清清楚楚的字，
            // 于是「透」在观感上等于「看得见后面的内容」，卡片再实也像没盖住。
            //
            // 现在背后由宿主统一糊掉（Backdrop.kt），卡片就可以回到「玻璃该有的透」：
            // 透过去的是一团模糊的色块，读不出字，这才像毛玻璃。
            // 所以这里把弹窗本体从 0.34 收到 0.26 —— 不是为了更透，是为了让「糊」看得见。
            // 0.26 → 0.12：底下多了那层暗底（见下面 dark 分支），白色只是「光泽」，
            // 不再是「亮度来源」。两笔加起来才是最后那张面。
            elevated && dark -> 0.12f
            // 浅色弹窗：0.92 → 0.56。
            // 浅底上叠白本来就是「看不见」的方向（见下面浅色分支的说明），
            // 靠 0.92 的白硬盖在观感上是一块白板；背后糊掉之后，
            // 0.56 的白 + 上沿高光 + 下沿厚度才是「一块毛玻璃」。
            elevated -> 0.56f
            dark -> 0.24f
            else -> 0.55f
        }
        if (dark) {
            /*
             * 深色弹窗：先压一层暗，再叠白。
             *
             * 这一笔是 v1.22.3 加的，起因是用户一句「文字和控件跟背景几乎融为一体，
             * 看都看不出来」。量一下当时的样子就明白为什么：
             *
             *   模糊后的背景 ≈ RGB 55
             *   弹窗本体     = 白 0.26 叠上去 → 107
             *   正文色       ≈ 230
             *   对比度       = (230+5)/(107+5) ≈ 2.1:1
             *
             * 正文要 4.5:1 才够读，2.1 就是「能看见有字，但读起来费劲」。
             * 而且**加白救不了** —— 白字配越亮的底，对比只会更低。
             * 唯一的方向是把底压暗，让白字重新有地方站。
             *
             * 0.68 的暗底把 55 压到 18，再叠 0.12 的白回到 46，
             * 对比度 (230+5)/(46+5) ≈ 4.6:1 —— 过线，同时还剩 32% 的背景
             * 透上来，仍然是玻璃，不是一块实心板。
             */
            drawRoundRect(Color.Black, cornerRadius = r, alpha = 0.68f * alpha)
            drawRoundRect(Color.White, cornerRadius = r, alpha = body * alpha)
        } else if (elevated) {
            /*
             * 浅色弹窗：**一层真的磨砂白**。
             *
             * 这一笔是 v1.22.5 改的。之前的浅色弹窗只叠了 10% 的正文色 ——
             * 在算数上「文字 / 卡片」的对比度是够的（≈12:1），但那是**假设
             * 卡片底就是纯白**。实际上卡片 90% 是透明的，背后是滚动中的聊天页：
             * 一屏字、气泡、图片都在，文字压在这些东西上面，读起来像隔着一层脏玻璃。
             *
             * 参考用户给的另一个应用：它的弹窗是「磨砂白卡片 + 压暗的背景」。
             * 所以这里补上 0.80 的白 —— 透过去只剩 20%，能看出背后是模糊的色块，
             * 但读不出任何内容。这才是毛玻璃该有的样子：**透，但不可读**。
             *
             * 再叠 0.05 的正文色压痕，让卡片不至于白得发飘（白纸感），
             * 并且和下面那层白遮罩拉开一点层次。
             */
            drawRoundRect(Color.White, cornerRadius = r, alpha = 0.80f * alpha)
            drawRoundRect(bodyTint, cornerRadius = r, alpha = 0.05f * alpha)
        } else {
            // 普通浅色卡片：仍然只压一点正文色。列表卡片要「贴」在背景上，
            // 做成磨砂白会和背景断开，一整屏变成白色方块
            drawRoundRect(
                color = bodyTint,
                cornerRadius = r,
                alpha = 0.085f * alpha,
            )
        }
        drawBottomShade(r, shadeBrush, alpha)
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
     * 浅色主题的页面本来就是近白的，卡片 0.80 的白和页面只差 8/255 ——
     * 高光那条边又是白的，等于没有边界。用户看到的会是一块「没有形状的白」。
     * 这一道 0.12 的暗线才是「卡片到这里为止」的信号。
     */
    if (!dark && elevated) {
        drawEdge(corner, w, h, alpha * 0.12f, SolidColor(bodyTint))
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
 * 一圈等宽的描边，亮度沿竖直方向衰减。
 *
 * - 顶端 0.30：玻璃上沿接收直射光，是整块最亮的一笔
 * - 中段 0.12：两侧的厚度感
 * - 底端 0.05：只留一点，全暗会让玻璃「没有下沿」
 *
 * 面是均匀的、边是渐变的 —— 反过来（面做渐变、边做均匀）就会像一张卡片，
 * 那是上一版的错法。
 */
private fun DrawScope.drawEdge(corner: Float, w: Float, h: Float, alpha: Float, brush: Brush) {
    val width = 0.8.dp.toPx()
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
