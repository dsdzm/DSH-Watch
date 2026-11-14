package com.dshwatch.ui.components

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dshwatch.ui.m3e.M3eBackButton
import com.dshwatch.ui.m3e.M3eIconTap
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.wear.LocalCircle
import com.dshwatch.ui.wear.circleBezel
import kotlin.math.abs

/**
 * 圆屏专用的页面骨架。
 *
 * ## 一、这台机器的屏幕不是正方形
 *
 * 412×502 px @200dpi = **329.6 × 401.6 dp**。圆只占中间那段直径 329.6dp 的带子，
 * 上下各 36dp 是黑边（`CircleGeometry.top`）。所有纵向比例都从**圆顶**起算。
 *
 * ## 二、顶栏压到 34dp，内容区下探到 0.862d
 *
 * 上一版顶栏 56dp、从 0.10d 起，内容区到 0.80d 就结束 —— 结果是
 * 上方 56dp 的标题栏、下方 65dp 的圆内空白（只放了个 46dp 的圆按钮），
 * 中间真正能看内容的只有 175dp。用户的原话是「上端占空间太大，下端截断又比较严重」。
 *
 * 现在：
 *
 * | 区域 | 旧 | 新 |
 * |---|---|---|
 * | 顶栏 | 0.10d–0.27d（56dp） | 0.072d–0.172d（33dp） |
 * | 内容 | 0.27d–0.80d（175dp） | 0.172d–0.862d（227dp） |
 * | 底栏 | 内容之下的独立带 | **浮层**，叠在内容上 |
 *
 * 内容高度 175 → 227dp（+30%），顶栏 56 → 33dp（-41%）。
 *
 * ## 三、底栏改成浮层
 *
 * 底栏原来占一条独立的横向带，把内容往上顶；现在它贴在圆底上方、叠在内容上，
 * 内容可以一直排到 0.862d。**代价是最后一行会被浮层遮住**，所以带底栏的页面
 * （对话页）自己要在列表底部加 56dp 的 contentPadding —— 见 ChatScreen。
 *
 * ## 四、宽度由矩形最远的那个角反推
 *
 * 设半径 r、圆心在屏幕 y=200.8dp。矩形上下边 y1/y2，离圆心更远的那条
 * 决定宽度上限：半宽 = √(r² − max(|y1−cy|, |y2−cy|)²)。
 *
 * 这台机器上：内容区 93.7dp → 320.2dp，取 320.2（离圆心 119.4dp），
 * 半宽 113.7dp，再留 3dp 余量 → **内容宽 221dp**。
 */
