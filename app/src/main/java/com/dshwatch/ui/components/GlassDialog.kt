package com.dshwatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dshwatch.ui.theme.LocalDarkTheme
import com.dshwatch.ui.theme.LocalGlass
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color

/**
 * 玻璃对话框 —— 全 App 对话框的唯一外壳。
 *
 * ## 为什么不用 `AlertDialog`
 *
 * Material 3 的 `AlertDialog` 有三处和玻璃主题冲突，且都改不动：
 *
 * | 问题 | 后果 |
 * |---|---|
 * | `containerColor` 默认 `surfaceContainerHigh` | 一块不透明的浅灰/深灰板，背后的界面完全看不见 |
 * | `DialogProperties(scrimColor = ...)` 渐变 | 遮罩是一层灰纱，把背景压成雾，色晕就没了 |
 * | 默认 `tonalElevation` | 表面色被 elevation 再偏移一次，颜色对不上 |
 *
 * 所以换成裸的 [Dialog]：外壳自己画玻璃（[glassSurface]），
 * 遮罩按主题给 —— 玻璃主题下是**几乎全透明**的，只比「什么都没有」暗一点点，
 * 靠玻璃卡片自己的亮度与边把它从背景里拎出来。
 *
 * ## 玻璃主题的对话框要多画的东西
 *
 * 普通卡片的光效是「按住才亮」，但对话框里没人会去按空白处 —— 所以这里常驻
 * 一层环境光（`onClick == null` 时 glassSurface 会自动给）。
 * 遮罩用 `0.20` 而不是 0：再淡也要让人看得出「下面那层被压住了，现在不能点」。
 */
