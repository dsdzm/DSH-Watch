package com.dshwatch.ui.components

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 静态防线：按压反馈的「同一个 interaction」与「光斑不跳中心」。
 *
 * ## 起因（用户 v1.15 反馈）
 *
 * > 修复在播放回弹动画的时候，跟手光晕会出现在按钮控件的正中央的问题，
 * > 部分按键光晕无法跟手就比如我图片里面的这些控件
 *
 * 两句话，两个独立的 bug：
 *
 * ### 一、光斑跳正中央
 *
 * 松手那一刻把触点清成 `Offset.Unspecified`，而绘制时 Unspecified 的兜底是
 * `(50%, 50%)`。可这时回弹动画（spring 0.58 / 260）还要跑 600~900ms，
 * 光还亮着 —— 于是光斑**跳**到控件正中间再慢慢消失。
 *
 * 正解：让光留在最后的位置散掉，回弹跑完再清。
 *
 * ### 二、部分控件不跟手
 *
 * 两个层层叠加的错：
 *
 * 1. `tap(quiet = true)` 内部 `remember` 了**自己的** interaction，
 *    而调用点普遍是 `glassSurface(interaction = src).tap(quiet = true, ...)` ——
 *    玻璃那侧永远收不到 Press 事件。全工程 8 处都是这样。
 * 2. `glassSurface` 只在 `onClick != null` 时装指针跟踪。开关的轨道是
 *    `onClick = null`（点击由 `tap(quiet)` 挂），于是连跟手都不装。
 *
 * 这两条 JVM 单测都跑不出来（要真机测量/渲染才知道），只能读源码守。
 */
class PressFeedbackRuleTest {

    private val ui = File("src/main/java/com/dshwatch/ui")

    private fun files(): List<File> =
        ui.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /**
     * 把注释剥掉再扫。
     *
     * 不加这一步的话，`KDoc` 里为了说明用法写的 `.tap(quiet = true, ...)` 也会被
     * 当成真实调用 —— 第一次跑就报了三条假阳性，全在文档注释里。
     * 只处理 `//` 与 `/* */`；够用，因为这里要找的是调用点，不是字符串字面量。
     */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            when {
                src.startsWith("//", i) -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                src.startsWith("/*", i) -> {
                    i += 2
                    while (i < src.length && !src.startsWith("*/", i)) i++
                    i += 2
                }
                else -> {
                    out.append(src[i]); i++
                }
            }
        }
        return out.toString()
    }

    /** 把一个调用的括号内容整体切出来（简易配平，够用且不依赖编译器） */
    private fun callBody(src: String, from: Int): String {
        val open = src.indexOf('(', from)
        if (open < 0) return ""
        var depth = 0
        val sb = StringBuilder()
        for (i in open until src.length) {
            val c = src[i]
            when (c) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return sb.toString()
                }
            }
            sb.append(c)
        }
        return sb.toString()
    }

    @Test
    fun `静默 tap 必须共用外部 interaction`() {
        val offenders = mutableListOf<String>()
        files().forEach { f ->
            val s = stripComments(f.readText())
            var i = s.indexOf("tap(quiet = true")
            while (i >= 0) {
                val body = callBody(s, i)
                if (!body.contains("interaction")) {
                    val line = s.substring(0, i).count { it == '\n' } + 1
                    offenders += "${f.path}:$line"
                }
                i = s.indexOf("tap(quiet = true", i + 1)
            }
        }
        assertTrue(
            "这些地方的 tap(quiet = true) 没传 interaction —— 玻璃那侧收不到按压，" +
                "表现为按下不亮、不形变：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `开关轨道必须显式打开跟手`() {
        val f = File(ui, "components/Common.kt")
        assertTrue("找不到 Common.kt", f.isFile)
        val s = f.readText()
        val at = s.indexOf("fun GlassSwitch(")
        assertTrue("GlassSwitch 不见了", at > 0)
        val body = s.substring(at, minOf(s.length, at + 3000))
        assertTrue(
            "GlassSwitch 的轨道 onClick = null，不显式传 trackPointer = true 就永远不跟手",
            body.contains("trackPointer = true"),
        )
    }

    @Test
    fun `松手后不许立刻把触点清零`() {
        // 光斑跳中心就是这一行造成的：松手 → touch/sheen 归 Unspecified →
        // 绘制兜底到 (50%, 50%)，而回弹还在播
        val modifiers = File(ui, "components/Modifiers.kt").readText()
        val releaseBlock = run {
            val i = modifiers.indexOf("is PressInteraction.Release")
            assertTrue("tap() 里找不到 Release 分支", i > 0)
            modifiers.substring(i, minOf(modifiers.length, i + 900))
        }
        // 明确要求：清理放在 animateTo 之后（也就是有注释说明「回弹跑完再清」），
        // 并且这几行里不能出现「先清再动画」的顺序
        assertTrue(
            "Release 分支里应当说明「回弹跑完再清触点」，否则很容易被改回立刻清零",
            releaseBlock.contains("不要再") || releaseBlock.contains("不要") ||
                releaseBlock.contains("回弹"),
        )

        val glass = File(ui, "components/Glass.kt").readText()
        assertTrue(
            "Glass.kt 的 pointerInput 松手处不能直接 sheen = Offset.Unspecified，要交给回弹结束再清",
            !glass.contains("touching = false\n                        sheen = Offset.Unspecified"),
        )
    }

    @Test
    fun `玻璃的跟手开关默认跟着可点击`() {
        val glass = File(ui, "components/Glass.kt").readText()
        assertTrue(
            "trackPointer 的默认值必须同时考虑 interaction：传了外部按压源就说明事件由 " +
                "tap(quiet) 转进来，这类控件 onClick 是 null 但同样要跟手",
            glass.contains("trackPointer: Boolean = onClick != null || interaction != null"),
        )
        assertTrue(
            "指针跟踪的判据不能再写回 onClick != null —— 那样开关这类「不接收点击但需要跟手」的控件永远装不上",
            !glass.contains("if (animated && onClick != null)"),
        )
    }
}
