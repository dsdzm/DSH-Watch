package com.dshwatch.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import com.dshwatch.domain.DeviceTier
import com.dshwatch.ui.theme.LocalGlass
import com.dshwatch.ui.theme.LocalPerf

/**
 * 统一点击扩展。
 *
 * ## 三次迭代，前两版都不对
 *
 * | 版本 | 做法 | 用户反馈 |
 * |---|---|---|
 * | 1 | M3 默认 ripple | 「还是 M3 的黑色遮罩」——深底上 ripple 就是一层变暗 |
 * | 2 | 掐掉 ripple，压一层白 0.10 | 「太简陋和生硬，不灵动」——一个没有中间态的二值开关 |
 * | 3 | 本文件 | — |
 *
 * 第 2 版的病根：`pressed` 是布尔值，按下瞬间 alpha 从 0 跳到 0.10、抬起瞬间跳回 0。
 * 人眼对「瞬间到位」的反馈感知是「闪了一下」，不是「被按到了」。真实的手感要有：
 *
 * - **渐变**：光要漫上来，而不是啪地打开（120ms 起、180ms 落）
 * - **从手指处扩散**：光应该从触点向外铺开，而不是整块同时亮
 * - **一点点过冲**：抬起时光散得比进来慢一点，尾部拖一丝
 *
 * ## 为什么不是简单加个 animateFloatAsState
 *
 * 那样只能做到 alpha 渐变，做不到「从触点扩散」—— 而扩散恰恰是「灵动」的主要来源。
 * 所以这里要用 `pointerInput` 拿触点坐标，再用一个 `Animatable` 驱动整个渐变。
 *
 * ## 性能
 *
 * 触点位置用 `awaitPointerEvent` 在**按下之前**就要拿到，所以指针修饰符必须在
 * `clickable` 之前 —— 顺序错了就只能在按下之后才拿到位置，光会从中心亮起。
 * 省电档 / 低端档不装 pointer、不跑动画，退化成静态一层，与普通控件同开销。
 */
