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
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.ScrollInfoProvider
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
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
 * 手表布局的页面骨架 —— 表单 / 单卡片 / 长文这类**不自带列表**的页面。
 *
 * ## 这一版是照官方结构写的
 *
 * 上一版是手搓的：自己画时钟、自己压顶栏、自己按弦长算边距、自己加渐隐。
 * 结果是「看起来像手表，但不是 Wear」—— 顶栏没有官方的时间曲线、没有滚动指示器、
 * 内容被钉在一条固定高度带里。
 *
 * 现在换成 `androidx.wear.compose.material3.ScreenScaffold`：
 *
 *  - **时间**由外层 `AppScaffold` 的 `TimeText` 沿圆顶弧线绘制，进页淡入；
 *  - **内容边距**由 `ScreenScaffoldDefaults.contentPadding` 给（横向 5.2%、
 *    纵向 10%），这是官方按圆屏算好的比例，不是我们猜的 16dp；
 *  - **返回**用 `EdgeButton`，官方规定它就贴在屏幕下缘，随滚动方向收起/展开；
 *  - **滚动指示器**由 `ScreenScaffold` 自己画，只要给它滚动状态。
 *
 * 标题不是顶栏，是 `ListHeader` —— Wear M3 没有 top app bar，标题属于内容，
 * 滚动时跟着走。这也是「完全按圆屏来」的关键：任何常驻横条都会在圆顶两侧被切掉。
 *
 * ## 底栏
 *
 * 没有 `onBack` 的页面（对话页）把 `bottom` 钉在底部；有返回键的页面让 `bottom`
 * 跟在内容末尾 —— 否则返回的 `EdgeButton` 和底栏会抢屏幕正下方同一块地方。
 */
@Composable
fun WearScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    bottom: (@Composable () -> Unit)? = null,
    /**
     * 保留参数只为与 `WatchScaffold` 签名对齐：手表骨架下底栏的位置由 [onBack] 决定。
     */
    @Suppress("UNUSED_PARAMETER") bottomOverlay: Boolean = false,
    @Suppress("UNUSED_PARAMETER") fullBleed: Boolean = false,
    /**
     * 内容是否由骨架负责滚动。
     *
     * 对话页那种「LazyColumn + weight(1f) + 自己管滚动」的页面必须传 false：
     * 外面再套一层 `verticalScroll`，里面的 `weight` 就会撞上无限高度约束直接崩。
     */
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val pinBottom = bottom != null && onBack == null

    ScreenScaffold(
        scrollInfoProvider = ScrollInfoProvider(scroll),
        edgeButton = {
            if (onBack != null) WearBackEdgeButton(onClick = onBack)
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
                WearHeader(title = title, subtitle = subtitle)
                if (actions != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
                content()
                // 有底栏时在末尾留出它的高度，否则最后一行永远压在按钮下面
                Spacer(Modifier.height(if (pinBottom) 56.dp else 12.dp))
            }
        }

        if (pinBottom) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                bottom?.invoke()
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
