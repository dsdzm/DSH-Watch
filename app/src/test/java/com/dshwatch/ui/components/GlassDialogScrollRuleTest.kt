package com.dshwatch.ui.components

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 静态防线：`GlassDialog` 的 content 里不能再塞滚动容器。
 *
 * 背景（v1.15 的真实崩溃）：
 *
 * ```
 * IllegalStateException: Vertically scrollable component was measured with an
 * infinity maximum height constraints, which is disallowed.
 *   at CheckScrollableContainerConstraintsKt.checkScrollableContainerConstraints
 *   at ScrollNode.measure
 *   at AndroidDialog_androidKt$DialogLayout$1.measure
 * ```
 *
 * `GlassDialog` 默认会在正文外包一层 `heightIn(max) + verticalScroll`。
 * 滚动容器测量子项时传下去的是**无限高度**，所以 content 里如果还有
 * `verticalScroll` / `wearVerticalScroll` / `LazyColumn`，里层立刻抛异常 ——
 * 一打开弹窗就整个进程挂掉，而且**JVM 单测测不出来**（没有测量阶段）。
 *
 * 这一条用最笨的办法守：读源码，找 `GlassDialog(` 调用，把调用段内的
 * 滚动修饰符和 `scrollable = false` 对照一遍。笨，但它真的能拦住回归。
 */
class GlassDialogScrollRuleTest {

    private val uiRoot = File("src/main/java/com/dshwatch/ui")

    /** 三个会要求「有限最大高度」的修饰符 */
    private val scrollables = listOf("verticalScroll", "wearVerticalScroll", "LazyColumn")

    @Test
    fun `content 里有滚动容器时必须关掉外层滚动`() {
        assertTrue("找不到 UI 源码目录：${uiRoot.absolutePath}", uiRoot.isDirectory)

        val offenders = mutableListOf<String>()

        uiRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            // 组件本身的定义不算调用点
            .filter { it.name != "GlassDialog.kt" }
            .forEach { file ->
                val raw = file.readText()
                var index = raw.indexOf("GlassDialog(")
                while (index >= 0) {
                    val call = extractCall(raw, index)
                    val code = stripComments(call)
                    if (scrollables.any { it in code } && "scrollable = false" !in code) {
                        val line = raw.substring(0, index).count { it == '\n' } + 1
                        offenders += "${file.path}:$line"
                    }
                    index = raw.indexOf("GlassDialog(", index + 1)
                }
            }

        assertTrue(
            "这些 GlassDialog 的 content 里有滚动容器，却没写 scrollable = false ——\n" +
                "里层会拿到无限高度，打开就崩（详情见本测试的类注释）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** 从 `GlassDialog(` 往后按括号配平截出整个调用 */
    private fun extractCall(source: String, start: Int): String {
        val open = source.indexOf('(', start)
        if (open < 0) return ""
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
            i++
        }
        return source.substring(open)
    }

    /** 去掉 `//` 行注释与 `/* */` 块注释，避免注释里的示例代码造成误报 */
    private fun stripComments(text: String): String =
        text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .lines()
            .joinToString("\n") { line ->
                val cut = line.indexOf("//")
                if (cut >= 0) line.substring(0, cut) else line
            }
}
