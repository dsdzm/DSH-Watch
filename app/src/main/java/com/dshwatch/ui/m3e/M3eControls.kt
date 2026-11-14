package com.dshwatch.ui.m3e

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.MaterialTheme as M3
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme as W3
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.Slider
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton

/**
 * M3E 的「一行入口卡片」。
 *
 * 对应经典界面的 `WearCardRow`：图标 + 标题 + 副标题 + 右侧箭头。
 *
 * ## 为什么不用 Row 自己画
 *
 * `Card` 提供的不只是圆角底色：它带 Expressive 的**按下形变**
 * （按住时形状圆角变大、松手回弹）、涟漪从手指落点扩散、以及
 * `SurfaceTransformation` 在高亮/聚焦时的整体位移。
 *
 * 自己画 Row 只能做到「有背景 + 点了有回调」，那正是两套界面看起来
 * 「一个是原生应用、一个是仿的」的差别所在。
 */
@Composable
fun M3eCardRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val body: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                // 24dp 是 Wear 列表项里图标的标准尺寸，20dp 会显得比文字轻
                modifier = Modifier.size(24.dp),
                tint = W3.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = M3.typography.bodyMedium,
                    color = W3.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = M3.typography.labelSmall,
                        color = W3.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = M3.typography.labelMedium,
                    color = W3.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            } else if (onClick != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = W3.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        ) { body() }
    } else {
        // 同 [M3eSettingRow]：不可点 ≠ 不可用，别压低整块内容的透明度
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(W3.colorScheme.surfaceContainer),
        ) {
            Box(Modifier.padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) { body() }
        }
    }
}

/**
 * 一组设置项的外壳。
 *
 * 经典界面里它是一张**大卡片**，里面用分隔线切成多行；M3E 里反过来 ——
 * 每一项自己是独立卡片，之间留 6dp。这是 Expressive 的「一组胶囊」形态：
 * 相邻卡片之间看得见缝，单独一项就是一颗完整的圆角块。
 *
 * 所以这个容器只剩「纵向排列」一个职责，不再画背景。
 */
@Composable
fun M3eSettingCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/**
 * 一行设置文本（不带卡片背景）。
 *
 * 用于「卡片内部的多行文字」：标题 + 副标题 + 右侧当前值。
 * 与 [M3eSettingRow] 的区别是它**自己不是卡片**——放在 [M3eSettingCard] 里
 * 就是一整块内容，放在外面就是裸文字。
 */
@Composable
fun M3eSettingRow(
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    showDivider: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val body: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = M3.typography.bodyMedium,
                    color = W3.colorScheme.onSurface,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = M3.typography.labelSmall,
                        color = W3.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = M3.typography.labelMedium,
                    color = W3.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
    }
    if (onClick != null) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), content = { body() })
    } else {
        // 不可点的行不能走 `Card(enabled = false)`：那会把内容压到 38% 透明，
        // 读起来像"这一项坏了"。它只是没有动作，不是不可用 —— 所以画一块
        // 同样形状、同样容器色的静态底。
        M3eStaticSurface { body() }
    }
}

/** 一块不可点击的容器，形状与 [Card] 一致 */
@Composable
private fun M3eStaticSurface(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(26.dp))
            .background(W3.colorScheme.surfaceContainerHigh),
    ) {
        content()
    }
}

/**
 * 带开关的一行。
 *
 * 直接用它自己的 `SwitchButton`：这个组件的开关不是"一个开关控件贴在行尾"，
 * 而是**整行就是开关**——按下整块卡片，拇指从左边滑到右边，文字跟着变色。
 * 这正是 Expressive 的交互语言，也是自己拼 Row + Switch 拼不出来的部分。
 */
