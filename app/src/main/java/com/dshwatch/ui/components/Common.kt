package com.dshwatch.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshwatch.domain.model.TokenUsage
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.theme.LocalDeviceProfile
import com.dshwatch.ui.theme.LocalGlass
import com.dshwatch.ui.theme.LocalLayoutTheme
import com.dshwatch.ui.theme.LocalPerf
import com.dshwatch.ui.theme.WatchDimens
import com.dshwatch.util.Fmt
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember

// ---------------------------------------------------------------------------
// 页面骨架
// ---------------------------------------------------------------------------

/**
 * 圆角安全内边距。
 *
 * 屏幕的物理 R 角系统不会告诉我们半径（DisplayCutout 只报刘海/挖孔），
 * 所以用 DeviceProfile 估算出的 cornerInset 做内收。
 * 关键点：**纵向内边距比横向更重要**——内容离屏幕顶边越远，需要的横向内收越小
 * （推导见 DeviceProfile 注释），所以这里总是给一定的纵向 padding。
 */
@Composable
fun Modifier.cornerSafe(
    vertical: Dp = WatchDimens.barVerticalPadding,
    horizontalScale: Float = 1f,
): Modifier {
    val inset = LocalDeviceProfile.current.cornerInsetDp.dp * horizontalScale
    return this.padding(horizontal = inset, vertical = vertical)
}

@Composable
fun WatchScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    bottom: (@Composable () -> Unit)? = null,
    bottomOverlay: Boolean = false,
    /**
     * 内容是否吃满宽度。只有列表页（`WearList`）该传 true ——
     * 它靠逐项缩放淡出保证不越圆，再套内接矩形就白窄了。
     */
    fullBleed: Boolean = false,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    /*
     * 布局主题在这里分派 —— 这是**唯一**的分派点。
     *
     * 20 多个页面都调这个函数，它们只关心「给我一个带标题和底栏的壳」。
     * 把两套骨架放在这后面，页面签名一个都不用改，也就不可能在某个页面上
     * 出现「一半手表一半手机」的拼贴。
     *
     * 手表这一侧是官方 `ScreenScaffold`，不是自绘骨架 —— 详见 WearScreen 的注释。
     */
    if (com.dshwatch.domain.model.LayoutThemes.isWatchLayout(LocalLayoutTheme.current)) {
        com.dshwatch.ui.wear.WearScreen(
            title = title,
            onBack = onBack,
            subtitle = subtitle,
            actions = actions,
            bottom = bottom,
            bottomOverlay = bottomOverlay,
            fullBleed = fullBleed,
            scrollable = scrollable,
            content = content,
        )
        return
    }
    val rowActions: @Composable RowScope.() -> Unit = actions ?: {}
    /*
     * 手机/大屏布局：1.15.22 及以前那套。
     *
     * 圆屏走 CircleScaffold —— 不是「同一套换个 padding」：顶栏必须整体下压
     * （最顶一行的可用宽度是 0），横向边距必须由弦长算出，图标必须收到 40dp。
     * 三条都是硬约束，见 CircleScaffold 的注释。
     */
    if (com.dshwatch.ui.wear.LocalCircle.current != null) {
        CircleScaffold(
            title = title,
            onBack = onBack,
            subtitle = subtitle,
            actions = rowActions,
            bottom = bottom,
            bottomOverlay = bottomOverlay,
            content = content,
        )
        return
    }
    // 圆屏的可用宽度比同尺寸方屏小得多：屏幕是个内切圆，滚动到上下两端时
    // 左右各要收回近 1/4 的宽度。8dp 在方屏上刚好，在圆屏上会切字。
    val side = WatchDimens.screenPadding
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        WatchTopBar(title = title, onBack = onBack, subtitle = subtitle, actions = rowActions)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = side),
            content = content,
        )
        bottom?.invoke()
    }
}