@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    /** 顶部标题。传 null 表示不要标题区 */
    title: String? = null,
    /** 标题下的可滚动正文 */
    content: @Composable ColumnScope.() -> Unit,
    /** 底部按钮区，右对齐 */
    buttons: @Composable () -> Unit,
    /** 正文高度上限。对话框在圆屏上必须限高，否则会顶穿圆角 */
    maxContentHeight: Int = 300,
    /**
     * 正文是否由本组件包一层竖向滚动。
     *
     * 默认 true：绝大多数弹窗传进来的是若干行文本 / 选项，由这里统一限高滚动最省事。
     *
     * 传 false 的场合：**内容自己已经是滚动容器**（`verticalScroll`、
     * `wearVerticalScroll`、`LazyColumn`）。滚动容器必须拿到有限的最大高度，
     * 而这里的 `heightIn(max)` 只是「上限」，不会给下限 —— 里层再套一个滚动容器，
     * 它收到的仍是 `Constraints.Infinity`，Compose 会直接抛
     *
     * ```
     * IllegalStateException: Vertically scrollable component was measured with an
     * infinity maximum height constraints, which is disallowed.
     * ```
     *
     * 实测过：某个导入弹窗里放了 `wearVerticalScroll`（内部即 `verticalScroll`），
     * 一打开就整进程崩溃，堆栈顶在 `CheckScrollableContainerConstraints`。
     *
     * 所以「谁滚」必须只有一个。内容要自己滚，就把这里关掉，
     * 并且在内容里给出 `heightIn(max = ...)` 或 `fillMaxHeight()`。
     */
    scrollable: Boolean = true,
    /**
     * 是否给卡片投一道影。
     *
     * 默认 false —— 层级由**毛玻璃遮罩**承担（见下面背景那一层的注释），
     * 投影是「盖在图片上的卡片」的手法，在毛玻璃上画一道硬边反而脏。
     *
     * 只有一种情况要开：非玻璃主题。那时没有磨砂遮罩，卡片和下面对比强烈，
     * 必须靠投影把「这是一层浮起来的」说清楚。
     */
    shadow: Boolean = false,
) {
    if (com.dshwatch.ui.theme.isM3eUi) {
        com.dshwatch.ui.m3e.M3eDialog(
            onDismissRequest = onDismissRequest,
            title = title,
            maxContentHeight = maxContentHeight,
            content = content,
            buttons = buttons,
        )
        return
    }
    val glass = LocalGlass.current
    // 同上：透明背景不能反推明暗，问主题要
    val dark = LocalDarkTheme.current
    // 系统是否真的**能把背景糊掉**。不是「API 够不够」，而是「这个窗口有没有开模糊」——
    // Android 12 起才有，而且用户可以在开发者选项 / 无障碍里关掉。
    // 关掉时 Comose 的 Modifier.blur 还能用（它自己画离屏层），
    // 但这里问的是另一件事：系统级的背景模糊在不在。
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val backdropBlurSupported = remember(ctx) { WindowBlur.isBackgroundBlurSupported(ctx) }

    /*
     * 圆屏：弹窗是个居中矩形，四角同样要落在圆周内。
     *
     * 方屏上弹窗是 `fillMaxWidth()` + 12dp 边距 —— 圆屏上就是 181dp 宽，
     * 而一个居中矩形的四角能占多大，取决于它的高度：W² + H² ≤ (2r)²。
     * 高度一超，四角立刻冲出圆周，**冲出去的是标题和底部按钮**，
     * 用户看着弹窗在那里，点"确定"却毫无反应。
     *
     * 注意基准是**直径**不是屏幕高：这台机器 412×502，屏幕比圆高 72px。
     *
     * 所以宽度收 0.74d。正文上限给 0.36d：
     *
     *   矩形四角不出圆的条件是 W² + H² ≤ (2r)²，W = 0.74d：
     *   0.5476d² + H² ≤ d²  →  H ≤ 0.673d ≈ 277dp（d = 412）
     *
     * 而 H 里除了正文还有标题、按钮、上下 padding，约 98dp。
     * 所以正文最多 179dp —— 0.36d = 148dp 还留着 31dp 余量。
     *
     * 原来写的是 0.30d（124dp），四舍五入等于「菜单多到第三项就开始滚」，
     * 而屏幕明明还有地方。调到 0.36 让四项菜单能一屏看完。
     */
    val circle = com.dshwatch.ui.wear.LocalCircle.current
    val contentMax = if (circle == null) {
        maxContentHeight.dp
    } else {
        minOf(maxContentHeight.toFloat(), circle.diameter.value * 0.36f).dp
    }
    val widthMod = if (circle == null) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(0.74f)

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // ---- 遮罩层：这是「暗」的唯一来源，也是修过四轮的地方 ----
        //
        // 这一层同时干两件事：接住「点空白关闭」的点击，以及决定弹窗出现时
        // 后面那片背景长什么样。
        //
        // ## 前几轮都修错了地方
        //
        // 用户连着报了几次「弹窗太暗」，我一直在调**卡片**：叠多白、压多少暗、
        // 加不加投影。查一下顺序就明白错在哪：
        //
        //   · 第一次：`AlertDialog` 自带一块不透明板 → 换成自己画的 Dialog
        //   · 第二次：Dialog 默认遮罩 0.32 黑 → 减到 0.12
        //   · 第三次：玻璃本体的白叠太多 → 反复调 body
        //   · 第四次：量了截图，卡片亮度 135、背景 55，说「卡片比背景亮 2.3 倍
        //     所以不可能是暗的」—— 然后去调色相和投影
        //
        // 四次都没动这条根本事实：**遮罩是黑的**。
        //
        // 实测（用户截图，412×502）：
        //
        //   卡片外的背景  RGB(50,55,79)  亮度 55.7
        //   卡片本体      RGB(132,135,146) 亮度 135
        //
        // 背景看着像屏幕被熄了一半。玻璃是「透」，它透的永远是遮罩后的那一层 ——
        // 底被压了 0.12 的黑，卡片再白也只是在压暗过的底上叠白，
        // 整屏的基调从一开始就被钉在一个很低的亮度上。这就是用户那句
        // 「透明透过去也是黑的」的真正意思。
        //
        // ## 正解：模糊在**宿主页面**那一层，不在这个 Dialog 里
        //
        // `Dialog` 画在另一个 Window：它背后那页 Activity 根本不在这个节点的
        // 绘制子树里，所以在这里挂 `blur` 从来就糊不到背景。
        // 真正在糊背景的是 `GlassBackdropHost`（Backdrop.kt）—— 它包着页面，
        // 弹窗通过 `LocalBackdropDepth` 通知它把计数加一。
        //
        // ## 1.15.7：这一层自己挂模糊，把弹窗内容也糊没了
        //
        // 之前这里有一句 `.then(if (glass) Modifier.dialogBackdrop() else Modifier)`，
        // 玻璃主题 + API 31+ 时它等于 `blur(26.dp)`。而 `Modifier.blur` 作用于
        // **整个节点子树** —— 卡片 [Column] 是这个 Box 的 content，于是标题、
        // 正文、按钮连同木板一起被糊。用户看到的就是「只显示一片模糊的背景，
        // 没有内容」；圆屏只有 200dp 宽，26dp 相当于 13%，文字直接糊没。
        //
        // 结论：Dialog 这一层只负责两件事 —— 接住「点空白关闭」，以及
        // 非玻璃主题时压一层黑。玻璃主题保持全透明，让宿主层的模糊透过来。
        // 通知宿主：把背景糊掉。这是「模糊」真正发生的地方 —— 见 Backdrop.kt。
        //
        // 这里是**计数**不是开关：两个弹窗叠在一起时（审批框 + 询问框），
        // 先关的那个不能把闸拉回 0 —— 那样另一个还开着，背景却突然清楚了。
        // 用计数就自然是「最后一个关掉才算关」。
        val depth = LocalBackdropDepth.current
        DisposableEffect(Unit) {
            depth.value += 1
            onDispose { depth.value = (depth.value - 1).coerceAtLeast(0) }
        }

        val dismissInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    when {
                        /*
                         * 玻璃主题：底下已经是磨砂了，不再压黑。
                         *
                         * 浅色例外：浅色主题的页面本身就是近白的，磨砂白卡片压在同一片白上
                         * 完全看不出边界 —— 参考用户给的另一个应用，它的弹窗是
                         * 「磨砂白卡片 + 被压暗一档的背景」。
                         * 0.12 的黑刚好把页面压到 218 左右，卡片 247，
                         * 差 29 级：一眼能看出「下面那层被按住了」，又不至于变灰。
                         */
                        glass -> if (dark) Color.Transparent else Color.Black.copy(alpha = 0.12f)
                        // M3E 主题（OLED 黑 / 樱花 / 深海 …）**永远**自己压一层。
                        //
                        // 这里是我上一版真正的漏洞：当时以为「背景已经被弹窗层的
                        // dialogBackdrop 糊掉了，不需要再压」。但那东西只在玻璃
                        // 材质下生效，而 `Modifier.blur` 在 API 31 以下会被
                        // **静默忽略** —— 于是这类设备上 M3E 的弹窗后面既没糊、
                        // 也没压，用户看到的是一页清清楚楚的文字顶在弹窗后面。
                        // 0.45 是 M3 的标准 scrim 值。
                        //
                        // 系统支持背景模糊时（setBackgroundBlurRadius），压得更轻一点：
                        // 糊本身已经把下一层推远了，再压黑就又变成「太暗」
                        else -> Color.Black.copy(
                            alpha = if (backdropBlurSupported) 0.12f else 0.45f,
                        )
                    },
                )
                .clickable(
                    interactionSource = dismissInteraction,
                    indication = null,
                    onClick = onDismissRequest,
                ),
            contentAlignment = Alignment.Center,
        ) {
        val shape = RoundedCornerShape(26.dp)

        // 卡片本身：吞掉点击（否则点卡片会穿透到遮罩、把弹窗关掉），
        // 但用 clickable 而不是 pointerInput.consume() —— 后者会连按下一起吃掉，
        // 害得卡片里的按钮收不到按压态（用户报的「动画有点奇怪」就有这一条）
        val swallowInteraction = remember { MutableInteractionSource() }
        Column(
            modifier = modifier
                .then(widthMod)
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .clickable(
                    interactionSource = swallowInteraction,
                    indication = null,
                    // 点卡片不应该关闭
                    onClick = {},
                )
                // 投影只在非玻璃主题下用：那里没有毛玻璃遮罩，卡片和下面对比强烈。
                // 玻璃主题下层级由毛玻璃负责，再画一道硬边反而脏。
                .then(
                    if (shadow && !glass) {
                        Modifier.shadow(
                            elevation = 18.dp,
                            shape = shape,
                            clip = false,
                            ambientColor = Color.Black.copy(alpha = 0.5f),
                            spotColor = Color.Black.copy(alpha = 0.5f),
                        )
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (glass) {
                        Modifier.glassSurface(
                            shape = shape,
                            cornerRadius = 26.dp,
                            // 对话框要盖住下面的界面：浮一档，比列表卡片更实，
                            // 但仍然让边缘透出背后的色晕
                            elevated = true,
                        )
                    } else {
                        Modifier.background(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape,
                        )
                    },
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
            }
            Column(
                modifier = if (scrollable) {
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = contentMax)
                        .verticalScroll(rememberScrollState())
                } else {
                    // 内容自己滚时只限高，绝不再叠一层滚动 —— 见 scrollable 的说明
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = contentMax)
                },
                content = content,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                buttons()
            }
        }
        }
    }
}

