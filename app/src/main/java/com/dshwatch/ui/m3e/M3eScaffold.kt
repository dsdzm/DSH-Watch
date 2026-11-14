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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
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
 * ## 为什么内容区仍然给了 weight(1f) 而不是固定高度
 *
 * 经典的固定高度是为了「底栏不被压出圆外」。M3E 里底栏是浮起来的 FAB 或
 * 一整条按钮组，两者都不依赖内容高度；给 weight 反而让内容能真正撑满。
 * 需要留出 FAB 空间的页面自己加 `contentPadding`（对话页就是这么做的）。
 */
@Composable
fun M3eScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottom: (@Composable () -> Unit)? = null,
    bottomOverlay: Boolean = false,
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = inset, end = inset, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    M3eBackButton(onClick = onBack)
                    Spacer(Modifier.width(6.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                actions()
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
 * M3E 的返回键：填充色调的圆形图标按钮。
 *
 * 圆形是刻意的 —— 圆屏的左上角是整块屏幕最窄的地方，方形按钮在这里会
 * 让人以为「按钮被切掉了」，圆形则天然贴合。
 */
@Composable
fun M3eBackButton(onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = LocalStrings.current.back,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * M3E 的顶栏图标按钮。
 *
 * 用 [IconButton] 而不是自己画：它会自动处理涟漪、最小触摸区与无障碍语义，
 * 这是「用 M3E 组件」的实际好处 —— 观感之外，交互细节也一起拿过来了。
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
        modifier = Modifier.size(34.dp),
        enabled = enabled,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
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
        androidx.wear.compose.material3.FilledTonalIconButton(
            onClick = onClick,
            modifier = modifier.size(52.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        }
        return
    }
    androidx.wear.compose.material3.FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(52.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
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
