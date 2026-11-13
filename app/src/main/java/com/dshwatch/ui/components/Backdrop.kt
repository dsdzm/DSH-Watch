package com.dshwatch.ui.components

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.dshwatch.ui.theme.LocalDarkTheme

/**
 * 「有弹窗时，整屏背景被糊掉」的总闸。
 *
 * ## 为什么这件事必须做在宿主页面上，而不是弹窗里
 *
 * 前几轮试过三次，结论一致：**`Dialog` 模糊不了它背后的东西**。
 *
 * | 做法 | 为什么不行 |
 * |---|---|
 * | 在 Dialog 内部的 Box 上 `blur` | 只能糊自己的内容。背后那个 Activity 在**另一个 Window** 里，不在这个节点的子树里 |
 * | 把背景色晕在 Dialog 里重画一遍 | 画出来的是「另一张底」，不是「这一页糊了」。用户一眼看出页面变了 |
 * | `Window.setBackgroundBlurRadius` | 要 API 31，且必须在弹窗出现**之前**调用；Compose 的 `Dialog` 不暴露这个时机 |
 *
 * 所以只能反过来：**模糊加在宿主页面那一层**，弹窗只负责「通知宿主」。
 *
 * ## 弹窗怎么通知宿主
 *
 * Compose 的 `Dialog` 画面在别的 Window，但它的**组合**仍然挂在同一个
 * `Composition` 上 —— 在弹窗内容里改一个 `CompositionLocal`，宿主读得到。
 *
 * ## 这里是**计数**，不是开关
 *
 * v1.15 最初写成 `0f / 1f` 的开关，`onDispose` 一律归零。这在单弹窗时看不出问题，
 * 但两个弹窗叠在一起（审批框 + 询问框同时挂）时会错：先关的那个把闸拉回 0，
 * 另一个还开着，背景却突然清楚了。
 *
 * 改成引用计数后，「最后一个关掉才算关」。
 *
 * ## 页面切换必须强制归零
 *
 * 这是 v1.15 那次「点开就一片模糊、内容看不见」最可能的直接原因：
 *
 * 弹窗关闭 → 闸归零 → `animateFloatAsState` 还要 180ms 才把半径降到 0。
 * 用户如果在这 180ms 里返回上一页（非常常见：关掉弹窗立刻退），
 * **新页面一进来就是糊的**。而新页面自己的内容在糊的那层底下，
 * 用户看到的就是「加载出一片背景模糊，然后什么都没了」。
 *
 * 光靠 `onDispose` 归零不够 —— 它只管「弹窗没了」，不管「页面换了」。
 * 所以 [GlassBackdropHost] 收一个 [resetKey]，一变就无条件清零。
 */
val LocalBackdropDepth = compositionLocalOf { mutableStateOf(0) }

/**
 * 真模糊的最大半径。
 *
 * **14dp，不是 26dp。**
 *
 * 26 是 1.15 最初的值，写在「刚好糊掉文字轮廓」的注释里。但那句话是在
 * 手机上想的 —— 手表屏幕只有 200dp 宽，26dp 相当于宽度的 13%，
 * 一整屏文字会糊成一团色块，用户的第一反应不是「背景虚化了」，
 * 而是「内容没了」。
 *
 * 14dp 在 200dp 宽的屏上约 7%，刚好把文字糊成看不清的纹理，
 * 但还能看出页面的布局轮廓 —— 这才是「失焦」，不是「消失」。
 */
private val MaxBlurRadius = 14.dp

/** 淡入时长。淡出不走动画，见下方注释。 */
private const val FADE_IN_MS = 160

/**
 * 宿主：把页面包进来，弹窗出现时自动盖一层模糊。
 *
 * @param resetKey 页面标识（通常传 `nav.current`）。一旦变化就立刻清掉模糊。
 *   这是防「切页带着模糊进来」的硬保险 —— 不能只依赖弹窗自己的 onDispose。
 */
