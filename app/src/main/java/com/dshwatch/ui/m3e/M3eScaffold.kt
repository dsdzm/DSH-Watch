package com.dshwatch.ui.m3e

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.dshwatch.ui.i18n.LocalStrings

/**
 * 顶栏顶部的让位高度。
 *
 * `AppScaffold` 的时间文字是**画在内容之上的浮层**，不参与布局 ——
 * 它不会把内容往下推，只会在原地盖住第一行。所以内容必须自己让出这一段。
 *
 * 这个值是量出来的，不是拍的：Wear M3 的 `TimeText` 是 24dp 的字，
 * 上下各留 4dp 呼吸，合计 32dp。之前这里写的 24dp 只够字高、不含呼吸，
 * 结果顶栏第一行贴着时间文字的下沿，视觉上像被压住。
 */
internal val M3eTimeTextInset = 32.dp

/**
 * 内容区的横向内收。
 *
 * **不再读 `profile.cornerInsetDp`。**
 *
 * 那个值是给经典界面用的「用户可调」参数，用户可以把它设成 0（这台就是 0），
 * 于是 M3E 的卡片会一直贴到屏幕边 —— 圆屏的四个角直接把卡片切掉。
 * 圆屏的安全区是物理事实，不该跟着一个偏好开关走。
 *
 * 列表页传 0：`TransformingLazyColumn` 自己有一份 `contentPadding`（横向 6dp），
 * 再叠一层就是 14dp，卡片会窄得像手机上的缩略图。
 */
internal val M3ePageInset = 8.dp

/**
 * M3E 的页面骨架（固定顶栏版）。
 *
 * ## 这一版修掉的两个真 bug
 *
 * ### 一、列表滚上去的内容会盖住顶栏
 *
 * `TransformingLazyColumn` 为了让贴边项能缩小、淡出，**关掉了绘制裁剪** ——
 * 这是它做鱼眼变换的前提（缩放后的项需要画到列表边界之外）。
 *
 * 代价是：只要列表上面压着一个固定顶栏，滚上去的项就会**直接画在顶栏上面**。
 * 这不是「层级错了」，而是列表压根不管边界。
 *
 * 那两张截图正是这个：
 * - 设置页里「自动检查更新」那张卡滚到了时间文字上面，返回键和标题被压在它下面
 * - 对话页里最新的消息盖住了顶栏那一排图标，图标只露出下半截
 *
 * 修法是把「不裁剪」这个自由收回来：内容区显式 `clipToBounds()`。
 * 贴边项的缩放本来就在列表内部完成，裁掉的只是那些**还没进来**的部分 ——
 * 那部分原本就看不见（在圆周外），裁掉没有损失。
 *
 * ### 二、时间文字被压住
 *
 * 见 [M3eTimeTextInset]。
 *
 * ## 顶栏为什么是固定的，而不是跟着列表滚
 *
 * 官方的 `ScreenScaffold` 把标题和动作当作列表项，滚动时会滚走。那对
 * 「一屏一件事」的设置页没问题，但对话页不行 —— 用户一进来就在最底部，
 * 顶栏要是滚走了，他连「设置在哪」都看不到。
 *
 * 所以这里保留固定顶栏，用上面那条裁剪把边界守住。
 */
@Composable
fun M3eScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottom: (@Composable () -> Unit)? = null,
    bottomOverlay: Boolean = false,
    /**
     * 顶栏形态。
     *
     * `false`（默认）：返回在左、标题居中、动作在右。
     * `true`：动作收成一排**居中**的圆图标，标题变成下面一行小字。
     */
    stackedBar: Boolean = false,
    /** 内容区横向内收。列表页传 0（列表自己带内边距），表单页用默认值。 */
    horizontalInset: Dp = M3ePageInset,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bg = MaterialTheme.colorScheme.background

    AppScaffold(
        modifier = Modifier.fillMaxSize(),
        timeText = { TimeText() },
        containerColor = bg,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 给浮层的时间文字让位。见 M3eTimeTextInset 的注释。
            Spacer(Modifier.height(M3eTimeTextInset))

            if (stackedBar) {
                StackedBar(title = title, onBack = onBack, actions = actions)
            } else {
                CenteredBar(
                    title = title,
                    subtitle = subtitle,
                    onBack = onBack,
                    actions = actions,
                    inset = horizontalInset,
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    /*
                     * 这一行是本次修复的核心。
                     *
                     * 少了它，滚上去的列表项会画到顶栏和时间文字上面 ——
                     * 而且**不会报错**，只是看着像布局乱了。
                     */
                    .clipToBounds(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = horizontalInset),
                    content = content,
                )

                /*
                 * 顶栏下沿的一条渐隐。
                 *
                 * `clipToBounds` 能让滚上去的内容停在边界上，但**停得很硬** ——
                 * 卡片上边缘会被一刀切平，看着像被裁坏了。
                 *
                 * 铺一条从页面底色到透明的竖渐变，被切的那一截就"化"进背景里。
                 * 12dp 是刚好够遮住切口、又不至于吃掉一行正文的高度。
                 */
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(bg, bg.copy(alpha = 0f)),
                            )
                        ),
                )

                if (bottom != null && bottomOverlay) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        bottom()
                    }
                }
            }

            if (bottom != null && !bottomOverlay) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = horizontalInset, end = horizontalInset, bottom = 6.dp),
                ) {
                    bottom()
                }
            }
        }
    }
}