/**
 * 列表页骨架：内容是一个 [com.dshwatch.ui.wear.WearList]。
 *
 * ## 为什么列表页要单独一个入口
 *
 * `ScreenScaffold` 的滚动指示器和 `EdgeButton` 的收起动画，都靠**和列表同一个**
 * 滚动状态驱动。所以列表的 state 必须由骨架创建、再交给内容 —— 而
 * `WatchScaffold` 的 `content` 是 `ColumnScope`，拿不到这个 state。
 *
 * 手机布局下它就是「WatchScaffold + 一个填满剩余高度的列表」，和原来写法等价；
 * 手表布局下换成 `WearListScreen`（官方 `TransformingLazyColumn` + `ListHeader`）。
 */
@Composable
fun WatchListScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    bottom: (@Composable () -> Unit)? = null,
    state: androidx.wear.compose.foundation.lazy.TransformingLazyColumnState =
        androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState(),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(6.dp),
    content: com.dshwatch.ui.wear.WearListScope.() -> Unit,
) {
    if (com.dshwatch.domain.model.LayoutThemes.isWatchLayout(LocalLayoutTheme.current)) {
        com.dshwatch.ui.wear.WearListScreen(
            title = title,
            onBack = onBack,
            subtitle = subtitle,
            actions = actions,
            bottom = bottom,
            state = state,
            verticalArrangement = verticalArrangement,
            content = content,
        )
        return
    }
    WatchScaffold(
        title = title,
        onBack = onBack,
        subtitle = subtitle,
        actions = actions,
        bottom = bottom,
        fullBleed = true,
    ) {
        com.dshwatch.ui.wear.WearList(
            state = state,
            verticalArrangement = verticalArrangement,
        ) { content() }
    }
}

@Composable
fun WatchTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = WatchDimens.topBarHeight)
            .cornerSafe()
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconTap(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = LocalStrings.current.back,
                onClick = onBack,
            )
            Spacer(Modifier.width(2.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        actions()
    }
}

/** 顶栏 / 工具条上的图标按钮（跟手光晕在 `.tap()` 里，和页面转场无关） */
@Composable
fun IconTap(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    if (com.dshwatch.domain.model.LayoutThemes.isWatchLayout(LocalLayoutTheme.current)) {
        /*
         * Wear M3 的 IconButton：自带 52dp 触控目标与官方形变，
         * 这里收成 40dp 是为了顶栏一行能放下 3~4 颗（206dp 的表盘放不下 4×52）。
         */
        androidx.wear.compose.material3.IconButton(
            onClick = withHaptic(onClick),
            modifier = Modifier.size(40.dp),
            enabled = enabled,
        ) {
            androidx.wear.compose.material3.Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
            )
        }
        return
    }
    Box(
        modifier = Modifier
            .size(WatchDimens.iconButton)
            .clip(CircleShape)
            .tap(enabled = enabled, onClick = withHaptic(onClick)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.35f),
            modifier = Modifier.size(22.dp),
        )
    }
}

/** 统一给点击加一次轻触感；省电档下自动关闭 */
@Composable
fun withHaptic(onClick: () -> Unit): () -> Unit {
    val perf = LocalPerf.current
    val haptic = LocalHapticFeedback.current
    return {
        if (perf.enableHaptics) {
            runCatching { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
        }
        onClick()
    }
}

/**
 * 圆形玻璃图标按钮。
 *
 * 用在卡片上做「次要但常用」的动作 —— 例如酒馆卡上的「新开一个对话」「历史记录」。
 * 40dp 是这颗按钮的甜点尺寸：比 48dp 省 8dp 宽，又远大于裸图标的 24dp 触控区。
 */
@Composable
fun GlassCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .glassSurface(
                shape = CircleShape,
                cornerRadius = size / 2,
                enabled = enabled,
                onClick = if (enabled) withHaptic(onClick) else null,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.35f),
            modifier = Modifier.size(size * 0.48f),
        )
    }
}