@Composable
fun GlassBackdropHost(
    resetKey: Any? = Unit,
    content: @Composable () -> Unit,
) {
    val depth = remember { mutableStateOf(0) }
    val active = depth.value > 0

    /*
     * 页面一换，无条件清零。
     *
     * ## 为什么不用 LaunchedEffect
     *
     * 第一版写的是 `LaunchedEffect(resetKey) { depth.value = 0 }`，看起来最直白，
     * 但它有个要命的时序问题：
     *
     *   · `DisposableEffect` 的 effect 在 apply 那一刻**同步**执行 → depth 变 1
     *   · `LaunchedEffect` 的协程体要等一次 dispatch 才跑 → depth 被设回 0
     *
     * 也就是说：**任何「进入页面时就已经有弹窗」的情况，闸都会被这次清零抹掉**，
     * 弹窗开着、背景不糊。而首屏恰好最可能带弹窗（询问框、审批框）。
     *
     * 换成 `SideEffect`：它和 `DisposableEffect` 一样在 apply 阶段同步执行，
     * 且【首次不动作】—— 用一个哨兵值区分「第一次组合」和「真的换页了」。
     */
    val sentinel = remember { Any() }
    var lastKey by remember { mutableStateOf<Any?>(sentinel) }
    SideEffect {
        if (lastKey != resetKey) {
            val hadPage = lastKey !== sentinel
            lastKey = resetKey
            if (hadPage) depth.value = 0
        }
    }

    val animated by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = FADE_IN_MS),
        label = "backdropVeil",
    )

    val real = Build.VERSION.SDK_INT >= MIN_SDK_REAL_BLUR

    // 浅色底要更重一点：白底上的雾很容易看不见，糊了跟没糊一样
    // 透明背景反推不出明暗（玻璃预设就是透明的），直接问主题
    val lightBase = !LocalDarkTheme.current

    CompositionLocalProvider(LocalBackdropDepth provides depth) {
        Box(modifier = Modifier.fillMaxSize()) {
            /*
             * 页面层。
             *
             * **是否挂 blur 由 active 决定，不由 animated 决定。**
             *
             * 这条是关键：animated 是动画值，闸归零后它还要跑 160ms 才落到 0。
             * 如果拿它当开关，就等于「弹窗已经关了，页面还要糊 160ms」——
             * 而这 160ms 恰好是用户最可能点下一步的时间。
             *
             * 用 active 就把这个窗口彻底关掉了：闸一归零，Modifier 立刻从链上摘掉。
             * 代价是淡出不是渐变而是瞬切 —— 但这时候页面本来就在恢复清晰，
             * 从「糊」到「清楚」的瞬切，眼睛是感觉不到"跳"的（模糊没有方向感）。
             * 反过来「淡出」反而更糟：那 160ms 里页面是半糊的，像花屏。
             */
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (real && active && animated > 0.02f) {
                            Modifier.blur(MaxBlurRadius * animated.coerceIn(0f, 1f))
                        } else {
                            Modifier
                        },
                    ),
            ) {
                content()
            }

            /*
             * 老设备（API < 31）：没有真模糊，用静态雾代替。
             *
             * 这一层也只在 active 时存在。之前它跟着 animated 走，
             * 于是闸归零后的 160ms 里，屏幕上还盖着一层半透明的雾 ——
             * 内容被压淡，看着像"没加载出来"。
             */
            if (!real && active) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = animated }
                        .drawWithCache {
                            onDrawBehind { drawFrostVeil(lightBase) }
                        },
                )
            }
        }
    }
}

/**
 * 系统级「背景模糊」可用性。
 *
 * `Window.setBackgroundBlurRadius` 是 API 31 引入的，但它**不保证生效**：
 * 用户可以关掉、低端设备的合成器也不一定支持。
 * `WindowManager.isCrossWindowBlurEnabled` 才是「现在到底有没有」的答案。
 *
 * 为什么不直接用它来做弹窗模糊：它要求在本窗口显示**之前**设好半径，
 * 而 Compose 的 `Dialog` 不暴露这个时机 —— 真正的模糊由宿主的
 * [GlassBackdropHost] 负责。这里只用来决定「遮罩还要不要压黑」。
 */
object WindowBlur {

    fun isBackgroundBlurSupported(context: android.content.Context): Boolean {
        if (Build.VERSION.SDK_INT < MIN_SDK_REAL_BLUR) return false
        return runCatching {
            val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE)
                as? android.view.WindowManager
            wm?.isCrossWindowBlurEnabled == true
        }.getOrDefault(false)
    }
}
