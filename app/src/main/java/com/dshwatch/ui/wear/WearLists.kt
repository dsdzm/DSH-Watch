package com.dshwatch.ui.wear

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListAnchorType
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import com.dshwatch.ui.theme.LocalDeviceProfile

/**
 * Wear OS 列表的基础件。
 *
 * ## 为什么用 ScalingLazyColumn
 *
 * 它是 Wear 上「鱼眼」效果的正解：列表项越靠近屏幕中心越大（1.0），
 * 越靠近上下边缘越小（0.7）并逐渐透明，同时**自带旋转表冠支持**。
 * 自己用 graphicsLayer 去算缩放也能出效果，但拿不到表冠事件，
 * 而且滚动惯性、边界回弹、无障碍焦点都会跟着丢。
 *
 * ## 为什么圆屏要单独收窄
 *
 * 圆屏的可用区域是一个内切于屏幕的圆：屏幕 205dp 宽时，贴着上下边缘那几行
 * 的可用宽度只有中间那一行的一半。ScalingLazyColumn 的缩放只改视觉大小，
 * 不改变布局宽度，所以还是要靠 contentPadding 把内容收进圆里。
 */
object WearLayout {

    /** 列表左右留白：圆屏收得更狠，方屏只留一点点呼吸感 */
    @Composable
    fun horizontalPadding(): Dp {
        val p = LocalDeviceProfile.current
        return when {
            p.isRound -> 16.dp
            p.compact -> 8.dp
            else -> 10.dp
        }
    }

    /**
     * 列表内边距。
     *
     * 上下都留 26dp：顶部是给 TimeText 让位（Wear 的时间显示叠在内容上方），
     * 底部是让最后一项能滚到「视觉中心」，而不是贴着屏幕下沿被裁掉一半。
     */
    @Composable
    fun listPadding(): PaddingValues {
        val h = horizontalPadding()
        return PaddingValues(start = h, end = h, top = 26.dp, bottom = 26.dp)
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
 * 带鱼眼效果的列表。
 *
 * 用法与 `LazyColumn` 基本一致，只是 scope 换成 `ScalingLazyListScope`：
 * ```
 * WearList {
 *     item { ... }
 *     items(list.size) { i -> ... }
 * }
 * ```
 *
 * 注意单一项的高度不要超过一屏 —— 超过之后缩放会失真（整块一起缩小），
 * 那时应该改用普通 `Column` + 垂直滚动，或者把这项拆成多条。
 */
@Composable
fun WearList(
    modifier: Modifier = Modifier,
    /**
     * 列表状态。
     *
     * 想在这个列表「返回后回到原位」时，传一个
     * `rememberKeptScalingListState("页面名")`（见 ScrollMemory.kt）；
     * 不传就是每次进页面从顶部开始。
     */
    state: ScalingLazyListState = rememberScalingLazyListState(),
    contentPadding: PaddingValues? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(6.dp),
    content: ScalingLazyListScope.() -> Unit,
) {
    ScalingLazyColumn(
        modifier = modifier.fillMaxSize(),
        state = state,
        contentPadding = contentPadding ?: WearLayout.listPadding(),
        verticalArrangement = verticalArrangement,
        horizontalAlignment = Alignment.CenterHorizontally,
        // ItemCenter：第一项和最后一项也能滚到屏幕正中，配合上下 26dp 的留白，
        // 圆屏下不会出现「第一项被顶边切一半」的情况
        anchorType = ScalingLazyListAnchorType.ItemCenter,
        content = content,
    )
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