fun Modifier.tap(
    enabled: Boolean = true,
    /**
     * 静默模式：只挂点击，**不画任何反馈**。
     *
     * 给 [GlassController] 用 —— 玻璃控件自己已经画了更完整的光，
     * 这里再叠一层就是重复发亮（实测两层叠加后 alpha 到 0.25，
     * 按下去像一块白板，反而丢了质感）。
     *
     * 对调用方保持默认 false，绝大部分地方不用管。
     */
    quiet: Boolean = false,
    /**
     * 外部按压源。
     *
     * 这一条是补一个存在很久的错：`quiet = true` 时这里 `remember` 了一个
     * **自己的** interaction，而调用点普遍写成
     * `glassSurface(interaction = src).tap(quiet = true, ...)` —— 两边各拿一个，
     * 玻璃那侧永远收不到 Press 事件。结果就是「按下不亮、不形变」，
     * 只有 clickable 在悄悄响应。
     *
     * 所有 `quiet = true` 的调用点都必须把同一个 interaction 传进来。
     */
    interaction: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier =
    composed {
        if (!LocalGlass.current) {
            // 非玻璃主题：原样交给 M3
            return@composed this.clickable(enabled = enabled, onClick = onClick)
        }

        // interaction 要先建：下面 quiet 分支和正常分支都要用它。
        // 外部传了就用外部的 —— 见 interaction 参数的说明
        val ownInteraction = remember { MutableInteractionSource() }
        val src = interaction ?: ownInteraction

        if (quiet) {
            // 反馈由外层 glassSurface 负责，这里只挂事件。
            // 仍然用同一个 interaction —— 外层的按压状态就是从这里读的
            return@composed this.clickable(
                interactionSource = src,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
        }

        val perf = LocalPerf.current
        val animated = perf.enableAnimations
        /*
         * 跟手光晕开不开。
         *
         * 原来写的是 `animated && perf.tier != DeviceTier.LOW`。
         * 问题是 tier 是**设备算出来的、用户改不了**：一台被系统标成
         * 低内存的设备（比如 maxMemory 只有 192MB），哪怕用户在设置里
         * 明确选了「性能优先」，光晕也永远不跟手 —— 用户没法自己救回来。
         *
         * enableAnimations 已经承载了「特效开不开」这个决定（它由 tier 和
         * PerfMode 共同算出），这里再叠一层 tier 判断是重复的，
         * 而且还把这个开关锁死了。只留 enableAnimations。
         */
        val rich = animated
        // 光强：0 = 松手，1 = 按住最深
        val glow = remember { Animatable(0f) }
        // 触点。用普通 state 存，只在 draw 阶段读，不触发重组
        val touch = remember { androidx.compose.runtime.mutableStateOf(Offset.Unspecified) }

        // collectLatest 而不是 collect：
        //
        // 松手时的回弹（spring 0.58 / 260）要走 600~900ms 才停。用 collect 的话，
        // 这段时间里用户再按下，Press 事件会**排在动画后面**等 —— 表现是
        // 「回弹期间连点第二下没反应」。collectLatest 会把上一段动画取消掉，
        // 新按下立刻接上。
        LaunchedEffect(src, animated) {
            src.interactions.collectLatest { i ->
                when (i) {
                    is PressInteraction.Press -> {
                        touch.value = i.pressPosition
                        glow.animateTo(
                            targetValue = 1f,
                            // 140ms 缓出。
                            //
                            // 之前用 120ms 的 tween —— 而 tween 的**默认 easing 是线性**，
                            // 观感是「匀速推上去」，所以看着生硬：真实的光亮起来是
                            // 先快后慢（一开始很猛，接近饱和时慢下来）。
                            // 换成 FastOutSlowIn 后前 40ms 就走完 60% 的亮度，
                            // 剩下 100ms 慢慢收尾。
                            animationSpec = tween(
                                durationMillis = 110,
                                easing = FastOutSlowInEasing,
                            ),
                        )
                    }

                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        // 收尾慢一点、带一点过冲回落：光散得比铺开更缓，
                        // 这一丝不对称就是「水」和「开关」的区别
                        glow.animateTo(
                            targetValue = 0f,
                            // 松手散得慢一些、带一点回弹：这一丝不对称
                            // 就是「水」和「开关」的区别，也是「明显」的来源之一
                            animationSpec = spring(
                                dampingRatio = 0.58f,
                                stiffness = 260f,
                            ),
                        )
                        // **不要在松手时立刻把触点清空。**
                        //
                        // 这是用户报的「回弹动画播放的时候，跟手光晕会出现在
                        // 按钮控件的正中央」的直接原因：
                        // 松手瞬间 touch 变回 Unspecified，而 draw 里
                        // Unspecified 的兜底是 `(0.5w, 0.5h)` —— 正中央。
                        // 可这时 glow 还在回弹（还 > 0），于是光斑**跳**到中间再消失，
                        // 看起来像在按钮中心闪了一下。
                        //
                        // 正确做法：让光留在最后那个位置散掉。手指抬起来，
                        // 光本来就该在原地淡出。等回弹真的跑完（animateTo 返回）
                        // 再清 —— 那时 glow 已是 0，清不清都看不见。
                        touch.value = Offset.Unspecified
                    }
                }
            }
        }

        var m = this

        // 跟手：手指在控件内滑动时，光跟着走
        if (rich) {
            m = m.pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    // requireUnconsumed = false：列表滚动时只「看」不「吃」
                    val down = awaitFirstDown(requireUnconsumed = false)
                    touch.value = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        touch.value = change.position
                    }
                }
            }
        }

        m = m.drawWithContent {
            drawContent()
            val p = glow.value
            if (!enabled || p <= 0.004f) return@drawWithContent

            val r = CornerRadius(
                (size.height / 2f).coerceAtMost(size.minDimension / 2f),
            )
            val center = if (touch.value == Offset.Unspecified) {
                Offset(size.width * 0.5f, size.height * 0.5f)
            } else {
                touch.value
            }

            // ---- 光晕：从触点向外，亮得明显、但衰减得干净 ----
            //
            // 三版历程：
            //   · 第 1 版「底光 0.055 + 扩散 0.105」→ 整块同时变亮，像盖了层白膜
            //   · 第 2 版只留扩散、中心 0.18 → 不生硬了，但用户说「做得明显一点」
            //   · 第 3 版（这里）→ 中心提到 0.36，并把半径收小到 1.15 倍
            //
            // 关键是**两个参数一起动**：只提亮不提半径，光会糊满整块控件，
            // 又回到「一层膜」；只收半径不提亮，就成了一个干巴巴的白点。
            // 0.36 + 1.15 倍是「指尖周围一小团亮着，往外三四成就没了」——
            // 这是人眼认作「光晕」的形状。
            //
            // 再加一圈**外沿亮环**（62% 处那一档）：纯径向渐变到边缘完全为 0，
            // 在小控件上几乎看不出「光」，补一圈很淡的环，散开时才有「涟漪」的感觉。
            val radius = size.minDimension * 1.15f
            drawRoundRect(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Color.White.copy(alpha = 0.36f * p),
                        0.22f to Color.White.copy(alpha = 0.26f * p),
                        0.45f to Color.White.copy(alpha = 0.12f * p),
                        0.72f to Color.White.copy(alpha = 0.035f * p),
                        1f to Color.Transparent,
                    ),
                    center = center,
                    radius = radius,
                ),
                cornerRadius = r,
                size = Size(size.width, size.height),
            )
        }

        m.clickable(
            interactionSource = interaction,
            // 关键：掐掉 ripple。观感全交给上面的渐变光 + 控件自己的形变
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
    }