// ---------------------------------------------------------------------------
// 通用小组件
// ---------------------------------------------------------------------------

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        // 12sp + 半粗：小屏上不靠字重加粗，标题和正文会长得一样
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp, bottom = 3.dp, start = 2.dp),
    )
}

@Composable
fun SettingCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassSurface(
                shape = RoundedCornerShape(WatchDimens.cardRadius),
                cornerRadius = WatchDimens.cardRadius,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        content = content,
    )
}

/**
 * 独立胶囊设置项（截图风格）。
 *
 * 与 [SettingCard] 包多个 [SettingRow] 不同：每项自己是一块大圆角胶囊，
 * 用于「入口列表」这类一眼扫过的页面（设置主页、关于页）。
 */
@Composable
fun SettingPill(
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(
                shape = RoundedCornerShape(24.dp),
                cornerRadius = 24.dp,
                onClick = onClick?.let { withHaptic(it) },
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    showDivider: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = WatchDimens.touchMin)
                .then(if (onClick != null) Modifier.tap(onClick = withHaptic(onClick)) else Modifier)
                // 手表布局的行距收到 4dp：Wear 的列表节奏比手机紧，
                // 且 48dp 的最小行高已经保证了触控目标
                .padding(
                    vertical = if (
                        com.dshwatch.domain.model.LayoutThemes.isWatchLayout(LocalLayoutTheme.current)
                    ) 4.dp else 7.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            )
        }
    }
}

@Composable
fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    if (com.dshwatch.domain.model.LayoutThemes.isWatchLayout(LocalLayoutTheme.current)) {
        /*
         * 手表布局用官方 SwitchButton：整行就是一个可点的开关卡片，
         * 开关本体在右侧，点击任意处都能切换 —— 这是 Wear 的列表项语义。
         * 自绘的「文字 + M3 Switch」在 206dp 的表盘上会把开关挤成 30dp 宽。
         */
        androidx.wear.compose.material3.SwitchButton(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.fillMaxWidth(),
            label = {
                androidx.wear.compose.material3.Text(text = title, maxLines = 2)
            },
            secondaryLabel = subtitle?.takeIf { it.isNotBlank() }?.let { sub ->
                {
                    androidx.wear.compose.material3.Text(
                        text = sub,
                        maxLines = 2,
                    )
                }
            },
        )
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = WatchDimens.touchMin)
            .tap(onClick = withHaptic { onCheckedChange(!checked) })
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        M3Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 开关。
 *
 * 玻璃主题下用自己画的 [GlassSwitch] —— M3 的 Switch 是两段纯色：
 * 轨道 primary、滑块白圆，放进玻璃卡片里就是两块塑料贴片，
 * 用户截图里的「开关没做成玻璃」指的就是这个。
 *
 * 非玻璃主题仍然走 M3，一行都不多 —— M3E 的开关本来就该长成 M3 的样子。
 */
@Composable
fun M3Switch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    if (LocalGlass.current) {
        GlassSwitch(checked = checked, onCheckedChange = onCheckedChange)
        return
    }
    // 直接用 MD3 Switch，但缩小到手表尺寸
    androidx.compose.material3.Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = Modifier
            .size(width = 40.dp, height = 24.dp),
        thumbContent = if (checked) {
            {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                )
            }
        } else null,
    )
}

