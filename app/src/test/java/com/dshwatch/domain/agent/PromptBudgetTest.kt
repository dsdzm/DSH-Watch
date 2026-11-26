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
        /*
         * 真实下发的集合要过两道筛：
         *  - enabled：关掉的功能（B站、云端编译、设备操控、Shizuku）连 schema 都不进请求；
         *  - onlyInScene：只在某个模式里出现的工具（养鱼的 fish_skin）永远进不了
         *    编程场景的请求 —— 把它算进「每一轮都要付的钱」是虚报。
         *    它那点开销由 FishToolBudgetTest 单独管。
         */
        val active = tools.filter {
            it.enabled(com.dshwatch.domain.model.AppSettings()) && it.onlyInScene == null
        }
        val descActive = active.sumOf { it.description.length }
        val schemaActive = active.sumOf { it.parameters.toString().length }
        assertTrue("工具数变化了，请复核预算：${tools.size}", tools.size in 40..60)
        assertTrue("默认开关下的描述总量 $descActive 超预算", descActive <= 5200)
        assertTrue("默认开关下的 schema 总量 $schemaActive 超预算", schemaActive <= 13000)

        // 全量另设一个松一点的硬上限：这些工具默认关着，但真打开时也要能塞得下。
        // 它防的是「某个长尾工具写到没人管」，不是每轮成本。
        val descAll = tools.filter { it.onlyInScene == null }.sumOf { it.description.length }
        val schemaAll = tools.filter { it.onlyInScene == null }.sumOf { it.parameters.toString().length }
        assertTrue("全量描述总量 $descAll 超上限", descAll <= 5600)
        assertTrue("全量 schema 总量 $schemaAll 超上限", schemaAll <= 14500)
    }

    /**
     * 养鱼场景是**另一套**工具表：只有「约时间」和「换样子」两个。
     *
     * 它不参与上面那个全量预算（那些工具进不了养鱼的请求），
     * 但养鱼的提示词本来就很长（人设 + 记忆 + 文风 + 禁思考），
     * 工具说明再膨胀就会把「怎么说人话」挤掉。所以单独设一个上限。
     */
    @Test
    fun `养鱼场景的工具说明也受预算约束`() {
        val fish = com.dshwatch.domain.agent.tools.BuiltinTools.all()
            .filter { it.name in setOf("schedule_proactive", "fish_skin") }
        assertTrue("养鱼工具少了：${fish.map { it.name }}", fish.size == 2)
        val cost = fish.sumOf { it.description.length + it.parameters.toString().length }
        assertTrue("养鱼工具说明 $cost 超预算", cost <= 2600)
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
