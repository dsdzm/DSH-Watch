package com.dshwatch.ui.components

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 静态防线：M3E 主题的弹窗**必须有遮罩**。
 *
 * 背景（用户 v1.15 反馈）：「OLED 黑和其他主题色的弹窗太透明了，没有给弹窗背景做模糊」。
 * 截图里弹窗后面的设置页文字一行行清清楚楚，纸色和卡片几乎同亮度
 * （实测卡片内 111.4 / 卡片外 110.5）。
 *
 * 根因是我上一版的一个逻辑错位：
 *
 * ```
 * 遮罩 = if (玻璃) 透明 else 黑 0.45
 * ```
 *
 * 当时的假设是「非玻璃主题也能靠 dialogBackdrop 糊掉背景」。但
 * `dialogBackdrop()` 开头第一行就是 `if (!LocalGlass.current) return this` —— 它是空操作；
 * 而 `Modifier.blur` 在 API 31 以下还会被**静默忽略**。
 * 两个「不会发生」叠在一起，结果就是 M3E 的弹窗后面什么也没有。
 *
 * 这条测试守的是源码结构，不是运行时：只要还有人把遮罩写成
 * 「玻璃透明 / 其它透明」，它就会红。
 */
class GlassDialogScrimRuleTest {

    private val src = File("src/main/java/com/dshwatch/ui/components")

    private fun source(): String {
        val f = File(src, "GlassDialog.kt")
        assertTrue("找不到 GlassDialog.kt：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    @Test
    fun `非玻璃主题的遮罩不许是透明的`() {
        val s = source()
        // 只有当「玻璃分支」和「非玻璃分支」分开写时，才可能漏掉非玻璃那一支
        assertTrue("GlassDialog 里应当有 glass 分支", s.contains("glass ->"))
        assertTrue(
            "M3E 主题必须自己压一层黑：0.45（无系统模糊）或 0.12（有系统模糊）",
            s.contains("0.45f") && s.contains("0.12f"),
        )
        // 原来那句「玻璃恒透明、其余恒透明」的写法不能再出现
        assertTrue(
            "不要再写 `if (glass) Transparent else Transparent` 式的遮罩",
            !s.contains("glass -> Color.Transparent\n                        else -> Color.Transparent"),
        )
    }

    @Test
    fun `弹窗打开时通知宿主糊背景`() {
        val s = source()
        assertTrue(
            "GlassDialog 必须在进入时把 LocalBackdropVeil 置 1、离开时归 0，否则背景永远不糊 / 永远糊着",
            s.contains("LocalBackdropVeil") && s.contains("veil.value = 1f") && s.contains("veil.value = 0f"),
        )
    }

    @Test
    fun `模糊层挂在宿主页面上而不是弹窗里`() {
        // Dialog 画在另一个 Window，它模糊不了自己背后的 Activity —— 这条约束决定
        // 了 GlassBackdropHost 只能出现在 AppRoot（页面那一层），不能出现在 GlassDialog 里
        val dialog = source()
        assertTrue(
            "GlassDialog 里不该出现 Modifier.blur：它只能糊自己，糊不到背景",
            !dialog.contains("Modifier.blur(") && !dialog.contains(".blur("),
        )
        val root = File("src/main/java/com/dshwatch/ui/AppRoot.kt")
        assertTrue("AppRoot 没了", root.isFile)
        assertTrue(
            "AppRoot 必须用 GlassBackdropHost 包住页面",
            root.readText().contains("GlassBackdropHost"),
        )
    }
}
