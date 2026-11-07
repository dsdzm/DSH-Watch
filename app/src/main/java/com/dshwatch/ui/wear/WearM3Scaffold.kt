package com.dshwatch.ui.wear

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshwatch.ui.components.glassSurface
import com.dshwatch.ui.components.withHaptic
import com.dshwatch.ui.i18n.LocalStrings
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 底部操作栏占用的高度。
 *
 * 手表骨架把 `bottom` 一律做成**浮层**（见 [WearM3Scaffold] 里的说明），所以
 * 页面自己要在滚动内容的末尾留出这么多空白，否则最后一项会被压在按钮底下。
 * 会滚动的列表页（对话、会话）读它；表单页内容通常不满一屏，可以忽略。
 */
val LocalBottomInset = staticCompositionLocalOf { 0.dp }

/**
 * 手表布局的页面骨架（Wear OS Material 3）。
 *
 * ## 和 [com.dshwatch.ui.components.CircleScaffold] 的根本区别
 *
 * 那个是「把 320dp 的手机布局塞进圆周」：内容区取内接矩形，剩下的靠 `uiScale`
 * 把整块界面缩小。它成立的前提是**用户不会放大**。
 *
 * 这个不一样：它不假设任何设计基准宽度，所有尺寸都从**屏幕实际 dp 宽度**推：
 *  - 横向边距按该行的弦长算（[CircleGeometry.insetAt]），任何滚动位置都不越圆；
 *  - 内容区高度取圆内的固定带（0.862d 以下留给底栏），不靠缩放挤；
 *  - 缩放上限被夹在 1.0（见 [com.dshwatch.domain.UiScale.cap]），
 *    所以「拉大 → 越界」这条路在机制上就不存在。
 *
 * ## 为什么时钟放在圆顶那条"死区"
 *
 * Wear 规范要求表盘顶部显示时间。而顶栏必须下压到 0.115d 才能让标题行的弦宽
 * 达到 0.62d（推导见 `CircleScaffold.BAR_TOP_RATIO`）。圆顶到顶栏之间那 20dp
 * 一直是空的 —— 把时间放进去，**内容高度零损失**。
 *
 * ## 为什么 `bottom` 是浮层
 *
 * 206dp 的表盘，圆内可用高度只有约 110dp。独占一条 48dp 的操作栏会吃掉 44%，
 * 表单页只剩两行字。所以手表布局下 `bottom` 一律浮在内容之上，内容可以一直
 * 排到 0.862d；需要完整可读的列表页用 [LocalBottomInset] 在末尾留白。
 */
