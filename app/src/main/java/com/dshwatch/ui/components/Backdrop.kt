package com.dshwatch.ui.components

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

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
 * 于是 `AppRoot` 放一个 `MutableState`，`GlassDialog` 在 `DisposableEffect`
 * 里置 1 / 归 0。模糊半径跟着这个值做动画：弹窗淡入的同时背景一点点糊开，
 * 而不是「啪」一下换一张图。
 *
 * ## 代价
 *
 * `Modifier.blur` 是离屏渲染。它只在**弹窗存在的那几百毫秒到几秒**里发生
 * （半径按闸缩放，闸为 0 时 Modifier 都不挂），关掉即消失，不影响滚动帧率。
 *
 * API 31 以下没有 `Modifier.blur`（会被**静默忽略**，不报错），
 * 退成静态磨砂 [drawFrostVeil]：观感是「雾盖上来」，不是模糊。
 */
val LocalBackdropVeil = compositionLocalOf { mutableStateOf(0f) }

/** 真模糊的最大半径。26dp 在手表上刚好糊掉文字轮廓，又不至于糊成一块色块 */
private val MaxBlurRadius = 26.dp

/**
 * 宿主：把页面包进来，弹窗出现时自动盖一层模糊。
 *
 * 用法是把**整个页面区**放进来，不要每页各包一次 —— 见 [LocalBackdropVeil]。
 */
@Composable
fun GlassBackdropHost(content: @Composable () -> Unit) {
    val veil = remember { mutableStateOf(0f) }
    val target = if (veil.value > 0.01f) 1f else 0f
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 180),
        label = "backdropVeil",
    )
    val real = Build.VERSION.SDK_INT >= MIN_SDK_REAL_BLUR

    // 浅色底要更重一点：白底上的雾很容易看不见，糊了跟没糊一样
    val lightBase = MaterialTheme.colorScheme.background.luminance() > 0.35f

    CompositionLocalProvider(LocalBackdropVeil provides veil) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 只在需要时挂 blur：常态下这个 Modifier 根本不进链，零成本
                .then(
                    if (real && animated > 0.01f) {
                        Modifier.blur(MaxBlurRadius * animated)
                    } else {
                        Modifier
                    },
                ),
        ) {
            content()

            // 老设备：没有真模糊，用静态雾代替，淡入淡出靠 alpha
            if (!real && animated > 0.01f) {
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