/**
 * 标准 Wear 顶栏：返回在左、标题居中、动作在右。
 *
 * 标题用 `Box` 铺满整行再居中，而不是「weight(1f) 后居中」——
 * 后者会被两侧按钮的宽度推偏：左边一颗返回、右边一颗设置，标题就会右移 22dp，
 * 左右不对称在圆屏上非常明显。
 */
@Composable
private fun CenteredBar(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
    inset: Dp,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .padding(horizontal = inset),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (onBack != null) {
            Box(modifier = Modifier.align(Alignment.CenterStart)) {
                M3eBackButton(onClick = onBack)
            }
        }

        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

/**
 * 动作多的页面用的顶栏：一排居中的圆图标 + 下方一行标题小字。
 *
 * 四颗 40dp 的图标加间隔是 172dp，圆屏顶栏的弦宽正好是这个量级 ——
 * 再塞一个标题就必然互相压。把标题降到第二行，两组各占一行，谁都不挤。
 *
 * 返回键也排进这一行（放在最左）。它是「动作」里最先被找的一个，
 * 混在一排 26dp 的图标里也认得出。
 */
@Composable
private fun StackedBar(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            // 间距 0：四颗 44dp 已经是 176dp，圆屏那一行的弦宽只有 189dp，
            // 再留间隔就顶到圆周了。图标本身只有 26dp，相邻之间视觉上仍有 18dp 空隙。
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) M3eBackButton(onClick = onBack)
            actions()
        }
        if (title.isNotBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * M3E 的返回键。
 *
 * ## 为什么是无底色的图标
 *
 * 灰底圆形（`FilledTonalIconButton`）在手机上是 FAB 的语言，出现在顶栏
 * 左上角会被读成「一颗悬在标题旁边的按钮」，而不是「返回」。
 *
 * Wear 的顶栏动作一律**无容器**：只有 26dp 的图标 + 44dp 的命中区。
 */
@Composable
fun M3eBackButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = LocalStrings.current.back,
            modifier = Modifier.size(26.dp),
        )
    }
}

/**
 * M3E 的顶栏图标按钮。
 *
 * 用 [IconButton] 而不是自己画：它会自动处理涟漪、最小触摸区与无障碍语义。
 *
 * 44dp 命中区 + 26dp 图标是 Wear 的 `IconSize` 规格。
 */
@Composable
fun M3eIconTap(
    icon: ImageVector,
    contentDescription: String?,
    enabled: Boolean = true,
    onClick: () -> Unit,
    /**
     * 命中区直径。默认 44dp（Wear 的常规值）。
     *
     * 五颗动作时传 36dp：5×44=220dp 已经超过圆屏那一行的弦宽（约 189dp），
     * 两侧的图标会被圆周切掉。
     */
    diameter: Dp = 44.dp,
    /** 图标边长，默认 26dp（Wear M3 规范） */
    iconSize: Dp = 26.dp,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(diameter),
        enabled = enabled,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * M3E 底部的主操作按钮。
 *
 * 圆屏底部的 FAB 有硬要求：**直径不能超过 0.20d**。再大就会顶到圆周。
 */
@Composable
fun M3eFab(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    if (danger) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = modifier.size(52.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(26.dp),
            )
        }
        return
    }
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(52.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(26.dp),
        )
    }
}

/** 一组横向紧挨着的 M3E 按钮：Expressive 的按钮组形态 */
@Composable
fun M3eButtonRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 6.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