@Composable
fun WearM3Scaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottom: (@Composable () -> Unit)? = null,
    /**
     * 保留参数只为与 `WatchScaffold` 签名对齐 —— 手表布局下 `bottom` 永远是浮层。
     */
    @Suppress("UNUSED_PARAMETER") bottomOverlay: Boolean = false,
    /**
     * 内容是否吃满整个宽度（只留 8dp 边距）。
     *
     * 列表页（`WearList`）必须为 true：它靠逐项缩放淡出保证不越圆，如果再套一层
     * 内接矩形的窄宽度，鱼眼就没意义了，而且列表会窄到只剩 130dp。
     * 表单页保持 false：整块内容不能拆项，只能整体收进内接矩形。
     */
    fullBleed: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val g = LocalCircle.current
    val s = LocalStrings.current
    val bg = MaterialTheme.colorScheme.background

    if (g == null) {
        // 方表 / 手动选了手表布局的矩形屏：没有圆可算，退回常规纵向结构
        Column(Modifier.fillMaxSize().background(bg)) {
            Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
                WearClock()
            }
            WearBar(title, subtitle, onBack, actions)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                content = content,
            )
            if (bottom != null) {
                Box(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
                    bottom()
                }
            }
        }
        return
    }

    val d = g.diameter.value
    val top = g.top.value
    val cy = g.height.value / 2f

    // 时钟带：圆顶下方 2dp，高 18~20dp。这个位置弦宽还有 0.7d，放 5 个字符绰绰有余
    val clockH = if (d < 240f) 18.dp else 20.dp
    val clockTop = top + 2f

    // 顶栏：0.115d 起（比手机布局的 0.108d 再让 4dp 给时钟）
    val barTop = top + d * BAR_TOP_RATIO
    val barH = if (onBack != null) BAR_HEIGHT_WITH_BACK else BAR_HEIGHT_PLAIN
    val contentTop = barTop + barH.value
    val contentBottom = top + d * CONTENT_BOTTOM_RATIO
    val bodyHeight: Dp = (contentBottom - contentTop).dp.coerceAtLeast(0.dp)
    // 时钟与顶栏之间的空隙（时钟已经由上面的 Box 排过，这里只补差额）
    val barPadTop: Dp = (barTop - clockTop - clockH.value).dp.coerceAtLeast(0.dp)

    // 内容横向：列表吃满，表单取内接矩形
    val far = if (abs(contentTop - cy) > abs(contentBottom - cy)) contentTop else contentBottom
    val bodyInset: Dp = if (fullBleed) {
        BLEED_PADDING
    } else {
        (g.insetAt(far) + d * 0.006f).dp
    }

    // 底栏浮层：圆心落在 0.95d，和对话页的 FAB 同一位置
    val footPad: Dp = (g.height.value - (top + d * FAB_BOTTOM_RATIO)).dp.coerceAtLeast(0.dp)
    val bottomInset: Dp = if (bottom != null) BOTTOM_INSET else 0.dp

    Box(Modifier.fillMaxSize().background(bg).circleBezel(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))) {
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = clockTop.dp)
                    .height(clockH),
                contentAlignment = Alignment.Center,
            ) {
                WearClock()
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = barPadTop)
                    .padding(
                        start = (g.insetAt(barTop) + d * 0.004f).dp,
                        end = (g.insetAt(barTop) + d * 0.004f).dp,
                    )
                    .height(barH),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    WearBackButton(onClick = onBack)
                    Spacer(Modifier.width(4.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                actions()
            }

            /*
             * 内容区：高度写死，不吃 weight。
             *
             * weight 会把底栏顶出圆周；固定成 0.862d 之后，无论页面里怎么滚，
             * 可见区域都在圆内。滚动本身由页面自己的 wearVerticalScroll/WearList 负责。
             */
            CompositionLocalProvider(LocalBottomInset provides bottomInset) {
                Box(
                    modifier = Modifier
                        .height(bodyHeight)
                        .fillMaxWidth()
                        .padding(horizontal = bodyInset)
                        .edgeFade(
                            color = bg,
                            top = 14.dp,
                            bottom = if (bottom != null) 26.dp else 14.dp,
                        ),
                ) {
                    Column(Modifier.fillMaxSize(), content = content)
                }
            }
        }

        if (bottom != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = footPad),
                contentAlignment = Alignment.Center,
            ) {
                bottom()
            }
        }
    }
}

/**
 * 矩形屏（`LocalCircle == null`）的顶栏。
 *
 * 圆屏那份要按弦宽收边距、还要让出时钟带，逻辑长；矩形屏没有这些约束，
 * 单独一个简单版本更直观。两者共用 [WearBackButton] 与标题排版。
 */
