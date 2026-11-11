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
        // 真实下发的集合按 enabled 过滤：关掉的功能（B站、云端编译、设备操控、Shizuku）
        // 连 schema 都不进请求，不该算进「每一轮都要付的钱」。
        val active = tools.filter { it.enabled(com.dshwatch.domain.model.AppSettings()) }
        val descActive = active.sumOf { it.description.length }
        val schemaActive = active.sumOf { it.parameters.toString().length }
        assertTrue("工具数变化了，请复核预算：${tools.size}", tools.size in 40..60)
        assertTrue("默认开关下的描述总量 $descActive 超预算", descActive <= 5200)
        assertTrue("默认开关下的 schema 总量 $schemaActive 超预算", schemaActive <= 13000)

        // 全量另设一个松一点的硬上限：这些工具默认关着，但真打开时也要能塞得下。
        // 它防的是「某个长尾工具写到没人管」，不是每轮成本。
        val descAll = tools.sumOf { it.description.length }
        val schemaAll = tools.sumOf { it.parameters.toString().length }
        assertTrue("全量描述总量 $descAll 超上限", descAll <= 5600)
        assertTrue("全量 schema 总量 $schemaAll 超上限", schemaAll <= 14500)
    }

    @Test
    fun `设备工具子集只占全量的一小部分`() {
        val all = com.dshwatch.domain.agent.tools.BuiltinTools.all()
        val device = all.filter { it.name.startsWith(DeviceTurnPruner.PREFIX) }
        fun cost(tools: List<AgentTool>) =
            tools.sumOf { it.description.length + it.parameters.toString().length }
        // 设备工具只是全量的一小部分：这意味着「打开设备操控」不会额外顶爆工具表
        assertTrue("设备工具 ${device.size} 个、${cost(device)} 字；全量 ${cost(all)} 字",
            cost(device) * 4 < cost(all))
    }
}
