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
        // 真实下发的只剩常驻的一小撮，但描述总量仍按全量卡：
        // 每一个都可能被 tool_search 取出来，写胖了就是给自己加税。
        assertTrue("工具数变化了，请复核预算：${tools.size}", tools.size in 40..60)
        val descWithoutMeta = tools.filter { it.name != "tool_search" }.sumOf { it.description.length }
        // 5200 是「除元工具外」的旧上限；tool_search 自己那 80 字买下了其余 36 个
        // 工具的 schema 不再常驻，这一笔本身是赚的（见 ToolCatalogTest）。
        assertTrue("非元工具的描述总量 $descWithoutMeta 超预算", descWithoutMeta <= 5200)
        assertTrue("工具描述总量 $desc 超预算", desc <= 5300)
        val schemaWithoutMeta = tools.filter { it.name != "tool_search" }
            .sumOf { it.parameters.toString().length }
        assertTrue("非元工具的 schema 总量 $schemaWithoutMeta 超预算", schemaWithoutMeta <= 13000)
        assertTrue("工具 schema 总量 $schema 超预算", schema <= 13200)
    }

    @Test
    fun `设备工具子集只占全量的一小部分`() {
        val all = com.dshwatch.domain.agent.tools.BuiltinTools.all()
        val device = DeviceToolScope.narrow(all)
        fun cost(tools: List<AgentTool>) =
            tools.sumOf { it.description.length + it.parameters.toString().length }
        val deviceCost = cost(device)
        val allCost = cost(all)
        // 操控期间只带设备工具：收窄必须真的省下一个数量级，否则这个机制没有意义
        assertTrue("设备子集 ${device.size} 个、$deviceCost 字；全量 $allCost 字",
            deviceCost * 4 < allCost)
    }
}
