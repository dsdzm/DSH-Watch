package com.dshwatch.domain.agent.tools

import com.dshwatch.domain.agent.AgentTool
import com.dshwatch.domain.agent.Schema
import com.dshwatch.domain.agent.ToolContext
import com.dshwatch.domain.agent.ToolResult
import com.dshwatch.domain.agent.ToolSafety
import com.dshwatch.domain.agent.build.ProjectBuilder
import com.dshwatch.domain.agent.strOf
import kotlinx.serialization.json.JsonObject

/**
 * 「构建」工具。
 *
 * ## 手表上的「编译」到底是什么
 * 这台设备上**没有真正的编译器** —— 没有 CPython、没有 JDK、没有 Kotlin 编译器，
 * 也没有足够的内存和电量去跑它们（光是 Kotlin 编译器就要几百 MB 堆）。
 * 所以任何声称「在本机编译出可执行文件」的说法都是假的。
 *
 * 那还能做什么？**静态校验**，而且这件事的价值被严重低估：
 * 模型写代码时最常犯的错不是算法错，而是括号没配平、字符串没闭合、
 * Python 缩进混了 tab 和空格、`if` 后面忘了冒号 —— 这些**都能在不上编译器的情况下查出来**，
 * 而且查出来的时机正好：模型刚写完、上下文还没丢的时候，它可以立刻改。
 *
 * 如果等用户把文件拷到电脑上跑、发现报错、再回来描述给模型，
 * 那一轮的成本（往返 + 重新理解上下文）比在手表上查一次高一个数量级。
 *
 * ## 两种产出
 *  - **HTML 项目**：额外把入口页引用的本地 CSS/JS/图片内联成单文件，产出可预览的单文件 HTML 到 `dist/` 下；
 *  - **其它语言**：只出校验报告。产物目录里不会有东西，这是如实的 ——
 *    报告里会写明「本机无法产出可执行文件，请把源码拷到电脑上运行」。
 */
object BuildTools {

    fun all(): List<AgentTool> = listOf(BuildProjectTool)

    object BuildProjectTool : AgentTool {
        override val name = "build_project"
        override val description =
            "校验并构建当前工作目录。会按文件类型逐个做静态检查：" +
                "JSON/YAML 解析、XML/HTML 标签配对、" +
                "JS/TS/Java/Kotlin/C/Swift/Go 的括号与字符串闭合、" +
                "Python 的缩进一致性与冒号、" +
                "CSS/SCSS 括号、Shell 的 if/fi 与 do/done 配对等。" +
                "**有错误会直接失败并给出「文件:行号」**，修好后重新调用即可 —— " +
                "这是你在手表上唯一能验证代码正确性的手段，写完代码请务必调一次。" +
                "若项目里有 HTML 入口，还会把它引用的本地 CSS/JS/图片内联成单文件，产出可预览的 dist/*.html；" +
                "其它语言只出校验报告（手表上没有这些语言的编译器，无法产出可执行文件）。"
        override val safety = ToolSafety.WRITE
        override val parameters = Schema.obj(
            properties = mapOf(
                "entry" to Schema.str(
                    "入口文件。HTML 项目用 index.html；" +
                        "其它语言可指定任意源文件（如 main.py、src/Main.kt），" +
                        "留空时会自动挑一个：优先 index.html，其次任意源文件。"
                ),
                "out_name" to Schema.str("HTML 产物文件名，默认与入口同名，例如 demo.html"),
                "out_dir" to Schema.str("产物输出目录（相对路径），默认 dist"),
            ),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val entry = args.strOf("entry")
            val outName = args.strOf("out_name")
            val outDir = args.strOf("out_dir")
            val report = ProjectBuilder.build(
                store = ctx.workspace,
                entry = entry,
                outName = outName,
                outDir = outDir,
                baseDir = ctx.sessionDir,
            )
            return if (report.ok) {
                // 把产物路径带回上层，界面才能渲染成可点开的「产物卡片」
                ToolResult.ok(report.toToolText(), artifact = report.outputPath.orEmpty())
            } else {
                ToolResult.fail(report.toToolText())
            }
        }

        override fun summarize(args: JsonObject) = "入口 " + (args.strOf("entry") ?: "自动")
    }
}
