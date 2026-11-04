package com.dshwatch.ui.nav

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 页面转场：只用 Compose 官方自带的淡入淡出。
 *
 * ## 为什么整个自定义动画都删了
 *
 * 1.15 到 1.15.5 连着做了五版「从按钮长成整屏」的转场：抓快照、算圆角、
 * 查矩形、管两页 slot、调曲线。每一版都在真机上冒出新的静默故障 ——
 * 空白背景、先整页出现再播、负圆角闪退、退场没动画。
 * 共同点是**这些逻辑全靠我们自己维护时序**，而手表上任何一次
 * 「等一帧」都可能变成一次卡顿，且不抛异常、不打日志。
 *
 * 现在交给平台：`AnimatedContent` 是 androidx.compose.animation 自带的，
 * `fadeIn()` / `fadeOut()` 是它的标准转场，没有自定义几何、没有状态机、
 * 没有回调握手。系统开发者选项里的「过渡动画缩放」也能正常影响它。
 *
 * 仍保留的两条底线（都是之前踩过的坑，和动画方案无关）：
 *
 * 1. [content] **必须用 lambda 给的 screen 渲染**。转场期间新旧两页同时在组合里，
 *    如果读外层的 `nav.current`，两页会画成同一份 —— 表现为「新页先出现一下」。
 * 2. 底下垫一层主题背景色。两页交叉时 alpha 都不满 1，露出来的必须是纸，
 *    而不是 Activity 窗口底色（OLED 主题下就是纯黑，看着像「黑色遮罩闪一下」）。
 */
@Composable
fun ExpansionHost(
    nav: WatchNavigator,
    reducedMotion: Boolean,
    content: @Composable (Screen) -> Unit,
) {
    // 省电档 / 用户选了性能优先：连淡入淡出也不挂，直接切页。
    if (reducedMotion) {
        content(nav.current)
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AnimatedContent(
            targetState = nav.current,
            // 官方标准交叉淡入淡出，不加任何自定义变换。
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "PageFade",
        ) { screen ->
            content(screen)
        }
    }
}