@Composable
fun M3eSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = Modifier.fillMaxWidth(),
        label = {
            Text(
                text = title,
                style = M3.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        secondaryLabel = subtitle?.let {
            {
                Text(
                    text = it,
                    style = M3.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    )
}

/**
 * 分段选择器。
 *
 * 用 [ButtonGroup]：这是 M3E 最标志性的组件。组内按钮**共享宽度**、
 * 相邻处圆角压平，看起来是一条连续的控件而不是三颗独立按钮。
 *
 * 选中态用 `Button`（实心主色），未选中用 `FilledTonalButton`（浅一档）——
 * 两者同属 ButtonGroup，宽度会自动对齐，不需要把 `weight` 算成 1/n。
 */
@Composable
fun M3eSegmentedRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    if (options.isEmpty()) return
    ButtonGroup(
        Modifier.fillMaxWidth(),
        // 组内间距 4dp：Expressive 的按钮组靠"挨得近"成为一个整体，
        // 拉开到 8dp 以上就变成三颗独立按钮了
        4.dp,
        0.dp,
        PaddingValues(0.dp),
        Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            // InteractionSource 必须 remember：它承载"按住了"的状态，
            // 每次重组新建一个的话，形变动画会在手指还没抬起来时被重置
            val source = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            /*
             * 高度给到 48dp 而不是默认 52dp。
             *
             * 52dp 是单个按钮的高度；一组横排按钮并排时，视觉重心会往下压，
             * 52 在圆屏上会把下面的内容挤走。48dp 仍然是规范里触摸目标的下限，
             * 所以"变小"只发生在视觉上，命中率没变。
             */
            val itemModifier = Modifier
                .weight(1f)
                .height(48.dp)
                .animateWidth(source)
            // 标签用 labelMedium（15sp）而不是按钮的 labelLarge（20sp）：
            // 一组里要放 2–3 项，20sp 在三分之一屏宽里会直接省略成「…」
            val itemLabel: @Composable RowScope.() -> Unit = {
                Text(
                    text = label,
                    style = W3.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            if (selected) {
                Button(
                    onClick = { onSelect(index) },
                    modifier = itemModifier,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    label = itemLabel,
                )
            } else {
                FilledTonalButton(
                    onClick = { onSelect(index) },
                    modifier = itemModifier,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    label = itemLabel,
                )
            }
        }
    }
}

/** 小标签。选中 = 实心主色，未选中 = 描边 */
@Composable
fun M3eChip(
    text: String,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    /*
     * 32dp —— 这是 Wear 上 chip 的高度（按钮 52、chip 32）。
     *
     * 标签用 labelSmall（13sp）：chip 是"分类标记"，不是动作，
     * 尺寸跟正文拉开才有层次。触摸区由 Compose 的最小交互尺寸兜住，
     * 视觉上小、点起来仍然是 48dp。
     */
    if (selected) {
        FilledTonalButton(
            onClick = onClick ?: {},
            enabled = onClick != null,
            modifier = Modifier.height(32.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            label = {
                Text(
                    text = text,
                    style = W3.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
    } else {
        OutlinedButton(
            onClick = onClick ?: {},
            enabled = onClick != null,
            modifier = Modifier.height(32.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            border = BorderStroke(1.dp, W3.colorScheme.outlineVariant),
            label = {
                Text(
                    text = text,
                    style = W3.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
    }
}

/**
 * 主按钮。
 *
 * ## 尺寸是查出来的，不是估的
 *
 * Wear M3 的按钮高度就是 [ButtonDefaults.Height]（52dp），标签是
 * `labelLarge`（20sp），图标 26dp。这三个数决定了「这看起来像不像手表」：
 *
 * - **52dp** 是戴着手套、手指有点湿时也点得中的高度；手机上 40dp 的那套搬过来
 *   会显得又矮又挤。
 * - **20sp** 的标签不是"字号偏大"，而是手表上的正文尺寸：屏幕离眼睛近，
 *   字必须比手机上大。用 14sp 会让整屏幕看起来像缩略图。
 * - **26dp 图标**同理，18dp 在 52dp 的按钮里会显得孤零零。
 *
 * ## 为什么用 icon / label 两个槽，而不是自己拼 Row
 *
 * 组件内部会算「图标占左边多少、标签在剩下的空间里居中」。
 * 自己拼 Row 只能做到「图标 + 左对齐文字」，那正是手机按钮的样子。
 */
@Composable
fun M3ePrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(ButtonDefaults.Height),
        enabled = enabled,
        icon = {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
            }
        },
        label = {
            Text(
                text = text,
                style = W3.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        },
    )
}

/**
 * 次级按钮：描边、同样 52dp。
 *
 * 和主按钮**同样的高度**是刻意的。手表上「次要」不等于「更小」——
 * 它仍然必须点得中，只是视觉权重低一档（描边 vs 实心）。
 * 手机上那个「主按钮大、次按钮小」的层级，搬到手表上就变成了
 * 「次按钮点不到」。
 */
@Composable
fun M3eGhostButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(ButtonDefaults.Height),
        enabled = enabled,
        border = BorderStroke(
            1.dp,
            if (danger) W3.colorScheme.error else W3.colorScheme.outlineVariant,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (danger) W3.colorScheme.error else W3.colorScheme.onSurface,
        ),
        icon = {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
            }
        },
        label = {
            Text(
                text = text,
                style = W3.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        },
    )
}

/** 空态 */
@Composable
fun M3eEmptyState(title: String, hint: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = M3.typography.titleSmall,
            color = W3.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (!hint.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = hint,
                style = M3.typography.bodySmall,
                color = W3.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 对话框。
 *
 * 用 Wear 的 [AlertDialog]，不是手机的 `AlertDialog`：前者会把内容做成
 * **可滚动的圆形卡片**，在圆屏上边缘永远收在圆周内；后者是矩形，
 * 顶部那行标题会被圆周切掉。
 *
 * [buttons] 整块塞进 `confirmButton` 槽 —— 那个槽只是一块 Composable 区域，
 * 不强制只有一个按钮。这样调用方仍然可以自由排布「取消 / 确定」，
 * 而外框、遮罩、动画都由 M3E 组件负责。
 */
@Composable
fun M3eDialog(
    onDismissRequest: () -> Unit,
    title: String? = null,
    maxContentHeight: Int = 300,
    content: @Composable ColumnScope.() -> Unit,
    buttons: @Composable () -> Unit,
) {
    AlertDialog(
        visible = true,
        onDismissRequest = onDismissRequest,
        confirmButton = { buttons() },
        title = {
            if (title != null) {
                Text(
                    text = title,
                    style = W3.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        text = {
            /*
             * 高度上限压到 190dp。
             *
             * `text` 槽是固定区域，不像 `content` 那样能滚 —— 给它 300dp
             * 在直径 400dp 的表上会直接顶到圆周外面，底部按钮被切掉。
             * 190dp 是「标题 + 按钮 + 上下留白」之后还能落进圆里的余量。
             *
             * 内容自己带滚动（`scrollable = false` 那类调用）时，这里仍然只是
             * 一个上限，不会产生第二层滚动容器。
             */
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = minOf(maxContentHeight, 190).dp),
                content = content,
            )
        },
    )
}

/**
 * 二次确认。
 *
 * ## 只有一个按钮，这是规范不是偷懒
 *
 * Wear 的对话框里**没有「取消」按钮**：关掉它的方式是下滑，或者按表冠/返回。
 * 手机上的「取消 / 确定」并排是双指操作留下的习惯，在 1.5 英寸的屏幕上
 * 两颗并排按钮各自只有 60dp 宽，命中率会掉一半。
 *
 * 所以这里只画确认那一个（52dp、整宽），并在正文下面补一句「下滑取消」——
 * 提示比多画一个按钮便宜，也比用户以为退不出去好。
 */
@Composable
fun M3eConfirmDialog(
    title: String,
    text: String,
    confirmText: String? = null,
    destructive: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = com.dshwatch.ui.i18n.LocalStrings.current
    AlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ButtonDefaults.Height),
                colors = if (destructive) {
                    // 破坏性动作用 errorContainer 而不是纯 error：
                    // 纯红实心在 OLED 表上整块发光，反而看不清字
                    ButtonDefaults.buttonColors(
                        containerColor = W3.colorScheme.errorContainer,
                        contentColor = W3.colorScheme.onErrorContainer,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
                label = {
                    Text(
                        text = confirmText ?: if (destructive) s.delete else s.ok,
                        style = W3.typography.labelLarge,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                },
            )
        },
        title = {
            Text(
                text = title,
                style = W3.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = text,
                    style = W3.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "下滑取消",
                    style = W3.typography.labelSmall,
                    color = W3.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
    )
}

/**
 * 滑块。
 *
 * M3E 的 Slider 自带上下两个图标位（减/加），按下时轨道会变粗、
 * 拇指会放大。这些都是组件内部状态，不需要调用方管。
 */
@Composable
fun M3eSlider(
    value: Float,
    onChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    label: String,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    valueText: String? = null,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = M3.typography.bodyMedium,
                color = W3.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (valueText != null) {
                Text(
                    text = valueText,
                    style = M3.typography.labelMedium,
                    color = W3.colorScheme.primary,
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onChange,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            valueRange = valueRange,
        )
    }
}

/**
 * 加减一行。
 *
 * 用两颗 `IconButton` 而不是自绘：M3E 的图标按钮有固定的 48dp 触摸区和
 * 按下形变，戴手套时命中率明显高于裸图标。
 */
@Composable
fun M3eStepperRow(
    title: String,
    value: String,
    onDecrease: (() -> Unit)? = null,
    onIncrease: (() -> Unit)? = null,
    subtitle: String? = null,
) {
    M3eStaticSurface {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = M3.typography.bodyMedium,
                    color = W3.colorScheme.onSurface,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = M3.typography.labelSmall,
                        color = W3.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(
                onClick = { onDecrease?.invoke() },
                enabled = onDecrease != null,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = value,
                style = M3.typography.labelLarge,
                color = W3.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            IconButton(
                onClick = { onIncrease?.invoke() },
                enabled = onIncrease != null,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** 勾选态的小图标，给「已选」这类行尾用 */
@Composable
fun M3eCheckMark(visible: Boolean) {
    if (!visible) return
    Icon(
        imageVector = Icons.Filled.Check,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
        tint = W3.colorScheme.primary,
    )
}

/** 一行说明文字（无卡片），常用于卡片内补充 */
@Composable
fun M3eCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = M3.typography.labelSmall,
        color = W3.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** 分组标题 */
@Composable
fun M3eSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = M3.typography.labelMedium,
        color = W3.colorScheme.primary,
        modifier = modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** 一个空箱子，只为占位（对齐用） */
@Composable
internal fun M3eGap(height: Int) = Spacer(Modifier.height(height.dp))

/** 未使用告警的抑制：保留 import 供后续扩展 */
private val unusedBox: (@Composable () -> Unit)? = null

/**
 * 对话气泡。
 *
 * ## 为什么 M3E 下不看 `layout` 设置
 *
 * 经典界面给了三种排版（气泡 / 卡片 / 极简），那是**在没有 M3E 的情况下**
 * 自己凑出来的层次手段。M3E 里这件事由组件本身解决：`Card` 的容器色、
 * 圆角、按下形变已经把「这是一条消息」说清楚了，再加一层「卡片模式 / 极简模式」
 * 只是在同一套视觉语言上叠补丁。
 *
 * 所以 M3E 下统一走这一种：**标签在上、卡片在下**。用户选气泡还是卡片，
 * 切回经典界面依然生效。
 */
@Composable
fun M3eBubble(
    isUser: Boolean,
    label: String,
    headerTrailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = M3.typography.labelSmall,
                color = if (isUser) W3.colorScheme.primary else W3.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(4.dp))
            headerTrailing()
        }
        /*
         * 消息气泡用静态容器，不用 `Card(enabled = false)`。
         *
         * 那会把整条消息压到 38% 透明 —— 表现是「一进 M3E 模式，历史消息全变灰」。
         * 气泡本来也不需要按下的形变：它是内容，不是按钮。
         */
        Box(
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.96f else 1f)
                .clip(RoundedCornerShape(26.dp))
                .background(
                    if (isUser) W3.colorScheme.primaryContainer else W3.colorScheme.surfaceContainerHigh,
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides if (isUser) {
                    W3.colorScheme.onPrimaryContainer
                } else {
                    W3.colorScheme.onSurface
                },
            ) {
                Column(content = content)
            }
        }
    }
}

/**
 * 对话框底部的一个动作。
 *
 * 对应经典界面的 `GlassDialogButton`。区别在高度：那边是 34dp 的胶囊，
 * 这边 52/48dp —— 对话框是"必须点准"的场景，尤其是确认删除。
 *
 * [primary] 为 true 用实心主色，否则用 Wear 的 [TextButton]：
 * 它是"只剩文字"的按钮，仍然整宽、仍然 48dp 高，不是手机上那个
 * 挤在右下角的小文字链接。
 */
@Composable
fun M3eDialogAction(
    label: String,
    primary: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    if (primary) {
        M3ePrimaryButton(text = label, enabled = enabled, onClick = onClick)
        return
    }
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(ButtonDefaults.CompactButtonHeight),
    ) {
        Text(
            text = label,
            style = W3.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
