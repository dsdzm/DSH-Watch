package com.dshwatch.ui.wear

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.dshwatch.ui.theme.LocalDeviceProfile

/**
 * Wear OS 列表的基础件。
 *
 * ## 为什么是 TransformingLazyColumn 而不是 ScalingLazyColumn
 *
 * 两者都能做「鱼眼」，差别在**锚点**：
 *
 * - `ScalingLazyColumn` 的默认锚点把第一项对齐**屏幕中心**。列表不满一屏时，
 *   上方就空出一大块 —— 用户截图里设置页顶部那片空白正是这个造成的。
 * - `TransformingLazyColumn` 从 contentPadding 顶部开始排，项随滚动**渐进变换**
 *   （靠近上下边时缩小、淡出），既没有顶部空档，也不会在底部被圆周硬切。
 *
 * 圆屏上第二点尤其重要：屏幕是圆的，越靠边可用宽度越小。整块矩形列表只要
 * 高度超过圆的中段，上下两端就必然出界；靠逐项缩放把内容「收」进圆里，
 * 是 Wear 官方的解法，也是第三方同类应用（Wraive）的做法。
 *
 * ## 表冠
 *
 * `TransformingLazyColumn` 不内置表冠支持，要显式传 `rotaryScrollableBehavior`。
 * 传 `RotaryScrollableDefaults.behavior(state)` —— state 本身就是 ScrollableState。
 */
object WearLayout {

    /**
     * 列表左右留白。
     *
     * 圆屏给 0：外层 [com.dshwatch.ui.components.CircleScaffold] 已经把内容区
     * 按弦长收成了内接矩形，这里再叠一层就是重复收窄 —— 上一版 16dp + 36dp 的
     * 结果是把 223dp 的可用宽压到了 190dp。
     */
    @Composable
    fun horizontalPadding(): Dp {
        val p = LocalDeviceProfile.current
        return when {
            LocalCircle.current != null -> 0.dp
            p.compact -> 8.dp
            else -> 10.dp
        }
    }

    /**
     * 列表内边距。
     *
     * TransformingLazyColumn 从顶部开始排，所以上下不需要留「让第一项滚到中心」
     * 的空间：给个 2dp 只是让卡片阴影不被容器边裁掉。
     */
    @Composable
    fun listPadding(): PaddingValues {
        val h = horizontalPadding()
        return PaddingValues(start = h, end = h, top = 2.dp, bottom = 2.dp)
    }

    /** 页面级左右内边距，给非列表内容（标题、单块卡片）用 */
    @Composable
    fun pagePadding(): PaddingValues = PaddingValues(
        start = horizontalPadding(),
        end = horizontalPadding(),
        top = 12.dp,
        bottom = 12.dp,
    )
}

/**
 * [WearList] 的 scope。
 *
 * 存在的唯一理由是：给每一项套上 `transformedHeight` + 容器变换。
 * `TransformingLazyColumn` 自己不会缩放内容 —— 变换要由调用方在 item 上应用，
 * 这样框架才知道「这一项变换后占多高」，从而正确计算滚动范围。
 */
class WearListScope internal constructor(
    private val scope: TransformingLazyColumnScope,
    private val spec: TransformationSpec,
) {
    fun item(
        key: Any? = null,
        content: @Composable TransformingLazyColumnItemScope.() -> Unit,
    ) {
        scope.item(key = key) { FisheyeItem(spec, content) }
    }

    fun items(
        count: Int,
        key: ((index: Int) -> Any)? = null,
        itemContent: @Composable TransformingLazyColumnItemScope.(index: Int) -> Unit,
    ) {
        scope.items(count = count, key = key) { index ->
            FisheyeItem(spec) { itemContent(index) }
        }
    }
}

/**
 * 单项的鱼眼变换。
 *
 * 两件事必须一起做，少一件就会错位：
 * 1. `transformedHeight` —— 告诉布局「这项变换后只剩这么高」，否则滚动范围会按
 *    原始高度算，最后一项永远滚不到底（正是用户说的「底部截断」）。
 * 2. `applyContainerTransformation` —— 真正把这一项缩放、推移、淡出。
 */
@Composable
private fun TransformingLazyColumnItemScope.FisheyeItem(
    spec: TransformationSpec,
    content: @Composable TransformingLazyColumnItemScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, spec)
            .graphicsLayer {
                with(spec) {
                    applyContainerTransformation(scrollProgress)
                }
            },
    ) {
        content()
    }
}

/**
 * 带鱼眼效果的列表。
 *
 * 用法与 `LazyColumn` 基本一致：
 * ```
 * WearList {
 *     item { ... }
 *     items(list.size) { i -> ... }
 * }
 * ```
 */
@Composable
fun WearList(
    modifier: Modifier = Modifier,
    /**
     * 列表状态。
     *
     * 想在这个列表「返回后回到原位」时，传一个
     * `rememberKeptListState("页面名")`（见 ScrollMemory.kt）。
     */
    state: TransformingLazyColumnState = rememberTransformingLazyColumnState(),
    contentPadding: PaddingValues? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(6.dp),
    content: WearListScope.() -> Unit,
) {
    val spec = rememberTransformationSpec()
    val rotary = RotaryScrollableDefaults.behavior(state)
    TransformingLazyColumn(
        modifier = modifier.fillMaxSize(),
        state = state,
        contentPadding = contentPadding ?: WearLayout.listPadding(),
        verticalArrangement = verticalArrangement,
        horizontalAlignment = Alignment.CenterHorizontally,
        rotaryScrollableBehavior = rotary,
    ) {
        WearListScope(this, spec).content()
    }
}

/**
 * 普通纵向滚动 + 表冠支持。
 *
 * 表单类页面（设置分组、关于、用量）里单块内容可能超过一屏，不能用
 * [WearList]（缩放会把整块卡片一起缩），但这些页面同样要能用表冠翻。
 * 所以这里给 `verticalScroll` 补上 rotary —— Wear 上「转表冠」和「滑屏幕」
 * 是一等一的两个手势，只做后者等于少了一半操作方式。
 */
@Composable
fun Modifier.wearVerticalScroll(state: ScrollState): Modifier {
    val focus = remember { FocusRequester() }
    val behavior = RotaryScrollableDefaults.behavior(scrollableState = state)
    // 焦点要在组合完成后给，否则 requestFocus 会在节点还没挂上时抛异常
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    return this
        .verticalScroll(state)
        .rotaryScrollable(behavior, focus)
        .focusRequester(focus)
        .focusable()
}
