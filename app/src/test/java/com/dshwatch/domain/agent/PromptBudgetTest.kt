package com.dshwatch.domain.agent

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词体积护栏。
 *
 * 系统提示与工具 schema 是**每一轮都要发**的固定成本：一次对话二十轮，
 * 多出 200 token 的说明就是 4000 token。这类膨胀没有痛感 —— 它不是某一次
 * 改动造成的，而是每次「顺手多解释一句」累积出来的，所以用测试钉住上限。
 *
 * 数值取当前实际值再留约 15% 余量。要放宽，请先想清楚这 15% 花在哪。
 */
class PromptBudgetTest {

    @Test
    fun `系统提示各段不超预算`() {
        val agent = AgentGuide.PROMPT.length
        val canvas = CanvasGuide.PROMPT.length
        val device = DeviceGuide.PROMPT.length
        assertTrue("AgentGuide 过长：$agent", agent <= 1100)
        assertTrue("CanvasGuide 过长：$canvas", canvas <= 1000)
        assertTrue("DeviceGuide 过长：$device", device <= 500)
    }

    @Test
    fun `工具描述总量不超预算`() {
        val tools = com.dshwatch.domain.agent.tools.BuiltinTools.all()
        val desc = tools.sumOf { it.description.length }
        val schema = tools.sumOf { it.parameters.toString().length }
        // 48 个工具；描述是全量常驻的主要来源，schema 是参数说明
        assertTrue("工具数变化了，请复核预算：${tools.size}", tools.size in 40..60)
        assertTrue("工具描述总量 $desc 超预算", desc <= 5200)
        assertTrue("工具 schema 总量 $schema 超预算", schema <= 13000)
    }
}
