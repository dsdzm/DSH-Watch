package com.dshwatch.domain.model

/**
 * 外观风格：界面用哪种「材质」。
 *
 * 玻璃和 M3E **共用同一套底色**。
 *
 * 这一点是用户明确纠正过的：玻璃主题不该有自己的背景。
 * 玻璃的本质是「透」——它透出来的是底下那张纸的颜色。把纸换成黑色，
 * 玻璃就只剩一块灰板，什么也透不出来；反过来，白底上的玻璃才看得出
 * 透明、厚薄、边缘光。所以玻璃只是一个**绘制方式**，
 * 底色仍然由主题预设（[AppSettings.themePreset]）决定，预设也仍然包含
 * 「玻璃」这一项（见 `ThemePresets.GLASS_ID`）。
 *
 * 于是：
 * - **预设**回答「底是什么颜色」，包含质感玻璃那一套
 * - **外观风格**回答「卡片是纯色还是玻璃」
 *
 * 「玻璃 + 樱花」= 粉调底上的玻璃，「M3E + 质感玻璃预设」= 同一套底色上的实心卡片。
 */
object Appearances {

    /** M3E 纯色：卡片是不透明的容器色 */
    const val M3E = "m3e"

    /** 质感玻璃：卡片、按钮、开关都是半透明玻璃，能透出背后的底色 */
    const val GLASS = "glass"

    val all = listOf(M3E, GLASS)

    fun of(id: String): String = if (id == GLASS) GLASS else M3E

    fun isGlass(id: String): Boolean = id == GLASS
}