@Composable
fun CircleScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottom: (@Composable () -> Unit)? = null,
    /**
     * 底栏是否浮在内容之上。
     *
     * - `true`（对话页）：底栏只有一个 46dp 的圆按钮，浮起来刚好，内容能一直
     *   排到 0.862d —— 这是圆屏上唯一能「把正文还回来」的做法。
     * - `false`（表单页）：底栏是一整条保存/发送栏，必须独占一条带，
     *   否则会把表单最后一行盖住。带的位置按弦长内缩，不会切角。
     */
    bottomOverlay: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val g = LocalCircle.current
    val s = LocalStrings.current
    val bg = MaterialTheme.colorScheme.background

    if (g == null) {
        // 理论上走不到（WatchScaffold 只在圆屏分派过来），但别在这里炸
        Column(Modifier.fillMaxSize().background(bg)) {
            CircleTopBar(title, subtitle, onBack, actions)
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), content = content)
            bottom?.invoke()
        }
        return
    }

    val d = g.diameter.value          // 圆的直径。不是屏幕高 —— 见文件头第一节
    val top = g.top.value             // 圆顶在屏幕上的 y
    val cy = g.height.value / 2f      // 圆心（屏幕垂直居中，和圆同心）

    val barTop = top + d * BAR_TOP_RATIO
    /*
     * 有返回键时顶栏要让出一个 44dp 的圆形按钮。
     *
     * 原来的返回键是 32dp 的裸图标，紧贴标题 —— 圆屏顶栏那一行只有 155dp，
     * 图标和标题挤在一起，点击区还只有 32dp，戴着手套几乎点不中。
     * 现在换成 44dp 的玻璃圆按钮：既是尺寸达标，也和设置页的卡片同一套材质。
     */
    val barH = if (onBack != null) maxOf(d * BAR_HEIGHT_RATIO, 44f) else d * BAR_HEIGHT_RATIO
    val contentTop = barTop + barH
    val contentBottom = top + d * CONTENT_BOTTOM_RATIO

    // 顶栏的横向边距按顶栏最上沿算：那一行最窄
    val barInset: Dp = (g.insetAt(barTop) + d * 0.006f).dp
    // 内容矩形的宽度：取离圆心更远的那条边
    val far = if (abs(contentTop - cy) > abs(contentBottom - cy)) contentTop else contentBottom
    val bodyInset: Dp = (g.insetAt(far) + d * 0.009f).dp
    // 独占带：表单页的底栏要占一条自己的高度，内容相应减掉
    val band: Dp = if (bottom != null && !bottomOverlay) (d * BAND_RATIO).dp else 0.dp
    val bodyHeight: Dp = (contentBottom - contentTop).dp - band
    // 带的横向边距按带的上沿算 —— 那一条比内容区更宽
    val bandInset: Dp = (g.insetAt(contentBottom - band.value) + d * 0.006f).dp

    // 底栏浮层：46dp 的圆按钮，圆心落在 0.905d，下沿离圆周还有 10dp 余量
    val footPad: Dp = (g.height.value - (top + d * FAB_BOTTOM_RATIO)).dp.coerceAtLeast(0.dp)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .circleBezel(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = barTop.dp, start = barInset, end = barInset)
                    .height(barH.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    CircleBackButton(onClick = onBack)
                    Spacer(Modifier.width(8.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
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
             * 内容区：**固定高度**，不是 weight(1f)。
             *
             * 撑满会把底栏压出圆外。固定成 0.69d 之后，内容滚到哪都不会越过圆周。
             */
            Column(
                modifier = Modifier
                    .height(bodyHeight)
                    .fillMaxWidth()
                    .padding(horizontal = bodyInset),
                content = content,
            )

            if (bottom != null && !bottomOverlay) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = bandInset),
                ) {
                    bottom()
                }
            }
        }

        /*
         * 底栏浮层。
         *
         * 贴在圆底之上、叠在内容之上 —— 不占竖向空间。带底栏的页面（对话页）
         * 需要自己在列表底部留 56dp，否则最后一行会被这个浮层盖住。
         */
        if (bottom != null && bottomOverlay) {
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
 * 顶栏顶端距圆顶的比例。
 *
 * ## 这个数不是审美，是几何反推出来的
 *
 * 圆顶那一行的弦宽是 0。设圆半径 r、圆心到该行距离 dy，弦宽
 *      w = 2·√(r² − dy²)
 * 要让 w 至少达到直径的 0.62（顶栏要放一个标题 + 最多四颗 32dp 图标），
 * 令 w = 0.62d、r = 0.5d：
 *      0.31d = √(0.25d² − dy²)  →  dy² = 0.1539d²  →  dy = 0.392d
 *      y = r − dy = 0.5d − 0.392d = **0.108d**
 *
 * ## 之前的 0.072 为什么会让图标点不到
 *
 * 0.072d 处弦宽只有 0.52d（420dp 的表盘上约 220dp），而「标题 + 四颗图标」
 * 需要 128 + 至少 90 = 218dp —— 刚好卡在临界点。标题稍长（会话名超过十来个字）
 * 就会被挤成省略号，两颗图标则被推到圆周之外：看得见，但按不到。
 *
 * 下移 0.036d（420dp 上约 15dp）换来 +40dp 的横向空间。代价是内容区少了
 * 同样的 15dp，比"按钮点不到"划算得多。
 */
private const val BAR_TOP_RATIO = 0.108f
/** 顶栏高度占直径的比例（33dp） */
private const val BAR_HEIGHT_RATIO = 0.10f
/** 内容区底边距圆顶的比例 */
private const val CONTENT_BOTTOM_RATIO = 0.862f
/** 独占底栏带占直径的比例（约 51dp） */
private const val BAND_RATIO = 0.155f
/**
 * 底栏浮层下沿距圆顶的比例。
 *
 * 从 0.972 收到 0.95：0.972 时按钮下沿距圆周只剩 2.8% 直径（420dp 上 12dp），
 * 在矩形屏上（圆外侧还有大片空白）看着就像"掉到圆外面去了"。
 * 0.95 处弦宽仍有 0.995d，横向完全够，纵向留出 21dp 的呼吸空间。
 */
private const val FAB_BOTTOM_RATIO = 0.95f

@Composable
private fun CircleTopBar(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            CircleIconTap(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = LocalStrings.current.back,
                onClick = onBack,
            )
            Spacer(Modifier.width(1.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
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
 * 圆屏的返回键：44dp 的玻璃圆按钮。
 *
 * 为什么不是一颗裸图标：
 * `32dp` 的图标在圆屏上正好卡在圆周收窄的那一段，左边一半贴着圆边，
 * 视觉上像是"长在标题上"；触摸区只有 32dp，戴手套或用指腹按经常落空。
 * 44dp 是 Material 的最小可点尺寸，配一块玻璃底之后边界清清楚楚，
 * 也告诉用户"这里是一颗按钮"而不是装饰。
 */
@Composable
fun CircleBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (com.dshwatch.ui.theme.isM3eUi) {
        M3eBackButton(onClick)
        return
    }

    Box(
        modifier = modifier
            .size(44.dp)
            .glassSurface(
                shape = CircleShape,
                cornerRadius = 22.dp,
                tint = MaterialTheme.colorScheme.surfaceContainerHigh,
                onClick = withHaptic(onClick),
            ),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = LocalStrings.current.back,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 圆屏顶栏专用图标按钮：34dp。
 *
 * 比 Material 建议的 48dp 小 —— 顶栏最上面那一行的弦宽只有 170dp，
 * 一个返回键 + 标题 + 两颗动作图标正好卡满，48dp 的话标题直接消失。
 * 34dp 已经是「能点准」和「放得下」之间的平衡点。
 */
@Composable
fun CircleIconTap(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    if (com.dshwatch.ui.theme.isM3eUi) {
        M3eIconTap(icon, contentDescription, enabled, onClick)
        return
    }

    Box(
        modifier = Modifier
            .height(32.dp)
            .width(32.dp)
            .tap(enabled = enabled, onClick = withHaptic(onClick)),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            },
            modifier = Modifier.height(19.dp).width(19.dp),
        )
    }
}