@Composable
fun SegmentedRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val glass = LocalGlass.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            // 玻璃主题下用很淡的白：分段条本身是「凹槽」，不能和里面的选中块一样亮，
            // 否则选中态分不出来
            .background(
                if (glass) Color.White.copy(alpha = 0.07f)
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            // 选中块是玻璃，反馈（形变 + 光）由它自己画；
            // 未选中的块没有玻璃，反馈交给 tap()。
            // 共享同一个 interaction，两边的按压状态才不会各走各的
            val src = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .then(
                        if (glass) {
                            // 选中块才是真正的玻璃；未选中的块什么都不画，透出凹槽
                            if (selected) {
                                Modifier.glassSurface(
                                    shape = RoundedCornerShape(12.dp),
                                    cornerRadius = 12.dp,
                                    interaction = src,
                                )
                            } else {
                                Modifier.clip(RoundedCornerShape(12.dp))
                            }
                        } else {
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                        },
                    )
                    .tap(
                        // 选中块已有玻璃反馈，别再叠一层
                        quiet = glass && selected,
                        onClick = withHaptic { onSelect(index) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                    color = when {
                        selected && glass -> MaterialTheme.colorScheme.onSurface
                        selected -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    // 只有两三个短选项时才用分段条；超过 4 项或文字长，调用方应改用 ChoiceRow。
                    // 这里给两行兜底，避免中文被硬截成省略号。
                    maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
    }
}

@Composable
fun StepperRow(
    title: String,
    value: String,
    onDecrease: (() -> Unit)? = null,
    onIncrease: (() -> Unit)? = null,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = WatchDimens.touchMin)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        StepButton(Icons.Filled.KeyboardArrowDown, enabled = onDecrease != null) { onDecrease?.invoke() }
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .width(46.dp)
                .padding(horizontal = 2.dp),
            maxLines = 1,
        )
        StepButton(Icons.Filled.KeyboardArrowUp, enabled = onIncrease != null) { onIncrease?.invoke() }
    }
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val glass = LocalGlass.current
    val src = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(26.dp)
            .then(
                if (glass) {
                    Modifier.glassSurface(
                        shape = RoundedCornerShape(11.dp),
                        cornerRadius = 11.dp,
                        enabled = enabled,
                        interaction = src,
                    )
                } else {
                    Modifier
                        .clip(RoundedCornerShape(11.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                },
            )
            .tap(enabled = enabled, quiet = glass, onClick = withHaptic(onClick)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            },
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
fun Chip(
    text: String,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .height(WatchDimens.chipHeight)
            .glassSurface(
                shape = CircleShape,
                tint = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Unspecified,
                onClick = onClick?.let { withHaptic(it) },
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            // 32dp 高的 chip 里，10sp 的 labelSmall 在手表上等于看不清；
            // Wear 的 chip 文案用 labelLarge（14sp），行高 18dp 仍装得下
            style = if (com.dshwatch.domain.model.LayoutThemes.isWatchLayout(LocalLayoutTheme.current)) {
                MaterialTheme.typography.labelLarge
            } else {
                MaterialTheme.typography.labelSmall
            },
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // 手表布局按 Wear M3 的 48dp 按钮；手机布局沿用原来的 54dp（更厚的点击面）
    val watch = com.dshwatch.domain.model.LayoutThemes.isWatchLayout(LocalLayoutTheme.current)
    if (watch) {
        /*
         * 手表布局直接用官方 Button。
         *
         * 不自己画「胶囊 + 玻璃 + 光斑」：Wear M3 的按钮高度、圆角、按下形变
         * 都是 `ButtonDefaults` 里算好的，自绘版本只会看起来「像但不是」。
         * 文字必须用 Wear 的 Text —— 它读的是 Wear 的 LocalContentColor，
         * 手机 Text 在 primary 容器里会拿到 onSurface，黑底黑字。
         */
        androidx.wear.compose.material3.Button(
            onClick = withHaptic(onClick),
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
        ) {
            if (icon != null) {
                androidx.wear.compose.material3.Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            androidx.wear.compose.material3.Text(text = text, maxLines = 1)
        }
        return
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(if (watch) WatchDimens.buttonHeight else WatchDimens.buttonHeight + 6.dp)
            .glassSurface(
                shape = CircleShape,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                enabled = enabled,
                onClick = withHaptic(onClick),
                solid = true,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            // 手表布局用 typography 自己的 titleSmall（16sp，Wear 规范值）；
            // 手机布局仍按旧排版压到 15sp —— 两者差 1sp，但语义不同：一个是规范值，
            // 一个是当年为了在 320dp 里塞下按钮手调的。
            style = if (watch) {
                MaterialTheme.typography.titleSmall
            } else {
                MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp)
            },
            color = if (enabled) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val glass = LocalGlass.current
    val src = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(WatchDimens.buttonHeight + 6.dp)
            .then(
                if (glass) {
                    Modifier
                        .glassSurface(shape = CircleShape, interaction = src)
                        .tap(quiet = true, interaction = src, onClick = withHaptic(onClick))
                } else {
                    Modifier
                        .clip(CircleShape)
                        .border(1.dp, tint.copy(alpha = 0.5f), CircleShape)
                        .tap(onClick = withHaptic(onClick))
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), color = tint)
    }
}

/**
 * 空态。
 * 注意：默认 modifier 不用 fillMaxSize —— 放在 verticalScroll 的 Column 里时高度约束是无限大，
 * fillMaxSize 会量成 0 高导致内容不可见。需要占满的场景（如聊天页）由调用方显式传入。
 */
@Composable
fun EmptyState(title: String, hint: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hint.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String? = null,
    destructive: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    GlassDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = { Text(text = text, style = MaterialTheme.typography.bodySmall) },
        buttons = {
            TextButton(onClick = onDismiss) { Text(s.cancel) }
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmText ?: if (destructive) s.delete else s.ok,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
    )
}

@Composable
fun TextRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    isSecret: Boolean = false,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        placeholder = {
            if (placeholder.isNotBlank()) {
                Text(placeholder, style = MaterialTheme.typography.labelSmall)
            }
        },
        textStyle = MaterialTheme.typography.bodyMedium,
        singleLine = singleLine,
        visualTransformation = if (isSecret) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}

@Composable
fun TokenBadge(
    usage: TokenUsage,
    estimated: Boolean = false,
    showCost: Double = 0.0,
) {
    if (usage.isEmpty) return
    val s = LocalStrings.current
    val text = buildString {
        append("↑").append(Fmt.tokens(usage.prompt))
        append(" ↓").append(Fmt.tokens(usage.completion))
        append(" · ").append(Fmt.tokens(usage.total)).append(" ")
        append(s.tokensLabel)
        if (estimated) append(" · ").append(s.estimatedTag)
        if (showCost > 0) append(" · ").append(Fmt.money(showCost))
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
fun ContextBar(used: Int, total: Int, modifier: Modifier = Modifier) {
    if (total <= 0) return
    val ratio = (used.toFloat() / total).coerceIn(0f, 1f)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = LocalStrings.current.contextUsage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${Fmt.tokens(used)} / ${Fmt.tokens(total)}",
                style = MaterialTheme.typography.labelSmall,
                color = if (ratio > 0.85f) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.outline
                },
            )
        }
        Spacer(Modifier.height(2.dp))
        LinearProgressIndicator(
            progress = { ratio },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = if (ratio > 0.85f) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
            trackColor = if (LocalGlass.current) {
                // 玻璃主题下轨道是「背景上的一道刻痕」，用淡白，不用不透明容器色
                Color.White.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
        )
    }
}

@Composable
fun KeyValueRow(label: String, value: String, valueColor: Color? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun SpacerH(dp: Dp) = Spacer(Modifier.height(dp))

/**
 * 连续滑块。
 *
 * 手表圆屏宽度只有 ~200dp，三格分段条每格 60dp、还得挤下"标准"两个汉字 ——
 * 结果是看上去有"标准"这一项，实际选中值和视觉位置对不上（旧的字号设置就是
 * 滑块停在"标准"、写进去的却是 0.92）。滑块把所有取值放在同一条轴上，
 * 点哪儿就是哪儿，右边常驻实时数值，配合 samples 让用户当场看到效果。
 *
 * 拖动过程只改本地状态（避免每一帧都写 DataStore 引发整棵树重组），
 * 抬手或旋转表冠停下时才 [onChange] 一次。
 */
@Composable
fun WatchSlider(
    value: Float,
    onChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    label: String,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    valueText: String? = null,
    enabled: Boolean = true,
    sample: String? = null,
    sampleStyle: androidx.compose.ui.text.TextStyle? = null,
) {
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    fun snap(raw: Float): Float {
        val v = raw.coerceIn(valueRange.start, valueRange.endInclusive)
        if (steps <= 0) return (Math.round(v * 100f) / 100f)
        val gaps = steps + 1
        val step = span / gaps
        val snapped = valueRange.start + Math.round((v - valueRange.start) / step) * step
        return (Math.round(snapped * 100f) / 100f).coerceIn(valueRange.start, valueRange.endInclusive)
    }

    var local by remember(value) { mutableFloatStateOf(value) }
    val haptic = LocalHapticFeedback.current

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText ?: ((local * 100).toInt().toString() + "%"),
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (!sample.isNullOrBlank()) {
            Text(
                text = sample,
                style = sampleStyle ?: MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
            )
        }
        Slider(
            value = local,
            onValueChange = { raw ->
                val next = snap(raw)
                // 每跳一档给一次触感，连续拖动时判断变化量避免刷屏
                if (Math.abs(next - local) >= 0.049f) {
                    runCatching { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                }
                local = next
            },
            onValueChangeFinished = { onChange(local) },
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = if (LocalGlass.current) {
                    Color.White.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            ),
            // 圆屏两侧被切掉，轨道左右留出来才不会把滑块端点藏进黑边
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = WatchDimens.touchMin),
        )
    }
}

/**
 * 玻璃开关。
 *
 * ## 和 M3 开关的三处区别
 *
 * | | M3 Switch | GlassSwitch |
 * |---|---|---|
 * | 轨道 | 纯色（primary / surfaceVariant） | 玻璃：底透 + 渐变 + 描边 |
 * | 滑块 | 白圆，无描边 | 玻璃小球：中心微亮、下沿一道暗 |
 * | 状态切换 | 位置突变 + 轨道换色 | 位置走弹簧，轨道亮度跟着推子走 |
 *
 * 轨道用 `glassSurface(tint = primary)` 会调成「染色玻璃」——
 * 那是把白色**替换**成主色再乘 0.42，所以打开时是带蓝的玻璃而不是一块蓝塑料。
 *
 * 滑块单独画：一个圆形的 `glassSurface`，用 `padding` 定位而不是 `offset`，
 * 这样轨道变宽变窄时它自动跟着走，不需要手动算 dp。
 */
@Composable
fun GlassSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val trackWidth = 42.dp
    val trackHeight = 26.dp
    val thumb = 20.dp
    val pad = 3.dp

    val travel = trackWidth - thumb - pad * 2
    val progress = animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "switchTravel",
    )

    val src = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(width = trackWidth, height = trackHeight)
            .glassSurface(
                shape = CircleShape,
                tint = MaterialTheme.colorScheme.primary,
                interaction = src,
                // 轨道本身不响应点击 → 下面用 tap(quiet) 挂事件。
                // 但**必须显式打开跟手**：按默认值（trackPointer = onClick != null）
                // 这里是 null，玻璃就不会装 pointerInput，
                // 光斑永远钉在静态位置 —— 用户报的「开关的光晕不跟手」就是这条
                onClick = null,
                trackPointer = true,
            )
            .tap(quiet = true, interaction = src, onClick = withHaptic { onCheckedChange(!checked) })
            // 关着的时候轨道要更淡：染色玻璃的 alpha 跟随状态，
            // 否则「关」和「开」只差滑块位置，一眼看不出状态
            .then(
                if (checked) Modifier
                else Modifier.background(Color.Black.copy(alpha = 0.18f), CircleShape)
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(start = pad + travel * progress.value)
                .size(thumb)
                .glassSurface(shape = CircleShape, cornerRadius = thumb / 2)
                .background(Color.White.copy(alpha = 0.16f), CircleShape),
        )
    }
}