/**
 * 对话框里的一行可选项：标题 + 说明，选中时标题换成主色。
 *
 * 玻璃主题下这一行**不再套一个胶囊**：对话框本身已经是玻璃，
 * 里面再叠一层半透明白会和底下的玻璃互相稀释，越叠越灰。
 * 靠文字层级与左边一条 2dp 的主色指示条来区分选中，反而更干净。
 */
@Composable
fun GlassDialogRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val glass = LocalGlass.current
    // 选中行自带玻璃反馈，未选中行走 tap() 的反馈 —— 共享按压源，
    // 保证「按下的那一行」形变与亮起是同一个时刻
    val src = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .then(
                if (glass && selected) {
                    // 选中行给一道染色玻璃，让它从同色背景里浮出来。
                    // hint：这里用 14dp 圆角，与 tap() 反馈的胶囊（高度一半）不同 ——
                    // 所以选中行必须 quiet，否则按下时会多出一个形状不对的胶囊光
                    Modifier.glassSurface(
                        shape = RoundedCornerShape(14.dp),
                        cornerRadius = 14.dp,
                        tint = MaterialTheme.colorScheme.primary,
                        interaction = src,
                    )
                } else {
                    Modifier
                },
            )
            .tap(enabled = enabled, quiet = glass && selected, onClick = withHaptic(onClick))
            // 内边距要**同时**作用在玻璃与光上：放在 tap 之前，
            // 玻璃/高光就只覆盖内容区，而行的点击区仍是满宽 —— 视觉与命中区一致
            .padding(horizontal = if (glass) 10.dp else 4.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
                    selected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

/**
 * 对话框底部的按钮。最后一个（最右）用主色，其余用次要色。
 *
 * 玻璃主题下是**玻璃按钮**而不是纯文字：文字按钮在玻璃卡片上没有边界，
 * 按下去只有文字周围亮一下，看起来像「点了个标签」。玻璃按钮自带边和光晕，
 * 按在哪儿、按下没按下都是一眼可见的。
 *
 * 非玻璃主题保持原来的 TextButton —— M3E 的文字按钮是该有的样子。
 */
@Composable
fun GlassDialogButton(
    label: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // M3E：对话框里的动作是整宽的按钮，不是贴着右下角的小文字
    if (com.dshwatch.ui.theme.isM3eUi) {
        com.dshwatch.ui.m3e.M3eDialogAction(
            label = label,
            primary = primary,
            enabled = enabled,
            onClick = onClick,
        )
        return
    }
    val glass = LocalGlass.current
    val color = if (primary) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    if (!glass) {
        TextButton(onClick = onClick) {
            Text(text = label, color = color)
        }
        return
    }

    val src = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .heightIn(min = 34.dp)
            .glassSurface(
                shape = CircleShape,
                tint = if (primary) MaterialTheme.colorScheme.primary else Color.Unspecified,
                /*
                 * 主按钮做实心。
                 *
                 * 半透明的主色叠在**已经压暗**的弹窗底上，会暗到看不出是主按钮 ——
                 * 而且 onPrimary 的文字压在偏暗的染色上，对比度也跟着掉。
                 * 实心主色是这一层唯一「一眼看得出该按哪个」的东西。
                 */
                solid = primary,
                interaction = src,
            )
            .tap(quiet = true, interaction = src, onClick = withHaptic(onClick))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (primary) MaterialTheme.colorScheme.onPrimary else color,
        )
    }
}
