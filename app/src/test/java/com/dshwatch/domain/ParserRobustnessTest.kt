package com.dshwatch.domain

import com.google.common.truth.Truth.assertThat
import com.dshwatch.ui.html.HtmlBlocks
import org.junit.Test

/**
 * 解析器的抗噪性。
 *
 * ## 为什么这条链路值得单独盯
 *
 * 这些解析全部跑在**组合期**：`remember(body) { Panels.extract(body) }` 之类。
 * 组合期是 Choreographer 的帧回调，异常没有上一层的 try/catch 可接，直接冒到
 * `Looper.loop` = 闪退。而组合的内容来自**历史消息** —— 一条存进会话记录的畸形
 * 输出，会在之后每一次冷启动时被重新解析一遍，用户被自己的聊天记录卡死不出去。
 *
 * 1.23.2(84) 就是这么崩的：正则写错一个反斜杠，`PatternSyntaxException` 从
 * 思维导图解析里抛出来。
 *
 * 所以这里的判定标准只有一条：**任何输入都不能抛**。结果难看可以接受 ——
 * 显示原文也算工作，闪退不行。
 */
class ParserRobustnessTest {

    /** 能想到的畸形输入：半截围栏、嵌套围栏、超长行、控制字符、极端括号 */
    private val nasty = listOf(
        "",
        " ",
        "\u0000\u0001\u0002",
        "```",
        "```panel",
        "```panel 标题",
        "```panel 标题 | 副标题\n",
        "```panel 公子 · 墨染 | 江南书香世家嫡子\n姓名：沈砚\n```",
        "```panel a|b\n||\n```",
        "```panel a|b\n1\n```\n```panel c|d\n2\n```\n```panel e|f\n3\n```\n```panel g|h\n4\n```",
        "```panel\n\n\n```",
        "```mindmap\n{",
        "```mindmap\n}\n}",
        "```mindmap\nroot((x)\n",
        "```geom\n{}",
        "```geom\n{\"points\":[",
        "```geom\n{\"points\":[[1]],\"segments\":[[]]}```",
        "```math\n\\frac{1}{",
        "```math\n\\sqrt",
        "```html\n<!DOCTYPE html>",
        "<html><body>",
        "A. 选项一\nB. 选项二",
        "A.",
        "1.",
        "1",
        "|",
        "||||",
        "#",
        ">",
        "- ",
        "汉字".repeat(2000),
        "}".repeat(500),
        "{".repeat(500),
        "(".repeat(500),
        "*".repeat(500),
        "$$".repeat(100),
    )

    @Test
    fun `正文解析不会把异常抛给组合期`() {
        for (t in nasty) {
            Panels.extract(t)
            Choices.parse(t)
            HtmlBlocks.find(t)
        }
    }

    @Test
    fun `去掉界面的结果不会变长`() {
        // strip 用 replace(block.raw, "")，如果 raw 为空串会变成「每个字符之间插入」——
        // 那是比闪退更隐蔽的伤害：正文被撑成几百倍
        for (t in nasty) {
            val block = HtmlBlocks.find(t)
            if (block != null) {
                assertThat(HtmlBlocks.strip(t, block).length).isAtMost(t.length)
            }
        }
    }

    @Test
    fun `围栏解析只认闭合的块`() {
        val closed = "前言\n```panel 标题 | 副标题\n姓名：沈砚\n```\n后记"
        assertThat(Panels.extract(closed).panels).hasSize(1)

        val open = "前言\n```panel 标题 | 副标题\n姓名：沈砚"
        assertThat(Panels.extract(open).panels).isEmpty()
    }
}
