package com.dshwatch.domain.model

/**
 * 界面体系：经典 / M3E。
 *
 * ## 为什么要「两套并列」而不是「直接把界面换掉」
 *
 * 经典那套是为一件事优化的：**在圆表盘上把尽量多的字塞进尽量小的面积**。
 * 顶栏压到 33dp、内容区固定 0.69d、卡片 24dp 圆角、自绘玻璃材质 ——
 * 每一项都是几何推导出来的，代价是「不像系统应用」。
 *
 * M3E 那套反过来：直接用 Wear Material 3 Expressive 的 `AppScaffold` /
 * `ButtonGroup` / `Card` / `TimeText`，一眼就是 Wear OS 4/5 的原生观感，
 * 代价是每屏能放的内容比经典少一档。
 *
 * 两种取向都是合理的，没有哪一种是「新版」—— 所以做成并列的两个选项，
 * 而不是一次覆盖式升级。用户选哪个，全局就是哪个。
 *
 * ## 注意它和 [Appearances] 不是一回事
 *
 * | 字段 | 决定 |
 * |---|---|
 * | [AppSettings.uiStyle] | **控件与版式**：用哪一套组件搭页面 |
 * | [AppSettings.appearance] | **材质**：卡片是纯色还是玻璃 |
 * | [AppSettings.themePreset] | **颜色**：哪套色板 |
 *
 * M3E 界面 + 玻璃材质是合法的组合（玻璃卡片套在 M3E 骨架里），
 * 反之亦然。三者互不替代。
 */
object UiStyles {

    /**
     * 经典界面（默认）。
     *
     * 默认选它而不是 M3E，理由很实际：老用户升级后界面不该变样。
     * 新用户想要原生观感，引导页最后一屏会问一次。
     */
    const val CLASSIC = "classic"

    /** Wear Material 3 Expressive：整套组件换成 `androidx.wear.compose.material3` */
    const val M3E = "m3e"

    val all = listOf(CLASSIC, M3E)

    fun of(id: String): String = if (id == M3E) M3E else CLASSIC

    fun isM3e(id: String): Boolean = of(id) == M3E
}
