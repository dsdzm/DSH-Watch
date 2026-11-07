package com.dshwatch.ui.wear

import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.ScrollInfoProvider
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.dshwatch.ui.i18n.LocalStrings

/**
 * 骨架自己持有的滚动状态。
 *
 * 页面里的 `wearVerticalScroll(rememberScrollState())` 会优先复用它（见
 * `WearLists.kt`），这样整页只有**一个**滚动容器：`ScreenScaffold` 拿得到滚动
 * 偏移，右侧的 `PositionIndicator` 才会出现；页面再套一层就会撞上
 * 「verticalScroll 里又套 verticalScroll」的无限高约束崩溃。
 */
val LocalWearScroll = staticCompositionLocalOf<ScrollState?> { null }

/**
 * 手表布局的页面骨架。
 *
 * ## 结构照 Wraive（youshen2/Wraive）来
 *
 * 参考项目里没有一处「常驻底栏」：Wear M3 的屏幕正下方只归 `EdgeButton`
 * （返回 / 发送 / 输入），其它动作一律是内容里的列表项。原因是圆的：底栏
 * 下沿那一行的弦宽只有屏宽的 0.5~0.6，任何 `fillMaxWidth` 的横条都会被圆周
 * 啃掉两端 —— 1.15.24 之前那版就是照手机习惯把输入条钉在底部，结果整条被
 * 裁掉，看起来像「控件消失了」。
 *
 * 所以这个骨架的规则是：
 *
 *  - `edgeAction` 有值 → 它占 `EdgeButton`，页面主操作永远够得着；
 *  - 此时 `onBack` 挪到标题行左侧的小箭头（一个屏幕只有一个「下缘」）；
 *  - `bottom` 只在**没有** `edgeAction` 时，排在内容末尾随手指滚动，
 *    绝不静默丢弃 —— 这是 1.15.24 丢控件的直接原因；
 *  - 内容横向不吃满：`ScreenScaffoldDefaults.contentPadding` 按圆屏算好了
 *    左右 5.2%、上下 10%，这比任何手算弦长都准。
 */
@Composable
fun WearScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    /**
     * 页面底栏。**手机布局专用**。
     *
     * 手表布局下：给了 [edgeAction] 就忽略它（动作已在边键上）；没给就把它
     * 排到内容末尾。两种都不会让它凭空消失。
     */
    bottom: (@Composable () -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") bottomOverlay: Boolean = false,
    @Suppress("UNUSED_PARAMETER") fullBleed: Boolean = false,
    /**
     * 内容是否由骨架负责滚动。
     *
     * 对话页那种「LazyColumn + weight(1f) + 自己管滚动」、输入页那种
     * 「TextField + weight(1f)」的页面必须传 false：外面再套一层
     * `verticalScroll`，里面的 `weight` 就会撞上无限高度约束直接崩。
     */
    scrollable: Boolean = true,
    /**
     * 主操作，渲染成屏幕下缘的 [EdgeButton]。
     *
     * 内容只放图标或短文字：边键宽度受圆周限制（ExtraSmall ≈ 屏宽的 60%），
     * 塞长句会被截断。需要说明的操作放内容里当列表项。
     */
    edgeAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    // 一个屏幕只有一个「下缘」：边键被主操作占了，返回就只能上到标题行
    val backInHeader = edgeAction != null && onBack != null
    // 没有边键动作时，底栏排在内容末尾随手指滚动
    val bottomAtEnd = bottom != null && edgeAction == null

    ScreenScaffold(
        scrollInfoProvider = ScrollInfoProvider(scroll),
        edgeButton = {
            when {
                edgeAction != null -> edgeAction.invoke()
                onBack != null -> WearBackEdgeButton(onClick = onBack)
            }
        },
    ) { padding ->
        CompositionLocalProvider(
            LocalWearScroll provides if (scrollable) scroll else null,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // 顺序不能反：padding 要在 verticalScroll 之内，
                    // 否则内容一滚就贴到圆心以外，官方留的边距全白给
                    .then(if (scrollable) Modifier.verticalScroll(scroll) else Modifier)
                    .padding(padding),
            ) {
                if (backInHeader) {
                    WearHeaderWithBack(title = title, subtitle = subtitle, onBack = onBack)
                } else {
                    WearHeader(title = title, subtitle = subtitle)
                }
                if (actions != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
                content()
                if (bottomAtEnd) {
                    Spacer(Modifier.height(10.dp))
                    bottom?.invoke()
                }
                /*
                 * 末尾留白：最后一项不该贴着圆周，也要躲开下缘的边键。
                 *
                 * 只在骨架自己滚动时加：不自滚的页面（对话/输入）内容已经
                 * `fillMaxSize` 吃掉剩余高度，再塞一个 Spacer 只会被排到屏幕外。
                 */
                if (scrollable) {
                    Spacer(Modifier.height(if (edgeAction != null) 44.dp else 16.dp))
                }
            }
        }
    }
}

