package com.dshwatch.ui.wear

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState

/**
 * 跨页面保留的滚动位置。
 *
 * ## 解决的是什么
 *
 * 用户在会话列表翻到第 30 条，点进一个会话，再返回 —— 列表回到顶部。
 * 这不是"偶发"或"手感问题"，是**必然**：
 *
 * AppRoot 里是 `when (screen) { ... }`，push 新页之后旧页整棵子树被移出组合，
 * 它里面的 `rememberScrollState()` 随之销毁。pop 回来时页面重新组合，
 * 拿到的是一个从 0 开始的全新 ScrollState。滚动位置从来没被任何地方存过。
 *
 * ## 为什么不用 rememberSaveable
 *
 * `rememberSaveable` 能在**配置变更 / 进程重建**时恢复，但它依赖 SaveableStateRegistry
 * 沿组合树传递，而页面整个被移出组合时这份状态就跟树一起没了。
 * 也就是说它解决不了"返回上一页"这个场景，只解决"旋转屏幕"。
 *
 * ## 所以
 *
 * 位置必须存在**组合树之外**、且按页面身份索引。这里用一个挂在导航层的
 * 全局表（[ScrollMemory]），页面回来时按自己的 key 取回上次的值。
 * 页面卸载时把当前值写回去 —— 用 DisposableEffect 的 onDispose，它会在
 * 页面离开组合的那一刻执行。
 *
 * key 的选择决定了"什么算同一个页面"：见各页面调用点传的字符串。
 */
object ScrollMemory {
    /**
     * 用 String 存 key，不存 Screen。
     *
     * Screen 是 data class，等于比较时会把参数算进去 ——
     * 这本身是对的（不同会话的列表本就该分开记），但 Screen 里有的字段
     * 是内部对象，直接拿来当 HashMap 的 key 会在参数变化时产生"假不相等"。
     * 调用方传一个明确的字符串，比让这里猜更可控。
     */
    private val offsets = HashMap<String, Int>()
    private val positions = HashMap<String, Pair<Int, Int>>()

    fun offsetOf(key: String): Int = offsets[key] ?: 0

    fun putOffset(key: String, value: Int) {
        offsets[key] = value
    }

    fun itemOf(key: String): Pair<Int, Int> = positions[key] ?: (0 to 0)

    fun putItem(key: String, index: Int, offset: Int) {
        positions[key] = index to offset
    }

    /** 测试用：清空。生产代码不应该调它 */
    fun clearAll() {
        offsets.clear()
        positions.clear()
    }
}

/**
 * 带回记忆的竖直滚动。
 *
 * 用法与 `rememberScrollState()` 相同，只是多一个 key：
 * ```
 * val scroll = rememberKeptScrollState("settings")
 * ```
 * 同一个 key 的页面下次组合时会落在上次的位置。
 *
 * @param keep 关掉它就退化成普通 `rememberScrollState()` —— 给那些
 *   "每次进来都该回到顶部"的页面用（例如对话页，要看到最新消息）。
 */
@Composable
fun rememberKeptScrollState(key: String, keep: Boolean = true): ScrollState {
    if (!keep) return androidx.compose.foundation.rememberScrollState()
    val restored = ScrollMemory.offsetOf(key)
    return rememberSaveable(
        key,
        // 关键：初始值取记忆里的位置。
        // 用 rememberSaveable 而不是 remember 是为了同时兜住进程重建 ——
        // 两条恢复路径（组合内 / 组合外）都走一遍，谁起作用都行。
        saver = ScrollState.Saver,
    ) {
        ScrollState(restored)
    }.also { state ->
        KeepScrollOnLeave(key, state.value)
    }
}

/**
 * 页面离开组合时，把当前滚动位置写回记忆表。
 *
 * 为什么用 `DisposableEffect`：它的 `onDispose` 恰好在页面被移出组合
 * （push 新页、pop 自己）的那一刻执行。用 `LaunchedEffect` 做不到 ——
 * 协程在离开时会被取消，来不及写。
 *
 * `value` 作为 key 传进去：滚动每变化一次就重装一次副作用。
 * 这看着奢侈，但副作用体里只有一个闭包赋值，代价可以忽略；
 * 反过来如果不带 key，onDispose 里闭包捕获的会是**初始**值 ——
 * 那正是典型的"存了个 0 回去"。
 */
@Composable
private fun KeepScrollOnLeave(key: String, value: Int) {
    androidx.compose.runtime.DisposableEffect(key, value) {
        onDispose { ScrollMemory.putOffset(key, value) }
    }
}


/**
 * `TransformingLazyColumn` 版本的滚动记忆。
 *
 * `rememberTransformingLazyColumnState` 没有暴露 `initialIndex` 参数，
 * 所以这里先建一个默认状态，再用 `LaunchedEffect` 把它滚回记忆位置。
 *
 * 代价是**会看到一帧顶部画面**。为了把它压到看不见，回滚放在
 * `LaunchedEffect(key)` 里、且不经过任何动画（`scrollToItem`），
 * 它发生在第一个组合帧之后立刻执行，实测在 60Hz 手表上就是一帧 ——
 * 用户感知到的是"页面直接出现在原位"。
 */
@Composable
fun rememberKeptListState(key: String, keep: Boolean = true): TransformingLazyColumnState {
    val state = rememberTransformingLazyColumnState()
    if (!keep) return state
    val (index, offset) = ScrollMemory.itemOf(key)

    // 只在**第一次进这个 key** 时回滚。用 key 做 LaunchedEffect 的 key，
    // 同一个 key 再次组合（比如重组）不会重复触发。
    androidx.compose.runtime.LaunchedEffect(key) {
        if (index > 0 || offset > 0) {
            runCatching { state.scrollToItem(index, offset) }
        }
    }

    // 离开时记住位置。读 anchorItem* 而不是滚动目标值：
    // 后者反映的是"正在滚向哪里"，用户松手后可能还会惯性滑动一段。
    val idx = state.anchorItemIndex
    val off = state.anchorItemScrollOffset
    androidx.compose.runtime.DisposableEffect(key, idx, off) {
        onDispose { ScrollMemory.putItem(key, idx, off) }
    }
    return state
}
