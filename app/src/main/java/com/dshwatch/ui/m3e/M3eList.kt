package com.dshwatch.ui.m3e

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState

/**
 * M3E 页面统一用的列表。
 *
 * ## 为什么必须是 `TransformingLazyColumn`
 *
 * 经典那套用 `LazyColumn` 加逐项的 padding / scale —— 那是「模拟」出鱼眼，
 * 每一项的几何都随滚动重算，文字可用宽度逐帧变化，换行位置会跳（这条
 * 在 1.15.7 因为抖动被整套删掉了）。
 *
 * `TransformingLazyColumn` 是官方的做法：变换发生在**绘制阶段**，
 * 布局尺寸只算一次，靠边时缩小、变淡、形状收窄，文字不重排、不抖动。
 * 这是 M3E 列表「会呼吸」的来源，也是自己拼不出来的部分。
 *
 * ## 内边距是全项目共用的三个常量
 *
 * 官方给的是 `PaddingValues(horizontal = 6.dp, vertical = 32.dp)`：
 * 上下各 32dp 是为了让首尾项能完整露出来（圆屏边缘会被切），
 * 左右 6dp 让卡片的圆角不被圆周吃掉。这两个数不要各页各写一套 ——
 * 一页一个值的结果是翻页时列表左右边界在跳。
 */
@Composable
fun M3eList(
    state: TransformingLazyColumnState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = M3eListPadding,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(6.dp),
    content: TransformingLazyColumnScope.() -> Unit,
) {
    TransformingLazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** 列表的内边距：上下留够首尾项的呼吸，左右让圆角躲开圆周 */
val M3eListPadding = PaddingValues(horizontal = 6.dp, vertical = 32.dp)

/**
 * 建一个记住的列表状态。
 *
 * 单独包一层是为了统一初始锚点：`initialFirstVisibleItemIndex = 0` 时
 * Wear 会把第 0 项放在**圆心**（不是顶部），这正是官方列表的默认观感 ——
 * 列表从屏幕中间开始，上面那一截留给时间与下滑的余量。
 */
@Composable
fun rememberM3eListState(
    initialIndex: Int = 0,
    initialOffset: Int = 0,
): TransformingLazyColumnState = rememberTransformingLazyColumnState(
    initialIndex,
    initialOffset,
)
