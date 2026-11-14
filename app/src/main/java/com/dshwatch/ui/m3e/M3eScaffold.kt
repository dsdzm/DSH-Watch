package com.dshwatch.ui.m3e

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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.dshwatch.ui.theme.LocalDeviceProfile

/**
 * M3E 的页面骨架。
 *
 * ## 和经典骨架的根本差别：不再自己算几何
 *
 * 经典那套（`CircleScaffold`）是「按弦长反推内容矩形」：算圆顶、算 0.69d、
 * 算某一行离圆心的距离，把内容裁进一个内接矩形里。好处是每屏能多放一行，
 * 代价是**所有比例都是硬编码的**，换一块屏幕就得重算。
 *
 * M3E 这套走 Wear 官方的结构：
 *
 * ```
 * AppScaffold            ← 底色、时间文字、圆形安全区
 *   ├─ TimeText          ← 屏幕顶部那条时间（Wear 的签名元素）
 *   ├─ 顶栏              ← 返回 + 标题 + 动作
 *   └─ 内容              ← 页面自己的 WearList / 滚动容器
 * ```
 *
 * 内容不再裁剪，超出圆周的部分靠 `WearList` 的逐项变换（越靠边越小、越淡）
 * 收进来。这是官方做法，也是 M3E 的观感来源：**滚动中的列表项会呼吸**。
 *
 * ## 顶栏为什么是「标题居中」而不是「左标题 + 右图标」
 *
 * 上一版顶栏照搬了手机的 `TopAppBar`：标题左对齐、动作挤在右侧一行。
 * 那种排布在手表上会立刻读成「手机界面缩小版」—— 手机之所以能左标题，
 * 是因为横屏有 360dp 宽、可以一边标题一边四颗图标；手表最宽也就 220dp，
 * 两边同时占位的代价是标题被压成三四个字。
 *
 * Wear 的答案是把标题放到**正中间**、动作退到两侧，两边各留 44dp 的命中区。
 * 视线先落在中间，再往两边找按钮 —— 这是腕表的读法。
 *
 * 动作多于两颗时用 [M3eScaffold] 的 `stackedBar`：图标收成一排居中，
 * 标题降为下面一行小字。对话页是这种形态（历史 / 新建 / 切换模式 / 设置 四颗）。
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
     * `false`（默认）：Wear 的标准 AppBar —— 返回在左、标题居中、动作在右。
     * 适用于设置页、列表页这类「一屏一件事」的页面。
     *
     * `true`：动作收成一排**居中**的圆图标，标题变成下面一行小字。
     * 适用于动作多的页面（对话页）。四颗图标加一个居中标题在 220dp 上排不下，
     * 硬挤的结果就是标题只剩两个字的宽度。
     */
    stackedBar: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bg = androidx.compose.material3.MaterialTheme.colorScheme.background
    val profile = LocalDeviceProfile.current
    /*
     * 圆屏的横向内收。
     *
     * 不再是经典那套「按弦长逐行算」，而是取一个固定值：顶栏那一行的弦宽最窄，
     * 按它收一次就够 —— 下面的内容只会更宽，不会更窄。省掉逐行几何的好处是
     * 页面在滚动时宽度恒定，不会出现「滚到中间字宽变了」的抖动。
     */
    val inset = profile.cornerInsetDp.dp

    AppScaffold(
        modifier = Modifier.fillMaxSize(),
        timeText = { TimeText() },
        containerColor = bg,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            /*
             * TimeText 是画在顶部的浮层，不参与布局。
             *
             * 所以这里必须显式让出一段高度，否则第一行内容会被时间文字压住。
             * 24dp 是 TimeText 的默认高度；不能让给它的部分跟着界面缩放变 ——
             * 时间文字自己会缩，这里只是让位。
             */
            Spacer(Modifier.height(24.dp))

            if (stackedBar) {
                StackedBar(title = title, onBack = onBack, actions = actions)
            } else {
                CenteredBar(
                    title = title,
                    subtitle = subtitle,
                    onBack = onBack,
                    actions = actions,
                    inset = inset,
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = inset),
                    content = content,
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
                        .padding(start = inset, end = inset, bottom = 6.dp),
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
    inset: androidx.compose.ui.unit.Dp,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .padding(horizontal = inset),
    ) {
        /*
         * 标题铺满整行、居中，两侧用 48dp 的 padding 避让按钮。
         * 这样即使左右按钮数量不等，标题的几何中心也和屏幕中心重合。
         */
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
 * 混在一排 26dp 的图标里也认得出；单独占一行反而会把标题挤得更低。
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
 * 上一版是 `FilledTonalIconButton`（灰底圆形）。那种「浮起来的圆」在手机上
 * 是 FAB 的语言，出现在顶栏左上角会被读成「一颗悬在标题旁边的按钮」，
 * 而不是「返回」。
 *
 * Wear 的顶栏动作一律**无容器**：只有 26dp 的图标 + 44dp 的命中区。
 * 底色留给真正需要强调的东西（主按钮、FAB），顶栏靠图标本身被识别。
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
 * 用 [IconButton] 而不是自己画：它会自动处理涟漪、最小触摸区与无障碍语义，
 * 这是「用 M3E 组件」的实际好处 —— 观感之外，交互细节也一起拿过来了。
 *
 * 44dp 命中区 + 26dp 图标是 Wear 的 `IconSize` 规格。上一版是 34/18 —— 图标
 * 比规范小了一圈，在 1.4 英寸的屏上看起来像「一排标点符号」。
 */
@Composable
fun M3eIconTap(
    icon: ImageVector,
    contentDescription: String?,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        enabled = enabled,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(26.dp),
        )
    }
}

/**
 * M3E 底部的主操作按钮（对话页的「新对话 / 输入」这类）。
 *
 * 单独抽出来是因为圆屏底部的 FAB 有个硬要求：**直径不能超过 0.20d**。
 * 再大就会顶到圆周，横向弦长不够，按钮两侧会被切掉。
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
    spacing: androidx.compose.ui.unit.Dp = 6.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