@Composable
private fun WearBar(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            WearBackButton(onClick = onBack)
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

/**
 * 顶部时间（Wear 规范要求）。
 *
 * 不用 `androidx.wear.compose.material3.TimeText` 的两个理由：
 *  1. 它读 Wear 自己的 `LocalTimeSource` / `LocalContentColor`，而这个 App 外层
 *     只包了手机的 `MaterialTheme`，两者不是同一套 Local，取不到正确的颜色；
 *  2. 它默认跟随系统时间格式，但刷新靠 `LocalTime` 的分钟级 tick，在没有 Wear
 *     主题的环境里不保证触发。
 *
 * 自己实现只有二十行，且格式、刷新节奏都可见：跟随 12/24 小时制，每分钟跳一次。
 */
@Composable
private fun WearClock() {
    val context = LocalContext.current
    val is24 = remember(context) { android.text.format.DateFormat.is24HourFormat(context) }
    val fmt = remember(is24) { if (is24) "HH:mm" else "h:mm" }
    var text by remember { mutableStateOf(formatNow(fmt)) }

    LaunchedEffect(fmt) {
        while (true) {
            text = formatNow(fmt)
            // 对齐到下一分钟，而不是固定 sleep 60 秒 —— 否则显示会越走越偏
            val now = System.currentTimeMillis()
            delay(60_000 - (now % 60_000) + 40)
        }
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

private fun formatNow(pattern: String): String =
    java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(Date())

/**
 * 圆屏的返回键：40dp。
 *
 * 比手机布局的 44dp 小 4dp，因为手表布局的顶栏要同时塞下「返回 + 标题 +
 * 最多一个动作」，而 206dp 表盘这一行的弦宽只有约 128dp。40dp 仍高于
 * Wear 的 40dp 触控下限，再用 `minimumInteractiveComponentSize` 兜到 48dp 命中。
 */
@Composable
private fun WearBackButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .glassSurface(
                shape = CircleShape,
                cornerRadius = 20.dp,
                tint = MaterialTheme.colorScheme.surfaceContainerHigh,
                onClick = withHaptic(onClick),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = LocalStrings.current.back,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 圆屏顶栏的紧凑图标键：32dp 视觉、40dp 命中 */
@Composable
fun WearIconTap(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .glassSurface(
                shape = CircleShape,
                cornerRadius = 20.dp,
                tint = MaterialTheme.colorScheme.surfaceContainerHigh,
                enabled = enabled,
                onClick = withHaptic(onClick),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * 上下边缘渐隐。
 *
 * 表单页是整块 `Column`，不能像列表那样逐项缩放，滚到上下端时内容会顶到圆周上。
 * 这里画一层从背景色到透明的渐变，让内容"淡入圆周"而不是被硬切一刀。
 *
 * 只画在内容层之上、**不参与测量**（`drawWithContent`），所以不会改变布局高度。
 */
private fun Modifier.edgeFade(color: androidx.compose.ui.graphics.Color, top: Dp, bottom: Dp): Modifier =
    drawWithContent {
        drawContent()
        val tp = top.toPx()
        val bp = bottom.toPx()
        if (tp > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(color, color.copy(alpha = 0f)),
                    startY = 0f,
                    endY = tp,
                ),
                topLeft = Offset.Zero,
                size = androidx.compose.ui.geometry.Size(size.width, tp),
            )
        }
        if (bp > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(color.copy(alpha = 0f), color),
                    startY = size.height - bp,
                    endY = size.height,
                ),
                topLeft = Offset(0f, size.height - bp),
                size = androidx.compose.ui.geometry.Size(size.width, bp),
            )
        }
    }

/* ---------------------------------------------------------------------------
 * 尺寸常量
 *
 * 全部相对圆直径，和手机布局的比例分开维护：手机那套是按 320dp 设计反推的，
 * 这套是按 Wear 的 40/48dp 触控目标反推的，混用会两头不讨好。
 * ------------------------------------------------------------------------- */

/** 顶栏顶端距圆顶 0.115d：比手机布局多让 4dp 给时钟 */
private const val BAR_TOP_RATIO = 0.115f

/** 有返回键时的高度：40dp 按钮 + 4dp 呼吸 */
private val BAR_HEIGHT_WITH_BACK = 44.dp

/** 没有返回键（根页面）：只要一行标题 */
private val BAR_HEIGHT_PLAIN = 36.dp

/** 内容区底边：0.862d，与手机布局一致，实测不切角 */
private const val CONTENT_BOTTOM_RATIO = 0.862f

/** 底栏浮层圆心：0.95d */
private const val FAB_BOTTOM_RATIO = 0.95f

/** 列表页吃满时的左右边距（Wear 规范的屏幕内容边距是 8dp） */
private val BLEED_PADDING = 8.dp

/** 浮层占位：48dp 按钮 + 8dp 呼吸 */
private val BOTTOM_INSET = 56.dp
