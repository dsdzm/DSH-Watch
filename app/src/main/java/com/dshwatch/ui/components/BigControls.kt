package com.dshwatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshwatch.ui.theme.WatchDimens

/**
 * 手表上「够大、够统一」的一组基础控件。
 *
 * ## 为什么单独开一个文件
 *
 * v1.13 之前，按钮散落在各处手搭：有的用 `PrimaryButton`，有的直接 `Box + tap`，
 * 高度 30/38/40dp 混着来；开关有的用 `SwitchRow`，有的用 `SettingRow` 配一个
 * "✓ / —" 的文字（看板娘就是后者）—— 同一个「开 / 关」有两套长相。
 * 用户反馈里的"控件 UI 不统一"指的就是这个。
 *
 * 这里只提供**一种**可点控件的写法，其余地方逐步收敛过来。
 */

/**
 * 统一的可点区域修饰符。
 *
 * 两个作用：保证不小于 [WatchDimens.touchMin]（48dp，Material 3 的最小触控目标），
 * 并统一圆角与按下反馈。任何可点元素都应该先过它，再叠自己的背景色。
 */
fun Modifier.watchTouchTarget(
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(WatchDimens.cardRadius),
    minSize: androidx.compose.ui.unit.Dp = WatchDimens.touchMin,
): Modifier = this.heightIn(min = minSize).clip(shape)

/**
 * 主按钮：实心主色、48dp 高、圆角，图标 + 文字居中。
 *
 * 与 [PrimaryButton] 的区别是尺寸与圆角随 [WatchDimens] 走，
 * 且文字用 titleSmall（15sp）而不是 bodyMedium —— 按钮上的字要一眼读清。
 */
@Composable
fun WatchButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    container: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(WatchDimens.buttonHeight)
            .clip(RoundedCornerShape(WatchDimens.cardRadius))
            .background(if (enabled) container else container.copy(alpha = 0.38f))
            .then(if (enabled) Modifier.tap(onClick = withHaptic(onClick)) else Modifier)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** 次要按钮：描边、透明底 */
@Composable
fun WatchGhostButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(WatchDimens.buttonHeight)
            .clip(RoundedCornerShape(WatchDimens.cardRadius))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(WatchDimens.cardRadius),
            )
            .then(if (enabled) Modifier.tap(onClick = withHaptic(onClick)) else Modifier)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 单选行：一行一个选项，右边一个圆点。
 *
 * 取代「三格分段条塞满一行」的写法。分段条在 200dp 宽的圆屏上，每个格子
 * 只有 60dp 出头，中文两三个字就被挤成省略号；而它**本身又没有滚动**，
 * 用户反馈的「字号滑块选不到标准」正是这一类问题的极端表现。
 * 竖排列表每项 48dp 高、可滚动、可被表冠聚焦，选择起来稳得多。
 */
@Composable
fun ChoiceRow(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badge: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = WatchDimens.touchMin)
            .tap(onClick = withHaptic(onClick))
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!badge.isNullOrBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        RadioDot(selected)
    }
}

/** 单选圆点：选中实心 + 外圈，未选中只留细描边 */
@Composable
fun RadioDot(selected: Boolean, modifier: Modifier = Modifier) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .border(2.dp, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/*
 * 分节小标题直接用 [SectionHeader]（Common.kt）。
 *
 * 这里一度也写了一个同名同签名的版本 —— 同一模块里两个 `fun SectionHeader`
 * 会直接编译失败（Platform declaration clash）。样式差异（字号 12sp + 半粗）
 * 已经合并进 Common.kt 那一个，全 App 只有一处定义。
 */

/** 说明文字：统一 11sp + 次要色，避免各处手写 bodySmall/labelSmall 混用 */
@Composable
fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** 卡片：统一圆角、统一内边距，替代各屏手写的 Column + clip + background */
@Composable
fun WatchSection(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WatchDimens.cardRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(contentPadding),
        content = content,
    )
}
