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
 * 当时的假设是「非玻璃主题也能靠弹窗层的 dialogBackdrop 糊掉背景」。但那个函数
 * 开头第一行就是 `if (!LocalGlass.current) return this` —— 非玻璃主题下是空操作；
 * 而 `Modifier.blur` 在 API 31 以下还会被**静默忽略**。
 * 两个「不会发生」叠在一起，结果就是 M3E 的弹窗后面什么也没有。
 * （1.15.7 里 dialogBackdrop 已整个删除，见最后一条测试。）
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

    /**
     * 把注释剥掉再扫。
     *
     * 这已经是**第二次**被同一件事骗了：v1.15 的 `PressFeedbackRuleTest` 第一次跑就
     * 报了三条假阳性，全在 KDoc 里（为了讲用法写的示例调用被当成真实调用）。
     *
     * 这次也一样 —— 我在 Backdrop.kt 的注释里写了「第一版写的是
     * `LaunchedEffect(resetKey) { ... }`」，而断言正是「不许出现 LaunchedEffect(resetKey)」。
     * 于是测试红在一个已经改好的实现上。
     *
     * 教训：**凡是扫源码的断言，先剥注释**。这一条以后每次都带着。
     */
    private fun stripComments(text: String): String =
        text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")

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
            "GlassDialog 必须在进入时把 LocalBackdropDepth 加一、离开时减一，" +
                "否则背景永远不糊 / 永远糊着",
            s.contains("LocalBackdropDepth") && s.contains("depth.value += 1"),
        )
        assertTrue(
            "关掉时必须减一（并且夹到 >= 0）—— 写成归零的话，两个弹窗叠在一起时，" +
                "先关的那个会把另一个的模糊也一起关掉",
            s.contains("depth.value = (depth.value - 1).coerceAtLeast(0)"),
        )
    }

    @Test
    fun `模糊不能跟着动画值走，否则关掉之后还要糊一段时间`() {
        /*
         * v1.15 的用户反馈：「好多地方点开就加载个背景模糊就没了」。
         *
         * 直接原因是这个：闸归零后 `animateFloatAsState` 还要跑 160ms 才落到 0，
         * 而那 160ms 恰好是用户最可能点下一步的时间。新页面继承上一页的模糊，
         * 一进去就是糊的 —— 内容在糊层底下，看着就是「什么都没了」。
         *
         * 修法是两条一起：
         *   1. 是否挂 blur 由「计数 > 0」决定，不由动画值决定（闸归零即摘除）
         *   2. 宿主收 resetKey，页面一变无条件清零
         */
        val s = File("src/main/java/com/dshwatch/ui/components/Backdrop.kt").readText()
        val code = stripComments(s)
        assertTrue(
            "blur 的挂载条件里必须有 active（计数），不能只有 animated",
            code.contains("if (real && active && animated >"),
        )
        assertTrue(
            "宿主必须收 resetKey，并且页面变化时把计数清零",
            code.contains("resetKey") && code.contains("depth.value = 0"),
        )
        /*
         * 清零必须用同步副作用，不能用 LaunchedEffect。
         *
         * DisposableEffect（弹窗把计数 +1）在 apply 时**同步**执行；
         * LaunchedEffect 的协程体要等一次 dispatch。于是「进入页面时就已经有弹窗」
         * 的情况会被这次清零抹掉 —— 弹窗开着、背景不糊。首屏恰好最可能带弹窗。
         */
        assertTrue(
            "清零要用 SideEffect（同步），不能用 LaunchedEffect —— 后者会晚一帧，" +
                "把首屏弹窗刚加上的计数抹掉",
            code.contains("SideEffect") && !code.contains("LaunchedEffect(resetKey)"),
        )
        assertTrue(
            "AppRoot 必须把当前页面传给宿主，否则切页不会清模糊",
            File("src/main/java/com/dshwatch/ui/AppRoot.kt").readText()
                .contains("GlassBackdropHost(resetKey = page)"),
        )
    }

    @Test
    fun `模糊层挂在宿主页面上而不是弹窗里`() {
        // Dialog 画在另一个 Window，它模糊不了自己背后的 Activity —— 这条约束决定
        // 了 GlassBackdropHost 只能出现在 AppRoot（页面那一层），不能出现在 GlassDialog 里
        val dialog = stripComments(source())
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

    @Test
    fun `弹窗窗口内不许再挂任何模糊`() {
        /*
         * 用户 1.15.7 反馈（华为手表等圆屏 + 玻璃主题）：
         * 「弹窗类控件触发后只显示弹窗本身模糊的背景，没有内容」。
         *
         * 根因：GlassDialog 的遮罩根 Box 上挂过 `dialogBackdrop()`，
         * 玻璃主题 + API 31+ 时它等于 `blur(26.dp)`。卡片 Column 是这个 Box
         * 的 content，而 Modifier.blur 作用于**整棵子树** —— 标题、正文、按钮
         * 一起被糊掉。Dialog 又在另一个 Window，本来就糊不到背后那页 Activity。
         *
         * 修法：Dialog 里一点模糊都不挂，背景模糊只由页面层的 GlassBackdropHost 做。
         * 上面那条测试只查了 `Modifier.blur(`，查不到这种间接调用，所以补这条。
         */
        val dialog = stripComments(source())
        assertTrue(
            "GlassDialog 里不许再调 dialogBackdrop()",
            !dialog.contains("dialogBackdrop()"),
        )
        val frosted = stripComments(File(src, "Frosted.kt").readText())
        assertTrue(
            "Frosted.kt 里不该再有给弹窗画磨砂的函数",
            !frosted.contains("fun Modifier.dialogBackdrop") &&
                !frosted.contains("fun Modifier.frostedBackground") &&
                !frosted.contains(".blur("),
        )
        val backdrop = stripComments(File(src, "Backdrop.kt").readText())
        assertTrue(
            "页面层的模糊必须还在，否则弹窗背后就不糊了",
            backdrop.contains("Modifier.blur(MaxBlurRadius") &&
                backdrop.contains("drawFrostVeil"),
        )
    }
}
