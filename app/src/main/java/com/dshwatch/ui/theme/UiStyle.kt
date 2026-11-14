package com.dshwatch.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import com.dshwatch.domain.model.UiStyles

/**
 * 当前界面体系（[UiStyles.CLASSIC] / [UiStyles.M3E]）。
 *
 * ## 为什么用 CompositionLocal 而不是给每个组件加参数
 *
 * 界面里有两百多处「一行设置项」「一个开关」「一张卡片」的调用点。给每个组件
 * 都加一个 `m3e: Boolean` 参数，结果是每个调用点都要传一遍，而且**总有漏的**
 * —— 漏掉的那几处会在 M3E 模式里保持旧观感，看起来像"没做完"。
 *
 * 放在 CompositionLocal 里，组件自己决定长什么样，调用点一行都不用改。
 * 这也是 Compose 处理「主题」的常规做法（`LocalContentColor` 同理）。
 *
 * 注意它是 `staticCompositionLocalOf`：切换界面体系时整棵树重建本来就是预期行为
 * （两套组件的组合结构不同，没法局部复用），用 static 版本读取开销更低。
 */
val LocalUiStyle = staticCompositionLocalOf { UiStyles.CLASSIC }

/** 便捷判定：当前是不是 M3E 界面 */
val isM3eUi: Boolean
    @androidx.compose.runtime.Composable
    get() = UiStyles.isM3e(LocalUiStyle.current)