/**
 * 手表布局的列表页骨架。
 *
 * 列表页必须让 `ScreenScaffold` 和 `TransformingLazyColumn` **共用同一个 state**：
 * 指示器的位置、`EdgeButton` 的收起动画都从它推。各自 new 一个的话，
 * 指示器永远停在顶部、返回键永远张着。
 *
 * 列表项逐项做官方变换（[SurfaceTransformation]）——靠近上下缘时缩小淡出，
 * 圆屏的两端因此不会被硬切。表单页做不到这一点（整块内容不能拆），
 * 所以那类页面用 [WearScreen] 的普通滚动。
 */
@Composable
fun WearListScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    bottom: (@Composable () -> Unit)? = null,
    state: androidx.wear.compose.foundation.lazy.TransformingLazyColumnState =
        rememberTransformingLazyColumnState(),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(4.dp),
    content: WearListScope.() -> Unit,
) {
    val spec = rememberTransformationSpec()

    ScreenScaffold(
        scrollState = state,
        edgeButton = {
            if (onBack != null) WearBackEdgeButton(onClick = onBack)
        },
    ) { padding ->
        TransformingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = state,
            contentPadding = padding,
            verticalArrangement = verticalArrangement,
            horizontalAlignment = Alignment.CenterHorizontally,
            rotaryScrollableBehavior = RotaryScrollableDefaults.behavior(state),
        ) {
            item {
                ListHeader(
                    modifier = Modifier
                        .transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) {
                    Text(text = title, textAlign = TextAlign.Center)
                }
            }
            if (!subtitle.isNullOrBlank()) {
                item {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, spec),
                    )
                }
            }
            if (actions != null) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .transformedHeight(this, spec),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
            }
            WearListScope(this, spec).content()
        }

        // 列表页没有边键动作，底栏浮在屏幕下方；圆屏下已经收进官方边距里
        if (bottom != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                bottom()
            }
        }
    }
}

@Composable
private fun WearHeader(title: String, subtitle: String?) {
    ListHeader {
        Text(text = title, textAlign = TextAlign.Center)
    }
    if (!subtitle.isNullOrBlank()) {
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
        )
    }
}

/**
 * 有边键主操作时的标题行：左侧一颗返回箭头 + 标题。
 *
 * 这不是「又做了一条顶栏」：它没有背景、没有固定高度、跟着内容一起滚走，
 * 只是把「返回」从被别人占掉的下缘挪到一眼能找到的位置。
 */
@Composable
private fun WearHeaderWithBack(title: String, subtitle: String?, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.wear.compose.material3.IconButton(
            onClick = onBack,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = LocalStrings.current.back,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
    }
}

/**
 * 返回键。
 *
 * 用官方的 `EdgeButton` 而不是自己画一颗 40dp 圆钮：Wear M3 规定返回就贴屏幕
 * 下缘的「边」上，随滚动方向收起/展开；自己画在顶栏里等于把手机的习惯
 * 搬到了圆表上。
 */
@Composable
private fun WearBackEdgeButton(onClick: () -> Unit) {
    EdgeButton(
        onClick = onClick,
        buttonSize = EdgeButtonSize.ExtraSmall,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = LocalStrings.current.back,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 主操作边键。
 *
 * 尺寸与 Wraive 的用法一致：`EdgeButtonSize.ExtraSmall` + `fillMaxWidth()`，
 * 让边键贴着圆周铺开；图标单独控制大小，文字用 `labelMedium` —— 这是
 * Wear M3 对 EdgeButton 的规格，不是随手给的数。
 */
@Composable
fun WearEdgeAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    content: @Composable () -> Unit,
) {
    EdgeButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        buttonSize = EdgeButtonSize.ExtraSmall,
        colors = if (danger) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) {
        content()
    }
}
